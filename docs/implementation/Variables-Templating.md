# 変数とテンプレートの実装詳細 (Variables and Templating Implementation)

本ドキュメントでは、`graal-ansible` における変数の管理、優先順位の解決、および Jinja2 互換テンプレートエンジンの統合について詳述します。

## 1. テンプレートエンジンの選定

Ansible の Jinja2 テンプレートとの高い互換性を Java で実現するため、**Jinjava** を採用します。

- **ライブラリ**: [Jinjava (HubSpot)](https://github.com/HubSpot/jinjava)
- **選定理由**:
    - Java ベースで Jinja2 構文を高度にサポートしている。
    - 独自フィルターやテストの追加が容易。
    - GraalVM Native Image での動作実績がある。

## 2. 変数の優先順位 (Variable Precedence)

Ansible (ansible-core 2.17+) は 22 段階の非常に詳細な優先順位を定義しています。`graal-ansible` では、これらの互換性を維持しつつ、主要なレベルから順次実装を進めています。

下表は、低い順（上が低く、下が最高優先）の優先順位リストと、現在の `graal-ansible` におけるサポート状況です。

- **◎**: 実装済み (Implemented)
- **△**: 計画中 / 一部対応 (Planned / Partial)
- **×**: 未着手 (Not yet)

| 優先度 | 変数のソース | サポート状況 | 備考 |
| :--- | :--- | :---: | :--- |
| 1 | コマンドライン値 (例: `-u user` ※ `-e` 以外) | ◎ | `PlaybookExecutor` にて `ansible_check_mode` 等を統合。 |
| 2 | ロールデフォルト (`roles/x/defaults/main.yml`) | △ | ロール機能と共に実装予定。 |
| 3 | インベントリファイル / スクリプトのグループ変数 | ◎ | `Inventory.java` にて解決。 |
| 4 | インベントリ `group_vars/all` | ◎ | `all` グループとして処理。 |
| 5 | プレイブック `group_vars/all` | ◎ | プレイブック相対パスの検索をサポート。 |
| 6 | インベントリ `group_vars/*` | ◎ | グループ階層パスに沿って解決。 |
| 7 | プレイブック `group_vars/*` | ◎ | プレイブック相対パスの検索をサポート。 |
| 8 | インベントリファイル / スクリプトのホスト変数 | ◎ | `host` 定義内の変数。 |
| 9 | インベントリ `host_vars/*` | ◎ | インベントリ相対パスの検索をサポート。 |
| 10 | プレイブック `host_vars/*` | ◎ | プレイブック相対パスの検索をサポート。 |
| 11 | ホストファクト / キャッシュされた `set_facts` | ◎ | `VariableManager.addFacts` にて管理。 |
| 12 | プレイ変数 (`vars`) | ◎ | `Play` レコードに保持。 |
| 13 | プレイ `vars_prompt` | × | インタラクティブ入力のため優先度低。 |
| 14 | プレイ変数ファイル (`vars_files`) | ◎ | `VariableManager.loadVarsFile` にて解決。 |
| 15 | ロール変数 (`roles/x/vars/main.yml`) | △ | ロール機能と共に実装予定。 |
| 16 | ブロック変数 (`block` 内の `vars`) | ◎ | 実行エンジンでのスコープ分離と伝播を実装済み。 |
| 17 | タスク変数 (`task` 内の `vars`) | ◎ | `Task` レコードに保持。 |
| 18 | `include_vars` | ◎ | Action Plugin として Python 実装をそのまま実行。 |
| 19 | `set_facts` / `register` 変数 | ◎ | `VariableManager.registerVariable` で実行時に保存。 |
| 20 | ロールパラメータ | △ | ロール呼び出し時の引数。 |
| 21 | インクルードパラメータ | △ | `include_tasks` 等の引数。 |
| 22 | エクストラ変数 (`-e` / `--extra-vars`) | ◎ | **最高優先。** `VariableManager.extraVars` に保持。 |

### 2.1 マージ戦略
- **原則**: 同じ変数名が異なるレベルで定義されている場合、高い優先度の値が低い優先度の値を完全に上書きします。
- **ハッシュマージ**: 辞書（Map）型の変数について、Ansible の `hash_behaviour=merge` 相当の再帰的マージをサポートするかは将来の検討事項です。

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

## 5. Native Image への対応

- Jinjava が内部で使用するリフレクション情報を `reflect-config.json` に定義する必要があります。
- 動的なクラスロードが発生する箇所を特定し、ビルド時に静的に解決されるよう設定します。
