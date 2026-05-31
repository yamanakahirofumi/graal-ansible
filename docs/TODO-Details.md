# TODOリスト（検討事項）

本プロジェクトにおける、機能追加、技術的課題、未確定事項の一覧です。
**※本ファイルには、設計面（アーキテクチャ、アルゴリズム、データ構造、インターフェース定義など）に関する事項のみを記載してください。**

## 1. 技術面 (Technical)

### 1.1 [ ] CI における Native Image ビルドの安定化
- **概要**: 優先度低。GitHub Actions 上での Native Image コンパイル時間の短縮とリソース最適化。
- **検討内容**:
    - Build Cache の有効活用。
    - Windows/macOS 環境でのビルドエラーの監視と修正。

## 2. 実装時の詳細事項

### 2.1 [ ] Ansible 本体の完全ロードと基本動作の実現 (フェーズ1)
- **概要**: `ansible-core` を完全にロードし、Linux/macOS での全 72 モジュールの動作確認。
- **詳細は [Module-Support-Status.md](features/Module-Support-Status.md) を参照。** (現在 65/72 モジュール検証済み: 61 ◎, 4 ○)
- **テスト拡充戦略については [Test-Expansion-Strategy.md](tech/Test-Expansion-Strategy.md) を参照。**
- **注意**: `python.IsolateNativeModules` と `python.PosixModuleBackend` はフェーズ 1 においては原則として固定（Linuxでは安定のため True/Native）とする。
- **備考**: 検証には必要に応じて **Testcontainers** を**ターゲットノード**として活用する。全モジュールの検証完了をもってフェーズ 1 完了とする。

### 2.2 [ ] ハイブリッド実装による Windows サポート (フェーズ2)
- **概要**: ハイブリッド実装（モンキーパッチ等）による Windows サポート。
- **詳細は [Module-Support-Status.md](features/Module-Support-Status.md) を参照。**

### 2.3 [ ] Ansible 本体のロード排除と最適化 (フェーズ3)
- **概要**: `ansible-core` 全体のロードを排除し、最適化と安定化を図る。
- **詳細は [Module-Support-Status.md](features/Module-Support-Status.md) を参照。**

## 3. 完了済みの項目 (Completed)

### 3.1 [✓] インベントリシステムの動的更新に関する記述の修正
- **完了日**: 2026-04-16
- **概要**: `docs/implementation/Inventory-System.md` においてインベントリが不変（Immutable）であると記載されていた不整合を解消。
- **解決策**: 実行時の動的更新（`add_host`, `group_by`）をサポートするため、内部コレクションが可変（Mutable）である旨を明記。

### 3.2 [✓] Truthiness 判定ルールの明文化
- **完了日**: 2026-04-16
- **概要**: `org.example.ansible.util.Truthiness` に実装されている判定ルールをドキュメント化。
- **解決策**: `docs/implementation/Task-Control.md` に「15. 真偽判定 (Truthiness)」セクションを追加。

### 3.3 [✓] モジュールサポート状態の同期
- **完了日**: 2026-07-26
- **概要**: `docs/features/Module-Support-Status.md` および本ドキュメントのテスト済みモジュール数を最新化。
- 解決策: 動作確認済みモジュール数を 65（うち 61 ◎、4 ○）に更新し、`mount_facts`, `dpkg_selections`, `deb822_repository`, `expect`, `subversion`, `wait_for_connection` 等を含める形で同期。

### 3.4 [✓] 実際の Ansible コレクションを使ったテストの実施
- **完了日**: 2026-07-26
- **概要**: [実際のコレクションを使ったテスト方法の設計](tech/Actual-Collection-Testing.md) に基づき、主要なモジュールのテストを統合。
- **解決策**:
    - `ping`, `copy`, `file`, `stat`, `template`, `debug`, `command`, `shell`, `setup`, `lineinfile`, `replace`, `user`, `group`, `find`, `tempfile`, `hostname`, `slurp`, `assert`, `blockinfile`, `getent`, `fetch`, `uri`, `unarchive`, `include_vars`, `include_tasks`, `import_tasks`, `set_fact`, `add_host`, `fail`, `get_url`, `group_by`, `gather_facts`, `assemble`, `script`, `package_facts`, `apt`, `apt_key`, `apt_repository`, `service_facts`, `systemd`, `systemd_service`, `raw`, `set_stats`, `validate_argument_spec`, `pip`, `wait_for`, `debconf`, `sysvinit`, `package`, `service`, `git`, `cron`, `iptables`, `known_hosts`, `import_playbook`, `pause`, `meta`, `import_role`, `include_role`, `mount_facts`, `dpkg_selections`, `deb822_repository`, `expect`, `subversion`, `wait_for_connection` の計 65 モジュールの動作確認済み（うち 61 モジュールは統合テスト ◎、4 モジュールはエンジン検証 ○）。
    - GraalPy および `ansible-core` のセットアップを GitHub Actions 上で自動化済み。

### 3.5 [✓] Action Plugin の互換性向上 (Python-first)
- **完了日**: 2026-03-25
- **概要**: 重厚な Ansible Core への依存を排除し、本物の Action Plugin を GraalPy 上で安定動作させる。
- **解決策**:
    - [テクニカルリファレンス](implementation/Action-Plugins-Investigation.md) に基づく Dependency Emulation Strategy の確立。
    - `ansible_bridge.py` による `ansible.module_utils` 等の徹底的なモック化により、`copy`, `template`, `debug`, `setup`, `command`, `shell` 等の主要モジュールを、Java エミュレータを介さずオリジナルの Python ソースコードで実行することに成功。
    - **注記**: 以前開発されていた Java ベースの Action Plugin エミュレータは、本方針の確立に伴い、完全な互換性確保のためすべて削除・置換されました。

### 3.6 [✓] Native Image 時のリフレクション設定
- **完了日**: 2026-03-05
- **概要**: YAML 解析や動的クラスロードに伴うリフレクション定義の生成。
- **解決策**:
    - `src/main/resources/META-INF/native-image/` 配下に `reflect-config.json`, `resource-config.json`, `native-image.properties` を作成済み。
    - SnakeYAML, Jackson, Picocli, および主要な Record クラスのリフレクション設定を包含。

### 3.7 [✓] --extra-vars における JSON/YAML および @file 構文のサポート
- **完了日**: 2026-07-27
- **概要**: `ansible-playbook` 互換の `--extra-vars` / `-e` オプションにおける高度な構文をサポート。
- **解決策**:
    - `@file.yml`, `@file.yaml`, `@file.json` によるファイルからの変数読み込みを実装。
    - `{...}` によるインライン JSON/YAML 指定をサポート。
    - `PlaybookCli.parseExtraVars` において SnakeYAML を用いた解析ロジックを統合。

### 3.8 [✓] 権限昇格 (become) の実装
- **完了日**: 2026-03-05
- **概要**: [権限昇格 (become)](implementation/Privilege-Escalation.md) に基づく sudo/su 等の実行サポート。
- **解決策**:
    - `BecomeContext` レコードを定義し、`Connection` インターフェースの `execCommand` メソッドへ統合。
    - `LocalConnection` において、`sudo` および `su` によるコマンドのラップ処理を実装。
    - `PlaybookExecutor` にて Playレベルおよび Taskレベルの `become` 設定の解決ロジックを実装済み。

### 3.9 [✓] OS 抽象化レイヤー (OSHandler) の実装
- **完了日**: 2026-03-05
- **概要**: [OS 抽象化レイヤー](implementation/OS-Abstraction.md) に基づき、ターゲット OS ごとの差異を吸収。
- **解決策**:
    - `OSHandler` インターフェースおよび `LinuxHandler`, `WindowsHandler` を実装。
    - シェル実行コマンド (`/bin/sh -c` vs `cmd.exe /c`) や一時ディレクトリ、パス区切り文字の共通化を実現。
    - `OSHandlerFactory` による実行環境に応じた動的なハンドラ切り替えを実装済み。

### 3.10 [✓] 独自 Jinja2 フィルターの拡充 (Ansible 互換)
- **完了日**: 2026-05-11
- **概要**: Playbook で頻繁に使用される Ansible 特有の Jinja2 フィルターを Java で実装。
- **解決策**: `mandatory`, `basename`, `dirname`, `splitext`, `realpath`, `ternary`, `flatten` を実装し `VariableResolver` に登録。


### 3.11 [✓] GraalPy と Java のシームレスな統合
- **完了日**: 2026-03-04
- **概要**: Java コードから既存の Ansible Python モジュールを効率的に呼び出すためのブリッジ設計。
- **解決策**: `PythonModule` クラスにて Polyglot API を使用し、`complex_args` を介した引数受け渡しと標準出力キャプチャによる結果取得を実装済み。

### 3.12 [✓] SnakeYAML 2.x による Playbook 解析の実装
- **完了日**: 2026-03-04
- **概要**: YAML 形式の Playbook を Java オブジェクト（Record）へマッピング。
- **解決策**: `YamlParser` にて予約語（`when`, `loop` 等）とモジュール引数の分離、および `block/rescue/always` の再帰的パースを実装済み。

### 3.13 [✓] インベントリ管理の基本機能
- **完了日**: 2026-02-22
- **概要**: ターゲットホストの静的ファイル（INI/YAML）からの読み込みと優先順位解決。
- **解決策**:
    - INI/YAML 形式のインベントリサポート済み。
    - [インベントリシステム実装](implementation/Inventory-System.md) に基づき、`Inventory` クラスにて階層化されたグループ変数の優先順位解決（all < parent < child < host）を実装済み。

### 3.14 [✓] 実行エンジンの基本設計
- **完了日**: 2026-02-22
- **概要**: 複数タスクの順次実行とエラーハンドリングの基本方針。
- **解決策**: [タスク実行エンジン](implementation/Task-Executor.md) にて、`linear` 戦略の採用を策定・実装済み。

### 3.15 [✓] Jinjava による変数テンプレートの実装
- **完了日**: 2026-03-04
- **概要**: Playbook や変数ファイル内の Jinja2 テンプレートを展開する仕組み。
- **解決策**:
    - `VariableResolver` にて Jinjava を統合し、動的な変数展開を実装済み。
    - 主要なフィルター（`bool`, `combine`, `default`, `dict2items`, `ipaddr`, `to_json`, `to_yaml`）を Java で実装し登録済み。

### 3.16 [✓] タスク制御機能（when, register, loop, handlers, block, retry, check_mode 等）の実装
- **完了日**: 2026-03-05
- **概要**: 実行の動的制御や変数の再利用、繰り返し処理、エラーハンドリング、およびドライランのサポート。
- **解決策**:
    - [タスク制御の実装詳細](implementation/Task-Control.md) に基づき、`PlaybookExecutor` および `TaskExecutor` へ全機能を組み込み済み。
    - `when`, `loop` (`with_items`), `register`, `handlers` (`notify`), `block/rescue/always`, `until/retries/delay`, `failed_when/changed_when`, `delegate_to`, `run_once`, `ignore_errors`, `check_mode` をサポート。

### 3.17 [✓] タスク制御キーワードの拡充 (environment)
- **完了日**: 2026-03-05
- **概要**: [タスク制御の実装詳細](implementation/Task-Control.md) に基づき、`environment` のサポートを追加。
- **解決策**:
    - `environment` 変数のテンプレート展開と、`LocalConnection` / `SshConnection` への伝播を実装済み。
    - Play, Block, Task の各レベルでのマージと、タスク実行直前の遅延評価をサポート。

### 3.18 [✓] Ansible 互換性の維持レベル
- **決定事項**: **Ansible 13** で動くコレクションが動作することを目標とする。

### 3.19 [✓] 変数の優先順位（22段階）の完全な実装とテスト
- **完了日**: 2026-07-27
- **概要**: [Variables-Templating.md](implementation/Variables-Templating.md) に基づき、Ansible 互換の 22 段階の変数優先順位を実装.
- **解決策**: `VariableManager` において全 22 レベルの優先順位解決ロジックを実装し、テストスイートによりその正当性を検証済み。

### 3.20 [✓] Lookup プラグインの実装
- **完了日**: 2026-05-14
- **概要**: 外部ソースからデータを取得するための `lookup` および `query` 関数の実装。
- **解決策**:
    - `Jinjava` へのカスタム関数登録を行い、`lookup` と `query` をサポート。
    - 主要なプラグイン (`file`, `env`, `template`, `pipe`, `dict`) を実装済み。
    - `Lookup` インターフェースを `List<Object>` 対応に改善し、型安全なデータ受け渡しを実現。

### 3.21 [✓] 動的インベントリの実装 (Script mode)
- **完了日**: 2026-05-20
- **概要**: 実行可能スクリプトから JSON 形式でインベントリを取得する機能を実装。
- **解決策**:
    - `ScriptInventoryProvider` を実装し、`--list` 引数によるスクリプト実行と JSON 解析をサポート。
    - `_meta.hostvars` によるホスト変数の解決を統合。

### 3.22 [✓] インベントリ・ディレクトリ・サポート
- **完了日**: 2026-05-22
- **概要**: インベントリソースとしてディレクトリを指定した際の再帰的走査と除外ルールの実装。
- **解決策**:
    - `InventoryManager` においてディレクトリ走査ロジックを実装。
    - アルファベット順の処理、および隠しファイルや `vars` ディレクトリ等の除外ルールを Ansible 互換で適用。

## 4. 整理・調整済み (Refactored/Adjusted)

### 4.1 [✓] GitHub Actions CI ワークフローの構築
- **完了日**: 2026-03-05
- **概要**: docs/tech/CI-Setting.md に記載されていたが未実装だった CI 環境を構築。
- **解決策**: `.github/workflows/build.yml` を作成し、マルチプラットフォームでのビルドとテストを自動化。

### 4.2 [✓] Task Record および YamlParser の同期
- **完了日**: 2026-03-05
- **概要**: docs/implementation/Task-Control.md で予約されていたが未実装だったキーワードの追加。
- **解決策**: `Task` レコードに `ignore_unreachable`, `delegate_facts` を追加し、`YamlParser` での解析をサポート。

### 4.3 [✓] タスク制御キーワードの追加実装 (delegate_facts, ignore_unreachable)
- **完了日**: 2026-03-19
- **概要**: `delegate_facts` および `ignore_unreachable` の実行エンジン（TaskQueueManager）への組み込み。
- **解決策**:
    - `ignore_unreachable`: `UnreachableException` キャッチ時のスキップ処理を実装。
    - `delegate_facts`: `_ansible_delegated_host` メタデータを利用したファクト保存先制御を実装。

### 4.4 [✓] Action Plugin の実行ブリッジ実装
- **完了日**: 2026-03-19
- **概要**: 管理ノード側での Action Plugin 実行ブリッジ（`ansible_action_launcher.py`）の実装。
- **解決策**:
    - `TaskExecutor` において Action Plugin の動的検知とランチャー起動を実装。
    - Java (ITaskExecutor) から Python (Action Plugin) への双方向呼び出しを実現。
    - ※ 互換性の課題については [Action-Plugins-Investigation.md](implementation/Action-Plugins-Investigation.md) を参照。

### 4.5 [✓] Java による Action Plugin 軽量エミュレータの導入 (Transitional)
- **完了日**: 2026-03-20
- **概要**: GraalPy 上での Ansible Core ロードに伴う互換性問題の回避と高速化のための暫定措置。
- **解決策**:
    - `ActionPlugin` Java インターフェースを定義し、`TaskExecutor` に組み込みプラグインの検索・実行ロジックを実装。
    - `debug`, `set_fact`, `copy` の Java 版エミュレータを実装し、一時的な回避策として運用。
    - **現状**: 上記「Action Plugin の互換性向上 (Python-first)」の完了により、本エミュレータ群は役割を終え、現在はオリジナルの Python コード実行に完全に置き換えられています。

### 4.6 [✓] 実行エンジン（TQM/Worker）のリファクタリングと抽象化
- **完了日**: 2026-03-20
- **概要**: `PlaybookExecutor` および `TaskQueueManager`, `TaskExecutor` の責務ের明確化と抽象化。
- **解決策**:
    - **コネクション解決の抽象化**: `ConnectionFactory` インターフェースを導入し、`ansible_connection` に基づく動的なコネクション生成を `TaskQueueManager` で管理。
    - **ループ処理の分離**: `TaskExecutor` 内で `executeLoopTask`, `resolveLoopItems`, `executeLoopIteration` にメソッドを分割し、可読性を向上.
    - **条件評価の集約**: `when` 句の評価ロジックを `VariableResolver` へ集約し、呼び出し側（TQM, Worker）のコードを簡素化。
    - **委譲 (delegate_to) の実装**: コネクションの動的な切り替えと、委譲先ホストに応じた変数解決の基盤を実装。

### 4.7 [✓] Java 版 Action Plugin インターフェースの削除と整理
- **完了日**: 2026-04-16
- **概要**: ドキュメントとの不整合を解消するため、使用されていなかった `ActionPlugin` Java インターフェースおよび `TaskExecutor` 内の関連ロジックを完全に削除。
- **解決策**: Action Plugin の実行を GraalPy による Python-first 実装に一本化し、不要になった Java 側のインフラを排除。

### 4.8 [✓] エラーハンドリング方針の詳細化
- **完了日**: 2026-04-17
- **概要**: `docs/tech/Error-Handling-Policy.md` を更新し、`ConnectionResult` による結果返却メカニズムや、`UnreachableException` による接続失敗時の挙動、および `meta: flush_handlers` の実行エンジンレベルでの処理詳細について具体的に記載。
- **解決策**: 実行エンジンの実装に基づき、接続失敗時の `ignore_unreachable` の挙動、`ConnectionResult` による標準出力・標準エラー・終了コードの返却、および `meta: flush_handlers` によるハンドラーの即時実行仕様を明文化。

## 5. 今後のリファクタリング検討事項 (Future Refactoring Items)

### 5.1 [ ] PlaybookExecutor および実行エンジンのさらなる整理
- **概要**: `PlaybookExecutor` および `TaskQueueManager`, `TaskExecutor` 内に存在する課題の継続的な改善。
- **検討内容**:
    - 動的インベントリ（プラグインモード）の統合実装。

