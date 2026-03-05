# ドキュメント一覧

このディレクトリには、ansible-coreをJavaで再実装するプロジェクトに関する詳細なドキュメントが格納されています。

## 1. フォルダ構成と配置

ドキュメントは内容に応じて以下のいずれかに分類して配置します。

## 0. プロジェクトの現在のステータス

`graal-ansible` は、Java 21 と GraalPy を基盤とした Ansible 実行エンジンの再実装プロジェクトです。現在、以下の主要機能が実装され、動作検証が行われています。

- **コアエンジン**: linear 戦略による Playbook の順次実行、マルチホスト対応。
- **YAML 解析**: SnakeYAML 2.x による Playbook (Record) へのマッピング、`block/rescue/always` 対応。
- **変数解決**: Jinjava による Jinja2 互換テンプレート、11段階の変数優先順位（all, group, host, play, extra-vars等）。
- **タスク制御**: `when`, `loop`, `register`, `notify/handlers`, `until/retries`, `delegate_to` 等の完全サポート。
- **権限昇格**: `become` (sudo, su) の実装。
- **接続**: `local` 接続および `ssh` (Apache MINA SSHD) の基盤。
- **OS 抽象化**: `OSHandler` による Linux/Windows 間の差異吸収。
- **配布**: GraalVM Native Image による単一バイナリ化と、GitHub Actions によるマルチプラットフォーム CI。

- **`docs/features/`**：機能仕様、Ansibleモジュールの動作、コマンドライン引数の仕様など。
- **`docs/tech/`**：技術スタック、アーキテクチャ、コーディング規約、GraalVM設定など。
- **`docs/implementation/`**：YAML解析、Playbook実行エンジン、インベントリ管理などの詳細な実装方法。

---

## 2. 機能・仕様 (`docs/features/`)
- [CLI仕様](features/CLI-Specification.md)：ansible-playbook互換のコマンドライン引数
- [動作環境](features/System-Requirements.md)：Java/GraalVMの実行環境とOS互換性
- [インベントリ管理](features/Inventory-System.md)：静的・動的インベントリのサポート範囲
- [Playbook実行仕様](features/Playbook-Execution.md)：タスク実行、ループ、条件分岐のサポート範囲
- [Ansible用語集](features/Ansible-Terminology.md)：コレクション、モジュール、ロール等の用語定義
- [モジュール互換性](features/Module-Compatibility.md)：実装済みのコアモジュール一覧
- [モジュールの開発方針](features/Module-Development-Policy.md)：Ansibleモジュールの実行および再実装禁止の方針

## 3. 技術・開発設定 (`docs/tech/`)
- [アーキテクチャ設計](tech/Architecture.md)：システムのパッケージ構造と主要クラスの責務
- [エラーハンドリング方針](tech/Error-Handling-Policy.md)：基本方針と各ケースでの対応
- [ロギング方針](tech/Logging-Policy.md)：デバッグおよび保守のためのログ出力指針
- [技術スタック](tech/Tech-Stack.md)：使用している言語、ライブラリ、ツールなどの情報
- [CI 設定](tech/CI-Setting.md)：GitHub Actions を利用した自動ビルドとテストの設定について
- [実際のコレクションを用いたテスト](tech/Actual-Collection-Testing.md)：実際の Ansible コレクションを用いた自動テストの実施方法
- [実際のコレクションを用いた自動テストの要件](tech/Automated-Testing-Requirements.md)：テスト実行に必要な環境設定や手順の詳細
- [テストルール](tech/Test-Rule.md)：テストケース作成の一般的なガイドライン
- [品質方針](tech/Quality-Policy.md)：フェーズ（仕様未確定/確定）に応じた品質の考え方と到達目標
- [配布方法](tech/Distribution-Method.md)：カスタム JRE による配布パッケージの作成について
- [コーディング規約](tech/Coding-Convention.md)：クラス作成基準（record, final の使用等）について
- [仕様書の書き方ルール](tech/Specification-Rule.md)：本プロジェクトにおけるドキュメント作成基準
- [TODOリストの書き方ルール](tech/TODO-Rule.md)：検討事項の追加・更新ルール

## 4. 実装詳細 (`docs/implementation/`)
- [YAML解析エンジン](implementation/YAML-Parser.md)：SnakeYAML等を用いたPlaybook解析の実装
- [インベントリシステム実装](implementation/Inventory-System.md)：静的インベントリの解析と管理
- [タスク実行エンジン](implementation/Task-Executor.md)：マルチスレッド実行と順序制御
- [タスク制御の実装詳細](implementation/Task-Control.md)：when, loop, register, block, retry等の制御ロジック
- [接続プラグイン実装](implementation/Connection-Plugins.md)：Local, SSH(JSch/Apache MINA SSHD)の実装
- [権限昇格 (become)](implementation/Privilege-Escalation.md)：sudo, su等による実行ユーザーの切り替え
- [変数とテンプレート](implementation/Variables-Templating.md)：Jinja2互換エンジンの統合
- [OS非依存レイヤー](implementation/OS-Abstraction.md)：ファイル操作やプロセス実行のOS差分吸収
- [Native Image最適化](implementation/Native-Image-Optimization.md)：GraalVMでのリフレクション設定と最適化

## 5. 検討事項（TODOリスト）
開発を進めるにあたって検討・具体化が必要な事項のリストです。
詳細な内容は [検討事項・TODOリスト](TODO-Details.md) を参照してください。
