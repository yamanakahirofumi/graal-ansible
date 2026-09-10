# YAML解析エンジン (YAML Parser)

`graal-ansible` は、Ansible Playbook (YAML形式) を効率的に解析し、Java の不変オブジェクト (Record) にマッピングするために、**SnakeYAML 2.x** を採用します。

## 1. 解析ライブラリとバージョン

- **ライブラリ**: [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml)
- **バージョン**: 2.x 以上 (セキュリティおよび GraalVM 対応の観点)

## 2. 実装のポイント

### 2.1 統一的な解析基盤 (YamlUtil)

- `org.example.ansible.util.YamlUtil` を通じて `Yaml` インスタンスの生成を一本化しています。
- Playbook だけでなく、インベントリや変数ファイルの解析においても `YamlUtil.createYaml()` を使用することで、プロジェクト全体で一貫した解析ルールを適用しています。

### 2.2 不変オブジェクトへのマッピング (Java Record)

- `SnakeYAML` の `Constructor` をカスタマイズし、解析結果を Java 14 以降の `record` クラスに直接マッピングします。
- **可変性の確保**: `record` 自体は不変ですが、`add_host` や `group_by` モジュールによる実行時の動的な更新をサポートするため、Record が保持するリストやマップは、解析時に `ArrayList` や `HashMap` などの可変（Mutable）なコレクションとしてインスタンス化されます。

### 2.3 Ansible 特有の構造への対応

- **リストとディクショナリの混在**: `tasks:` セクション内での複雑なリスト構造を、型安全に解析します。
- **YAML タグの処理とフォールバック**:
    - `AnsibleYamlConstructor` (SafeConstructor を継承) を実装し、Ansible 特有のタグを処理します。
    - `!vault`, `!unsafe`, `!unknown_seq` などの未知または未対応のカスタムタグに遭遇した場合、解析エラーで停止させるのではなく、対応するベースの YAML 型（String, List, Map 等）として透過的にフォールバックして処理を継続します。

## 3. Native Image への対応

- `SnakeYAML` は実行時にリフレクションを多用するため、GraalVM Native Image で動作させるためには `reflect-config.json` の設定が必要です。
- **動的生成**: 解析対象となる `record` クラスの一覧を抽出し、ビルド時にリフレクション設定を自動生成する仕組みを検討します。

## 4. 解析フロー

1. **InputStream** 経由で Playbook ファイルを読み込む。
2. `Yaml` インスタンスにより、汎用的な `Map<String, Object>` または `List<Object>` に変換。
3. **PlaybookValidator** により、Ansible スキーマに準拠しているかバリデーションを実行（※バリデーターの実装は計画中）。
4. 解析済みのデータを `Playbook`, `Play`, `Task` などの Record オブジェクトに変換。

## 5. トップレベル・ディレクティブの処理 (Top-level Directives)

Playbook のルート階層（トップレベル）で指定可能なディレクティブの処理について。

- **`import_playbook`**:
    - 通常の Play（リスト形式）とは別に、トップレベルでの `import_playbook` キーを検出し、再帰的に読み込みを行います。
    - 読み込まれたプレイブックの内容は、インポート元のコンテキストと適切にマージされ、単一の実行可能なプレイのシーケンスとして構築されます。
- **拡張性**:
    - 将来的に `import_tasks` や `import_role` がトップレベルでサポートされる場合（Ansible の古いバージョンとの互換性など）に備え、解析ロジックは特定のキーに依存しない柔軟な構造を持たせています。

## 6. 予約キーワード判定とモジュールアクション分離 (Reserved Key Resolution & Action Extraction)

Ansible のタスク定義においては、タスク制御用の制御キーワード（`when`, `loop`, `become` 等）と、実行対象のモジュール名（`command`, `copy`, `apt` 等）が単一の YAML マップ内にフラットに混在して記述されます。`YamlParser` はこれを型安全に分離・パースします。

### 6.1 予約キーワード (`RESERVED_TASK_KEYS`)
`YamlParser` は、以下の 39 個の予約語集合を保持し、これらに一致するキーを制御用属性として処理します。

`name`, `register`, `when`, `loop`, `loop_control`, `until`, `retries`, `delay`, `ignore_errors`, `ignore_unreachable`, `tags`, `become`, `become_user`, `become_method`, `become_flags`, `vars`, `notify`, `listen`, `with_items`, `with_list`, `with_dict`, `failed_when`, `changed_when`, `delegate_to`, `delegate_facts`, `run_once`, `block`, `rescue`, `always`, `check_mode`, `environment`, `any_errors_fatal`, `async`, `poll`, `throttle`, `max_fail_percentage`, `pre_tasks`, `post_tasks`

### 6.2 アクション（モジュール名）と引数の抽出ロジック
1. タスクの YAML マップエントリを走査し、`RESERVED_TASK_KEYS` に含まれない**最初のキー**をモジュールのアクション名（`action`）として特定します。
2. モジュールの指定値のデータ型に応じて以下のように引数マップ（`args`）を構築します：
   - **マップ構造 (`Map`)**: 指定されたマップをそのままモジュール引数 `args` として適用（例: `copy: {src: "a", dest: "b"}`）。
   - **単一文字列 (`String`)**: Ansible 互換のパラメータ形式として、キー `_raw_params` に文字列を格納したマップ `Map.of("_raw_params", strValue)` を構築（例: `command: "ls -la"`）。
3. **未定義エラーチェック**: アクション名が検出されず、かつ後述の `block` 定義も存在しない場合、構造不正として `IllegalArgumentException("Task '<name>' is missing a module/action.")` をスローします。

## 7. 構造化タスクブロックのパース (Block, Rescue, Always Parsing)

例外処理構造である `block`, `rescue`, `always` キーが存在する場合、`YamlParser` はネストされたタスクリストを再帰的にパースします。

- **親コンテキストからのタグ継承**:
  - `parseTaskList` メソッドを介して子タスク群をパースする際、親の `Play` または `Task`（`block` 自体）から継承されたタグ（`inheritedTags`）が自動的に引き継がれます。
  - 親タグと子タスク固有の `tags` は結合され、各子タスクの `taskTags` リストに保持されます。
- **データ構造**:
  - `Task` レコードの `block`, `rescue`, `always` フィールドに、解析済みの `List<Task>` オブジェクトとして格納されます。

## 8. レガシー・ループ構文の内部変換とフィルタラッピング (Legacy Loop Syntax Conversion)

Ansible 2.5 以前のレガシーなループ構文（`with_items`, `with_dict`, `with_list`）を検出した場合、`YamlParser` は標準の `loop` 処理へ動的に変換・マッピングします。

### 8.1 構文ごとの変換ルール
- **`with_items`**:
  - 渡されたリストを 1 段階平坦化するため、`wrapWithFilter(withItems, "flatten(levels=1)")` を実行します。
  - Jinja2 テンプレート文字列（`{{ my_list }}`）の場合は、`"{{ my_list | flatten(levels=1) }}"` に変換したテンプレート文字列を `loop` フィールドにセットします。
  - Java のオブジェクト構造（List/Map等）の場合は、基底オブジェクトを示す `__ansible_loop_source` キーと適用フィルターを示す `__ansible_loop_filter` キーを持つ特殊なラッパー Map にラップして `loop` に割り当てます。
- **`with_dict`**:
  - 辞書オブジェクトを `key`/`value` リストへ変換するため、`wrapWithFilter(withDict, "dict2items")` を呼び出し、`dict2items` フィルターをラップします。
- **`with_list`**:
  - 平坦化を行わないため、渡されたオブジェクトをそのまま `loop` フィールドへ割り当てます。

## 9. プレイレベル構成要素のパース (Play-Level Components Parsing)

Playbook の各 Play (`Play` レコード) に含まれる補助的なセクションや変数・ロール定義のパース仕様です。

- **`pre_tasks` / `post_tasks` / `handlers`**:
  - 各セクションに記述されたタスクリストは `parseTaskList` を通じてパースされ、Play レベルのタグ（`playTags`）を継承します。
  - 登録されたハンドラーの通知キー（`notify`）およびトピック（`listen`）は、単一文字列および文字列リストの両方の形式から `List<String>` へ正規化されます。
- **`roles`（ロール定義）**:
  - **ショートハンド形式 (文字列)**: リスト要素が文字列の場合（例: `- common`）、ロール名として `Role(name)` インスタンスを生成します。
  - **パラメータ付き辞書形式 (マップ)**: リスト要素が Map の場合、`role` キーの値（または最初の非予約キー）をロール名とし、指定された引数・変数を保持する `Role(name, roleVars)` インスタンスをパースします。
- **`vars_files` および `vars_prompt`**:
  - `vars_files`: 単一のパス文字列、またはパス文字列のリストのいずれの指定形式からでも `List<String>` へ集約します。
  - `vars_prompt`: 入力変数名のみの簡易指定（文字列リスト）および詳細パラメータ付き指定（マップリスト: `name`, `prompt`, `private` 等）を `List<Map<String, Object>>` へ透過的にパースします。

## 10. インポートと変数・タグの再帰的伝播 (import_playbook)

`import_playbook` ステートメントにより外部 Playbook ファイルがインポートされる際、`YamlParser.handleImportPlaybook` はファイルパスの解決とコンテキストの伝播を処理します。

- **ファイルパスの解決**:
  - 指定されたパスが相対パスの場合、現在解析中の Playbook が存在する親ディレクトリ（`currentDir`）を基準として絶対パスを構築します。
- **変数とタグのスコープ結合**:
  - `import_playbook` ステートメントに付与された `vars` (`importVars`) および `tags` (`importTags`) は、親ファイルから継承された `inheritedVars` および `inheritedTags` とマージされます。
- **Play への変数の再帰的インジェクション**:
  - インポートされた子 Playbook 内の全 Play に対して、マージ済みの継承変数マップ（`inheritedVars`）を Play レベルの `vars` へ上書き・統合し、インポート階層全体での変数優先順位（最高優先度のエクストラ変数等）を保持します。

## 11. 独自タグのカスタム処理と透過フォールバック (AnsibleYamlConstructor)

Ansible Vault などのカスタム YAML タグをエラーなく解釈するため、`YamlUtil` 内に特化した `AnsibleYamlConstructor` (SnakeYAML の `SafeConstructor` を継承) を組み込んでいます。

- **`!vault` タグのネイティブマッピング**:
  - YAML 内で `!vault` タグが検知された場合、`ConstructVault` クラスにより、その暗号化文字列コンテンツを保持する `VaultDecryptedValue` オブジェクトとしてインスタンス化されます。
- **未知・未対応タグの透過フォールバック**:
  - `!` で始まる未認識のカスタムタグ（例: `!unsafe`, `!unknown_tag`）に直面した場合、`getConstructor` をオーバーライドしてノードの種別を識別します：
    - `scalar` ノード -> `Tag.STR` (文字列) のコンストラクタへフォールバック。
    - `sequence` ノード -> `Tag.SEQ` (リスト) のコンストラクタへフォールバック。
    - `mapping` ノード -> `Tag.MAP` (マップ) のコンストラクタへフォールバック。
  - これにより、サードパーティ製のロールや複雑な Playbook に含まれる未知の YAML タグが存在しても解析を即座に破棄せず、安全にデータ構造を読み込みます。
