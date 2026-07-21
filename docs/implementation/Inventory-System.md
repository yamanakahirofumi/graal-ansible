# インベントリシステムの実装詳細 (Inventory System Implementation)

本ドキュメントでは、Ansible 互換のインベントリ管理システムの内部設計および実装方法について詳述します。

## 1. データ構造

インベントリ情報を表現するために、以下の Java Record を使用します。

### 1.1 Host
個々のターゲットノードを表します。
- `name`: ホスト名または IP アドレス。
- `variables`: ターゲットノード固有の変数。

### 1.2 Group
ターゲットノードの集合を表します。
- `name`: グループ名。
- `hosts`: グループに属するターゲットノードのリスト。
- `children`: 子グループのリスト。
- `variables`: グループ変数。

### 1.3 Inventory
インベントリ全体を管理します。
- `all`: 全てのターゲットノードとグループを内包するルートグループ。

### 1.4 構造と可変性
すべてのデータモデル（Host, Group, Inventory）は Java **Record** として実装されていますが、`add_host` や `group_by` モジュールによる実行時の動的なインベントリ更新をサポートするため、内部で保持するリスト（hosts, children）やマップ（variables）は可変（Mutable）なコレクション（`ArrayList`, `HashMap`）として保持されます。

## 2. 解析ロジック (INI 形式)

`IniInventoryParser` は以下の規則に従って INI ファイルを解析します。

1.  **セクションなし**: ファイルの先頭にあるセクションに属さないホストは `all` グループに直接追加されます。
2.  **グループセクション (`[group_name]`)**: その後に続くホストは、指定されたグループに属します。
3.  **子グループセクション (`[group_name:children]`)**: 指定されたグループの子として登録されるグループ名を記述します。
4.  **変数セクション (`[group_name:vars]`)**: グループ全体に適用される変数を `key=value` 形式で記述します。

## 3. 解析ロジック (YAML 形式)

`YamlInventoryParser` は以下の規則に従って YAML ファイルを解析します。

1.  **ルート要素**: 原則として `all` グループをルートとします。
2.  **ホスト定義 (`hosts`)**: 各グループ配下の `hosts` キーにホスト名を記述します。ホスト個別の変数はその値として定義します。
3.  **子グループ定義 (`children`)**: 各グループ配下の `children` キーに子グループを定義します。
4.  **変数定義 (`vars`)**: 各グループ配下の `vars` キーにグループ変数を定義します。

## 4. 変数の解決順序

インベントリにおける変数の解決順序は、[変数とテンプレートの実装詳細](Variables-Templating.md#2-変数優先順位-variable-precedence) に基づき、以下の優先順位で解決されます。

1. `all` グループ変数
2. 親グループ変数
3. 子グループ変数
4. ホスト固有の変数

### 4.1 複数グループに属する場合の挙動
ターゲットノードが複数のグループに属している場合、それぞれのグループパスを辿って変数を収集し、マージします。この際、ターゲットノード固有の変数が最終的にすべてのグループ変数を上書きします。

### 4.2 優先順位 3-10 の厳密な実装
[変数とテンプレートの実装詳細](Variables-Templating.md#2-変数優先順位-variable-precedence) に基づく優先順位 3 から 10 を正確に実現するため、`Inventory.java` においてグループ変数（`getGroupVariablesForHost`）とホスト変数（`getHostVariables`）の取得メソッドを分離しています。これにより、`VariableManager` は以下の順序で変数をマージし、インベントリホスト変数（優先度 8）がプレイブックグループ変数（優先度 7）を正しく上書きすることを保証します。

1.  優先度 3-7: `getGroupVariablesForHost` で取得されたインベントリ/プレイブックのグループ変数を順次マージ。
2.  優先度 8-10: `getHostVariables` で取得されたインベントリホスト変数、および `host_vars/*` をマージ。

## 5. 外部インベントリの統合 (External Inventory Integration) {#5-外部インベントリの統合-external-inventory-integration}

Ansible 互換の外部インベントリ（スクリプトまたはプラグイン）を統合するための実装方針です。

### 5.1 スクリプト方式 (Inventory Scripts)
実行可能なプログラムから JSON 形式でインベントリ情報を取得します。

- **実行メカニズム**:
    - `ProcessBuilder` を使用して、指定されたスクリプトを `--list` 引数付きで実行します。
    - スクリプトの標準出力をキャプチャし、Jackson を用いて JSON 解析を行います。
- **データマッピング**:
    - JSON の `_meta.hostvars` セクションからホスト個別の変数を取得します。
    - 各グループ配下の `hosts`, `children`, `vars` を再帰的に解析し、内部の `Inventory` オブジェクトへマージします。

### 5.2 プラグイン方式 (Inventory Plugins)
YAML 設定ファイルに基づき、特定のソース（AWS, GCP, NetBox 等）から動的にホストを取得します。

- **実装詳細 (Python-first アプローチ)**:
    - `PythonInventoryProvider` を用いて、GraalPy 上でオリジナルの Python 製 Ansible Inventory Plugin を実行する仕組みを完全にサポートしています。
    - **サポート判定 (`supports`)**:
        - ファイルが存在し、拡張子が `.yml` または `.yaml` であるかを検証します。
        - `YamlUtil.createYaml()` を用いてファイルをロードし、最上位に `plugin` キー（例: `test_ns.test_coll.test_plugin`）が定義されている場合に対象ファイルとして自動判定します。
    - **GraalPy 統合とブリッジ**:
        - `Context` API を介して Python 実行環境を構築します。この際、ネイティブモジュールの分離設定として `python.IsolateNativeModules` に `false` を指定して初期化します。
        - Java 側で用意した OS 互換性ブリッジ（`PythonOSMock`）およびモジュールファクトリ（`PythonAnsibleModuleMock.Factory`）を Python の bindings に注入します。
        - Python の共有ブリッジ `ansible_bridge.py` を事前ロードし、`ansible.plugins.inventory` などの名前空間や `BaseInventoryPlugin`、`BaseFileInventoryPlugin` のモック群を適用します。
    - **プラグイン実行と変換 (`load`)**:
        - 解決された Python パスやライブラリパスを sys.path に追加設定した上で、`ansible_inventory_launcher.py` を実行します。
        - ランチャーは `ansible_bridge._create_inventory_plugin(plugin_name)` により本物の Python プラグインを動的に解決・インスタンス化し、`plugin.parse(inventory, loader, inventory_path)` を呼び出します。
        - 解析結果を蓄積した `InventoryData` オブジェクトから `to_dict()` 経由で JSON 形式にシリアライズされ、Java の呼び出し元に返却されます。
        - Java の `PythonInventoryProvider` は Jackson を用いてこの JSON をパースし、Java の `Inventory` レコード（Group、Host、変数、およびグループ間親子関係）へと動的にパージ・マージしてインベントリを再構築します。

### 5.3 インベントリ・プロバイダー (InventoryProvider)
インベントリのソース（静的ファイル、スクリプト、プラグイン）を抽象化するため、`InventoryProvider` インターフェースを導入し、以下の通り実装しています。

```java
public interface InventoryProvider {
    /**
     * 指定されたソースを処理可能かどうかを判定します。
     */
    boolean supports(String source);

    /**
     * ソースからインベントリを読み込み、Inventory オブジェクトを構築または更新します。
     * @param source ソースのパスまたは識別子
     * @param inventory 更新対象のインベントリ
     */
    void load(String source, Inventory inventory);
}
```

### 5.4 インベントリ・マネージャー (InventoryManager)
複数の `InventoryProvider` を管理し、複数のインベントリソースを一つの `Inventory` オブジェクトに透過的に統合（マージ）する役割を担います。

### 5.5 実行時の考慮事項
- **キャッシュ**: 動的インベントリの取得はコストが高いため、同一実行セッション内でのキャッシュ機構を設けます。
- **環境変数**: スクリプト実行時、管理ノードの環境変数を継承させつつ、必要に応じて `ANSIBLE_` 等の変数を追加注入します。

## 6. インベントリ・ディレクトリのサポート (Inventory Directory Support)

単一のファイルだけでなく、ディレクトリをインベントリソースとして指定した場合の挙動を定義します。

### 6.1 ディレクトリの再帰的走査
- `InventoryManager` は、指定されたパスがディレクトリである場合、その配下を再帰的に走査してインベントリファイルを探索します。
- 発見されたファイルは、ファイル名（パス全体ではなくベース名）に基づき、大文字小文字を区別しないアルファベット順にソートして処理されます。

### 6.2 除外ルール
走査時、以下のファイルおよびディレクトリは無視されます。

- **隠しファイル**: `.` で始まるファイル。
- **バックアップ・一時ファイル**: `~`, `.bak`, `.old`, `.orig`, `.retry`, `.rpmnew`, `.rpmsave`, `.tmp` で終わるファイル。
- **特定のディレクトリ**: `vars`, `group_vars`, `host_vars` という名前のディレクトリ（これらは変数定義として別途処理されるため）。

### 6.3 エラーハンドリングの差異
- **明示的な指定**: ユーザーが CLI 等で明示的に指定したファイルが、どの `InventoryProvider` でもサポートされていない（または存在しない）場合は、`RuntimeException` をスローして実行を中断します。
- **ディレクトリ走査時**: ディレクトリ走査中に発見されたファイルが、サポートされていない形式（例: バイナリファイルや未知の拡張子）である場合は、警告をログに出力した上でスキップし、他のファイルの処理を継続します。

## 7. ホストパターンのマッチングと範囲パターン展開 (Bracket-Aware Splitting & Range Pattern Expansion)

ホストパターン（`hosts` キーや `--limit` オプション）に含まれる範囲指定や複数指定を正しく解析・展開するための詳細設計です。

### 7.1 Bracket-Aware Splitting（ブラケットを考慮した分割）

ホストパターン全体はカンマ（`,`）またはコロン（`:`）で分割されますが、範囲指定内のコロンやカンマ（例: `web[0:5]`）で誤分割されないよう、ブラケットのネストを考慮して分割を行います。

#### アルゴリズム
1. 入力文字列を1文字ずつ走査します。
2. 開きブラケット `[` が出現した場合は、ネストレベルをインクリメントします。
3. 閉じブラケット `]` が出現した場合は、ネストレベルをデクリメントします。
4. ネストレベルが `0` のときにカンマ（`,`）またはコロン（`:`）が出現した位置でのみ、文字列を分割します。
5. 分割された各トークンに対して、不要な空白をトリミングします。

### 7.2 範囲パターンの展開（Range Pattern Expansion）

分割されたトークン（ホストパターン）にブラケット `[...]` が含まれる場合、それを解析して全パターンのリストに展開します。単一トークン内に複数のブラケットが存在する場合は、それらの直積（Cartesian Product）を生成します。

#### ブラケット内パターンの解析
ブラケット `[start:end]` の内部は `:` で分割され、`start` と `end` の2つの境界値として扱われます。

- **数値範囲 (Numeric Range)**:
  - `start` と `end` が共に整数値である場合、その範囲を展開します。
  - **ゼロ埋め (Zero-Padding)**: `start` 文字列の長さが `end` 文字列の長さと異なり、かつ `start` が `0` で始まる場合（例: `01`）、展開される各数値の文字列長を `start` の長さに揃えるために `0` で左詰めします。
- **アルファベット範囲 (Alphabetical Range)**:
  - `start` と `end` が共に1文字の英文字（`a-z`, `A-Z`）である場合、その文字コード範囲を展開します。
- **方向性（増加・減少）**:
  - `start` が `end` 以下の場合は順方向に展開（例: `1` から `5`）。
  - `start` が `end` より大きい場合は逆方向に展開（例: `5` から `1`）。

#### 複数ブラケットの展開（再帰展開）
単一のホストパターン内に複数の `[...]` が含まれる場合（例: `node[1:2]-site[a:b]`）、再帰的な展開アプローチ、または直積生成アルゴリズムを用いて、すべての組み合わせを生成します。
- 例: `node[1:2]-site[a:b]` -> `node1-sitea`, `node1-siteb`, `node2-sitea`, `node2-siteb`
