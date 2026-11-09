# CLI仕様 (CLI Specification)

`graal-ansible` は、`ansible-playbook` と互換性のあるコマンドラインインターフェース（CLI）を提供します。本ドキュメントでは、サポートされる CLI オプション、CLI 引数から Level 1 CLI 変数へのマッピング規則、`--extra-vars` の詳細構文、Ansible Vault パスワード解決規則、環境変数、コールバック選択メカニズム、および終了コードについて定義します。

## 1. 基本コマンド形式

```bash
graal-ansible [options] playbook.yml
```

## 2. サポートされる主要オプション

`PlaybookCli` において解析・サポートされるコマンドライン引数一覧です。

| オプション              | 短縮形 | 引数の型 / デフォルト値 | 説明                                                                   | 実装状況 |
| :---------------------- | :----: | :---------------------- | :--------------------------------------------------------------------- | :------: |
| `--inventory`           |  `-i`  | `String`                | インベントリファイルのパス（ターゲットノードの定義）を指定             |    ◎     |
| `--extra-vars`          |  `-e`  | `List<String>`          | 追加の変数を設定 (`key=value`, インライン JSON/YAML, `@file` をサポート)|    ◎     |
| `--limit`               |  `-l`  | `String`                | 実行対象のターゲットノードをパターン制限                               |    ◎     |
| `--tags`                |  `-t`  | `List<String>`          | 特定のタグが付いたタスクのみ実行                                       |    ◎     |
| `--skip-tags`           |   -    | `List<String>`          | 特定のタグが付いたタスクをスキップ                                     |    ◎     |
| `--check`               |  `-C`  | `boolean` (フラグ)      | 変更を加えずに実行（ドライラン / チェックモード）                       |    ◎     |
| `--diff`                |  `-D`  | `boolean` (フラグ)      | ファイルの変更差分を表示                                               |    ◎     |
| `--force-handlers`      |   -    | `boolean` (フラグ)      | タスクが失敗した場合でも通知済みハンドラーを強制実行                   |    ◎     |
| `--verbose`             |  `-v`  | `boolean[]` (累積)      | 詳細ログを表示 (`-v`, `-vv`, `-vvv`, `-vvvv` などの複数指定をサポート) |    ◎     |
| `--become`              |  `-b`  | `boolean` (フラグ)      | 権限昇格を有効にする                                                   |    ◎     |
| `--become-method`       |   -    | `String` (デフォルト: `sudo`) | 権限昇格に使用するメソッドを指定 (`sudo`, `su`, `runas` 等)          |    ◎     |
| `--become-user`         |   -    | `String` (デフォルト: `root`) | 昇格後の実行ユーザーを指定                                             |    ◎     |
| `--become-flags`        |   -    | `String`                | 権限昇格コマンドに渡す追加フラグを指定                                 |    ◎     |
| `--ask-become-pass`     |  `-K`  | `boolean` (フラグ)      | 権限昇格パスワードを対話型プロンプトで問い合わせる                     |    ◎     |
| `--vault-password-file` |   -    | `File`                  | Ansible Vault 暗号化データの復号用パスワードファイルを指定             |    ◎     |
| `--vault-id`            |   -    | `String`                | Vault ID およびパスワードソース (`label@source` または `source`) を指定 |    ◎     |
| `--forks`               |  `-f`  | `int` (デフォルト: `5`) | 並列実行するホストの数を指定                                           |    ◎     |
| `--version`             |   -    | -                       | バージョン情報を表示                                                   |    ◎     |
| `--collections-path`    |   -    | `String`                | コレクションの探索パスを指定                                           |    ◎     |
| `--help`                |  `-h`  | -                       | ヘルプメッセージを表示                                                 |    ◎     |

※ ◎: 実装済み、○: 計画中、△: 部分的/検討中

## 3. CLI 引数から Level 1 CLI 変数へのマッピング規則

本家 Ansible との変数優先順位互換性を確保するため、CLI で指定された実行時フラグやオプションは、`PlaybookCli` によって内部的な変数マップ（`cliVars`）に集約されます。これらは `VariableManager` において **「CLI変数 (Level 1)」** として登録・保持され、Play や Task 内で明示的に上書きされない限り、最優先の基本設定として実行コンテキストに適用されます。

| CLI オプション                 | 内部変数名 (`cliVars`)      | 型                 | 説明・設定値                                                                 |
| :----------------------------- | :-------------------------- | :----------------- | :--------------------------------------------------------------------------- |
| `-C`, `--check`                | `ansible_check_mode`        | `Boolean`          | チェックモード（ドライラン）の有効状態 (`true`/`false`)                      |
| `-D`, `--diff`                 | `ansible_diff_mode`         | `Boolean`          | 差分表示モードの有効状態 (`true`/`false`)                                    |
| `--force-handlers`             | `ansible_force_handlers`    | `Boolean`          | エラー発生時でも通知済みハンドラーを強制フラッシュする有効状態               |
| `-b`, `--become`               | `ansible_become`            | `Boolean`          | 全タスクにおける権限昇格のデフォルト有効状態 (`true`/`false`)                |
| `--become-method`              | `ansible_become_method`     | `String`           | 使用する権限昇格メソッド (`sudo`, `su` 等、未指定時は `"sudo"`)              |
| `--become-user`                | `ansible_become_user`       | `String`           | 昇格後の実行ユーザー名 (未指定時は `"root"`)                                 |
| `--become-flags`               | `ansible_become_flags`      | `String`           | 昇格コマンドに追加付与するフラグ文字列 (指定時のみ注入)                      |
| `-K`, `--ask-become-pass`      | `ansible_become_password`   | `String`           | 対話型コンソールから取得された権限昇格パスワード文字列                       |
| `-v`, `--verbose`              | `ansible_verbosity`         | `Integer`          | ログ冗長性レベル (`0`: 標準, `1`: `-v`, `2`: `-vv`, `3`: `-vvv`, `4`: `-vvvv`) |
| `-t`, `--tags`                 | `ansible_run_tags`          | `List<String>`     | 実行対象としてフィルタリング指定されたタスクタグリスト                       |
| `--skip-tags`                  | `ansible_skip_tags`         | `List<String>`     | スキップ対象としてフィルタリング指定されたタスクタグリスト                   |

### 3.1 対話型 become パスワードプロンプトのエラーハンドリング
`-K` または `--ask-become-pass` が指定された場合、`PlaybookCli` は `System.console()` を介して対話的にパスワードプロンプト（`"BECOME password: "`）を表示し、安全に入力値を取得して `ansible_become_password` に格納します。
- **ヘッドレス環境（コンソール不在時）**: バックグラウンドジョブやパイプ実行など `System.console() == null` の環境で `--ask-become-pass` が指定された場合、標準エラー出力にエラーメッセージ（`"Error: --ask-become-pass specified but no console available."`）を出力し、即座に終了コード `1` で安全に異常終了します。

## 4. エクストラ変数 (`--extra-vars` / `-e`) の構文解析詳細

`--extra-vars` / `-e` オプションは複数回指定可能であり、`PlaybookCli.parseExtraVars` メソッドによって以下の構文が順次解析され、単一の変数マップ（Level 22: `extra_vars`）へと統合されます。

1. **ファイル読み込み構文 (`@file`)**:
   - 先頭が `@` で始まる場合（例: `@vars.yml`, `@data.json`）、`@` 以降のパスからファイルを読み込み、SnakeYAML (`YamlUtil.createYaml()`) を用いて Map 構造として解析します。
   - ファイルが存在しない場合や YAML/JSON 構文エラーが発生した場合は、`RuntimeException` を送出します。
2. **インライン JSON / YAML 構文 (`{...}`)**:
   - 先頭が `{` で始まる場合（例: `{"http_port": 8080, "enable_ssl": true}`）、SnakeYAML を用いてインライン辞書オブジェクト（`Map<String, Object>`）としてパースし、型情報（Boolean, Integer 等）を保持したままマップに展開します。
3. **キー・値ペア構文 (`key=value`)**:
   - 文字列内に `=` が含まれる場合、最初の `=` でキーと値に分割し、文字列ペアとしてマップに登録します（例: `env=production`）。
4. **汎用 YAML フォールバック**:
   - 上記のいずれにも直接合致しない形式であっても、SnakeYAML によるロードを試み、`Map` インスタンスが得られた場合は自動的に結果にマージします。

## 5. Ansible Vault パスワードオプションの詳細仕様

Ansible Vault で暗号化された変数の動的復号に使用するパスワードの指定オプションについて、以下の構文および解決優先順位をサポートしています（◎）。

- **`--vault-password-file`**: パスワードテキストが格納されたファイルの絶対パスまたは相対パスを指定します。
- **`--vault-id`**: `vault_id@source` 形式またはソース単体を指定します。ソース部としてファイルパス、または `prompt` キーワードが利用可能です。
- **`prompt` キーワードによる対話型入力**: パスワードソースとして `prompt` が指定された場合（例: `--vault-id dev@prompt` または `--vault-password-file prompt`）、コンソールからインタラクティブに Vault パスワード（`"Vault password: "`）を入力させます。

### 5.1 パスワードソースの解決優先順位
複数の設定が存在する場合、`PlaybookCli.getVaultPassword()` は以下の優先順位に従って使用する Vault パスワードソースを決定します。

1. **`--vault-password-file` CLI オプション**
2. **`--vault-id` CLI オプション**（`@` が含まれる場合は `@` 以降のソースパスを抽出）
3. **`ANSIBLE_VAULT_PASSWORD_FILE` 環境変数**

### 5.2 ヘッドレス環境およびファイル不在時のエラーハンドリング
- パスワードソースに `prompt` が指定されているにもかかわらず `System.console() == null` である場合、`"Vault password source 'prompt' specified but no console available."` 例外を送出します。
- 指定されたパスワードファイルが存在しない、または通常ファイルでない場合、`"Vault password file not found: <path>"` 例外を送出します。
- 読み込みに成功したファイル内容の先頭・末尾の改行文字は `.trim()` により自動的に正規化されます。

## 6. 環境変数 (Environment Variables)

`graal-ansible` は、Ansible 本家と互換性のある以下の環境変数をサポートしています。

| 環境変数                      | 説明                                                                               | 実装状況 |
| :---------------------------- | :--------------------------------------------------------------------------------- | :------: |
| `ANSIBLE_STDOUT_CALLBACK`     | 使用する標準出力コールバックプラグインを指定（例: `default`, `json`, `yaml` 等）    |    ◎     |
| `ANSIBLE_COLLECTIONS_PATH`    | コレクションの探索パスをコロン区切り（Windows はセミコロン区切り）で指定           |    ◎     |
| `ANSIBLE_ROLES_PATH`          | ロールの探索パスをコロン区切り（Windows はセミコロン区切り）で指定                 |    ◎     |
| `ANSIBLE_HASH_BEHAVIOUR`      | 辞書型変数のマージ戦略を指定 (`replace` または `merge`)                            |    ◎     |
| `ANSIBLE_SITE_PACKAGES`       | Python の `site-packages` （依存ライブラリの探索パス）を区切り文字で指定           |    ◎     |
| `ANSIBLE_VAULT_PASSWORD_FILE` | デフォルトの Vault パスワードファイルパスを指定                                     |    ◎     |
| `ANSIBLE_LOG_PATH`            | 実行ログファイル出力先パス（`java.util.logging.FileHandler` で使用）               |    ◎     |

### 6.1 Java システムプロパティ (Java System Properties)

Java の起動時オプション（`-Dproperty=value`）として、以下の設定をサポートしています。

| プロパティ名            | 説明                                                                               | 実装状況 |
| :---------------------- | :--------------------------------------------------------------------------------- | :------: |
| `ansible.site.packages` | Python の `site-packages` （依存ライブラリの探索パス）を区切り文字で指定           |    ◎     |

## 7. コレクション探索パスの優先順位

複数の方法でコレクションの探索パスが指定された場合、`PythonEnv.getCollectionPaths` は以下の優先順位で探索ディレクトリをマージ・解決します。

1. **CLI オプション (`--collections-path`)**
2. **環境変数 (`ANSIBLE_COLLECTIONS_PATH`)**
3. **`ansible.cfg` の `collections_paths` / `collections_path` 設定**
4. **デフォルトパス** (`~/.ansible/collections`, `/usr/share/ansible/collections` 等。詳細は [コレクションの管理と取得方法](Collection-Management.md) を参照)

## 8. 実装方針・コールバック選択・終了コード

- **CLI 解析ライブラリ**: [picocli 4.7.5](https://picocli.info/) を採用。
- **Native Image 対応**: GraalVM Native Image での実行時リフレクションをサポートするため、picocli のアノテーションプロセッサによる事前メタデータ生成を利用。

### 8.1 Stdout コールバックプラグインの選択メカニズム
`PlaybookCli` は、実行開始時に `CallbackFactory.createStdoutCallback(null, collectionPaths)` を呼び出して標準出力用コールバックを動的に初期化します。
1. 環境変数 `ANSIBLE_STDOUT_CALLBACK` が設定されている場合、その名称（`default`, `json`, `yaml`, `minimal` 等）に対応するコールバックインスタンスを生成します。
2. 同一名称の Python 製コールバックプラグインがコレクションパス上に存在する場合は、`PythonCallback` を介して GraalPy 上で Python コールバックを実行します。
3. 未指定時のデフォルトは `DefaultCallback`（Ansible 互換の標準フォーマット）が適用されます。

### 8.2 コマンドライン終了コードマッピング (CLI Exit Code Mapping)

`PlaybookCli` は、実行結果および例外種別に応じて本家 Ansible と完全に同一の終了コードを返却します。

| 終了コード | 状態分類                       | 判定条件・発生事象                                                                     |
| :---: | :----------------------------- | :------------------------------------------------------------------------------------- |
| **`0`** | 正常終了 (Success)             | 全ホストのタスクが正常終了（失敗ホスト数および到達不能ホスト数がともに `0`）。         |
| **`1`** | 一般エラー (Runtime Error)     | インベントリ未指定、`--ask-become-pass` ヘッドレスエラー、Vault 認証失敗、未捕捉例外。 |
| **`2`** | ターゲット実行失敗 (Host Fail) | いずれかのホストでタスクが失敗、またはホストが到達不能（`ignore_errors` 未適用時）。  |
| **`4`** | 構文・解析エラー (Parse Error) | Playbook の YAML 構文エラー、スキーマ不整合、未知のキーワード検知。                   |

### 8.3 プログラム API とレポート統合

Java コードからのプログラム的な実行においては、`PlaybookExecutor` クラスのインターフェースを使用します。

- **`PlaybookExecutor.executeAndReport(...)`**:
  Playbook の実行完了時に詳細なメトリクスを集計した `ExecutionReport` オブジェクトを返却します。
- **実行結果と終了コードの対応関係**:
  `ExecutionReport.hasFailed()` または `ExecutionReport.hasUnreachable()` が真の場合、コマンドライン実行時には適切な非ゼロ終了コード (`1` または `2`) へマッピングされます。
