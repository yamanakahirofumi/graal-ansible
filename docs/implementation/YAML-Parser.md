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
