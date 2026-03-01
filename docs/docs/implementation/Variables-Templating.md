# 変数管理とテンプレートエンジン (Variables & Templating)

`graal-ansible` における変数のスコープ管理、優先順位解決、および Jinja2 互換のテンプレートエンジンの実装詳細を定義します。

## 1. テンプレートエンジン

Ansible 本家が使用する Jinja2 との互換性を確保するため、Java 実装の **Jinjava** を採用します。

- **ライブラリ**: [Jinjava (HubSpot)](https://github.com/HubSpot/jinjava)
- **選定理由**: 
    - 純粋な Java 実装であり、GraalVM Native Image との相性が良い。
    - Jinja2 の構文を広くサポートしており、Ansible 独自の拡張を実装しやすい。

### 1.1 カスタムフィルターの実装
Ansible 特有のフィルター（`ipaddr`, `dict2items`, `default` 等）は、Jinjava の `Filter` インターフェースを実装して個別に追加します。
- `org.example.ansible.engine.templating.filters` パッケージ内に配置。
- 起動時に `Jinjava` インスタンスへ一括登録。

## 2. 変数管理 (VariableManager)

変数の解決は `VariableManager` クラスが担当します。Ansible の複雑な変数優先順位を整理し、以下の主要なスコープを優先度（低から高）の順に解決します。

1. **Role vars** (将来的なロールサポート時)
2. **Group vars** (Inventory 内で定義)
3. **Host vars** (Inventory 内で定義)
4. **Play vars** (Playbook の `vars` セクション)
5. **Task vars** (各タスクの `vars` セクション)
6. **Extra vars** (CLI 引数 `-e` で渡された変数)

### 2.1 変数のマージ戦略
- ディクショナリ（Map）型の変数は、設定によりマージ（結合）するか、上位のスコープで完全に上書きするかを選択可能にします（デフォルトは上書き）。
- リスト型は常に上位スコープで上書きされます。

## 3. テンプレート展開のタイミング

変数の展開は、「必要な時に、必要な箇所だけ」行う Lazy Evaluation（遅延評価）を基本とします。

1. **タスク実行直前**: タスクの引数（`args`）に含まれるテンプレート文字列を展開します。
2. **条件分岐判定時**: `when` 句の評価時に展開します。
3. **ループ処理時**: `loop` または `with_items` の各要素を展開します。

## 4. エラーハンドリング

- **未定義変数**: テンプレート内で未定義の変数を参照した場合、Ansible のデフォルト挙動に合わせてエラー（`Fatal`）として扱い、タスクを停止させます。
- **解析エラー**: テンプレート構文に誤りがある場合は、パース時点でエラーを報告します。

## 5. Native Image への対応

Jinjava は内部でリフレクションを使用するため、動的なプロパティアクセス（`Map` 以外の POJO や Record へのアクセス）には `reflect-config.json` の設定が必要です。基本的には `Map<String, Object>` をコンテキストとして渡すことで、リフレクションの影響を最小限に抑えます。
