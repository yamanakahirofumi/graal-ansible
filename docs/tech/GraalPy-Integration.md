# GraalPy 統合の詳細

本プロジェクトでは、本家 Ansible の Python モジュールをそのまま実行するために、GraalVM の Python ランタイムである **GraalPy** を Java エンジンに統合しています。本ドキュメントでは、その統合仕様、コンテキスト設定、および互換性維持のためのモンキーパッチについて詳述します。

## 1. 統合の概要

Ansible モジュールの多くは複雑な Python スクリプトであり、これを Java で再実装することは現実的ではありません。`graal-ansible` では、GraalVM SDK の Polyglot API を使用して、Java プロセス内で Python インタプリタを直接制御し、モジュールを実行します。

## 2. GraalVM コンテキスト構成

安定した実行と高い互換性を確保するため、以下のコンテキストオプションを設定しています。

| オプション | 設定値 | 目的 |
| :--- | :--- | :--- |
| `python.IsolateNativeModules` | `false` (`true`) | ネイティブモジュールの分離。Linux環境では安定のため `true` を使用（詳細は [5. 実行環境コンテキストの固定](../tech/Test-Expansion-Strategy.md#25-実行環境コンテキストの固定) を参照）。 |
| `python.PosixModuleBackend` | `java` (`native`) | POSIX モジュールのバックエンド。Linux環境では `native` を使用。 |
| `python.Executable` | (自動検出) | GraalPy 実行バイナリのパスを指定します。 |

## 3. Python 環境の構築

### sys.path の管理
ビルド時に `target/python-packages` にインストールされた `ansible-core` および依存パッケージを優先的にロードするため、実行時に Java 側からディレクトリパスを渡し、Python の `sys.path.insert(0, ...)` で動的に追加します。

### 依存関係の解決
`ansible-core` が依存するライブラリ（`cryptography`, `pyyaml` 等）のうち、GraalPy 環境で動作に支障をきたすものや、ネイティブ拡張が必要なものについては、スタブ（Stub）やモック（Mock）に差し替えるか、ビルド時に適切なバイナリを配置することで解決します。

## 4. モンキーパッチとモックの実装 (Dependency Emulation Strategy)

GraalPy 上での実行時に発生する、Ansible 特有の循環参照や、Java 環境とのデータ交換上の制限を回避するため、`src/main/python/ansible_bridge.py` において以下のパッチを適用しています（**Dependency Emulation Strategy**）。

### 4.1 `ansible.utils.display` のモック
Ansible の内部で多用される `display` シングルトンは、インポート時に複雑な依存関係（`termios` 等）を引き起こします。これを単純なログ出力のみを行うスタブクラスに差し替えることで、インポートエラーを回避しています。

### 4.2 JSON エンコーダーの拡張
Ansible の `setup` モジュールなどは、戻り値として Python の `set`, `frozenset`, `range` 等の型を返します。これらは標準の `json` モジュールではシリアライズできないため、Java 側へ渡す直前に `json.dumps` をパッチし、これらの型をリストに自動変換する `AnsibleEncoder` を適用しています。

### 4.3 システムモジュールのエミュレーション
POSIX 環境を前提とした `termios`, `grp`, `pwd` 等のモジュールが利用できない環境（または制限がある環境）に対応するため、必要最低限のメソッドを持つモックモジュールを `sys.modules` に直接注入しています。

### 4.4 `AnsibleModule` クラスの調整
- `exit_json` / `fail_json`: 実行結果を確実に Java 側でキャプチャできるよう、結果を JSON 形式で標準出力に書き出すようにオーバーライドしています。
- `run_command`: コマンド実行を Java の `Connection` オブジェクトへ委譲し、ターゲットノード上での実行を透過的に行います。
- `_load_params`: GraalVM Context から渡された `complex_args` を直接参照するように変更しています。
    - 詳細なパッチ内容については、[Ansible モジュールの初期化と設定](../implementation/Ansible-Module-Initialization.md) を参照してください。

### 4.5 その他のモンキーパッチ
- **`os` モジュールのパッチ**: `makedirs` や `exists` 等に `_normalize_path` を適用し、Windows パスを適切に処理します。
- **JSON エンコーダー**: `AnsibleEncoder` により、`bytes` や `set` 型を自動的に JSON シリアライズ可能な型へ変換します。
- **`sys.modules` への直接注入**: `cryptography` や `selinux` 等、GraalPy 環境で問題となるモジュールを `None` またはモックに差し替えています。

## 5. 環境変数の取り扱い

Playbook の `environment` キーで指定された環境変数は、以下の設計に基づき GraalPy 環境へ伝播されます。

- **モジュール実行時**:
    - `PythonModule` を通じてモジュールを実行する際、Java 側で評価済みの環境変数 Map を Python コンテキストの Binding（例: `environment_java`）として渡します。
    - `ansible_launcher.py` 内で、この Map を Python の `os.environ` に一時的にマージします。
- **サブプロセスへの影響**:
    - `os.environ` が更新されることで、モジュール内から `subprocess` モジュール等を使用して外部コマンドを呼び出す際にも、指定された環境変数が正しく引き継がれます。
- **スレッドセーフティ**:
    - `graal-ansible` ではタスクをマルチスレッドで実行する可能性があるため、環境変数の設定はスレッドローカルな管理、または実行の都度 Python コンテキストの状態を適切に初期化/復元することで、他スレッドへの影響を防ぎます。

## 6. 実行モデル

### 6.1 モジュール呼び出しフロー
1. `TaskExecutor` のコンストラクタにて、共通ブリッジである `ansible_bridge.py` を事前ロード。
2. `PythonModule` クラスが GraalVM Context を取得。
3. `ansible_launcher.py` (または Action Plugin 用の `ansible_action_launcher.py`) を実行。
4. ランチャー内で `ansible_bridge.initialize()` を呼び出し、タスク変数のバインドとパッチ適用を実施。
5. ランチャー内で、指定されたモジュール名から実際の Python ファイルを特定。
6. `exec()` によりモジュールを実行。この際、`__package__` を `ansible.modules` に設定し、相対インポートをサポートします。

### 6.2 結果の抽出
モジュールの終了コードや `exit_json` / `fail_json` の呼び出しをフックし、最終的な結果（JSON）を Java 側の `TaskResult` に変換します。

## 7. 関連ドキュメント
- [技術スタック](Tech-Stack.md)
- [タスク実行エンジン](../implementation/Task-Executor.md)
- [OS 抽象化レイヤーの仕様](../implementation/OS-Abstraction.md)
