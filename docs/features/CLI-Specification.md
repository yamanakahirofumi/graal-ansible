# CLI仕様 (CLI Specifications)

`graal-ansible` は、`ansible-playbook` と互換性のあるコマンドラインインターフェースを Picocli フレームワークを用いて提供します。

## 1. 基本コマンド形式

```bash
graal-ansible [options] playbook.yml
```

## 2. サポートオプション一覧

| オプション | 短縮形 | デフォルト値 | 説明 | 実装状況 |
| :--- | :---: | :--- | :--- | :---: |
| `--inventory` | `-i` | なし | インベントリファイルまたはスクリプト/ディレクトリのパスを指定 | ◎ |
| `--extra-vars` | `-e` | `[]` | 追加の変数を設定 (`key=value`, JSON/YAML, `@file` をサポート) | ◎ |
| `--limit` | `-l` | なし | 実行対象のターゲットノードを制限 | ◎ |
| `--tags` | `-t` | `[]` | 特定のタグが付いたタスクのみ実行 | ◎ |
| `--skip-tags` | - | `[]` | 特定のタグが付いたタスクをスキップ | ◎ |
| `--check` | `-C` | `false` | 変更を加えずに実行（ドライランモード） | ◎ |
| `--diff` | `-D` | `false` | ファイルやテンプレート変更時の差分表示フラグ | ◎ |
| `--force-handlers` | - | `false` | タスク失敗時であっても通知済みハンドラーを強制実行 | ◎ |
| `--verbose` | `-v` | `0` | 詳細ログレベル (`-v`, `-vv`, `-vvv`, `-vvvv` の指定をサポート) | ◎ |
| `--become` | `-b` | `false` | 全タスクで権限昇格をデフォルト有効化 | ◎ |
| `--become-method` | - | `"sudo"` | 権限昇格に使用するメソッドを指定 (`sudo`, `su`, `runas` 等) | ◎ |
| `--become-user` | - | `"root"` | 昇格後の実行ユーザーを指定 | ◎ |
| `--become-flags` | - | なし | 権限昇格コマンドに渡す追加フラグ | ◎ |
| `--ask-become-pass` | `-K` | `false` | 権限昇格用パスワードを対話型コンソールからプロンプト入力 | ◎ |
| `--vault-password-file` | - | なし | Ansible Vault 暗号化データの復号用パスワードファイル | ◎ |
| `--vault-id` | - | なし | Vault ID およびソース (`label@source` または `source`) を指定 | ◎ |
| `--forks` | `-f` | `5` | 並列実行するホストの最大プロセス/スレッド数を指定 | ◎ |
| `--collections-path` | - | なし | コレクションの探索パスを指定 | ◎ |
| `--version` | - | - | バージョン情報を表示 | ◎ |
| `--help` | `-h` | - | ヘルプメッセージを表示 | ◎ |

※ ◎: 実装済み、○: 計画中、△: 部分的/検討中

## 3. CLI オプションから内部変数 (Level 1) へのマッピング

CLI で指定されたオプションフラグは、`PlaybookCli` によって内部の `cliVars` 変数マップへ格納され、`VariableManager` において最高優先度である **Level 1 (CLI Variables / Extra Vars)** としてコンテキストへ適用されます。

| CLI オプション | 内部変数名 (`cliVars`) | 変数の型 / 説明 |
| :--- | :--- | :--- |
| `-b`, `--become` | `ansible_become` | `Boolean`: 全タスクでの権限昇格の有効化フラグ。 |
| `--become-method` | `ansible_become_method` | `String`: 権限昇格に使用する手法 (`"sudo"`, `"su"` 等)。 |
| `--become-user` | `ansible_become_user` | `String`: 昇格後の目的ユーザー (`"root"` 等)。 |
| `--become-flags` | `ansible_become_flags` | `String`: 昇格コマンドへ付与するフラグ文字列。 |
| `-K`, `--ask-become-pass` | `ansible_become_password` | `String`: `System.console().readPassword()` から取得した入力パスワード。 |
| `-C`, `--check` | `ansible_check_mode` | `Boolean`: チェックモード（ドライラン）指定。 |
| `-D`, `--diff` | `ansible_diff_mode` | `Boolean`: 差分表示フラグ。 |
| `--force-handlers` | `ansible_force_handlers` | `Boolean`: 強制ハンドラーフラッシュフラグ。 |
| `-v`, `--verbose` | `ansible_verbosity` | `Integer`: 詳細レベルの数値 (`0`〜`4`)。 |
| `-t`, `--tags` | `ansible_run_tags` | `List<String>`: 実行対象の指定タグリスト。 |
| `--skip-tags` | `ansible_skip_tags` | `List<String>`: スキップ対象の指定タグリスト。 |

### 3.1 対話型 become パスワードプロンプト (`-K` / `--ask-become-pass`)
`-K` オプション指定時、`PlaybookCli` は実行直前に `System.console()` を介して非表示対話入力（`console.readPassword("BECOME password: ")`）を起動します。
- ターミナル環境（`System.console() != null`）の場合、安全に入力文字を隠蔽した状態でパスワードを取得し、`ansible_become_password` へバインドします。
- 非対話環境（CI/CD パイプラインやリダイレクト実行時等、`System.console() == null`）の場合、エラーメッセージを出力して即座に終了コード `1` で処理を中断します。

## 4. エクストラ変数 (`--extra-vars` / `-e`) 解析メカニズム

`PlaybookCli.parseExtraVars` は、指定された複数個の `-e` 引数を順次パースし、1つの統合変数 Map にマージします。各引数の判定順序と処理構文は以下の通りです。

1. **`@file` 構文**:
   - 先頭が `@` で始まる場合（例: `-e @/path/to/vars.yml`）、指定パスのファイルを開き、`SnakeYAML` を用いてオブジェクト（Map 構造）として展開・ロードします。
2. **インライン JSON/YAML 構文**:
   - 先頭が `{` で始まる場合（例: `-e '{"http_port": 8080, "enable_vhost": true}'`）、インライン JSON/YAML 文字列として `SnakeYAML` 経由で直接 Map へデコードします。
3. **`key=value` 単体記述構文**:
   - 文字列に `=` が含まれる場合、最初の `=` 位置で分割（`split("=", 2)`）し、キーおよび値の文字列として Map へ登録します。
4. **フォールバック構文**:
   - 上記いずれにも該当しない単一文字列の場合、`SnakeYAML` でパースを試み、辞書オブジェクトとして抽出された場合はそのままマージします。

## 5. Ansible Vault パスワード解決と言語内連携

Ansible Vault 暗号化データ（`!vault`）をネイティブ復号するためのパスワード解決仕様です。

- **`--vault-password-file <path>`**: 指定されたファイルパスからプレーンテキストのパスワードを読み込み、末尾改行や前後の空白を自動トリミング（`trim()`）します。
- **`--vault-id <label@source>`**: `@` 記号の区切りをパースし、ソース部を抽出します。
- **`prompt` ソースキーワード**: ファイルパス位置に `prompt` が指定された場合、`System.console().readPassword("Vault password: ")` を起動して対話入力させます。コンソール非存在時は `RuntimeException` を発起します。
- 参照リンク：[Ansible Vault 復号仕様](Vault-Support.md)

### 5.1 解決優先順位 (Resolution Precedence)
1. **`--vault-password-file` CLI オプション**
2. **`--vault-id` CLI オプション**
3. **環境変数 `ANSIBLE_VAULT_PASSWORD_FILE`**

## 6. コールバックプラグイン動的解決と出力選択 (`CallbackFactory`)

実行時の標準出力フォーマットを決定するコールバックプラグインの解決フローです。

- **解決順序**:
  1. 環境変数 `ANSIBLE_STDOUT_CALLBACK`（例: `ANSIBLE_STDOUT_CALLBACK=json` や `ANSIBLE_STDOUT_CALLBACK=minimal`）
  2. `ansible.cfg` 設定ファイル内の `stdout_callback` 項目
  3. デフォルトフォールバック: `default` (`DefaultCallback`)
- **生成処理**: `PlaybookCli` は `CallbackFactory.createStdoutCallback(null, collectionPaths)` を呼び出してコールバックインスタンスを動的に構築し、`PlaybookExecutor` へ登録します。
- 参照リンク：[コールバックプラグインの設計仕様](../implementation/Callback-Plugins.md)

## 7. 環境変数および Java システムプロパティ

`graal-ansible` の動作制御に使用される各種設定環境変数および Java プロパティです。

| 環境変数 / プロパティ | 型 | デフォルト値 | 説明 |
| :--- | :--- | :--- | :--- |
| `ANSIBLE_STDOUT_CALLBACK` | `String` | `"default"` | 標準出力コールバックプラグイン名の指定。 |
| `ANSIBLE_COLLECTIONS_PATH` | `String` | なし | コレクションの探索パス（コロン区切り）。 |
| `ANSIBLE_HASH_BEHAVIOUR` | `String` | `"replace"` | 辞書変数のマージ戦略 (`"replace"` または `"merge"`). |
| `ANSIBLE_SITE_PACKAGES` | `String` | なし | Python の `site-packages` 探索パス（コロン区切り）。 |
| `ANSIBLE_VAULT_PASSWORD_FILE` | `String` | なし | Vault パスワードファイルのデフォルトパス。 |
| `ansible.site.packages` (SysProp) | `String` | なし | Java 起動時システムプロパティでの `site-packages` 指定。 |

## 8. CLI 終了コード (Exit Codes) マッピング

`ansible-playbook` との完全な CLI 互換性を保つため、`graal-ansible` は実行結果およびエラー種別に応じた特定の終了コードを返却します。

| 終了コード | 状態区分 | 発生条件 / 判定規約 |
| :---: | :--- | :--- |
| `0` | **正常終了 (Success)** | 全 Play およびタスクが正常終了（`ok`, `changed`, `skipped` のみで完了）した場合。 |
| `1` | **一般エラー (General Error)** | 未補足の例外、インベントリ未指定、指定パスの不備、対話コンソール非存在時の `-K` / `prompt` エラー。 |
| `2` | **ターゲット実行失敗 (Host Failure)** | 実行後に `ExecutionReport.hasFailed()` または `hasUnreachable()` が真（いずれかのホストでタスク失敗または到達不能が発生）の場合。 |
| `4` | **構文・検証エラー (Syntax Error)** | Playbook YAML ファイルの構文解析エラー (`PlaybookParseException`) や入力バリデーション失敗時。 |

### 8.1 実装とレポート統合 (`ExecutionReport`)
`PlaybookCli` 内で実行された Playbook 処理は、内部で `PlaybookExecutor.executeAndReport` を呼び出し、返却された `ExecutionReport` を評価します。
```java
ExecutionReport report = executor.executeAndReport(playbook, inventory, variableManager, check, tags, skipTags, limit);
if (report.hasFailed() || report.hasUnreachable()) {
    return 2; // ターゲット失敗の終了コード
}
return 0; // 成功
```
