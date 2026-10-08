# CLI仕様

`graal-ansible` は、`ansible-playbook` と互換性のあるコマンドラインインターフェースを Picocli ライブラリを用いて提供します。

## 1. 基本コマンド形式

```bash
graal-ansible [options] playbook.yml
```

## 2. サポート予定の主要オプション

| オプション | 短縮形 | 説明 | 実装状況 |
| :--- | :---: | :--- | :---: |
| `--inventory` | `-i` | インベントリファイルのパス（ターゲットノードの定義）を指定 | ◎ |
| `--extra-vars` | `-e` | 追加の変数を設定 (key=value, JSON/YAML, @file をサポート) | ◎ |
| `--limit` | `-l` | 実行対象のターゲットノードを制限 | ◎ |
| `--tags` | `-t` | 特定のタグが付いたタスクのみ実行 | ◎ |
| `--skip-tags` | - | 特定のタグが付いたタスクをスキップ | ◎ |
| `--check` | `-C` | 変更を加えずに実行（ドライラン） | ◎ |
| `--diff` | `-D` | ファイルの変更内容を表示 | ◎ |
| `--verbose` | `-v` | 詳細ログを表示 (`-vvv` 等の複数指定をサポート) | ◎ |
| `--become` | `-b` | 権限昇格を有効にする | ◎ |
| `--become-method` | - | 権限昇格に使用するメソッドを指定 (sudo, su等) | ◎ |
| `--become-user` | - | 昇格後のユーザーを指定 (デフォルト: root) | ◎ |
| `--become-flags` | - | 権限昇格に使用するフラグを指定 | ◎ |
| `--ask-become-pass` | `-K` | 権限昇格パスワードをプロンプトで問い合せる | ◎ |
| `--vault-password-file` | - | Ansible Vault 暗号化データの復号用パスワードファイルを指定 | ◎ |
| `--vault-id` | - | Vault ID およびパスワードソース (`label@source` または `source`) を指定 | ◎ |
| `--forks` | `-f` | 並列実行するホストの数を指定 (デフォルト: 5) | ◎ |
| `--version` | - | バージョン情報を表示 | ◎ |
| `--collections-path` | - | コレクションの探索パスを指定 | ◎ |
| `--help` | `-h` | ヘルプメッセージを表示 | ◎ |

※ ◎: 実装済み、○: 計画中、△: 部分的/検討中

## 3. 権限昇格 (become) オプションの詳細仕様

本家 Ansible との互換性を確保するため、CLI で指定された権限昇格フラグは以下の通り内部変数へマッピングされ、Playbook 内の定義よりも高い優先順位で扱われます。

| CLI オプション | 内部変数名 | 説明 |
| :--- | :--- | :--- |
| `-b`, `--become` | `ansible_become` | `true` の場合、全タスクでデフォルトで権限昇格を有効にします。 |
| `--become-method` | `ansible_become_method` | `sudo`, `su` 等のメソッドを指定します (デフォルト: `sudo`)。 |
| `--become-user` | `ansible_become_user` | 昇格後のユーザーを指定します (デフォルト: `root`)。 |
| `--become-flags` | `ansible_become_flags` | 昇格コマンドに渡す追加フラグを指定します。 |

これらの変数は、`VariableManager` において「CLI変数 (Level 1)」として保持され、Play や Task で明示的に `become: no` 等が指定されない限り、実行コンテキストに適用されます。

### 3.1 対話型昇格パスワード入力 (`-K` / `--ask-become-pass`)
- CLI オプション `-K` または `--ask-become-pass` が指定された場合、`PlaybookCli` は `System.console()` を介してインタラクティブプロンプト (`BECOME password: `) を表示し、パスワードを取得します。
- 取得されたパスワードは CLI 変数 `ansible_become_password` にマッピングされ、タスク実行時の `sudo` / `su` コマンド実行時の標準入力 (stdin) 注入に使用されます。
- **コンソール非存在時の動作**: CI/CD 環境などでコンソールが利用できない場合（`System.console() == null`）、`Error: --ask-become-pass specified but no console available.` メッセージを標準エラー出力へ表示し、終了コード `1` で即座に終了します。

## 4. エクストラ変数 (`--extra-vars`) の解釈ロジック

`--extra-vars` / `-e` オプションは複数回指定可能であり、入力された文字列は SnakeYAML を用いて `PlaybookCli.parseExtraVars` で解析され、マージされます。具体的には以下の 4 つの形式を解釈します。

| 入力形式 | パース判定条件 | 動作および解析仕様 |
| :--- | :--- | :--- |
| **ファイル読み込み** | 文字列が `@` で始まる | 指定されたパスのファイル（`.yml`, `.yaml`, `.json`）を読み込み、YAML 辞書構造としてマージ。 |
| **インライン JSON/YAML** | 文字列が `{` で始まる | JSON / YAML インラインオブジェクト構造としてパースし、Map としてマージ。 |
| **キー・バリューペア** | 文字列に `=` が含まれる | 最初の `=` を区切り文字とし、`key=value` の単一 Map エントリとして登録。 |
| **フォールバック解析** | 上記いずれにも該当しない | 文字列全体を SnakeYAML で読み込み、Map オブジェクトであればそのままマージ。 |

## 5. Ansible Vault パスワード解決仕様

Ansible Vault で暗号化された変数（`!vault` タグ付きデータ）の動的復号に使用するパスワードの指定オプションについて、以下の構文および解決優先順位をサポートしています。

- **`--vault-password-file <path>`**: 指定されたパスのファイル内容から前後の空白・改行（`.trim()`）を除去してパスワードとして読み込みます。
- **`--vault-id <label@source>`**: `label@source` または `source` 形式で指定します。`@` が含まれる場合は `@` 以降をソース（ファイルパスまたは `prompt` キーワード）として抽出します。
- **`prompt` キーワード**: ソースとして `prompt` が指定された場合（例: `--vault-id dev@prompt` または `--vault-password-file prompt`）、コンソールから対話型入力プロンプト (`Vault password: `) を表示してパスワードを取得します。コンソールが利用できない場合は `RuntimeException` をスローします。

### 5.1 パスワードソースの解決優先順位
複数の設定が存在する場合、`PlaybookCli` は以下の優先順位に従って使用する Vault パスワードソースを決定します（1 が最優先）。

1. **`--vault-password-file` CLI オプション**
2. **`--vault-id` CLI オプション**
3. **`ANSIBLE_VAULT_PASSWORD_FILE` 環境変数**

## 6. コールバックプラグイン選択と環境変数

`PlaybookCli` 実行時、`CallbackFactory.createStdoutCallback` を呼び出して出力用コールバックプラグインを選択・生成し、`PlaybookExecutor` に登録します。

| 環境変数 / 設定 | 優先順位 | 説明 |
| :--- | :---: | :--- |
| `ANSIBLE_STDOUT_CALLBACK` | 1 (最優先) | 環境変数に設定されたプラグイン名 (`default`, `minimal`, `yaml`, `json` 等) を使用 |
| `ansible.cfg` `stdout_callback` | 2 | `ansible.cfg` 設定ファイルの `[defaults]` セクションの `stdout_callback` を参照 |
| デフォルト | 3 | 指定がない場合は standard 対話型出力 `DefaultCallback` (`default`) を使用 |

## 7. 環境変数およびシステムプロパティ一覧

`graal-ansible` は、以下の環境変数および Java システムプロパティをサポートしています。

| 環境変数 | 説明 | 実装状況 |
| :--- | :--- | :---: |
| `ANSIBLE_STDOUT_CALLBACK` | 使用するコールバックプラグインを指定（例: `default`, `minimal`, `yaml`, `json`） | ◎ |
| `ANSIBLE_COLLECTIONS_PATH` | コレクションの探索パスをコロン区切りで指定 | ◎ |
| `ANSIBLE_HASH_BEHAVIOUR` | 辞書型変数のマージ戦略を指定 (`replace` または `merge`) | ◎ |
| `ANSIBLE_SITE_PACKAGES` | Python の `site-packages` （依存ライブラリの探索パス）をコロン区切りで指定 | ◎ |
| `ANSIBLE_VAULT_PASSWORD_FILE` | デフォルトの Vault パスワードファイルパスを指定 | ◎ |

### 7.1 Java システムプロパティ (Java System Properties)

Java の起動時オプション（`-Dproperty=value`）として、以下の設定をサポートしています。

| プロパティ名 | 説明 | 実装状況 |
| :--- | :--- | :---: |
| `ansible.site.packages` | Python の `site-packages` （依存ライブラリの探索パス）をコロン区切りで指定 | ◎ |

## 8. コレクション探索パスの優先順位

複数の方法でコレクションの探索パスが指定された場合、以下の優先順位で解決されます。

1. **CLI オプション (`--collections-path`)**
2. **環境変数 (`ANSIBLE_COLLECTIONS_PATH`)**
3. **デフォルトパス** (詳細は [コレクションの管理と取得方法](Collection-Management.md) を参照)

## 9. 終了コード (Exit Codes) 仕様

`PlaybookCli` の実行終了コードは、`ansible-playbook` と同一の規格に準拠してマッピングされます。

| 終了コード | 定義 | 発生条件および説明 |
| :---: | :--- | :--- |
| `0` | 正常終了 (Success) | 全ターゲットホストにおいて Playbook がエラーなく正常完了した場合。 |
| `1` | 一般エラー (General Error) | コマンドライン引数エラー、コンソール未検出エラー、未指定インベントリエラー、一般的な実行例外発生時。 |
| `2` | 実行失敗 (Host Failure) | 1 つ以上のターゲットホストでタスクの失敗 (`failed`) または到達不能 (`unreachable`) が発生した場合。 |
| `4` | 構文エラー (Syntax/Parse Error) | Playbook (YAML) の解析失敗や構文定義違反 (`PlaybookParseException`) が発生した場合。 |

### 9.1 プログラム API とレポート統合

Java コードからのプログラム的な実行や、各種ツールのバックエンドとしての組み込みにおいては、`PlaybookExecutor` クラスのインターフェースを使用します。

- **`PlaybookExecutor.executeAndReport(...)`**:
  Playbook の実行完了時に `ExecutionReport` オブジェクトを返却します。
- **実行結果と終了コードの対応関係**:
  `ExecutionReport.hasFailed()` または `ExecutionReport.hasUnreachable()` が真の場合、コマンドライン実行時には適切な非ゼロ終了コード (`1` または `2`) へマッピングされます。
