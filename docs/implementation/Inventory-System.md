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

## 5. 外部インベントリの統合 (External Inventory Integration)

Ansible 互換の外部インベントリ（スクリプトまたはプラグイン）を統合するための実装詳細です。

### 5.1 スクリプト方式 (Inventory Scripts)
実行可能なプログラムから JSON 形式でインベントリ情報を取得します。

- **実行メカニズム**:
    - `ProcessBuilder` を使用して、指定されたスクリプトを `--list` 引数付きで実行します。
    - スクリプトの標準出力をキャプチャし、Jackson を用いて JSON 解析を行います。
- **データマッピング**:
    - JSON の `_meta.hostvars` セクションからホスト個別の変数を取得し、`VariableManager` で利用可能な形式に変換します。
    - 各グループ配下の `hosts`, `children`, `vars` を再帰的に解析し、内部の `Inventory` オブジェクトへマージします。

### 5.2 プラグイン方式 (Inventory Plugins)
YAML 設定ファイルに基づき、特定のソース（AWS, GCP, NetBox 等）から動的にホストを取得します。

- **実装方針 (将来的な課題)**:
    - GraalPy 上でオリジナルの Ansible Inventory Plugin を実行する「Python-first」アプローチを検討します。
    - [Action Plugin 実装仕様](Action-Plugins.md) と同様のブリッジメカニズム（`ansible_bridge.py`）を利用し、プラグインを実行して得られた結果（Python 辞書）を Java 側で `Inventory` オブジェクトに変換することを計画しています。

### 5.3 インベントリ・プロバイダー (InventoryProvider)
インベントリのソース（静的ファイル、スクリプト、プラグイン）を抽象化するため、`InventoryProvider` インターフェースを導入し、`FileInventoryProvider` および `ScriptInventoryProvider` を実装済みです。

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
複数の `InventoryProvider` を管理し、複数のインベントリソースを一つの `Inventory` オブジェクトに統合（マージ）します。

### 5.5 実行時の考慮事項
- **キャッシュ**: 動的インベントリの取得はコストが高いため、同一実行セッション内でのキャッシュ機構を設けます。
- **環境変数**: スクリプト実行時、管理ノードの環境変数を継承させつつ、必要に応じて `ANSIBLE_` 等の変数を追加注入します。
