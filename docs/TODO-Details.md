# TODOリスト（検討事項）

本プロジェクトにおける、機能追加、技術的課題、未確定事項の一覧です。
**※本ファイルには、設計面（アーキテクチャ、アルゴリズム、データ構造、インターフェース定義など）に関する事項のみを記載してください。**

## 1. 技術面 (Technical)

### [ ] CI における Native Image ビルドの安定化
- **概要**: GitHub Actions 上での Native Image コンパイル時間の短縮とリソース最適化。
- **検討内容**:
    - Build Cache の有効活用。
    - Windows/macOS 環境でのビルドエラーの監視と修正。

## 2. 実装時の詳細事項

### [ ] 実際の Ansible コレクションを使ったテストの拡充
- **概要**: 主要なモジュールのテストを継続的に追加し、互換性を検証する。
- **検討内容**:
    - `ansible.builtin.template`, `ansible.builtin.file`, `ansible.builtin.stat` 等のテスト実装。
    - 各 OS（Linux, Windows, macOS）固有のモジュール挙動の検証。

### [ ] 本物のコレクション実行に向けた段階的実装 (フェーズ1)
- **概要**: `ansible-core` を完全にロードし、Linux/macOS での基本動作を実現する。
- **検討内容**:
    - GraalPy へのライブラリパス統合。
    - 不足している Python 依存パッケージの特定。

### [ ] 本物のコレクション実行に向けた段階的実装 (フェーズ2)
- **概要**: ハイブリッド実装（モンキーパッチ等）による Windows サポート。
- **検討内容**:
    - Windows で動作しないモジュールの特定と代替実装。

### [ ] 本物のコレクション実行に向けた段階的実装 (フェーズ3)
- **概要**: `ansible-core` 全体のロードを排除し、最適化と安定化を図る。
- **検討内容**:
    - 最小限の `module_utils` 抽出とバンドル化。

## 完了済みの項目 (Completed)

### [x] Native Image 時のリフレクション設定
- **完了日**: 2026-03-05
- **概要**: YAML 解析や動的クラスロードに伴うリフレクション定義の生成。
- **解決策**:
    - `src/main/resources/META-INF/native-image/` 配下に `reflect-config.json`, `resource-config.json`, `native-image.properties` を作成済み。
    - SnakeYAML, Jackson, Picocli, および主要な Record クラスのリフレクション設定を包含。

### [x] 権限昇格 (become) の実装
- **完了日**: 2026-03-05
- **概要**: [権限昇格 (become)](implementation/Privilege-Escalation.md) に基づく sudo/su 等の実行サポート。
- **解決策**:
    - `BecomeContext` レコードを定義し、`Connection` インターフェースの `execCommand` メソッドへ統合。
    - `LocalConnection` において、`sudo` および `su` によるコマンドのラップ処理を実装。
    - `PlaybookExecutor` にて Play レベルおよび Task レベルの `become` 設定の解決ロジックを実装済み。

### [x] OS 抽象化レイヤー (OSHandler) の実装
- **完了日**: 2026-03-05
- **概要**: [OS 抽象化レイヤー](implementation/OS-Abstraction.md) に基づき、ターゲット OS ごとの差異を吸収。
- **解決策**:
    - `OSHandler` インターフェースおよび `LinuxHandler`, `WindowsHandler` を実装。
    - シェル実行コマンド (`/bin/sh -c` vs `cmd.exe /c`) や一時ディレクトリ、パス区切り文字の共通化を実現。
    - `OSHandlerFactory` による実行環境に応じた動的なハンドラ切り替えを実装済み。

### [x] 実際の Ansible コレクションを使ったテストの実施
- **完了日**: 2026-03-05
- **概要**: [実際のコレクションを使ったテスト方法の設計](tech/Actual-Collection-Testing.md) に基づき、主要なモジュールのテストを統合。
- **解決策**:
    - `ansible.builtin.copy`, `ansible.builtin.command` 等のテストを CI 環境へ統合。
    - GraalPy および `ansible-core` のセットアップを GitHub Actions 上で自動化済み。

### [x] GraalPy と Java のシームレスな統合
- **完了日**: 2026-03-04
- **概要**: Java コードから既存の Ansible Python モジュールを効率的に呼び出すためのブリッジ設計。
- **解決策**: `PythonModule` クラスにて Polyglot API を使用し、`complex_args` を介した引数受け渡しと標準出力キャプチャによる結果取得を実装済み。

### [x] SnakeYAML 2.x による Playbook 解析の実装
- **完了日**: 2026-03-04
- **概要**: YAML 形式の Playbook を Java オブジェクト（Record）へマッピング。
- **解決策**: `YamlParser` にて予約語（`when`, `loop` 等）とモジュール引数の分離、および `block/rescue/always` の再帰的パースを実装済み。

### [x] インベントリ管理の基本機能
- **完了日**: 2026-02-22
- **概要**: ターゲットホストの静的ファイル（INI/YAML）からの読み込みと優先順位解決。
- **解決策**:
    - INI/YAML 形式のインベントリサポート済み。
    - [インベントリシステム実装](implementation/Inventory-System.md) に基づき、`Inventory` クラスにて階層化されたグループ変数の優先順位解決（all < parent < child < host）を実装済み。

### [x] 実行エンジンの基本設計
- **完了日**: 2026-02-22
- **概要**: 複数タスクの順次実行とエラーハンドリングの基本方針。
- **解決策**: [タスク実行エンジン](implementation/Task-Executor.md) にて、`linear` 戦略の採用を策定・実装済み。

### [x] Jinjava による変数テンプレートの実装
- **完了日**: 2026-03-04
- **概要**: Playbook や変数ファイル内の Jinja2 テンプレートを展開する仕組み。
- **解決策**:
    - `VariableResolver` にて Jinjava を統合し、動的な変数展開を実装済み。
    - 主要なフィルター（`bool`, `combine`, `default`, `dict2items`, `ipaddr`, `to_json`, `to_yaml`）を Java で実装し登録済み。

### [x] タスク制御機能（when, register, loop, handlers, block, retry等）の実装
- **完了日**: 2026-02-23
- **概要**: 実行の動的制御や変数の再利用、繰り返し処理、エラーハンドリングのサポート。
- **解決策**:
    - [タスク制御の実装詳細](implementation/Task-Control.md) に基づき、`PlaybookExecutor` および `TaskExecutor` へ全機能を組み込み済み。
    - `when`, `loop` (`with_items`), `register`, `handlers` (`notify`), `block/rescue/always`, `until/retries/delay`, `failed_when/changed_when`, `delegate_to`, `run_once`, `ignore_errors` をサポート。

### [✓] Ansible 互換性の維持レベル
- **決定事項**: **Ansible 13** で動くコレクションが動作することを目標とする。

## 3. 整理・調整済み (Refactored/Adjusted)

### [x] GitHub Actions CI ワークフローの構築
- **完了日**: 2026-03-05
- **概要**: docs/tech/CI-Setting.md に記載されていたが未実装だった CI 環境を構築。
- **解決策**: `.github/workflows/build.yml` を作成し、マルチプラットフォームでのビルドとテストを自動化。

### [x] Task Record および YamlParser の同期
- **完了日**: 2026-03-05
- **概要**: docs/implementation/Task-Control.md で予約されていたが未実装だったキーワードの追加。
- **解決策**: `Task` レコードに `ignore_unreachable`, `delegate_facts` を追加し、`YamlParser` での解析をサポート。
