# YAML解析エンジン (YAML Parser)

`graal-ansible` は、Ansible Playbook (YAML形式) を効率的に解析し、Java の不変オブジェクト (Record) にマッピングするために、**SnakeYAML 2.x** を採用します。

## 1. 解析ライブラリとバージョン

- **ライブラリ**: [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml)
- **バージョン**: 2.x 以上 (セキュリティおよび GraalVM 対応の観点)

## 2. 実装のポイント

### 2.1 不変オブジェクトへのマッピング (Java Record)

- `SnakeYAML` の `Constructor` をカスタマイズし、解析結果を Java 14 以降の `record` クラスに直接マッピングします。
- これにより、Playbook データが読み込み後に変更されることを防ぎ、並行実行時の安全性を確保します。

### 2.2 Ansible 特有の構造への対応

- **リストとディクショナリの混在**: `tasks:` セクション内での複雑なリスト構造を、型安全に解析します。
- **YAML タグの処理**: Ansible 本家がサポートする `!vault`, `!unsafe` 等のタグに対して、独自の `Representer` / `Constructor` を登録し、適切にオブジェクト化します。

## 3. Native Image への対応

- `SnakeYAML` は実行時にリフレクションを多用するため、GraalVM Native Image で動作させるためには `reflect-config.json` の設定が必要です。
- **動的生成**: 解析対象となる `record` クラスの一覧を抽出し、ビルド時にリフレクション設定を自動生成する仕組みを検討します。

## 4. 解析フロー

1. **InputStream** 経由で Playbook ファイルを読み込む。
2. `Yaml` インスタンスにより、汎用的な `Map<String, Object>` または `List<Object>` に変換。
3. **PlaybookValidator** により、Ansible スキーマに準拠しているかバリデーションを実行。
4. バリデーション済みのデータを `Playbook`, `Play`, `Task` などの Record オブジェクトに変換。
