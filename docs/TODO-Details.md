# TODOリスト（検討事項）

本プロジェクトにおける、機能追加、技術的課題、未確定事項の一覧です。
**※本ファイルには、設計面（アーキテクチャ、アルゴリズム、データ構造、インターフェース定義など）に関する事項のみを記載してください。**

## 1. 技術面 (Technical)

### [ ] 実際の Ansible コレクションを使ったテストの実施
- **概要**: [実際のコレクションを使ったテスト方法の設計](tech/Actual-Collection-Testing.md) に基づき、主要なモジュールのテストを統合。
- **進捗**:
    - `ansible.builtin.ping`, `copy`, `file`, `stat`, `template`, `lineinfile`, `replace`, `command`, `shell`, `setup` の統合テストを CI 環境へ統合済み。
    - GraalPy および `ansible-core` のセットアップを GitHub Actions 上で自動化済み。

### [ ] CI における Native Image ビルドの安定化
- **概要**: 優先度低。GitHub Actions 上での Native Image コンパイル時間の短縮とリソース最適化。
- **検討内容**:
    - Build Cache の有効活用。
    - Windows/macOS 環境でのビルドエラーの監視と修正。

## 2. 実装時の詳細事項

### [ ] Ansible 本体の完全ロードと基本動作の実現 (フェーズ1)
- **概要**: `ansible-core` を完全にロードし、Linux/macOS での主要モジュールの動作確認。
- **詳細は [Module-Support-Status.md](features/Module-Support-Status.md) を参照。**
- **テスト拡充戦略については [Test-Expansion-Strategy.md](tech/Test-Expansion-Strategy.md) を参照。**
- **注意**: `python.IsolateNativeModules` と `python.PosixModuleBackend` はフェーズ 1 では変更しない。
- **備考**: 検証には必要に応じて **Testcontainers** を**ターゲットノード**として活用する。

### [ ] ハイブリッド実装による Windows サポート (フェーズ2)
- **概要**: ハイブリッド実装（モンキーパッチ等）による Windows サポート。
- **詳細は [Module-Support-Status.md](features/Module-Support-Status.md) を参照。**

### [ ] Ansible 本体のロード排除と最適化 (フェーズ3)
- **概要**: `ansible-core` 全体のロードを排除し、最適化と安定化を図る。
- **詳細は [Module-Support-Status.md](features/Module-Support-Status.md) を参照。**

## 3. 完了済みの項目 (Completed)

### [✓] Native Image 時のリフレクション設定
- **完了日**: 2026-03-05
- **概要**: YAML 解析や動的クラスロードに伴うリフレクション定義の生成。
- **解決策**:
    - `src/main/resources/META-INF/native-image/` 配下に `reflect-config.json`, `resource-config.json`, `native-image.properties` を作成済み。
    - SnakeYAML, Jackson, Picocli, および主要な Record クラスのリフレクション設定を包含。

### [✓] 権限昇格 (become) の実装
- **完了日**: 2026-03-05
- **概要**: [権限昇格 (become)](implementation/Privilege-Escalation.md) に基づく sudo/su 等の実行サポート。
- **解決策**:
    - `BecomeContext` レコードを定義し、`Connection` インターフェースの `execCommand` メソッドへ統合。
    - `LocalConnection` において、`sudo` および `su` によるコマンドのラップ処理を実装.
    - `PlaybookExecutor` にて Playレベルおよび Taskレベルの `become` 設定の解決ロジックを実装済み。

### [✓] OS 抽象化レイヤー (OSHandler) の実装
- **完了日**: 2026-03-05
- **概要**: [OS 抽象化レイヤー](implementation/OS-Abstraction.md) に基づき、ターゲット OS ごとの差異を吸収。
- **解決策**:
    - `OSHandler` インターフェースおよび `LinuxHandler`, `WindowsHandler` を実装。
    - シェル実行コマンド (`/bin/sh -c` vs `cmd.exe /c`) や一時ディレクトリ、パス区切り文字の共通化を実現。
    - `OSHandlerFactory` による実行環境に応じた動的なハンドラ切り替えを実装済み。


### [✓] GraalPy と Java のシームレスな統合
- **完了日**: 2026-03-04
- **概要**: Java コードから既存の Ansible Python モジュールを効率的に呼び出すためのブリッジ設計。
- **解決策**: `PythonModule` クラスにて Polyglot API を使用し、`complex_args` を介した引数受け渡しと標準出力キャプチャによる結果取得を実装済み。

### [✓] SnakeYAML 2.x による Playbook 解析の実装
- **完了日**: 2026-03-04
- **概要**: YAML 形式の Playbook を Java オブジェクト（Record）へマッピング。
- **解決策**: `YamlParser` にて予約語（`when`, `loop` 等）とモジュール引数の分離、および `block/rescue/always` の再帰的パースを実装済み。

### [✓] インベントリ管理の基本機能
- **完了日**: 2026-02-22
- **概要**: ターゲットホストの静的ファイル（INI/YAML）からの読み込みと優先順位解決。
- **解決策**:
    - INI/YAML 形式のインベントリサポート済み。
    - [インベントリシステム実装](implementation/Inventory-System.md) に基づき、`Inventory` クラスにて階層化されたグループ変数の優先順位解決（all < parent < child < host）を実装済み。

### [✓] 実行エンジンの基本設計
- **完了日**: 2026-02-22
- **概要**: 複数タスクの順次実行とエラーハンドリングの基本方針。
- **解決策**: [タスク実行エンジン](implementation/Task-Executor.md) にて、`linear` 戦略の採用を策定・実装済み。

### [✓] Jinjava による変数テンプレートの実装
- **完了日**: 2026-03-04
- **概要**: Playbook や変数ファイル内の Jinja2 テンプレートを展開する仕組み。
- **解決策**:
    - `VariableResolver` にて Jinjava を統合し、動的な変数展開を実装済み。
    - 主要なフィルター（`bool`, `combine`, `default`, `dict2items`, `ipaddr`, `to_json`, `to_yaml`）を Java で実装し登録済み。

### [✓] タスク制御機能（when, register, loop, handlers, block, retry, check_mode 等）の実装
- **完了日**: 2026-03-05
- **概要**: 実行の動的制御や変数の再利用、繰り返し処理、エラーハンドリング、およびドライランのサポート。
- **解決策**:
    - [タスク制御の実装詳細](implementation/Task-Control.md) に基づき、`PlaybookExecutor` および `TaskExecutor` へ全機能を組み込み済み。
    - `when`, `loop` (`with_items`), `register`, `handlers` (`notify`), `block/rescue/always`, `until/retries/delay`, `failed_when/changed_when`, `delegate_to`, `run_once`, `ignore_errors`, `check_mode` をサポート。

### [✓] タスク制御キーワードの拡充 (environment)
- **完了日**: 2026-03-05
- **概要**: [タスク制御の実装詳細](implementation/Task-Control.md) に基づき、`environment` のサポートを追加。
- **解決策**:
    - `environment` 変数のテンプレート展開と、`LocalConnection` / `SshConnection` への伝播を実装済み。
    - Play, Block, Task の各レベルでのマージと、タスク実行直前の遅延評価をサポート。

### [✓] Ansible 互換性の維持レベル
- **決定事項**: **Ansible 13** で動くコレクションが動作することを目標とする。

## 4. 整理・調整済み (Refactored/Adjusted)

### [✓] GitHub Actions CI ワークフローの構築
- **完了日**: 2026-03-05
- **概要**: docs/tech/CI-Setting.md に記載されていたが未実装だった CI 環境を構築。
- **解決策**: `.github/workflows/build.yml` を作成し、マルチプラットフォームでのビルドとテストを自動化。

### [✓] Task Record および YamlParser の同期
- **完了日**: 2026-03-05
- **概要**: docs/implementation/Task-Control.md で予約されていたが未実装だったキーワードの追加。
- **解決策**: `Task` レコードに `ignore_unreachable`, `delegate_facts` を追加し、`YamlParser` での解析をサポート。

### [✓] タスク制御キーワードの追加実装 (delegate_facts, ignore_unreachable)
- **完了日**: 2026-03-19
- **概要**: `delegate_facts` および `ignore_unreachable` の実行エンジン（TaskQueueManager）への組み込み。
- **解決策**:
    - `ignore_unreachable`: `UnreachableException` キャッチ時のスキップ処理を実装。
    - `delegate_facts`: `_ansible_delegated_host` メタデータを利用したファクト保存先制御を実装。

### [✓] Action Plugin の実行ブリッジ実装
- **完了日**: 2026-03-19
- **概要**: 管理ノード側での Action Plugin 実行ブリッジ（`ansible_action_launcher.py`）の実装。
- **解決策**:
    - `TaskExecutor` において Action Plugin の動的検知とランチャー起動を実装。
    - Java (ITaskExecutor) から Python (Action Plugin) への双方向呼び出しを実現。
    - ※ 互換性の課題については [Action-Plugins-Investigation.md](implementation/Action-Plugins-Investigation.md) を参照。

### [✓] Java による Action Plugin 軽量エミュレータの導入
- **完了日**: 2026-03-20
- **概要**: GraalPy 上での Ansible Core ロードに伴う互換性問題の回避と高速化。
- **解決策**:
    - `ActionPlugin` Java インターフェースを定義し、`TaskExecutor` に組み込みプラグインの検索・実行ロジックを実装。
    - `debug`, `set_fact`, `copy` の Java 版エミュレータを実装し、Python 版よりも優先して実行するように調整。

### [✓] 実行エンジン（TQM/Worker）のリファクタリングと抽象化
- **完了日**: 2026-03-20
- **概要**: `PlaybookExecutor` および `TaskQueueManager`, `TaskExecutor` の責務の明確化と抽象化。
- **解決策**:
    - **コネクション解決の抽象化**: `ConnectionFactory` インターフェースを導入し、`ansible_connection` に基づく動的なコネクション生成を `TaskQueueManager` で管理。
    - **ループ処理の分離**: `TaskExecutor` 内で `executeLoopTask`, `resolveLoopItems`, `executeLoopIteration` にメソッドを分割し、可読性を向上。
    - **条件評価の集約**: `when` 句の評価ロジックを `VariableResolver` へ集約し、呼び出し側（TQM, Worker）のコードを簡素化。
    - **委譲 (delegate_to) の実装**: コネクションの動的な切り替えと、委譲先ホストに応じた変数解決の基盤を実装。

## 5. 今後のリファクタリング検討事項 (Future Refactoring Items)

### [✓] Action Plugin の互換性向上
- **完了日**: 2026-03-22
- **概要**: 重厚な Ansible Core への依存を排除し、Action Plugin の動作を安定させる。
- **解決策**:
    - [テクニカルリファレンス](implementation/Action-Plugins-Investigation.md) に基づく軽量なエミュレータの開発。
    - `copy` および `template` を Java で実装済み。

### [ ] PlaybookExecutor および実行エンジンのさらなる整理
- **概要**: `PlaybookExecutor` および `TaskQueueManager`, `TaskExecutor` 内に存在する課題の継続的な改善。
- **検討内容**:
    - 複雑な変数の優先順位の完全な実装（現在は主要な 11 段階のみ）。
    - 動的インベントリの完全なサポート。
