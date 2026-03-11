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

## 4. モンキーパッチとモックの実装

GraalPy 上での実行時に発生する、Ansible 特有の循環参照や、Java 環境とのデータ交換上の制限を回避するため、`src/main/python/ansible_launcher.py` において以下のパッチを適用しています。

### 4.1 `ansible.utils.display` のモック
Ansible の内部で多用される `display` シングルトンは、インポート時に複雑な依存関係（`termios` 等）を引き起こします。これを単純なログ出力のみを行うスタブクラスに差し替えることで、インポートエラーを回避しています。

### 4.2 JSON エンコーダーの拡張
Ansible の `setup` モジュールなどは、戻り値として Python の `set`, `frozenset`, `range` 等の型を返します。これらは標準の `json` モジュールではシリアライズできないため、Java 側へ渡す直前に `json.dumps` をパッチし、これらの型をリストに自動変換する `AnsibleEncoder` を適用しています。

### 4.3 システムモジュールのエミュレーション
POSIX 環境を前提とした `termios`, `grp`, `pwd` 等のモジュールが利用できない環境（または制限がある環境）に対応するため、必要最低限のメソッドを持つモックモジュールを `sys.modules` に直接注入しています。

### 4.4 `AnsibleModule` クラスの調整
- `_record_module_result`: 実行結果を確実に Java 側でキャプチャできるよう、結果を JSON 形式で標準出力に書き出すように変更しています。
- `get_bin_path`: 外部コマンドの検索パスを、プロジェクトが管理するパスや OS 抽象化レイヤーのパスに誘導します。
- `_load_params`: GraalVM Context から渡された `complex_args` を直接参照するように変更しています。

### 4.5 その他のモンキーパッチ
- **`ansible.module_utils.distro`**: OS 判定において常に特定の値を返すように固定（例: ID='debian'）。
- **`ansible.module_utils.common.process`**: `get_bin_path` をパッチし、常に `/usr/bin/` 以下のパスを返すように調整。
- **`sys.modules` への直接注入**: `cryptography` や `selinux` 等、GraalPy 環境で問題となるモジュールを `None` またはモックに差し替えています。

## 5. 環境変数の取り扱い

Playbook の `environment` キーで指定された環境変数は、以下の通り GraalPy 環境へ伝播されます。

- **モジュール実行時**: `PythonModule` を通じてモジュールを実行する際、Java 側から渡された環境変数を Python の `os.environ` に反映します。
- **サブプロセスへの影響**: これにより、モジュール内から `subprocess` モジュール等を使用して外部コマンドを呼び出す際にも、指定された環境変数が正しく引き継がれます。
- **スレッドセーフティ**: 複数のスレッドで同一の GraalVM コンテキストを共有する場合に備え、スレッドローカルな変数を活用してコンテキスト設定を動的に構築することで、環境変数の伝搬が他スレッドに影響を与えないよう安全に管理されています。

## 6. 実行モデル

### 6.1 モジュール呼び出しフロー
1. `PythonModule` クラスが GraalVM Context を作成/取得。
2. `ansible_launcher.py` をリソースとして読み込み、実行。
3. `ansible_launcher.py` 内で、指定されたモジュール名から `module_loader.find_plugin` を使用して実際の Python ファイルを特定。
4. `exec()` によりモジュールを実行。この際、`__package__` を `ansible.modules` に設定し、相対インポートをサポートします。

### 6.2 結果の抽出
モジュールの終了コードや `exit_json` / `fail_json` の呼び出しをフックし、最終的な結果（JSON）を Java 側の `TaskResult` に変換します。

## 7. 関連ドキュメント
- [技術スタック](Tech-Stack.md)
- [タスク実行エンジン](../implementation/Task-Executor.md)
- [OS 抽象化レイヤーの仕様](../implementation/OS-Abstraction.md)
