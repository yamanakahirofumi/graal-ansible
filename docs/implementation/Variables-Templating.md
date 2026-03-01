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

Ansible の複雑な変数の優先順位を簡略化しつつ、主要なレベルをサポートします。以下の順序でマージされ、後のものが優先されます。

1.  **Role Defaults**: ロールの `defaults/main.yml`（将来対応）
2.  **Inventory Group Vars (all)**: `group_vars/all.yml`
3.  **Inventory Group Vars (others)**: `group_vars/{{ group_name }}.yml`
4.  **Inventory Host Vars**: `host_vars/{{ host_name }}.yml`
5.  **Play Vars**: Play 定義内の `vars`
6.  **Play Vars Files**: Play 定義内の `vars_files`
7.  **Role Vars**: ロールの `vars/main.yml`（将来対応）
8.  **Task Vars**: Task 定義内の `vars`
9.  **Extra Vars**: コマンドライン引数 `-e` / `--extra-vars`

### 2.1 マージ戦略
- ディクショナリ（Map）型の変数は、デフォルトで「上書き」としますが、設定により「再帰的マージ」を選択可能にすることを検討します。

## 3. 遅延評価 (Lazy Evaluation)

Ansible と同様に、変数は定義時ではなく、実際に使用されるタイミングでテンプレート展開されます。

- **実装方法**:
    - 実行エンジン内の `VariableManager` が全変数を保持。
    - タスク実行直前に、そのタスクで使用される引数（`args`）に対して再帰的に `Jinjava` を適用。
    - 未定義の変数が参照された場合、原則として実行エラーとします（Ansible 互換）。

## 4. 独自フィルターとテストの拡張

Ansible 特有のフィルター（`ipaddr`, `dict2items`, `default` 等）は、Jinjava の `Filter` インターフェースを実装して追加します。

```java
// 実装イメージ
jinjava.getGlobalContext().registerFilter(new AnsibleIpAddrFilter());
```

## 5. Native Image への対応

- Jinjava が内部で使用するリフレクション情報を `reflect-config.json` に定義する必要があります。
- 動的なクラスロードが発生する箇所を特定し、ビルド時に静的に解決されるよう設定します。
