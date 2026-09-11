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

## 6. 予約済みタスクキーとモジュールアクションの抽出 (Task Action Extraction)

Ansible のタスク定義では、タスク属性（制御キーワード）と実行するモジュール名（アクション）が同一のマップ内にフラットに混在します。

- **予約済みタスクキー (`RESERVED_TASK_KEYS`)**:
  `YamlParser` では以下の予約キーセットを定義し、これらをタスクの制御属性として識別・パースします。
  `name`, `register`, `when`, `loop`, `loop_control`, `until`, `retries`, `delay`, `ignore_errors`, `ignore_unreachable`, `tags`, `become`, `become_user`, `become_method`, `become_flags`, `vars`, `notify`, `listen`, `with_items`, `with_list`, `with_dict`, `failed_when`, `changed_when`, `delegate_to`, `delegate_facts`, `run_once`, `block`, `rescue`, `always`, `check_mode`, `environment`, `any_errors_fatal`, `async`, `poll`, `throttle`, `max_fail_percentage`, `pre_tasks`, `post_tasks`
- **モジュールアクションの決定規則**:
  - タスクマップ内のキーを順次走査し、`RESERVED_TASK_KEYS` に含まれない**最初のキー**をモジュール名（`action`）として判定します。
  - **引数（`args`）の抽出**:
    - 値が Map の場合: 引数マップとして保持します（例: `copy: { src: "a", dest: "b" }`）。
    - 値が String の場合: フリーフォーム引数として `_raw_params` キーを持つマップに自動変換します（例: `command: "ls -la"` -> `{ "_raw_params": "ls -la" }`）。
  - ブロック構造（`block`）を含まないにもかかわらず非予約キーが存在しないタスクマップは、モジュール未指定として解析例外（`IllegalArgumentException`）をスローします。

## 7. 構造化タスクブロックの解析 (`block`, `rescue`, `always`)

例外処理および論理グループ化のためのブロック構造の解析ルールです。

- **再帰パース構造**:
  - `block`, `rescue`, `always` キーに割り当てられたタスクリストを検出した場合、`YamlParser.parseTaskList` を再帰的に呼び出し、それぞれ `List<Task>` オブジェクトとして親 `Task` レコードに保持します。
- **タグの継承**:
  - ブロックを親とする子タスク（`block`, `rescue`, `always` 内のタスク）は、Play や親タスクで指定されたタグ（`inheritedTags`）を自動的に継承します。

## 8. レガシー・ループ構文の変換 (`with_items`, `with_dict`, `with_list`)

`loop` キーワード以前のレガシーな `with_*` ループ構文を、テンプレートフィルター適用済みの標準 `loop` 表現に内部変換します。

- **`with_items` 変換 (`wrapWithFilter`)**:
  - 値が `{{ ... }}` 形式の文字列の場合、式の末尾に `| flatten(levels=1)` フィルターを追加したテンプレート文字列に書き換えます。
  - 値がオブジェクト/リストの場合、`__ansible_loop_source` と `__ansible_loop_filter: "flatten(levels=1)"` を保持する内部構造体へラップします。
- **`with_dict` 変換**:
  - 式の末尾に `| dict2items` フィルターを追加（または上記内部構造体にラップ）し、辞書を `key`/`value` リストに変換して `loop` に割り当てます。
- **`with_list` 変換**:
  - 平坦化を適用せず、渡されたリストをそのまま `loop` へ割り当てます。

## 9. Play レベル構成要素の解析 (`pre_tasks`, `post_tasks`, `roles`, `handlers`, `vars_files`, `vars_prompt`)

Play レベルで指定されるタスク群および構成要素の解析規則です。

- **`pre_tasks` / `post_tasks`**:
  - メインタスク（`tasks`）の前後に実行されるタスクリストを `parseTaskList` でパースし、Play レベルのタグを伝播させます。
- **`roles`**:
  - 文字列指定（ショートハンド: `roles: [common, web]`) の場合は `Role(name)` を生成。
  - マップ指定 (`role: web, vars: { port: 80 }`) の場合は `role` キー（または `vars`/`tags`/`when` 以外の最初のキー）をロール名とし、残りのキーを `roleVars` に保持する `Role` オブジェクトを構築します。
- **`handlers`**:
  - 通知トリガー用ハンドラータスクを `parseTaskList` でパースし、`Play.handlers` に格納します。
- **`vars_files`**:
  - 単一文字列または文字列リスト形式のファイルパスを `Play.varsFiles` リストへ格納します。
- **`vars_prompt`**:
  - インタラクティブ入力定義（プロンプトメッセージ、変数名等）を Map のリストとしてパースし、単一文字列指定の場合は `{"name": str}` に標準化します。

## 10. `import_playbook` のパス解決とコンテキスト継承

トップレベルにおける別プレイブックの静的インポート処理ルールです。

- **パス解決 (`handleImportPlaybook`)**:
  - `import_playbook` で指定されたファイルパスが相対パスの場合、現在のプレイブックのディレクトリ（`currentDir`）を基準に絶対パスへ補正します。
- **変数とタグの継承と伝播**:
  - `import_playbook` ステートメントに指定された `vars` および `tags` は、上位の `import_playbook` から引き継いだ `inheritedVars` / `inheritedTags` とマージされます。
  - マージされた変数は、インポートされた Play の `vars` に統合されます（`mergedVars.putAll(inheritedVars)`）。Ansible 規格に従い、インポートステートメントで指定された変数は、インポート先の Play 内の初期 `vars` よりも優先されます。

## 11. カスタム YAML タグのパースと構造化 (`AnsibleYamlConstructor`)

Ansible 特有のカスタム YAML タグおよび未知タグを安全に処理するためのクラスター構造です。

- **`!vault` タグのマッピング**:
  - `AnsibleYamlConstructor` (SnakeYAML `SafeConstructor` 拡張) にて `!vault` タグを検知し、スカラー値を `VaultDecryptedValue` オブジェクトに直列化変換します。これにより、テンプレート評価時のネイティブ動的復号を可能にします。
- **未知カスタムタグの透過的フォールバック**:
  - `!` で始まる未定義タグ（例: `!unsafe`, `!unknown_seq`）に遭遇した際、例外をスローせず Node の種類に応じたベース型にフォールバックします：
    - `scalar` Node -> `Tag.STR` (文字列)
    - `sequence` Node -> `Tag.SEQ` (リスト)
    - `mapping` Node -> `Tag.MAP` (マップ)
