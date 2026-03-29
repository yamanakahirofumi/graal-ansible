# Action Plugin / Module Mock 実装リファレンス (Reference)

**※注意: 本ドキュメントは今後の改善に向けた資料であり、プログラムの最新の状態とは必ずしも同期しません。**

本プロジェクトでは、Ansible の Action Plugin やモジュールを GraalPy 上で動作させるため、また Windows 実行環境や Java との相互運用のために、多くのモックやパッチを導入しています。以下にその主要な実装内容を記録します。

## 1. パス正規化と OS 抽象化 (Path Normalization & OS Abstraction)

Java から渡されるパスや Windows 環境特有の挙動を吸収するための実装です。

- **`_normalize_path`**: Java (特に Windows) から渡される `/C:\path\...` のような先頭スラッシュ付きの絶対パスを、OS が理解できる形式に変換します。
- **`os` モジュールのパッチ**: `os.makedirs`, `os.mkdir`, `os.path.exists`, `os.stat` をオーバーライドし、引数に `_normalize_path` を自動適用するようにしています。
- **POSIX 関数のモック**: Windows 環境で欠落している `os.geteuid`, `os.getuid`, `os.chown`, `os.setuid` などの POSIX 固有関数を、No-op (何もしない) または固定値を返すスタブとして実装しています。

## 2. ネイティブ / システムライブラリのモック (Native/System Library Mocks)

GraalPy での C 拡張ロード失敗を回避し、かつ依存関係を満たすための実装です。

- **C 拡張のスタブ化**: `cryptography`, `yaml._yaml`, `markupsafe._speedups`, `selinux`, `fcntl`, `termios` などを、インポート時にエラーにならないよう空のモジュールや単純なクラスで置き換えています。
- **システム情報モック (`grp`, `pwd`, `syslog`)**: ユーザー・グループ情報の取得関数をモック化し、`root` や `testuser` といった固定の情報を返すようにしています。これにより、Unix 固有の管理コマンドに依存するモジュールが Windows 上でも一部動作可能になります。

## 3. Ansible コアエンジン・プラグインのモック (Ansible Core Engine Mocks)

Action Plugin の実行コンテキストを擬似的に構築するための実装です。

- **`ActionBase`**: オリジナルの `ActionBase` を模倣し、`_execute_module` を呼び出した際に Java 側の `TaskExecutor` へ処理をルーティングする機能を備えています。
- **`Templar`**: Jinja2 テンプレートの評価を模倣します。Jinja2 が利用可能な場合はそれを使用し、不可な場合は単純な正規表現置換で対応します。`evaluate_conditional` による条件分岐の評価もサポートします。
- **`MockLoader`, `MockShell`**: ファイル読み込みやパス操作など、Action Plugin が内部で利用するユーティリティクラスの最小限の実装を提供します。
- **`Display`, `PlayContext`, `Task`**: プラグインの初期化に必要なメタデータ保持クラスのモックです。

## 4. モジュール実行と `AnsibleModule` (Module Execution Mocks)

モジュール内部で利用される `ansible.module_utils.basic.AnsibleModule` のモック実装です。

- **引数処理**: `argument_spec` に基づく型変換 (bool, int, list, path) を自動で行い、Java から渡された JSON データをモジュールが利用可能な形式に整えます。
- **`run_command` エミュレータ**:
    - 実際のコマンド実行は Java の `Connection` オブジェクトを介してリモート/ローカルで実行されます。
    - **`getent` エミュレータ**: Linux 環境以外でも `getent passwd root` などのコマンドが期待通り動作するよう、`run_command` 内部にハードコードされたレスポンスを返すロジックが含まれています。
- **ファイル属性操作**: `set_fs_attributes_if_different` や `set_file_attributes_if_different` をモック化し、属性変更（owner/group/mode）が要求された場合に「変更あり」というステータスだけを返し、実際の OS 操作によるエラーを回避しています。

## 5. データ相互運用とシリアライズ (Data Interop & Serialization)

Java と Python の間のデータ受け渡しを円滑にするための実装です。

- **`_deep_convert`**: Java の `Map`, `List`, `Set`, `String`, `Boolean` などのオブジェクトを再帰的に Python のネイティブ型に変換します。
- **JSON カスタムエンコーダ**: `json.dumps` をパッチし、`bytes`, `set`, `range` や Java の Proxy オブジェクトなどを適切に文字列化・リスト化して JSON 出力できるようにしています。
- **`ConnectionResult` ブリッジ**: Java 側から返される実行結果オブジェクト (Record) のフィールドを、Python 側で安全に読み取るためのパース処理（正規表現による文字列解析を含む）を実装しています。

## 6. 今後の改善に向けた視点

現在の実装は「動作させること」を最優先したワークアラウンドが多く含まれています。今後の改善において以下の点が検討材料となります。

- **モックの削減**: GraalPy の進化や依存ライブラリの pure-python 化により、スタブ化しているモジュールを本物に戻せる可能性があります。
- **忠実度の向上**: 現在の `Templar` や `AnsibleModule` のモックは一部の機能を制限しています。より複雑な Playbook をサポートするには、オリジナルの Ansible コードをより多く活用する構成への移行が望まれます。
- **プラットフォーム依存性の整理**: Windows と Linux での挙動の違いをモックで吸収するのではなく、Java 側の抽象化レイヤーでより洗練された形で処理できる可能性があります。
