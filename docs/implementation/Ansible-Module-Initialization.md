# Ansible モジュールの初期化と設定 (Ansible Module Initialization & Setup)

本ドキュメントでは、`graal-ansible` が Ansible の Python モジュールを実行する際、どのように引数を渡し、`AnsibleModule` インスタンスおよび Java 側の `PythonAnsibleModuleMock` を構成しているかについて詳述します。

## 1. 概要

Ansible モジュールは通常、標準入力経由で引数（JSON または key=value 形式）を受け取りますが、`graal-ansible` では GraalPy の Polyglot API を利用して、Java 側から Python コンテキストへ直接データを注入します。これにより、外部プロセスの起動オーバーヘッドを削減し、Java 側の接続オブジェクトなどを Python 側から直接利用することを可能にしています。

## 2. Java から Python へのデータ受け渡し

`PythonModule.java` において、以下のデータが GraalVM Context の bindings にセットされます。

| 変数名 | 型 (Java) | 説明 |
| :--- | :--- | :--- |
| `complex_args_java` | `Map<String, Object>` | モジュールに渡される引数。 |
| `module_name` | `String` | 実行対象のモジュール名（例: `ping`, `copy`）。 |
| `site_packages_java` | `List<String>` | `ansible-core` 等がインストールされているパス。 |
| `connection_java` | `Connection` | 現在のターゲットへの接続オブジェクト。 |
| `become_context_java`| `BecomeContext` | 権限昇格の設定。 |

## 3. 初期化フローと `ansible_bridge.py`

`graal-ansible` では、効率化のために `TaskExecutor` のコンストラクタ内で `ansible_bridge.py` をあらかじめロード（事前ロード）しています。このブリッジスクリプトは、GraalPy 環境における共通の初期化ロジック、モジュールモック、および Ansible へのパッチ提供を一手に担います。

各ランチャー（`ansible_launcher.py`, `ansible_action_launcher.py`, `ansible_mock_launcher.py`）は、実行の冒頭でこのブリッジ内の `initialize()` 関数を呼び出すことで、タスク固有の変数（`complex_args` 等）のバインドと実行環境の最終的なセットアップを行います。

### 3.1 ブリッジが提供する主要機能
- **`setup_sys_path(site_packages)`**: `site_packages_java` を `sys.path` に追加し、Ansible ライブラリをロード可能にします。
- **`setup_env(env_vars)`**: Java 側から渡された環境変数を `os.environ` に注入します。
- **`mock_problematic_modules()`**: `cryptography`, `selinux` などのネイティブ依存モジュールのモック化、および `grp`, `pwd`, `termios`, `syslog` などのシステムモジュールのスタブ化を行います。
- **`patch_ansible(...)`**: `AnsibleModule` の `run_command` や `_load_params` をパッチし、Java 側の `Connection` オブジェクトを介した実行を可能にします。
- **`execute_module(...)`**: `__main__` スコープの設定を行い、モジュールコードを安全に実行します。

## 4. グローバルスコープ (`__main__`) への属性注入

Ansible の一部のモジュール（`apt`, `package_facts` など）は、メタデータや引数を取得するために自分自身（`__main__`）をインポートしたり、特定のグローバル変数が存在することを期待したりします。

`ansible_launcher.py` では、モジュールの実行直前に `sys.modules['__main__']` に対して以下の属性を注入します。

| 属性名 | 説明 |
| :--- | :--- |
| `_module_fqn` | モジュールの完全修飾名（例: `ansible.builtin.ping`）。 |
| `complex_args` | 変換済みの引数辞書。 |
| `_modlib_path` | モジュールライブラリのパス（本プロジェクトでは原則 `None`）。 |

また、`exec()` を用いてモジュールコードを実行する際、以下の設定を行っています。

- **`__name__` の設定**: グローバル辞書の `__name__` を `'__main__'` に設定することで、多くのモジュールに含まれる `if __name__ == '__main__':` ブロックが正しく実行されるようにしています。
- **`module` インスタンスの参照**:
    - 通常、Ansible モジュール内では `module = AnsibleModule(...)` のようにインスタンス化が行われます。
    - この `module` 変数は通常 `main()` 関数などのローカル変数ですが、一部の共通コード（特に古いモジュールや特定の `module_utils`）は `from __main__ import module` のようにグローバルな `module` インスタンスを直接参照しようとします。
    - `ansible_launcher.py` は `__main__` モジュールとして動作しているため、モジュールがグローバルスコープで `module` を定義（または `main` 内で `global module` を使用）すると、それが `sys.modules['__main__']` の属性として保持され、他のユーティリティから参照可能になります。
- **グローバル環境の構築**:
    - `ansible_launcher.py` は、モジュールコードを `exec()` で実行する際、第2引数（globals）として渡す辞書を慎重に制御します。
    - この辞書には `__name__: '__main__'` が設定されており、実行されるモジュールコードにとってはこの辞書自体がグローバルスコープ（`__main__` の `__dict__`）として機能します。
- **インスタンスの生存期間**:
    - `exec()` 内で `AnsibleModule` が作成されると、そのインスタンスはモジュールの実行コンテキスト内で保持されます。
    - インスタンス化の過程で、モンキーパッチされた `_load_params` や `run_command` が呼び出され、Java 側から渡された引数や接続設定がインスタンスに統合されます。

## 5. `AnsibleModule` へのモンキーパッチ

Ansible モジュールの基盤となる `AnsibleModule` クラスに対して、以下のパッチを適用しています。

### 5.1 引数のロード (`_load_params`)
通常は標準入力から読み取る引数を、Java から渡された `complex_args` を直接使用するように変更します。
```python
ansible.module_utils.basic.AnsibleModule._load_params = lambda self: (complex_args, 'main')
```

### 5.2 コマンド実行 (`run_command`)
モジュール内でのコマンド実行を、Java 側の `Connection` オブジェクトを経由するように変更します。これにより、SSH 経由の実行などが透過的に行われます。
また、`ansible_bridge.py` 内の `mock_run_command` は、`getent` コマンドの出力をエミュレートする機能を備えており、Linux 以外の環境でもユーザー/グループ情報の取得を可能にしています。

### 5.3 バイナリパスの検索 (`get_bin_path`)
システム探索を避け、パフォーマンスと安全性のために `/usr/bin/` 以下のパスを優先的に返すように固定します。

### 5.4 結果の記録 (`_record_module_result`)
モジュールの実行結果（辞書型）を JSON シリアライズし、Java 側がキャプチャ可能な形式で出力（標準出力または内部変数への代入）します。

## 6. Java 側 AnsibleModule エミュレータ (`PythonAnsibleModuleMock.java`) の詳細設計

`ansible_bridge.py` の `AnsibleModule` 実装は、Polyglot API を介して Java 側の `PythonAnsibleModuleMock.java` に処理を委譲します。この Java 実装における引数解析、型変換、ファイル共通属性の合成、およびフラグ処理の仕様を以下に定義します。

### 6.1 スペック初期化と `add_file_common_args` 合成 (`initializeEffectiveSpec`)
モジュール定義で渡された `argument_spec` と、コンストラクタの `kwargs`（キーワード引数）から最終的な有効スペック（`effectiveSpec`）を構築します。

- `argument_spec` のマッピングをコピーした後、`kwargs.get("add_file_common_args")` が `Truthiness.isTrue()` で真と評価された場合、Ansible 標準のファイル共通引数定義を合成します：

| パラメータ名 | タイプ (`type`) | デフォルト値 / エイリアス (`aliases`) |
| :--- | :--- | :--- |
| `path` | `str` | `aliases: ["dest", "name"]` |
| `mode` | `raw` | なし |
| `owner` | `str` | なし |
| `group` | `str` | なし |
| `seuser` | `str` | なし |
| `serole` | `str` | なし |
| `setype` | `str` | なし |
| `selevel` | `str` | なし |
| `attributes` | `str` | `aliases: ["attr"]` |
| `unsafe_writes` | `bool` | `default: false` |

### 6.2 エイリアス解決とデフォルト値設定 (`initializeAliasesAndDefaults`)
- 各スペック項目の `aliases` リストからエイリアス名と正規キー名の双方向マッピングテーブル（`aliases`）を作成します。
- 各スペック項目に定義された `default` 値を `params` マップに初期値として格納します（デフォルト値未指定時は `null`）。

### 6.3 引数パースと型変換規則マトリクス (`convertType`)
タスクから渡された入力引数（`inputArgs`）を読み込み、スペックの型定義に従って型安全なオブジェクトへ変換します。

| タイプ (`type`) | 入力値の評価規則 | 変換後の Java 型 / 挙動 |
| :--- | :--- | :--- |
| `str` / `path` | 単一要素リスト/配列の場合は先頭要素を抽出。文字列化後に `osMock.normalizePath(s)` を適用。 | `String` (パス正規化済み) |
| `list` | 非リストかつ非配列の値が渡された場合、`Collections.singletonList(v)` でラップ。 | `List<?>` |
| `bool` | `Truthiness.isTrue(v)` により判定（`yes`, `true`, `1`, `on` を真、`no`, `false`, `0`, `off` を偽）。 | `Boolean` |
| `int` | `Integer.parseInt(v.toString())` によりパース。数値変換例外発生時は変換前オブジェクトを保持。 | `Integer` |
| `raw` / その他 | 型変換を行わず、そのままの値を保持。 | `Object` |

- エイリアス名で渡された引数は正規キー名に正規化されるとともに、後方互換性のため元のエイリアスキーでも `params` に保持されます。

### 6.4 パラメータ最終補正 (`finalizeParams`)
- **パス属性フォールバック**: `params.get("path")` が `null` の場合、`params.get("dest")` または `params.get("name")` の非空値を `path` へ自動フォールバック設定します。
- **Raw コマンド引数保持**: `inputArgs` に `_raw_params` が含まれる場合はそのまま保持し、`_uses_shell` フラグ（デフォルト: `false`）を登録します。

### 6.5 タスク実行制御フラグの抽出
`inputArgs` 内の内部メタデータフラグを抽出し、モジュール実行時の挙動を制御します。

- `_ansible_check_mode` -> `checkMode` (`getCheck_mode()`)
- `_ansible_debug` -> `debug` (`get_debug()`)
- `_ansible_diff` -> `diff` (`get_diff()`)

## 7. ファイル操作・ハッシュ計算・属性エミュレーション仕様

`PythonAnsibleModuleMock` は、外部バイナリや Python ライブラリに依存せず、Java 標準ライブラリと `PythonOSMock` を用いてファイル操作および属性管理を提供します。

### 7.1 ダイジェストおよびハッシュ計算 (`sha1`, `md5`, `sha256`, `digest_from_file`)
- Java `MessageDigest`（アルゴリズム: `"SHA-1"`, `"MD5"`, `"SHA-256"`）を使用し、8192 バイトバッファでストリーム読み込みを行って 16 進数小文字ヘキサ文字列を生成します。
- `digest_from_file(filename, algorithm)` では、大文字アルゴリズム指定（`SHA1` -> `SHA-1`, `SHA256` -> `SHA-256`）を自動正規化して計算します。

### 7.2 アトミック移動とローカルバックアップ (`atomic_move`, `backup_local`)
- **`atomic_move(src, dest)`**: `java.nio.file.Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)` を使用して不可分なファイル置換を保証します。
- **`backup_local(path)`**: ファイルが存在する場合、`<path>.<epoch_millis>~` 形式のバックアップファイルを生成し、`Files.copy(REPLACE_EXISTING)` で安全に退避してバックアップファイルパスを返却します。
- **`makedirs_safe(path, mode)`**: ディレクトリの親階層を再帰的かつ安全に作成します（デフォルトパーミッション: `0777`）。

### 7.3 ファイル属性の取得とマージ (`get_file_attributes`, `storedFileArgs`)
- **`get_file_attributes(path)`**: `PythonOSMock.stat(p)` の戻り値から、以下の属性マップを構築します：
  - `mode`: 8 進数パーミッション文字列 (`String.format("%o", st.st_mode & 0777)`)
  - `owner` / `uid`: POSIX ユーザー ID
  - `group` / `gid`: POSIX グループ ID
  - `size`: ファイルバイト長
- **`set_file_attributes_if_different(fileArgs, changed)`**:
  - モジュール側から指定された属性（`mode`, `owner`, `group` 等）を `storedFileArgs` マップに記録します。
  - `exit_json` 呼び出し時に、モジュール結果辞書に含まれていないファイル属性を `storedFileArgs` から自動的にマージ補完して返却します。

## 8. コマンド実行 (`run_command`) とシステムモックエミュレーション

`PythonAnsibleModuleMock.run_command` は、モジュール内でのサブプロセス実行要求を安全に横取りし、Java の `Connection` およびモックサービスへルーティングします。

### 8.1 コマンドライン構築とディレクトリ移動 (`chdir` / `cwdOverride`)
- **引数の正規化**: リストまたは文字列で渡された引数は、各要素に `osMock.normalizePath` を適用した上で空白区切りコマンドラインに結合されます。
- **カレントディレクトリのプリペンド**:
  - `cwdOverride` または `params.get("chdir")` が指定されている場合、ターゲット OS ファミリに応じてカレントディレクトリ移動コマンドを先頭にプリペンドします：
    - **Windows 環境 (`OSFamily == "Windows"`)**: `cd /d "<chdirPath>" && <command>`
    - **POSIX 環境 (Linux / macOS)**: `cd "<chdirPath>" && <command>`
- **接続委譲**:
  - 構築されたコマンドは `connection.execCommand(command, becomeContext, environment)` へ委譲され、`new Object[]{res.exitCode(), res.stdout(), res.stderr()}` の 3 要素タプルとして返却されます。

### 8.2 `getent` 組み込みエミュレーション (`handleGetentMock`)
Linux 以外の環境やコンテナ外環境でモジュールがユーザー/グループ情報を照会する際のエラーを回避するため、`getent` コマンドに対する静的エミュレーションを内包します：

- `getent passwd root` -> `exitCode: 0`, `stdout: "root:x:0:0:root:/root:/bin/bash\n"`
- `getent passwd` (引数なし) -> `root` および `testuser` (uid: 1001) の情報を返却。
- `getent group root` -> `exitCode: 0`, `stdout: "root:x:0:\n"`
- `getent group` (引数なし) -> `root` および `testgroup` (gid: 1001) の情報を返却。

## 9. システムモジュールのモック

GraalPy の制限や、Ansible 内部の複雑な依存関係を回避するため、以下のモジュールをモックに差し替えています。

- **`ansible.utils.display`**: `Display` クラスをスタブ化し、不必要な依存関係や表示処理を抑制します。
- **`grp`, `pwd`**: POSIX ユーザー/グループ情報を返すスタブを `sys.modules` に注入します。
- **`termios`**: 端末制御関連の定数とメソッドをスタブ化します。
- **`cryptography`, `selinux`**: ネイティブ依存が強い、あるいは不要なモジュールを `None` に設定し、インポートエラーを制御します。

## 10. チェックモードの制御

チェックモード（ドライラン）の動作は、Playbook レベルおよびタスクレベルの設定に基づき、`PlaybookExecutor` によって決定されます。

- **フラグの注入**: チェックモードが有効な場合、`_ansible_check_mode: true` という引数が `complex_args` に注入されます。
- **モジュールの動作**: `AnsibleModule` インスタンスは、自身に渡された `_ansible_check_mode` 引数を参照し、実際の変更を伴う処理をスキップするかどうかを判断します（これは Ansible 本来の動作と同じです）。
- **一貫性の維持**: Java 側でチェックモードの判定（テンプレート評価等）を完結させ、Python 側には最終的な真偽値のみを渡すことで、複雑な条件分岐の二重実装を防いでいます。

## 11. Polyglot 実行ライフサイクルのシーケンス構造 (Execution Flow)

Java `TaskExecutor` から GraalPy `Polyglot Context`、`ansible_bridge.py`、およびモジュール実行コードに至る一連の呼び出し・応答シーケンス構造です。

```mermaid
sequenceDiagram
    autonumber
    participant TE as Java TaskExecutor
    participant Poly as GraalPy Context
    participant Bridge as ansible_bridge.py
    participant Mock as Java PythonAnsibleModuleMock
    participant Mod as Module Code (__main__)
    participant Conn as Java Connection

    TE->>Poly: putMember("complex_args_java", args)
    TE->>Poly: putMember("connection_java", conn)
    TE->>Poly: eval("ansible_launcher.py")
    Poly->>Bridge: initialize()
    Bridge->>Bridge: patch_ansible() & mock_sys_modules()
    Poly->>Mod: exec(module_code, globals)
    Mod->>Bridge: AnsibleModule(argument_spec)
    Bridge->>Mock: AnsibleModuleJava.create(...)
    Mock->>Mock: parseInputArguments() & convertType()
    Mod->>Mock: run_command() / atomic_move()
    Mock->>Conn: execCommand(...)
    Mod->>Bridge: AnsibleModule.exit_json() / fail_json()
    Bridge->>Mock: exit_json(kwargs)
    Mock-->>Poly: pythonPrint(json) & pythonExit(code)
    Bridge-->>Poly: SystemExit(0 or 1)
    Poly-->>TE: Output JSON / PolyglotValue
    TE->>TE: parseModuleOutput() -> TaskResult
```

## 12. Python ブリッジの例外・終了コード制御仕様 (`SystemExit` / `parseModuleOutput`)

Python モジュールの実行終了および例外発生時のキャプチャ・レスポンス解析仕様です。

- **`SystemExit` の捕捉と終了コード解析**:
  - Python モジュールは処理完了時に `AnsibleModule.exit_json()` または `fail_json()` を呼び出し、内部で `sys.exit(0)` または `sys.exit(1)` (例外 `SystemExit`) を発起します。
  - `ansible_bridge.py` 内の `execute_module()` は `try...except SystemExit` ブロックでこれを捉え、正常終了コード `0` の場合は `_record_module_result` でシリアライズされた JSON 辞書を標準出力/戻り値として保持します。
  - `SystemExit(1)` または非ゼロ終了コードの場合は、`failed: true` フラグをセットした結果辞書を構築します。
- **未補足例外 (Unhandled Exception) の構造化変換**:
  - Python ランタイム上で未補足の `Exception` や `ImportError` が発生した場合、Python ブリッジはスタックトレース文字列を抽出・整形し、`{"failed": true, "msg": traceback_str, "exception": traceback_str}` 形式の JSON オブジェクトへ自動変換します。
- **Java 側 `parseModuleOutput` による復元**:
  - Java 側の `TaskExecutor.parseModuleOutput` は、返却された JSON 文字列を Jackson `ObjectMapper` により `Map<String, Object>` へデコードし、`TaskResult` インスタンスへ格納します。
  - JSON パースに失敗した場合は、生テキスト出力を `msg` フィールドに保持したフォールバック `TaskResult.failure(rawOutput)` オブジェクトを安全に作成します。

## 13. マルチスレッド並列実行時 (`FreeStrategy`) のコンテキスト隔離とクリーンアップ

`FreeStrategy` やマルチスレッド環境において、単一の GraalPy Context またはスレッド間での状態汚染（State Pollution）を防止するための分離仕様です。

- **スレッドローカル探索パス・コンテキスト管理**:
  - `TaskExecutor` は、スレッドごとのコレクション検索パスを ThreadLocal 変数（`setCurrentCollectionPaths`）として分離管理します。
- **`sys.modules['__main__']` の各タスク前後の初期化**:
  - 各タスク実行直前に `sys.modules['__main__']` の属性（`complex_args`, `module`, `_module_fqn`）をクリア・上書きバインドします。
- **Polyglot Bindings の明示的クリーンアップ**:
  - タスク実行完了（`finally` ブロック）時に、`context.getBindings("python").removeMember(...)` を呼び出し、前タスクの `complex_args_java` や `connection_java` オブジェクト参照を破棄してメモリリークおよび誤参照を防止します。

## 14. Action Plugin ランチャー連携仕様 (`ansible_action_launcher.py`)

制御ノード（管理ノード）側で動作する Action Plugin を GraalPy 上で実行するためのバインディング引数マトリクスおよびメソッドエミュレーション仕様です。

- **Java-Python バインディング引数マトリクス**:

| 変数名 | Java 型 | 説明 |
| :--- | :--- | :--- |
| `task_vars_java` | `Map<String, Object>` | タスクレベルで評価済みの全変数マップ。 |
| `connection_java` | `Connection` | ターゲット接続オブジェクト（`execCommand`, `putFile`, `fetchFile`）。 |
| `task_executor_java` | `ITaskExecutor` | Action Plugin 内からの低レベルモジュール呼び出し（`_execute_module`）用プロキシ。 |
| `task_args_java` | `Map<String, Object>` | Action Plugin に渡されたタスク引数（`args`）。 |
| `play_context_java` | `PlayContext` / `BecomeContext` | チェックモード、権限昇格情報等の実行コンテキスト。 |

- **`ActionBase` のメソッド・モンキーパッチ仕様**:
  - Python 側の `ansible.plugins.action.ActionBase` に対して以下のパッチを適用し、Java 側のインフラを直接呼び出せるよう構成します。
    - **`_execute_module(...)`**: `task_executor_java.executeModuleFromAction(...)` へ委譲し、ターゲットノードでのモジュール実行結果を Python 辞書として返却。
    - **`_low_level_execute_command(...)`**: `connection_java.execCommand(...)` へ委譲し、シェルコマンド実行結果（rc, stdout, stderr）を返却。
    - **`_transfer_file(...)`**: `connection_java.putFile(...)` へ委譲し、ローカルファイルをリモート一時パスへ転送。

## 15. 関連ドキュメント
- [タスク実行エンジン](Task-Executor.md)
- [タスク制御の実装詳細](Task-Control.md)
- [Action Plugin 実装仕様](Action-Plugins.md)
- [モジュールとモックの対応リファレンス](../features/Module-Mock-Reference.md)
- [Mock 実装リファレンス](Mock-Implementation-Reference.md)
- [OS 抽象化レイヤーの仕様](OS-Abstraction.md)
- [GraalPy 統合の詳細](../tech/GraalPy-Integration.md)
