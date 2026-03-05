# TODOリスト（検討事項）

本プロジェクトにおける、機能追加、技術的課題、未確定事項の一覧です。
**※本ファイルには、設計面（アーキテクチャ、アルゴリズム、データ構造、インターフェース定義など）に関する事項のみを記載してください。**

## 1. 技術面 (Technical)

### [ ] Native Image 時のリフレクション設定
- **概要**: YAML 解析や動的クラスロードに伴うリフレクション定義の生成。
- **検討内容**:
    - [Native Image 最適化](implementation/Native-Image-Optimization.md) に基づく設定ファイルの作成。
    - `native-image-configure-plugin` を用いた自動生成プロセスの構築。
    - GraalPy 実行時のリソース（Python スクリプト等）のバンドル設定。

## 2. 実装時の詳細事項

### [ ] 実際の Ansible コレクションを使ったテストの実施
- **概要**: [実際のコレクションを使ったテスト方法の設計](tech/Actual-Collection-Testing.md) に基づき、主要なモジュールのテストを実装する。
- **検討内容**:
    - `ansible.builtin.copy`, `ansible.builtin.command` 等のテスト実装。
    - CI 環境での GraalPy および ansible-core のセットアップ自動化。

## 完了済みの項目 (Completed)

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

### [x] 実際の Ansible コレクションを使ったテスト方法の設計
- **完了日**: 2026-03-04
- **概要**: Java/GraalPy 環境で、既存の Python 製 Ansible コレクションが正しく動作するかを確認するテスト戦略の策定。
- **解決策**: [実際のコレクションを用いた自動テストの実施方法](tech/Actual-Collection-Testing.md) にて、venv 構築、`ANSIBLE_COLLECTIONS_PATH` の設定、モジュール実行ラッパーの設計を完了。

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
