# GraalPy 統合の詳細

本プロジェクトでは、本家 Ansible の Python モジュールをそのまま実行するために、GraalVM の Python ランタイムである **GraalPy** を Java エンジンに統合しています。本ドキュメントでは、その統合仕様、コンテキスト設定、データバインディング行列、例外処理、および互換性維持のためのモンキーパッチについて詳述します。

## 1. 統合の概要

Ansible モジュールの多くは複雑な Python スクリプトであり、これを Java で再実装することは現実的ではありません。`graal-ansible` では、GraalVM SDK の Polyglot API (`org.graalvm.polyglot.Context`) を使用して、Java プロセス内で Python インタプリタを直接制御し、制御ノード (Control Node) 上で高速かつ省メモリに Python スクリプトを実行します。

## 2. GraalVM コンテキスト構成とライフサイクル

安定した実行と高い互換性を確保するため、以下のコンテキストオプションを設定し、スレッド・インスタンス単位でライフサイクルを管理しています。

### 2.1 ポリグロット・コンテキスト構成オプション

| オプション | 設定値 | 目的・説明 |
| :----------------------------- | :------------- | :--------------------------------------------------------------------------------------------------------------------------------------- |
| `allowAllAccess` | `true` | Java と GraalPy 間の双方向オブジェクトアクセス、ファイルシステムアクセス、プロセス実行を全面的に許可します。 |
| `python.IsolateNativeModules` | `false` / `true`| ネイティブ C 拡張モジュールの分離設定。Linux 環境で `ansible-core` との安定性を確保するため `false` (または安定重視時 `true`) を適用。 |
| `python.PosixModuleBackend` | `native` (Linux)| POSIX システムコールのバックエンド指定。Linux 環境では native を使用して OS ネイティブの動作を保証。 |
| `python.Executable` | (自動検出) | システム上に存在する GraalPy 実行バイナリのパスを指定。 |

### 2.2 ライフサイクルとスレッドセーフティ
- **`TaskExecutor` インスタンス統合**: `TaskExecutor` の初期化時に `Context.newBuilder("python").allowAllAccess(true)` により独立した `Context` インスタンスを生成します。
- **事前ロード (Pre-loading)**: コンストラクタ内で `os_java` (`PythonOSMock`) および `AnsibleModuleJava` (`PythonAnsibleModuleMock.Factory`) をバインドし、共通ブリッジである `ansible_bridge.py` を事前に評価 (`eval`) して初期化します。
- **スレッド分離**: `FreeStrategy` などの並列実行戦略では、各実行スレッドごとに独立した `TaskExecutor` / `Context` インスタンスを維持することで、コンテキスト競合やデータ汚染を防止します。

## 3. Python 環境の構築と sys.path 管理

### 3.1 sys.path の管理
ビルド時に `target/python-packages` やシステム環境に配置された `ansible-core` および依存パッケージを優先的にロードするため、実行時に Java 側の `PythonEnv` ユーティリティからパスを取得し、Python コンテキストの `sys.path.insert(0, ...)` により動的に追加します。

| パス種別 | Java ソース | 設定目的 |
| :----------------------- | :---------------------------------- | :--------------------------------------------------------------------------- |
| `site-packages` | `PythonEnv.getSitePackagesFromEnv()`| `ansible-core` および依存 Python ライブラリ群の探索パス。 |
| `ansible_collections` | `PythonEnv.getCollectionPaths(...)` | 各種コレクション（`ansible.builtin`, `community.general` 等）の探索パス。 |

### 3.2 依存関係の解決
`ansible-core` が依存するライブラリ（`cryptography`, `pyyaml` 等）のうち、GraalPy 環境で動作に支障をきたすものや、ネイティブ C 拡張が必要なものについては、スタブ（Stub）やモック（Mock）に差し替えるか、ビルド時に適切なバイナリを配置することで解決します。

## 4. モンキーパッチとモックの実装 (Dependency Emulation Strategy)

GraalPy 上での実行時に発生する Ansible 特有の循環参照や、Java 環境とのデータ交換上の制限を回避するため、`src/main/python/ansible_bridge.py` において以下のパッチを適用しています（**Dependency Emulation Strategy**）。

### 4.1 `ansible.utils.display` のモック
Ansible の内部で多用される `display` シングルトンは、インポート時に複雑な POSIX 依存関係（`termios` 等）を引き起こします。これを単純なログ出力のみを行うスタブクラスに差し替えることで、インポートエラーを回避しています。

### 4.2 JSON エンコーダーの拡張 (AnsibleEncoder)
Ansible の `setup` モジュールなどは、戻り値として Python の `set`, `frozenset`, `range`, `bytes` 等の型を返します。これらは標準の `json` モジュールではシリアライズできないため、Java 側へ渡す直前に `json.dumps` をパッチし、これらの型を UTF-8 文字列やリストに自動変換する `AnsibleEncoder` を適用しています。

### 4.3 システムモジュールのエミュレーション
POSIX 環境を前提とした `termios`, `grp`, `pwd`, `selinux` 等のモジュールが利用できない環境（または制限がある環境）に対応するため、必要最低限のメソッドを持つモックモジュールを `sys.modules` に直接注入しています。

### 4.4 `AnsibleModule` クラスの調整
- **`exit_json` / `fail_json`**: 実行結果を確実に Java 側でキャプチャできるよう、結果を JSON 形式で標準出力に書き出し、`sys.exit(0)` を呼び出します。
- **`run_command`**: コマンド実行を Java の `Connection` オブジェクト（`TaskExecutor.getCurrentConnection()`）へ委譲し、ターゲットノード上での実行を透過的に行います。
- **`_load_params`**: GraalVM Context から渡された `complex_args` を直接参照するように変更しています。

## 5. Java-Python ポリグロット・データバインディング行列

Java 側と GraalPy ランチャー間でのデータ交換は、`context.getBindings("python")` 経由で設定されるメンバー変数を通じて行われます。以下に主要なランチャーごとのバインディング仕様を示します。

| ランチャー / コンポーネント | バインド変数名 (`python`) | Java データ型 | 説明・役割 |
| :--------------------------- | :-------------------------- | :------------------ | :-------------------------------------------------------------------------- |
| **全般 (共通)** | `site_packages_java` | `List<String>` | Python `sys.path` に注入する `site-packages` ディレクトリリスト。 |
| | `collection_paths_java` | `List<String>` | コレクション探索ディレクトリパスリスト。 |
| **`ansible_launcher.py`** | `module_name` | `String` | 実行対象モジュール名（例: `ansible.builtin.copy`）。 |
| (通常モジュール実行) | `complex_args_java` | `Map<String, Object>`| モジュールに渡すタスク引数 Map。 |
| | `connection_java` | `Connection` | ターゲットノードとの通信を担当する Connection インスタンス。 |
| | `become_context_java` | `BecomeContext` | 権限昇格情報（`become_user`, `become_method` 等）。 |
| | `environment_java` | `Map<String, String>`| タスクレベルで評価された環境変数 Map。 |
| **`ansible_action_launcher.py`** | `action_name` | `String` | Action Plugin 名（例: `template`, `copy`）。 |
| (Action Plugin 実行) | `action_args_java` | `Map<String, Object>`| Action Plugin 引数 Map。 |
| | `task_vars_java` | `Map<String, Object>`| 実行時点の全評価済みタスク変数。 |
| | `task_executor_java` | `ITaskExecutor` | Java 側タスク実行エンジンインターフェースのバインディング。 |
| **`ansible_inventory_launcher.py`**| `inventory_source_java` | `String` | 解析対象インベントリファイル/スクリプトのパス。 |
| (インベントリプラグイン) | `result_json` (出力) | `String` | 解析結果の JSON 文字列。 |
| **`ansible_callback_launcher.py`** | `callback_name_java` | `String` | 呼び出す Python コールバックプラグイン名。 |
| (コールバックプラグイン) | `event_type_java` | `String` | 発火イベント名（`v2_playbook_on_start` 等）。 |
| | `event_data_java` | `Map<String, Object>`| イベント関連データ（Host, TaskResult 等）。 |

## 6. 例外ハンドリングとモジュール出力解析アルゴリズム

### 6.1 例外処理フロー (`PolyglotException` & `SystemExit`)
GraalPy 上でのモジュール実行時、Python 側の `sys.exit()` は Java 側で `PolyglotException` として補獲されます。

```
[ GraalPy モジュール実行 ]
         │
         ├──> 正常終了 (exit_json / fail_json)
         │       └─> sys.exit(0) 呼び出し
         │               └─> PolyglotException (isExitStatus = true, exitStatus = 0)
         │                       └─> 正常出力として標準出力をパース
         │
         ├──> 異常終了 / 画面エラー
         │       └─> sys.exit(non-zero) または 未捕捉例外
         │               └─> PolyglotException (exitStatus != 0)
         │                       └─> TaskResult.failure(...) を生成
         │
         └─> Java 側非チェック例外
                 └─> TaskResult.failure("GraalPy execution failed: ...")
```

1. **`isExit()` 判定**: `PolyglotException.isExit()` が `true` の場合、`getExitStatus()` を検証します。
2. **終了コード 0**: `exit_json` または `fail_json` による正常なスクリプト終了とみなし、`python.result` から結果文字列を取得します。
3. **非 0 終了コード / その他例外**: 実行失敗として例外メッセージを取得し、`TaskResult.failure(...)` を返却します。

### 6.2 モジュール出力解析アルゴリズム (`parseModuleOutput`)
モジュールの標準出力には、警告ログやデバッグ出力が混在する場合があります。`PythonModule` では以下のアルゴリズムで堅牢に JSON オブジェクトを抽出します。

```java
private String parseModuleOutput(String output) {
    if (output == null || output.isBlank()) return "{}";

    String trimmed = output.trim();
    String[] lines = trimmed.split("\\r?\\n");
    // 1. 逆順（末尾から）行単位で完全な JSON オブジェクトを探す
    for (int i = lines.length - 1; i >= 0; i--) {
        String line = lines[i].trim();
        int start = line.indexOf('{');
        int end = line.lastIndexOf('}');
        if (start != -1 && end != -1 && start < end) {
            return line.substring(start, end + 1);
        }
    }

    // 2. フォールバック: 全体から最初と最後の波括弧の範囲を抽出
    int start = trimmed.indexOf('{');
    int end = trimmed.lastIndexOf('}');
    if (start != -1 && end != -1 && start < end) {
        return trimmed.substring(start, end + 1);
    }
    return trimmed;
}
```

## 7. Ansiballz リモート転送・実行モデルの技術仕様

ターゲットノードがリモートホスト（SSH / WinRM 等）の場合、`PythonModule` は以下の Ansiballz パッケージングモデルでリモート実行を行います。

### 7.1 依存ライブラリの ZIP パッケージング (`ansible_lib.zip`)
`getOrCreateDependencyZip()` メソッドは、制御ノード上の `ansible-core` の共通ライブラリ (`ansible/module_utils`, `ansible/_vendor`, `ansible/compat` 等) を抽出して一時 ZIP ファイルを作成し、`ConcurrentHashMap<String, Path>` にキャッシュします。

### 7.2 ラッパースクリプト生成 (`wrapModule`)
転送用モジュールファイルには、以下のコードが動的に動的ビルド・インジェクトされます。
- `complex_args` およびモジュールソースコードの Base64 エンコード・デコード。
- `ansible.module_utils.basic.AnsibleModule._load_params` のモンキーパッチオーバーライド。
- `json.dumps` への `AnsibleEncoder` 注入（`bytes`, `set`, `WrappedValue` 等の変換）。
- リモートテンポラリディレクトリ構造への `ansible_lib.zip` の `sys.path` 挿入。

### 7.3 実行手順と権限分離
1. **テンポラリディレクトリ作成**: `/tmp/ansible.<uuid>` を作成。転送時の所有権エラーを防止するため、空の `BecomeContext.empty()` を指定して SSH ログインユーザー権限で作成。
2. **転送 (SCP/SFTP)**: `ansible_lib.zip` およびラッパーモジュールスクリプトをリモートへ転送。
3. **リモート実行**: 指定された `becomeContext` (sudo/su 等) および環境変数 Map を用いて `python3 /tmp/ansible.<uuid>/Ansiballz_<module>.py` を実行。
4. **クリーンアップ**: `finally` ブロックにて `rm -rf /tmp/ansible.<uuid>` を実行しリソースを回収。

## 8. 環境変数の取り扱い

Playbook の `environment` キーで指定された環境変数は、以下の設計に基づき GraalPy 環境へ伝播されます。

- **モジュール実行時**:
    - `PythonModule` を通じてモジュールを実行する際、Java 側で評価済みの環境変数 Map を Python コンテキストの Binding (`environment_java`) として渡します。
    - `ansible_launcher.py` 内で、この Map を Python の `os.environ` に一時的にマージします。
- **サブプロセスへの影響**:
    - `os.environ` が更新されることで、モジュール内から `subprocess` モジュール等を使用して外部コマンドを呼び出す際にも、指定された環境変数が正しく引き継がれます。
- **スレッドセーフティ**:
    - `graal-ansible` ではタスクをマルチスレッドで実行するため、各スレッドごとに分離された `Context` 内で処理を行い、他スレッドへの影響を遮断します。

## 9. 関連ドキュメント
- [技術スタック](Tech-Stack.md)
- [タスク実行エンジン](../implementation/Task-Executor.md)
- [Action Plugin 実装仕様](../implementation/Action-Plugins.md)
- [Ansible モジュールの初期化と設定](../implementation/Ansible-Module-Initialization.md)
- [リモートノードでのモジュール実行仕様](../implementation/Remote-Module-Execution.md)
- [OS 抽象化レイヤーの仕様](../implementation/OS-Abstraction.md)
