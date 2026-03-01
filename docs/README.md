# ドキュメント一覧

このディレクトリには、ansible-coreをJavaで再実装するプロジェクトに関する詳細なドキュメントが格納されています。

## 1. フォルダ構成と配置

ドキュメントは内容に応じて以下のいずれかに分類して配置します。

- **`docs/features/`**：機能仕様、Ansibleモジュールの動作、コマンドライン引数の仕様など。
- **`docs/tech/`**：技術スタック、アーキテクチャ、コーディング規約、GraalVM設定など。
- **`docs/implementation/`**：YAML解析、Playbook実行エンジン、インベントリ管理などの詳細な実装方法。

---

## 2. 機能・仕様 (`docs/features/`)
- [CLI仕様](features/CLI-Specification.md)：ansible-playbook互換のコマンドライン引数
- [動作環境](features/System-Requirements.md)：Java/GraalVMの実行環境とOS互換性
- [インベントリ管理](features/Inventory-System.md)：静的・動的インベントリのサポート範囲
- [Playbook実行仕様](features/Playbook-Execution.md)：タスク実行、ループ、条件分岐のサポート範囲
- [モジュール互換性](features/Module-Compatibility.md)：実装済みのコアモジュール一覧
- [モジュールの開発方針](features/Module-Development-Policy.md)：Ansibleモジュールの実行および再実装禁止の方針

## 3. 技術・開発設定 (`docs/tech/`)
- [アーキテクチャ設計](tech/Architecture.md)：システムのパッケージ構造と主要クラスの責務
- [エラーハンドリング方針](tech/Error-Handling-Policy.md)：基本方針と各ケースでの対応
- [ロギング方針](tech/Logging-Policy.md)：デバッグおよび保守のためのログ出力指針
- [技術スタック](tech/Tech-Stack.md)：使用している言語、ライブラリ、ツールなどの情報
- [CI 設定](tech/CI-Setting.md)：GitHub Actions を利用した自動ビルドとテストの設定について
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
- [接続プラグイン実装](implementation/Connection-Plugins.md)：Local, SSH(JSch/Apache MINA SSHD)の実装
- [変数とテンプレート](implementation/Variables-Templating.md)：Jinja2互換エンジンの統合
- [OS非依存レイヤー](implementation/OS-Abstraction.md)：ファイル操作やプロセス実行のOS差分吸収
- [Native Image最適化](implementation/Native-Image-Optimization.md)：GraalVMでのリフレクション設定と最適化

## 5. 検討事項（TODOリスト）
開発を進めるにあたって検討・具体化が必要な事項のリストです。
詳細な内容は [検討事項・TODOリスト](TODO-Details.md) を参照してください。
