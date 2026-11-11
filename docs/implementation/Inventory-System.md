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

#### 各プロバイダーの自動判別仕様 (`supports`)

各 `InventoryProvider` 実装クラスは、対象ソースのパスおよびファイル属性に基づき、以下の基準でサポート可否を判定します。

| プロバイダー実装クラス         | サポート対象ソースの判定条件（POSIX / Linux / macOS）                                                      | サポート対象ソースの判定条件（Windows）                                   |
| :----------------------------- | :--------------------------------------------------------------------------------------------------------- | :------------------------------------------------------------------------ |
| **`PythonInventoryProvider`**  | 存在かつ通常ファイル、`.yml` または `.yaml` 拡張子を持ち、YAML 解析結果のルート辞書に `plugin` キーが存在する。 | POSIX と同一（`plugin` キーの存在）。                                     |
| **`ScriptInventoryProvider`**  | 存在かつ通常ファイル、かつ OS レベルで実行権限が付与されている（`file.canExecute() == true`）。                 | 存在かつ通常ファイル、かつスクリプト拡張子（`.py`, `.bat`, `.ps1`, `.exe`）を持つ。 |
| **`FileInventoryProvider`**    | 存在かつ通常ファイル、かつ OS レベルで実行権限が付与されていない（`!file.canExecute()`）。                | 存在かつ通常ファイル、かつ静的拡張子（`.ini`, `.yml`, `.yaml`, `.txt`）を持つ。 |

### 5.4 インベントリ・マネージャー (InventoryManager)
複数の `InventoryProvider` を管理し、複数のインベントリソース（ファイル、スクリプト、プラグイン、ディレクトリ）を一つの `Inventory` オブジェクトに透過的に統合（マージ）する役割を担います。

#### プロバイダー登録順序と解決パイプライン
`InventoryManager` は `addProvider(InventoryProvider)` で登録されたプロバイダーリストを順次走査し、`supports(source)` が最初に `true` を返したプロバイダーにロード処理（`load(source, inventory)`）を委譲します。
- 推奨される登録順序:
  1. `PythonInventoryProvider`（YAML ファイル内で `plugin` キーを持つものを優先検出）
  2. `ScriptInventoryProvider`（実行権限またはスクリプト拡張子を持つものを検出）
  3. `FileInventoryProvider`（通常の INI / YAML 静的設定ファイルを検出）

### 5.5 実行時の考慮事項
- **キャッシュ**: 動的インベントリの取得はコストが高いため、同一実行セッション内でのキャッシュ機構を設けます。
- **環境変数**: スクリプト実行時、管理ノードの環境変数を継承させつつ、必要に応じて `ANSIBLE_` 等の変数を追加注入します。

## 6. インベントリ・ディレクトリのサポート (Inventory Directory Support)

単一のファイルだけでなく、ディレクトリをインベントリソースとして指定した場合の挙動を定義します。

### 6.1 ディレクトリの再帰的走査とファイル収集
- `InventoryManager.loadInventory` は、指定されたソースパスがディレクトリ（`file.isDirectory()`）である場合、`collectFiles` メソッドを用いて配下を再帰的に走査します。
- 発見されたファイルは、親ディレクトリパスを含まない**ファイル単体のベース名（`f.getName()`）に基づき、大文字小文字を区別しないアルファベット順（`Comparator.comparing(f -> f.getName().toLowerCase())`）** にソートされてから順次読み込まれます。

### 6.2 除外ルール
走査時、以下のファイルおよびディレクトリは無視され、インベントリ探索対象から自動的に除外されます。

| 分類                           | 除外判定基準                         | 具体例・対象パターン                                                   |
| :----------------------------- | :----------------------------------- | :--------------------------------------------------------------------- |
| **隠しファイル**               | ファイル名が `.` で始まるファイル    | `.gitignore`, `.ansible.yml`, `..hidden_host`                           |
| **特定ディレクトリ**           | 完全一致する予約済み変数ディレクトリ | `vars`, `group_vars`, `host_vars`                                      |
| **バックアップ・一時ファイル** | 対象拡張子で終了するファイル名       | `~`, `.bak`, `.old`, `.orig`, `.retry`, `.rpmnew`, `.rpmsave`, `.tmp`  |

### 6.3 エラーハンドリングの差異
- **明示的な指定**: ユーザーが CLI 等で明示的に指定したファイルが、どの `InventoryProvider` でもサポートされていない（または存在しない）場合は、`RuntimeException("No inventory provider found for source: " + source)` をスローして実行を即座に中断します。
- **ディレクトリ走査時**: ディレクトリ走査中に発見されたファイルが、サポートされていない形式（例: バイナリファイルや未知の拡張子）である場合は、ログに警告（`LOGGER.warning("Skipping unsupported inventory file discovered in directory: " + source)`）を出力した上で安全にスキップし、他のファイルの処理を継続します。

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

`HostPatternParser.expandPattern` は、分割されたトークン（ホストパターン）に含まれるブラケット `[...]` を解析し、全パターンのリストへと再帰的に展開します。単一トークン内に複数のブラケットが存在する場合は、それらの直積（Cartesian Product）を自動的に生成します。

#### ブラケット内パターンの解析と正規表現ルール
ブラケット内部の文字列は、範囲区切り文字としてコロン（`:`）およびハイフン（`-`）の両方をサポートします。

- **数値範囲 (Numeric Range)**:
  - 正規表現 `^(\d+)([:-])(\d+)$` を用いて解析を行います。
  - **ゼロ埋め (Zero-Padding)**: 開始文字列（`startStr`）の長さが 2 以上かつ先頭が `"0"` である場合（例: `"01"`）、`String.format("%0" + padLength + "d", current)` を用いて出力される全数値文字列の桁数を `startStr` の長さに揃えて左詰め（0 埋め）します。
  - **範囲指定例**: `[1:5]`, `[1-5]`, `[01:05]`, `[01-05]`
- **アルファベット範囲 (Alphabetical Range)**:
  - 正規表現 `^([a-zA-Z])([:-])([a-zA-Z])$` を用いて解析を行います。
  - `startChar` から `endChar` までの文字コード範囲を展開します。
  - **範囲指定例**: `[a:e]`, `[a-e]`, `[A:E]`, `[A-E]`
- **方向性（増加・減少 / リバース）**:
  - `start` が `end` 以下の場合はステップ `+1` で順方向に展開（例: `1` から `5`）。
  - `start` が `end` より大きい場合はステップ `-1` で逆方向に展開（例: `5` から `1` や `e` から `a`）。

#### 複数ブラケットの直積展開（再帰展開）
単一のホストパターン内に複数の `[...]` が含まれる場合（例: `node[1:2]-site[a:b]`）、最初のブラケットを展開した後に生成された各展開文字列に対して再帰的に `expandPattern` を呼び出すことで、全要素の直積（Cartesian Product）を安全に生成します。
- 例: `node[1:2]-site[a:b]` -> `node1-sitea`, `node1-siteb`, `node2-sitea`, `node2-siteb`

#### 範囲外・非範囲ブラケットのフォールバック動作
ブラケット内の文字列が数値範囲またはアルファベット範囲の正規表現にマッチしない場合、あるいは閉じブラケットが存在しない場合は、ブラケット文字をそのままリテラル文字列として維持しつつ、後続の接尾辞（suffix）に対して再帰的にパターン展開を適用します。

## 8. 複数インベントリソースのマージ仕様 (Multi-Source Inventory Merging)

`InventoryManager` が複数のソース（ファイル、スクリプト、プラグイン）から読み込んだインベントリデータを統合する際のマージアルゴリズムおよび整合性維持規則です。

### 8.1 マージフローとデータ構造の統合
単一の `Inventory(Group all)` インスタンスに対して、各プロバイダーが順次 `load(source, inventory)` を実行してマージを蓄積します。

```
Inventory (all)
├── variables: Map.putAll() による上書きマージ
├── hosts: ホスト名完全一致で検索 -> 既存ホスト変数の putAll() マージ / 新規ホストの追加
└── children: グループ名完全一致で検索 -> 再帰的 mergeGroup()
```

### 8.2 グループおよびホストのマージ規則 (`mergeGroup`)
1. **グループ変数のマージ**:
   - `target.variables().putAll(source.variables())` により、後から読み込まれたインベントリソースのグループ変数が既存の変数を上書きします。
2. **ホスト定義のマージ**:
   - ソースグループ内の各ホストについて、ターゲットグループ内に同名ホスト（`Host.name()`）が既に存在するかを探索します。
   - 既存の場合は、`targetHost.variables().putAll(sourceHost.variables())` によりホスト変数が上書きマージされます。
   - 存在しない場合は、新しい `Host` インスタンスを作成してターゲットグループのホストリストに追加します。
3. **子グループ（階層構造）の再帰マージ**:
   - ソースグループ内の各子グループ（`children`）について、ターゲットグループの子グループリスト内を走査します。
   - 存在しない場合は新規 `Group` を追加し、存在する場合は子グループ同士を再帰的に `mergeGroup(targetChild, sourceChild)` でマージします。

## 9. 実行時動的インベントリ更新仕様 (Runtime Inventory Updates)

`add_host` および `group_by` モジュールによって Playbook 実行中にインベントリが動的に変更される際の動作仕様です。

### 9.1 動的更新ライフサイクルと `addHostToGroup`

`Inventory.addHostToGroup(String hostName, String groupName)` は以下のアルゴリズムに従ってインベントリを更新します：

1. **ホストの解決と自動生成**:
   - インベントリ全体（`all` グループ配下の全階層）から指定された `hostName` のホストを探索します。
   - 未登録の場合は、新しい `Host(hostName)` をインスタンス化し、ルートグループ `all.hosts()` へ追加して登録します。
2. **グループの解決と自動生成**:
   - `groupName` が `"all"` の場合は追加処理を行わず完了します。
   - 指定されたグループ名が存在するか探索し、存在しない場合は新しい `Group(groupName)` を生成して `all.children()` へ追加します。
3. **グループへのホスト紐付け**:
   - 対象グループの `hosts()` リスト内に該当ホストが存在しない場合のみ追加（重複防止）します。

### 9.2 スレッド安全性と実行コンテキスト
- 内部コレクション（`hosts` リスト、`children` リスト、`variables` マップ）は実行時更新が可能な可変型（`ArrayList`, `HashMap`）で保持されています。
- 動的に追加されたホストおよびグループは、以降のタスクや Play において直ちにパターンマッチング（`hosts: new_group` 等）や変数解決（`hostvars`）の対象となります。
