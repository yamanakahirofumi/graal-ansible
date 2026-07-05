# ドキュメント一覧

このディレクトリには、ansible-coreをJavaで再実装するプロジェクトに関する詳細なドキュメントが格納されています。

## 1. プロジェクトの現在のステータス
`graal-ansible` は、Java 21 と GraalPy を基盤とした Ansible 実行エンジンの再実装プロジェクトです。現在、以下の主要機能が実装され、動作検証が行われています。

- **コアエンジン (管理ノード)**: linear（順次）および free（並列）戦略による Playbook 実行、マルチホスト対応、PlaybookExecutor による実行管理。
- **YAML 解析**: SnakeYAML 2.x による Playbook (Record) へのマッピング、`block/rescue/always` 対応。
- **変数解決**: Jinjava による Jinja2 互換テンプレート、22段階の変数優先順位（all, group, host, play, extra-vars等）。
- **タスク制御 (Worker)**: `when`, `loop`, `register`, `notify/handlers`, `until/retries`, `delegate_to`, `ignore_unreachable`, `delegate_facts` 等のサポート。
- **権限昇格**: `become` (sudo, su) の実装。
- **コレクション対応**: フェーズ 1 進行中（ansible-core の完全ロードと Linux での 67 モジュールの検証完了：61 ◎, 4 ○, 2 ●）。
- **接続 (Connection Plugin)**: `local` 接続および `ssh` (Apache MINA SSHD) の基盤。
- **ターゲット実行 (ターゲットノード)**: Ansiballz 形式によるモジュール転送・実行モデルの実装。
- **OS 抽象化**: `OSHandler` によるターゲット OS (Linux/Windows) 間の差異吸収。
- **配布**: GraalVM Native Image による単一バイナリ化と、GitHub Actions によるマルチプラットフォーム CI。

## 2. フォルダ構成と配置

ドキュメントは内容に応じて以下のいずれかに分類して配置します。

- **`docs/features/`**：機能仕様、管理ノードとターゲットノードの動作、コマンドライン引数の仕様など。
- **`docs/tech/`**：技術スタック、全体アーキテクチャ、コーディング規約、GraalVM設定など。
- **`docs/implementation/`**：各コンポーネント（Parser, Executor, Connection等）の詳細な実装方法。

---

## 3. 機能・仕様 (`docs/features/`)
- [CLI仕様](features/CLI-Specification.md)：ansible-playbook互換のコマンドライン引数
- [処理フロー](features/Process-Flow.md)：管理ノードからターゲットノードまでの全体フロー（**本プロジェクトの基本設計方針**）
- [動作環境](features/System-Requirements.md)：Java/GraalVMの実行環境とOS互換性
- [インベントリ管理](features/Inventory-System.md)：静的・動的インベントリのサポート範囲
- [コレクションの管理と取得方法](features/Collection-Management.md)：実際のコレクションを取得・利用する手順
- [Playbook実行仕様](features/Playbook-Execution.md)：タスク実行、ループ、条件分岐のサポート範囲
- [Ansible用語集](features/Ansible-Terminology.md)：コレクション、モジュール、ロール等の用語定義
- [モジュール互換性](features/Module-Compatibility.md)：モジュール互換性の目標と設計指針
- [モジュールサポート状態](features/Module-Support-Status.md)：OSごとのコレクション・モジュールサポート状況
- [モジュールとモックの対応リファレンス](features/Module-Mock-Reference.md)：各モジュールが依存するモックの詳細一覧
- [モジュールの開発方針](features/Module-Development-Policy.md)：Ansibleモジュールの実行および再実装禁止の方針

## 4. 技術・開発設定 (`docs/tech/`)
- [アーキテクチャ設計](tech/Architecture.md)：システムのパッケージ構造と主要クラスの責務
- [エラーハンドリング方針](tech/Error-Handling-Policy.md)：基本方針と各ケースでの対応
- [ロギング方針](tech/Logging-Policy.md)：デバッグおよび保守のためのログ出力指針
- [技術スタック](tech/Tech-Stack.md)：使用している言語、ライブラリ、ツールなどの情報
- [GraalPy 統合の詳細](tech/GraalPy-Integration.md)：Ansible Python モジュールの実行環境と互換性維持のためのパッチ
- [CI 設定](tech/CI-Setting.md)：GitHub Actions を利用した自動ビルドとテストの設定について
- [実際のコレクションを用いたテスト](tech/Actual-Collection-Testing.md)：実際の Ansible コレクションを用いた自動テストの実施方法
- [実際のコレクションを用いた自動テストの要件](tech/Automated-Testing-Requirements.md)：テスト実行に必要な環境設定や手順の詳細
- [テストケース拡充戦略](tech/Test-Expansion-Strategy.md)：フェーズ 1 におけるテストの網羅性向上計画
- [テストルール](tech/Test-Rule.md)：テストケース作成の一般的なガイドライン
- [品質方針](tech/Quality-Policy.md)：フェーズ（仕様未確定/確定）に応じた品質の考え方と到達目標
- [配布方法](tech/Distribution-Method.md)：カスタム JRE による配布パッケージの作成について
- [コーディング規約](tech/Coding-Convention.md)：クラス作成基準（record, final の使用等）について
- [仕様書の書き方ルール](tech/Specification-Rule.md)：本プロジェクトにおけるドキュメント作成基準
- [TODOリストの書き方ルール](tech/TODO-Rule.md)：検討事項の追加・更新ルール

## 5. 実装詳細 (`docs/implementation/`)
- [YAML解析エンジン](implementation/YAML-Parser.md)：SnakeYAML等を用いたPlaybook解析の実装
- [インベントリシステム実装](implementation/Inventory-System.md)：静的インベントリの解析と管理
- [タスク実行エンジン](implementation/Task-Executor.md)：マルチスレッド実行と順序制御
- [ストラテジ・プラグインの実装仕様](implementation/Strategy-Plugins.md)：実行戦略（linear, free等）による実行制御
- [Action Plugin 実装仕様](implementation/Action-Plugins.md)：制御ノード側で動作するプラグインの実行メカニズム (Python-first)
- [Action Plugin 実装調査報告](implementation/Action-Plugins-Investigation.md)：GraalPy 上での実行における技術的課題と解決策
- [Action Plugin / Module Mock 実装リファレンス](implementation/Mock-Implementation-Reference.md)：Dependency Emulation Strategy のためのモック実装詳細
- [タスク制御の実装詳細](implementation/Task-Control.md)：when, loop, register, block, retry等の制御ロジック
- [コールバックプラグインの設計仕様](implementation/Callback-Plugins.md)：実行イベントの通知と出力形式の制御
- [Ansible モジュールの初期化と設定](implementation/Ansible-Module-Initialization.md)：AnsibleModule のインスタンス構成、引数の受け渡し、モンキーパッチの詳細
- [接続プラグイン実装](implementation/Connection-Plugins.md)：Local, SSH(JSch/Apache MINA SSHD)の実装
- [リモートノードでのモジュール実行仕様](implementation/Remote-Module-Execution.md)：ターゲットノードへモジュールを転送して実行する仕組み（モジュール転送型）
- [権限昇格 (become)](implementation/Privilege-Escalation.md)：sudo, su等による実行ユーザーの切り替え
- [変数とテンプレート](implementation/Variables-Templating.md)：Jinja2互換エンジンの統合
- [OS非依存レイヤー](implementation/OS-Abstraction.md)：ファイル操作やプロセス実行のOS差分吸収
- [Native Image最適化](implementation/Native-Image-Optimization.md)：GraalVMでのリフレクション設定と最適化
- [コレクション実装ロードマップ](implementation/Collection-Implementation-Roadmap.md)：本物のコレクション実行に向けた段階的実装計画

## 6. 検討事項（TODOリスト）
開発を進めるにあたって検討・具体化が必要な事項のリストです。
詳細な内容は [検討事項・TODOリスト](TODO-Details.md) を参照してください。
