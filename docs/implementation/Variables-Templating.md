# 変数とテンプレートの実装詳細 (Variables and Templating Implementation)

本ドキュメントでは、`graal-ansible` における変数の管理、優先順位の解決、および Jinja2 互換テンプレートエンジンの統合について詳述します。

## 1. テンプレートエンジンの選定

Ansible の Jinja2 テンプレートとの高い互換性を Java で実現するため、**Jinjava** を採用します。

- **ライブラリ**: [Jinjava (HubSpot)](https://github.com/HubSpot/jinjava)
- **選定理由**:
    - Java ベースで Jinja2 構文を高度にサポートしている。
    - 独自フィルターやテストの追加が容易。
    - GraalVM Native Image での動作実績がある。

## 2. 変数の解決フローと優先順位

Ansible の複雑な変数の優先順位を簡略化しつつ、主要なレベルをサポートします。本プロジェクトでは以下の順序で変数がマージされ、**後に定義されたものが優先（上書き）**されます。

1.  **Role Defaults**: ロールの `defaults/main.yml`（将来対応）
2.  **Inventory Group Vars (all)**: `all` グループ変数（`group_vars/all.yml` 含む）
3.  **Inventory Group Vars (Parent)**: 親グループの変数
4.  **Inventory Group Vars (Child)**: 子グループの変数
5.  **Inventory Host Vars**: ホスト固有の変数（`host_vars/{{ host_name }}.yml` 含む）
6.  **Play Vars**: Play 定義内の `vars`
7.  **Play Vars Files**: Play 定義内の `vars_files`
8.  **Role Vars**: ロールの `vars/main.yml`（将来対応）
9.  **Task Vars**: Task 定義内の `vars`
10. **Registered Variables (hostVars)**: `register` によって実行時に保存された変数
11. **Extra Vars**: コマンドライン引数 `-e` / `--extra-vars`

### 2.1 インベントリ変数の解決
ホストが複数のグループに属している場合、それぞれのグループパスを辿って変数を収集します。この際、**子グループは親グループの変数を上書き**します。最終的にホスト固有の変数がすべてのグループ変数を上書きします。

### 2.2 マージ戦略
- ディクショナリ（Map）型の変数は、デフォルトで「上書き」としますが、設定により「再帰的マージ」を選択可能にすることを検討します。

## 3. 遅延評価 (Lazy Evaluation)

Ansible と同様に、変数は定義時ではなく、実際に使用されるタイミングでテンプレート展開されます。

- **実装方法**:
    - 実行エンジン内の `VariableManager` が全変数を保持。
    - タスク実行直前に、そのタスクで使用される引数（`args`）に対して再帰的に `Jinjava` を適用。
    - 未定義の変数が参照された場合、原則として実行エラーとします（Ansible 互換）。

## 4. 独自フィルターとテストの拡張

Ansible 特有のフィルターは、Jinjava の `Filter` インターフェースを実装して追加されています。現在、以下のフィルターが実装済みです。

- `bool`: 値を真偽値に変換。
- `combine`: 辞書（Map）をマージ。
- `default`: 未定義値に対するデフォルト値を設定。
- `dict2items`: 辞書をリスト形式に変換。
- `ipaddr`: IP アドレスの検証・操作。
- `to_json`: オブジェクトを JSON 文字列に変換。
- `to_yaml`: オブジェクトを YAML 文字列に変換。

```java
// 実装イメージ
jinjava.getGlobalContext().registerFilter(new CombineFilter());
```

## 5. Native Image への対応

- Jinjava が内部で使用するリフレクション情報を `reflect-config.json` に定義する必要があります。
- 動的なクラスロードが発生する箇所を特定し、ビルド時に静的に解決されるよう設定します。
