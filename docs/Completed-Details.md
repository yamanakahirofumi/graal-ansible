# 完了済み項目アーカイブ (Completed Items Archive)

本ドキュメントは、[TODO-Details.md](TODO-Details.md) から移動された完了済みの項目をアーカイブしたものです。過去の実装履歴や決定事項の参照に使用してください。

## 1. 完了済みの項目 (Completed)

### 1.1 [✓] シリアル実行 (serial) の実装
- **完了日**: 2026-06-26
- **概要**: `linear` 戦略において、プレイ内のホストをバッチに分割して実行する機能を実装。
- **解決策**:
    - `Play` レコードおよび `YamlParser` へ `serial` フィールドを追加.
    - `LinearStrategy` において、整数、パーセンテージ、およびリスト形式の `serial` 指定に基づくバッチ分割ロジックを実装.
    - 各バッチ実行後にハンドラーをフラッシュし、マジック変数 `ansible_play_batch` を現在実行中のバッチ内の非失敗ホストのみを指すよう動的に更新する仕組みを導入.

### 1.2 [✓] インベントリシステムの動的更新に関する記述の修正
- **完了日**: 2026-04-16
- **概要**: `docs/implementation/Inventory-System.md` においてインベントリが不変（Immutable）であると記載されていた不整合を解消。
- **解決策**: 実行時の動的更新（`add_host`, `group_by`）をサポートするため、内部コレクションが可変（Mutable）である旨を明記。

### 1.3 [✓] Truthiness 判定ルールのドキュメント化
- **完了日**: 2026-04-16
- **概要**: `org.example.ansible.util.Truthiness` に実装されている判定ルールをドキュメント化。
- **解決策**: `docs/implementation/Task-Control.md` に「15. 真偽判定 (Truthiness)」セクションを追加。

### 1.4 [✓] モジュールサポート状態の同期
- **完了日**: 2026-07-26
- **概要**: `docs/features/Module-Support-Status.md` および本ドキュメントのテスト済みモジュール数を最新化。
- **解決策**: 動作確認済みモジュール数を 68（うち 61 ◎, 5 ○, 2 ●）に更新し、`async_status`, `async_wrapper`, `mount_facts`, `dpkg_selections`, `deb822_repository`, `expect`, `subversion`, `wait_for_connection`, `reboot` 等を含める形で同期。

### 1.5 [✓] 実際の Ansible コレクションを使ったテストの実施
- **完了日**: 2026-07-26
- **概要**: [実際のコレクションを使ったテスト方法の設計](tech/Actual-Collection-Testing.md) に基づき、主要なモジュールのテストを統合。
- **解決策**:
    - `ping`, `copy`, `file`, `stat`, `template`, `debug`, `command`, `shell`, `setup`, `lineinfile`, `replace`, `user`, `group`, `find`, `tempfile`, `hostname`, `slurp`, `assert`, `blockinfile`, `getent`, `fetch`, `uri`, `unarchive`, `include_vars`, `include_tasks`, `import_tasks`, `set_fact`, `add_host`, `fail`, `get_url`, `group_by`, `gather_facts`, `assemble`, `script`, `package_facts`, `apt`, `apt_key`, `apt_repository`, `service_facts`, `systemd`, `systemd_service`, `raw`, `set_stats`, `validate_argument_spec`, `pip`, `wait_for`, `debconf`, `sysvinit`, `package`, `service`, `git`, `cron`, `iptables`, `known_hosts`, `import_playbook`, `pause`, `meta`, `import_role`, `include_role`, `mount_facts`, `dpkg_selections`, `deb822_repository`, `expect`, `subversion`, `wait_for_connection`, `reboot`, `async_status`, `async_wrapper` の計 68 モジュールの動作確認済み（うち 61 ◎, 5 ○, 2 ●）。
    - GraalPy および `ansible-core` のセットアップを GitHub Actions 上で自動化済み。

### 1.6 [✓] Action Plugin の互換性向上 (Python-first)
- **完了日**: 2026-03-25
- **概要**: 重厚な Ansible Core への依存を排除し、本物の Action Plugin を GraalPy 上で安定動作させる。
- **解決策**:
    - [テクニカルリファレンス](implementation/Action-Plugins-Investigation.md) に基づく Dependency Emulation Strategy の確立。
    - `ansible_bridge.py` による `ansible.module_utils` 等の徹底的なモック化により、`copy`, `template`, `debug`, `setup`, `command`, `shell` 等の主要モジュールを、Java エミュレータを介さずオリジナルの Python ソースコードで実行することに成功。
    - **注記**: 以前開発されていた Java ベースの Action Plugin エミュレータは、本方針の確立に伴い、完全な互換性確保のためすべて削除・置換されました。

### 1.7 [✓] Native Image 時のリフレクション設定
- **完了日**: 2026-03-05
- **概要**: YAML 解析や動的クラスロードに伴うリフレクション定義の生成。
- **解決策**:
    - `src/main/resources/META-INF/native-image/` 配下に `reflect-config.json`, `resource-config.json`, `native-image.properties` を作成済み。
    - SnakeYAML, Jackson, Picocli, および主要な Record クラスのリフレクション設定を包含。

### 1.8 [✓] --extra-vars における JSON/YAML および @file 構文のサポート
- **完了日**: 2026-07-27
- **概要**: `ansible-playbook` 互換の `--extra-vars` / `-e` オプションにおける高度な構文をサポート。
- **解決策**:
    - `@file.yml`, `@file.yaml`, `@file.json` によるファイルからの変数読み込みを実装。
    - `{...}` によるインライン JSON/YAML 指定をサポート。
    - `PlaybookCli.parseExtraVars` において SnakeYAML を用いた解析ロジックを統合。

### 1.9 [✓] 権限昇格 (become) の実装
- **完了日**: 2026-03-05
- **概要**: [権限昇格 (become)](implementation/Privilege-Escalation.md) に基づく sudo/su 等の実行サポート。
- **解決策**:
    - `BecomeContext` レコードを定義し、`Connection` インターフェースの `execCommand` メソッドへ統合。
    - `LocalConnection` において、`sudo` および `su` によるコマンドのラップ処理を実装。
    - `PlaybookExecutor` にて Playレベルおよび Taskレベルの `become` 設定の解決ロジックを実装済み。

### 1.10 [✓] OS 抽象化レイヤー (OSHandler) の実装
- **完了日**: 2026-03-05
- **概要**: [OS 抽象化レイヤー](implementation/OS-Abstraction.md) に基づき、ターゲット OS ごとの差異を吸収。
- **解決策**:
    - `OSHandler` インターフェースおよび `LinuxHandler`, `WindowsHandler` を実装。
    - シェル実行コマンド (`/bin/sh -c` vs `cmd.exe /c`) や一時ディレクトリ、パス区切り文字の共通化を実現。
    - `OSHandlerFactory` による実行環境に応じた動的なハンドラ切り替えを実装済み。

### 1.11 [✓] 独自 Jinja2 フィルターの拡充 (Ansible 互換)
- **完了日**: 2026-05-11
- **概要**: Playbook で頻繁に使用される Ansible 特有の Jinja2 フィルターを Java で実装。
- **解決策**: `mandatory`, `basename`, `dirname`, `splitext`, `realpath`, `ternary`, `flatten`, `to_nice_json`, `to_nice_yaml` を含む、計 27 種類のフィルターを実装し `VariableResolver` に登録済み。

### 1.12 [✓] GraalPy と Java のシームレスな統合
- **完了日**: 2026-03-04
- **概要**: Java コードから既存の Ansible Python モジュールを効率的に呼び出すためのブリッジ設計。
- **解決策**: `PythonModule` クラスにて Polyglot API を使用し、`complex_args` を介した引数受け渡しと標準出力キャプチャによる結果取得を実装済み。

### 1.13 [✓] SnakeYAML 2.x による Playbook 解析の実装
- **完了日**: 2026-03-04
- **概要**: YAML 形式の Playbook を Java オブジェクト（Record）へマッピング。
- **解決策**: `YamlParser` にて予約語（`when`, `loop` 等）とモジュール引数の分離、および `block/rescue/always` の再帰的パースを実装済み。

### 1.14 [✓] インベントリ管理の基本機能
- **完了日**: 2026-02-22
- **概要**: ターゲットホストの静的ファイル（INI/YAML）からの読み込みと優先順位解決。
- **解決策**:
    - INI/YAML 形式のインベントリサポート済み。
    - [インベントリシステム実装](implementation/Inventory-System.md) に基づき、`Inventory` クラスにて階層化されたグループ変数の優先順位解決（all < parent < child < host）を実装済み。

### 1.15 [✓] 実行エンジンの基本設計
- **完了日**: 2026-02-22
- **概要**: 複数タスクの順次実行とエラーハンドリングの基本方針。
- **解決策**: [タスク実行エンジン](implementation/Task-Executor.md) にて、`linear` 戦略の採用を策定・実装済み。

### 1.16 [✓] Jinjava による変数テンプレートの実装
- **完了日**: 2026-03-04
- **概要**: Playbook や変数ファイル内の Jinja2 テンプレートを展開する仕組み。
- **解決策**:
    - `VariableResolver` にて Jinjava を統合し、動的な変数展開を実装済み。
    - 計 27 種類の Ansible 互換フィルター（`bool`, `combine`, `default`, `dict2items`, `ipaddr`, `to_json`, `to_nice_json`, `to_yaml`, `to_nice_yaml` 等）を Java で実装し登録済み。

### 1.17 [✓] タスク制御機能（when, register, loop, handlers, block, retry, check_mode 等）の実装
- **完了日**: 2026-03-05
- **概要**: 実行の動的制御や変数の再利用、繰り返し処理、エラーハンドリング、およびドライランのサポート。
- **解決策**:
    - [タスク制御の実装詳細](implementation/Task-Control.md) に基づき、`PlaybookExecutor` および `TaskExecutor` へ全機能を組み込み済み。
    - `when`, `loop` (`with_items`), `register`, `handlers` (`notify`), `block/rescue/always`, `until/retries/delay`, `failed_when/changed_when`, `delegate_to`, `run_once`, `ignore_errors`, `check_mode` をサポート。

### 1.18 [✓] タスク制御キーワードの拡充 (environment)
- **完了日**: 2026-03-05
- **概要**: [タスク制御の実装詳細](implementation/Task-Control.md) に基づき、`environment` のサポートを追加。
- **解決策**:
    - `environment` 変数のテンプレート展開と、`LocalConnection` / `SshConnection` への伝播を実装済み。
    - Play, Block, Task の各レベルでのマージと、タスク実行直前の遅延評価をサポート。

### 1.19 [✓] Ansible 互換性の維持レベル
- **決定事項**: **Ansible 13** で動くコレクションが動作することを目標とする。

### 1.20 [✓] 変数の優先順位（22段階）の完全な実装とテスト
- **完了日**: 2026-07-27
- **概要**: [Variables-Templating.md](implementation/Variables-Templating.md) に基づき、Ansible 互換の 22 段階の変数優先順位を実装。
- **解決策**: `VariableManager` において全 22 レベルの優先順位解決ロジックを実装し、テストスイートによりその正当性を検証済み。

### 1.21 [✓] Lookup プラグインの実装
- **完了日**: 2026-05-14
- **概要**: 外部ソースからデータを取得するための `lookup` および `query` 関数の実装。
- **解決策**:
    - `Jinjava` へのカスタム関数登録を行い、`lookup` と `query` をサポート。
    - 主要なプラグイン (`file`, `env`, `template`, `pipe`, `dict`) を実装済み。
    - `Lookup` インターフェースを `List<Object>` 対応に改善し、型安全なデータ受け渡しを実現。

### 1.22 [✓] 動的インベントリ (Script mode) の実装
- **完了日**: 2026-05-20
- **概要**: 外部スクリプト（Python等）から JSON 形式でインベントリ情報を取得・統合する機能の実装。
- **解決策**:
    - `ScriptInventoryProvider` を実装し、`--list` 引数による外部スクリプトの実行と JSON 解析をサポート。
    - `InventoryManager` にて、静的ファイルと動的スクリプトの透過的なマージ処理を実現。

### 1.23 [✓] インベントリ・ディレクトリのサポート
- **完了日**: 2026-05-22
- **概要**: 単一ファイルだけでなく、ディレクトリを指定した際の再帰的なインベントリ読み込みをサポート。
- **解決策**:
    - `InventoryManager` にてディレクトリの再帰走査、除外ルール（隠しファイル、backup、vars等）、および大文字小文字を区別しないアルファベット順のソートを実装。

### 1.24 [✓] エラー時の即時停止 (any_errors_fatal) の実装
- **完了日**: 2026-06-05
- **概要**: いずれかのホストで失敗した場合に全ホストで実行を停止する機能を実装。
- **解決策**: `TaskQueueManager` に `playFatalError` フラグを導入し、`any_errors_fatal` が有効なタスクまたはプレイにおいて、ホスト失敗時に当該フラグをセットして以降のタスク実行を中断するロジックを実装。

### 1.25 [✓] コールバックプラグイン・システムの実装
- **完了日**: 2026-06-10
- **概要**: 実行イベントの通知と、それに基づく出力形式の制御を行う基盤を実装。
- **解決策**:
    - `Callback` インターフェースを定義し、Playbook, Play, Task の各開始・終了イベント、およびホストごとの実行結果（ok, failed, skipped, unreachable）をフック可能に。
    - `DefaultCallback` を実装し、Ansible 互換の標準出力（PLAY RECAP 等）を実現。
    - `PlaybookExecutor` および `TaskQueueManager` にコールバック登録・呼び出しメカニズムを統合。

### 1.26 [✓] インベントリ・プロバイダーの統合 (InventoryManager)
- **完了日**: 2026-06-12
- **概要**: 複数のインベントリソース（ファイル、スクリプト、ディレクトリ）を透過的に扱うための統合管理機構を実装。
- **解決策**:
    - `InventoryManager` を導入し、`FileInventoryProvider` と `ScriptInventoryProvider` をオーケストレートする構成に刷新。
    - 単一のソース指定に対して、その形式（静的/動的/ディレクトリ）を自動判別し、適切にマージされた `Inventory` オブジェクトを生成するロジックを実装。

### 1.27 [✓] ストラテジ・プラグイン（Strategy Plugins）の実装
- **完了日**: 2026-07-28
- **概要**: Playbook 内の実行戦略（linear, free）を切り替え可能にする基盤の実装。
- **解決策**:
    - `Strategy` インターフェースを定義し、`LinearStrategy` および `FreeStrategy` を実装。
    - `StrategyFactory` による動的な戦略選択をサポート。
    - `FreeStrategy` において、`ThreadPoolExecutor` を用いたホストごとの並列タスク実行を実現。
    - `TaskQueueManager.executePlay` の実行ループ制御を `Strategy` へ委譲するリファクタリングを完了。

### 1.28 [✓] 再帰的ハッシュマージ (hash_behaviour=merge) の実装
- **完了日**: 2026-07-28
- **概要**: 辞書型の変数が重複した場合に、単なる置換ではなく再帰的にマージする機能の導入。
- **解決策**:
    - `VariableManager` において再帰的なマージロジック (`mergeRecursive`) を実装.
    - 環境変数 `ANSIBLE_HASH_BEHAVIOUR` による動的な切り替えに対応.

### 1.29 [✓] 並列実行数 (forks) の外部設定
- **完了日**: 2026-07-29
- **概要**: 固定値となっていた並列実行数を、コマンドライン引数や設定ファイルから変更可能にする。
- **解決策**:
    - `PlaybookCli` への `-f` / `--forks` オプションを追加.
    - `PlaybookExecutor` および `TaskQueueManager` に `forks` 設定値を保持するフィールドと setter を追加し、`FreeStrategy` での実行時に参照するように修正.

### 1.30 [✓] 非同期タスク (async, poll) の実装
- **完了日**: 2026-07-30
- **概要**: タスクのバックグラウンド実行とポーリング機能。
- **解決策**:
    - `AsyncJobManager` および `DefaultAsyncJobManager` を導入.
    - `TaskExecutor` において `async` キーワードに応じたスレッドプールへの投入と `poll` に基づく待機処理を実装.
    - `async_status` モジュールを Java で実装.

### 1.31 [✓] タスク実行制限 (throttle) のサポート
- **完了日**: 2026-06-28
- **概要**: 特定のタスクやブロック、プレイにおいて、同時に実行できるホスト数を制限する機能の実装。
- **解決策**:
    - `Task` / `Play` レコードへ `throttle` キーワードを追加.
    - `FreeStrategy` において `Semaphore` を用いた同時実行ホスト数の制御を実装.
    - `VariableResolver` にて `throttle` 値（数値またはテンプレート）の動的評価をサポート.

### 1.32 [✓] 失敗許容率 (max_fail_percentage) の完全サポート
- **完了日**: 2026-08-02
- **概要**: `linear` 戦略に加えて、`free` 戦略および `Role` 実行時においても `max_fail_percentage` を有効化。
- **解決策**:
    - `TaskQueueManager` においてロール実行ループ後に `checkMaxFailPercentage` を呼び出すよう修正。
    - `FreeStrategy` において各ホストのタスク実行直後に `checkMaxFailPercentage` を呼び出し、並列実行中も閾値を超えた時点で新規タスクの開始を抑止する仕組みを導入。
    - 判定ロジックがロール変数を正しく参照できるよう、`checkMaxFailPercentage` の引数に `activeRoles` を追加。

### 1.33 [✓] ストラテジ固有のコールバック最適化
- **完了日**: 2026-07-07
- **概要**: `free` 戦略などにおいて、ホストごとに進捗が異なる場合の標準出力の出力形式を最適化。
- **解決策**:
    - `DefaultCallback` において、タスクおよびハンドラーのヘッダー出力を 1 回のみに制限する重複排除ロジック（Deduplication）を実装。
    - 全てのコールバックメソッドに `synchronized` を付与し、並列実行時（`free` 戦略等）の出力の混在を防止。

### 1.34 [✓] Python ベースのインベントリ・プラグインのサポート
- **完了日**: 2026-04-18
- **概要**: GraalPy 上でオリジナルの Ansible Inventory Plugin を実行する「Python-first」アプローチのサポート。
- **解決策**:
    - `PythonInventoryProvider` を実装し、拡張子が `.yml` または `.yaml` で、かつトップレベルに `plugin` キーを持つインベントリファイルを自動検知する仕組みを導入。
    - `ansible_bridge.py` および `ansible_inventory_launcher.py` を介して、GraalPy 環境上でオリジナルの Python 製インベントリプラグインを読み込み、実行するブリッジメカニズムを確立。
    - 実行結果を JSON 形式で出力（`InventoryData.to_dict()`）し、Java 側で Jackson を用いて解析、Java `Inventory` レコード（Group、Host、変数、親子関係）に透過的にマージして構築するパーサを実装。

### 1.35 [✓] Docker コネクションプラグインの実装
- **完了日**: 2026-07-25
- **概要**: `docker` コネクションプラグインの実装。
- **解決策**:
    - `DockerConnection` クラスを実装し、Docker CLI (`docker exec`, `docker cp`, `docker inspect`) を用いたコンテナ内でのコマンド実行やファイル転送をサポート。
    - 環境変数転送（`docker exec -e`）や複数権限昇格モード（`sudo`, `su`, `docker exec -u` によるネイティブユーザーの上書き）に対応。
    - `DockerConnectionTest` による、プロセスおよびデータストリームをモックした詳細なユニットテストを実装。
    - `DefaultConnectionFactory` にて、`ansible_connection` が `docker` の場合に自動的に `DockerConnection` が解決されるようマージ。

### 1.36 [✓] WinRM コネクションプラグインの実装
- **完了日**: 2026-10-24
- **概要**: `winrm` コネクションプラグインの実装。
- **解決策**:
    - `WinRMConnection` クラスを実装し、`WinRM4J` ライブラリを使用して Windows ターゲットノードへの接続、PowerShell 経由のコマンド実行、環境変数の伝播、および Base64 チャンク転送によるファイル転送（`putFile` / `fetchFile`）をサポート。
    - `DefaultConnectionFactory` において、`ansible_connection` が `winrm` の場合に、動的に `WinRMConnection` を作成・解決するロジック（SSL 検証やポート、プロトコルの設定を含む）を統合。
    - `WinRMConnectionTest` による、`WinRM4J` クライアントやセッションを Mockito で詳細にモック化したテストスイートを構築し、エラーハンドリングや become 設定に応じた動作を検証。

### 1.37 [✓] Native Java Ansible Vault 復号の実装
- **完了日**: 2026-08-05
- **概要**: `VaultDecrypter` の実装と `YamlParser` / `VariableResolver` への統合によるネイティブ復号のサポート。
- **解決策**:
    - PBKDF2WithHmacSHA256、HMAC-SHA256、および AES-CTR 暗号を用いた Java 標準暗号ライブラリベースの復号エンジン `VaultDecrypter` を実装。
    - SnakeYAML 解析フェーズにおいて `!vault` 独自タグを検知し `VaultDecryptedValue` オブジェクトへ透過的にマッピングするカスタムコンストラクタを `YamlUtil` に導入。
    - `VariableResolver` にて `VaultDecryptedValue` の変数解決時にパスワードを用いて動的復号するロジックを統合。パスワード未指定時の適切なエラーハンドリングをサポート。
    - `--vault-password-file` および `--vault-id` CLI引数のパースを `PlaybookCli` に追加し、パスワード解決およびエンジンへの伝播を実現。

### 1.38 [✓] SSH 踏み台サーバー (Bastion / Jump Host) 経由接続のサポート
- **完了日**: 2026-10-24
- **概要**: ターゲットホストがプライベートネットワークにあり、踏み台サーバー（Bastion）経由でしか接続できない環境において、Apache MINA SSHD を用いた Java ネイティブな多段 SSH トンネリング（ローカルポートフォワード等）による接続サポートを実装。
- **解決策**:
    - `SshJumpHostParser` クラスを実装し、`ansible_ssh_common_args`、`ansible_ssh_extra_args` 内の `ProxyJump` (-J) や `ProxyCommand` 形式の記述のパース、および `graal-ansible` 独自の踏み台サーバー専用変数（`ansible_bastion_host` 等）の解決ロジックをサポート。
    - `SshConnection` クラスにおいて、Apache MINA SSHD のセッションから `startLocalPortForwarding` を使用し、ローカルの動的ポートからターゲットホストへのポートフォワーディングを確立して接続する仕組みを実装。
    - クローズ処理（`close()`）の呼び出し時に、ターゲットセッション、ポートフォワード停止、踏み台セッションを逆順で安全に終了するカスケードクローズ（リソースリーク防止）を実装。
    - `SshConnectionTest.java` において、Mockito による `SshClient` や `ClientSession` の詳細なモック化を含む単体テストスイートを構築・検証。

### 1.39 [✓] first_found ルックアッププラグインの実装とテスト
- **完了日**: 2026-10-24
- **概要**: 候補ファイルリストの中から、最初に実在するファイルの絶対パスを解決する `first_found` ルックアッププラグインを Java でネイティブに実装。
- **解決策**:
    - `FirstFoundLookup.java` を追加し、`terms` や `kwargs` 内の `files`, `paths`, `skip` 引数をパースして適切にファイル存在チェックを行うロジックを実装。
    - `VariableResolver.java` において `first_found` ルックアッププラグインの登録を行い、Jinja2 テンプレート解決フェーズで呼び出せるよう統合。
    - `FirstFoundLookupTest.java` にて、ファイルのリスト指定、絶対・相対パス、comma区切りの解釈、inline map（Jinja2辞書）引数、`skip` パラメータ、および例外発生の境界テストなどを網羅。

### 1.40 [✓] to_nice_json / to_nice_yaml フィルターの実装とカスタマイズ拡張
- **完了日**: 2026-10-24
- **概要**: Jinja2 テンプレート内でオブジェクトを整形出力するための `to_nice_json` および `to_nice_yaml` フィルターを Java で実装し、`indent`, `sort_keys`, `width` などのカスタマイズ引数をサポート。
- **解決策**:
    - `ToNiceJsonFilter` および `ToNiceYamlFilter` を実装し、位置引数（Positional Arguments）およびキーワード/名前付き引数（Keyword Arguments）の両方によるパラメータ展開をサポート。
    - `VariableResolver` にて両フィルターを登録し、`ToNiceJsonFilterTest` および `ToNiceYamlFilterTest` により動作を検証済み。

## 2. 整理・調整済み (Refactored/Adjusted)

### 2.1 [✓] GitHub Actions CI ワークフローの構築
- **完了日**: 2026-03-05
- **概要**: docs/tech/CI-Setting.md に記載されていたが未実装だった CI 環境を構築。
- **解決策**: `.github/workflows/build.yml` を作成し、マルチプラットフォームでのビルドとテストを自動化。

### 2.2 [✓] Task Record および YamlParser の同期
- **完了日**: 2026-03-05
- **概要**: docs/implementation/Task-Control.md で予約されていたが未実装だったキーワードの追加。
- **解決策**: `Task` レコードに `ignore_unreachable`, `delegate_facts` を追加し、`YamlParser` での解析をサポート。

### 2.3 [✓] タスク制御キーワードの追加実装 (delegate_facts, ignore_unreachable)
- **完了日**: 2026-03-19
- **概要**: `delegate_facts` および `ignore_unreachable` の実行エンジン（TaskQueueManager）への組み込み。
- **解決策**:
    - `ignore_unreachable`: `UnreachableException` キャッチ時のスキップ処理を実装.
    - `delegate_facts`: `_ansible_delegated_host` メタデータを利用したファクト保存先制御を実装.

### 2.4 [✓] Action Plugin の実行ブリッジ実装
- **完了日**: 2026-03-19
- **概要**: 管理ノード側での Action Plugin 実行ブリッジ（`ansible_action_launcher.py`）の実装。
- **解決策**:
    - `TaskExecutor` において Action Plugin の動的検知とランチャー起動を実装.
    - Java (ITaskExecutor) から Python (Action Plugin) への双方向呼び出しを実現.
    - ※ 互換性の課題については [Action-Plugins-Investigation.md](implementation/Action-Plugins-Investigation.md) を参照.

### 2.5 [✓] Java による Action Plugin 軽量エミュレータの導入 (Transitional)
- **完了日**: 2026-03-20
- **概要**: GraalPy 上での Ansible Core ロードに伴う互換性問題の回避と高速化のための暫定措置。
- **解決策**:
    - `ActionPlugin` Java インターフェースを定義し、`TaskExecutor` に組み込みプラグインの検索・実行ロジックを実装.
    - `debug`, `set_fact`, `copy` の Java 版エミュレータを実装し、一時的な回避策として運用.
    - **現状**: 上記「Action Plugin の互換性向上 (Python-first)」の完了により、本エミュレータ群は役割を終え、現在はオリジナルの Python コード実行に完全に置き換えられています。

### 2.6 [✓] 実行エンジン（TQM/Worker）のリファクタリングと抽象化
- **完了日**: 2026-03-20
- **概要**: `PlaybookExecutor` および `TaskQueueManager`, `TaskExecutor` の責務の明確化と抽象化。
- **解決策**:
    - **コネクション解決の抽象化**: `ConnectionFactory` インターフェースを導入し、`ansible_connection` に基づく動的なコネクション生成を `TaskQueueManager` で管理.
    - **ループ処理の分離**: `TaskExecutor` 内で `executeLoopTask`, `resolveLoopItems`, `executeLoopIteration` にメソッドを分割し、可読性を向上.
    - **条件評価の集約**: `when` 句の評価ロジックを `VariableResolver` へ集約し、呼び出し側（TQM, Worker）のコードを簡素化.
    - **委譲 (delegate_to) の実装**: コネクションの動的な切り替えと、委譲先ホストに応じた変数解決の基盤を実装.

### 2.7 [✓] Java 版 Action Plugin インターフェースの削除と整理
- **完了日**: 2026-04-16
- **概要**: ドキュメントとの不整合を解消するため、使用されていなかった `ActionPlugin` Java インターフェースおよび `TaskExecutor` 内の関連ロジックを完全に削除。
- **解決策**: Action Plugin の実行を GraalPy による Python-first 実装に一本化し、不要になった Java 側のインフラを排除。

### 2.8 [✓] エラーハンドリング方針の詳細化
- **完了日**: 2026-04-17
- **概要**: `docs/tech/Error-Handling-Policy.md` を更新し、`ConnectionResult` による結果返却メカニズムや、`UnreachableException` による接続失敗時の挙動、および `meta: flush_handlers` の実行エンジンレベルでの処理詳細について具体的に記載。
- **解決策**: 実行エンジンの実装に基づき、接続失敗時の `ignore_unreachable` の挙動、`ConnectionResult` による標準出力・標準エラー・終了コードの返却、および `meta: flush_handlers` によるハンドラーの即時実行仕様を明文化。

### 2.9 [✓] Python ベースのコールバックのサポート
- **完了日**: 2026-07-11
- **概要**: GraalPy を利用して Ansible 本家の Python 製コールバックプラグインをそのまま実行する仕組みを導入。
- **解決策**:
    - `PythonCallback` を実装し、Java のコールバックイベントを Python 側のプラグインへブリッジ。
    - `ansible_callback_launcher.py` により、Python 側の `CallbackBase` 互換のオブジェクト（TaskResult, Task, Host等）を生成して渡す仕組みを構築。
    - 環境変数 `ANSIBLE_STDOUT_CALLBACK` を通じた動的なプラグイン選択をサポート。

### 2.10 [✓] 集合演算フィルターのサポートと統合テストの追加
- **完了日**: 2026-10-24
- **概要**: `difference`, `intersect`, `union`, `symmetric_difference` 集合演算フィルターのテスト追加・検証とドキュメント整備。
- **解決策**:
    - `SetFiltersTest.java` による各集合演算フィルターの動作確認と堅牢性の担保。
    - `docs/features/Playbook-Execution.md` を更新し、対応するフィルター数の増補を反映。

### 2.11 [✓] PlaybookExecutor 実行結果レポート (ExecutionReport) およびフィルタリング機能の実装
- **完了日**: 2026-10-24
- **概要**: `PlaybookExecutor` 実行後の詳細統計取得およびホスト・タスク結果のフィルタリング機能を `ExecutionReport` として標準実装。
- **解決策**:
    - `ExecutionReport` クラスを `org.example.ansible.engine` パッケージに追加。
    - ホスト別・全体共通のメトリクス（ok, changed, unreachable, failed, skipped, total）の自動集計、`isSuccess()` 判定、および `toSummaryMap()` によるレポート出力機能をサポート。
    - ホスト状態フィルタ（`getFailedHosts`, `getChangedHosts`, `getSkippedHosts`, Predicate）およびタスク結果フィルタ（`getFailedTaskResults`, `getUnreachableTaskResults`, `getChangedTaskResults`, Predicate）をサポート。
    - `PlaybookExecutor` に `executeAndReport` オーバーロードメソッドを統合し、`ExecutionReportTest` による単体テストスイートを構築。

### 2.12 [✓] マルチ OS 対応モジュールロード検証テストの展開と CI 統合
- **完了日**: 2026-10-24
- **概要**: `ansible.builtin` 全 72 モジュールのロード（`py_compile`）および `ansible.module_utils` 依存関係のロードを、Linux、macOS、Windows 実行コンテキスト（`PythonOSMock`）で全自動検証するテストスイートの構築。
- **解決策**:
    - `ModuleLoadVerificationTest.java` を実装し、LinuxContext、macOSContext (`MacOSHandler`)、WindowsContext (`WindowsHandler`) のそれぞれで全 72 モジュールが GraalPy 上でコンパイル・インポートエラーなく正常にロードできることを検証。
    - `docs/features/Module-Support-Status.md` および `docs/tech/Test-Expansion-Strategy.md` のモジュールロードテストステータスを同期・整理。
