# Action Plugin / Module Mock 実装リファレンス (Technical Reference)

本ドキュメントは、Ansible の Action Plugin やモジュールを GraalPy 上で動作させるため、および Windows 実行環境や Java との相互運用のために導入されている主要なモックとパッチの技術リファレンスです。

どのモジュールがどのモックに依存しているかなどの機能的な対応関係については、[モジュールとモックの対応リファレンス (Module & Mock Reference)](../features/Module-Mock-Reference.md) を参照してください。

## 1. パス正規化と OS 抽象化 (Path Normalization & OS Abstraction)

Java から渡されるパスや Windows 環境特有の挙動を吸収するための実装です。

- **`_normalize_path`**:
  - Java (特に Windows) から渡される `/C:\path\...` や `/\\server\share` のような、先頭に余分なスラッシュが付与された絶対パスを検知し、先頭のスラッシュを削除して OS が正しく認識できる形式に変換します。
  - Windows 環境（`os.name == 'nt'`）では、スラッシュをバックスラッシュに統一する処理も含まれます。
- **`os` モジュールのパッチ**:
  - `os.makedirs`, `os.mkdir`, `os.path.exists`, `os.stat` をオーバーライドし、引数に自動的に `_normalize_path` を適用します。
  - **`os.stat` の詳細**: `PythonOSMock.java` (Java) の `statPython` メソッドを呼び出します。Java 側ではファイルが見つからない場合に直接 Python 例外を投げることができないため、ブリッジ側で用意した `_raise_file_not_found` ヘルパー関数を Java から呼び出すことで、Python レベルの `FileNotFoundError` を正しく発生させます。
- **POSIX 関数のモック**:
  - Windows 環境で欠落している `os.geteuid`, `os.getuid`, `os.chown`, `os.lchown`, `os.lchmod`, `os.setegid`, `os.seteuid`, `os.setgid`, `os.setuid` などを、No-op (何もしない) または固定値（root 相当の 0 など）を返すスタブとして実装し、Unix 前提のモジュールコードのクラッシュを防ぎます。

## 2. ハイブリッド・ローディング戦略 (Hybrid Loading Strategy)

Ansible Core の一部をモック化しつつ、ディスク上のオリジナルのソースコードもロード可能にするための仕組みです。

- **`setup_sys_path`**:
  - `ansible`, `ansible.module_utils` 等の主要なパッケージを `sys.modules` にモックとして登録しつつ、それらの `__path__` プロパティに実際のディスクパス（`target/python-packages` 等）を追加します。
  - これにより、特定のクラス（`AnsibleModule` や `ActionBase`）をプロジェクト独自のモックに差し替えつつ、その配下にある膨大なユーティリティやサブモジュールをオリジナルの Python コードからロードすることを可能にしています。

## 3. データ相互運用とブリッジ (Data Interop & Bridge)

Java と Python の間のデータ受け渡しを円滑にし、型変換の不整合を解消するための実装です。

- **`_deep_convert`**:
  - Java の `Map`, `List`, `Set`, `String`, `Boolean`, `Integer`, `Long`, `Float`, `Double`, `Path`, `File` などのオブジェクトを、再帰的に Python のネイティブ型（`dict`, `list`, `str`, `bool`, `int`, `float`）に変換します。
  - GraalPy の Proxy オブジェクトに対しても、`toString()` 等を用いた頑健な文字列化処理を行います。
- **データ変換ユーティリティ**:
  - **`to_text`, `to_bytes`**: `ansible.module_utils._text` 内のこれらの関数を、`None` を受け取った場合に `str(None)` ではなく `None` を返すようにパッチしています。これにより、オプション引数を扱うモジュールの互換性を高めています。
- **JSON カスタムエンコーダ (`AnsibleEncoder`)**:
  - `json.dumps` をパッチし、標準ではシリアライズできない以下の型を自動変換します。
    - `bytes`: UTF-8（失敗時は latin-1）でデコード。
    - `set`, `frozenset`, `range`: `list` に変換。
    - `Exception`: `msg` と `failed: True` を持つ辞書に変換。
    - Java Proxy/Iterable: 可能な限り `dict` や `list` へ変換。

## 4. Ansible コアエンジン・プラグインのモック (Ansible Core Engine Mocks)

Action Plugin の実行コンテキストを擬似的に構築するための実装です。

- **`ActionBase`**:
  - `_execute_module` をパッチし、内部からのモジュール実行要求を Java 側の `TaskExecutor.execute_from_python` へルーティングします。
  - `setup` モジュールに対する `_execute_module` 呼び出しは、内部的な GraalVM エラーを回避するためにブリッジ内で用意された軽量なモック実装へバイパスされます。
  - `_transfer_file` や `_execute_remote_stat` を実装し、Java の `Connection` オブジェクトを介した実際のファイル操作と連携します。
- **`Templar`**:
  - `template` メソッドにより Jinja2 テンプレートの評価をサポートします。
  - `evaluate_conditional` は Python の `eval()` を用いて実装されており、`assert` モジュール等の条件評価に対応しています。
- **`MockLoader`, `MockShell`**:
  - `path_dwim`（パスの絶対パス化）や `path_has_trailing_slash` など、Action Plugin（特に `fetch` や `copy`）が内部で利用するパス操作ユーティリティを提供します。
  - `ansible.plugins.loader.module_loader` をモック化し、`gather_facts` 等の複雑な Action Plugin が内部で依存するモジュール検索ロジックをサポートします。

## 5. モジュール実行と `AnsibleModule` (Module Execution Mocks)

モジュール内部で利用される `ansible.module_utils.basic.AnsibleModule` の高度なモック実装です。

- **引数処理と型変換**:
  - `argument_spec` に基づき、Java から渡された引数を `list`, `str`, `path`, `bool`, `int` へ自動的にキャストします。
- **`run_command` エミュレータ**:
  - 実際のコマンド実行は Java の `Connection` オブジェクトを介して行われます。
  - **`getent` エミュレータ**: Linux 環境以外でも `getent passwd root` などのコマンドが期待通り動作するよう、`run_command` 内部にハードコードされたレスポンスを返すロジックが含まれています。
  - **Java 結果解析の堅牢化**: Java の `ConnectionResult` オブジェクトから戻り値を取得する際、メソッド呼び出しに失敗した場合は `toString()` の出力を正規表現で解析するフォールバック処理を備えています。
- **ファイル属性操作**:
  - `set_fs_attributes_if_different` 等をモック化し、OS の実際の権限（owner/group）操作をスキップしつつ、要求された属性を内部に保持し、実行結果の JSON に反映させます。これにより `changed` ステータスの正確な追跡を可能にしています。

## 6. 今後の課題と展望

- **ネイティブ拡張の制限**: 現在、`cryptography` や `PyYAML` の C 拡張部分は完全に無効化（Pure Python 版または None への差し替え）されています。
- **モックの動的削減**: GraalPy の互換性向上に伴い、可能な限りオリジナルの Ansible コードへの差し戻しを検討します。
- **Jinja2 互換性**: 現在の `Templar` モックは Jinja2 の一部の高度な機能やフィルターに制限があるため、継続的な改善が必要です。
