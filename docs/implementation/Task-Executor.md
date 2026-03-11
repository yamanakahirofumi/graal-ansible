# タスク実行エンジン

タスク実行エンジン（TaskExecutor）は、Playbook内の個別のタスクを解釈し、適切なモジュールを実行して結果を収集する責務を持ちます。

## 1. 実行フロー

`TaskExecutor` は、`PlaybookExecutor` によって決定された個別のタスク実行ユニットを処理します。

1. **引数のテンプレート展開**: `VariableResolver` を用いて、モジュール引数（`args`）に含まれる Jinja2 テンプレートを評価します。
2. **モジュール実行環境の準備**: 実行対象のモジュール（Python/Java）に応じたコンテキスト（環境変数、接続情報等）を構築します。
3. **モジュールの実行**: `PythonModule`（GraalPy 経由）または組み込みの Java 実装を使用して、ターゲットホスト上で処理を実行します。
4. **結果の解析と変換**: モジュールの出力を `TaskResult` オブジェクトに変換します。

※ ループの展開（`loop`）や実行条件の評価（`when`）は、上位の `PlaybookExecutor` が担当します。詳細は [タスク制御の実装詳細](Task-Control.md) を参照してください。

## 2. 実行戦略 (Strategy)
初期実装では、Ansible のデフォルトである `linear` 戦略を採用しています。
- **Linear 戦略**: 1つのタスクが全ターゲットホストで完了（または失敗）してから、次のタスクに進みます。
- **失敗ホストの追跡**: あるタスクで失敗したホストは、同じ Play 内の以降のタスク実行から除外されます。

## 3. 実行結果の解析 (TaskResult)
モジュールからの戻り値を `TaskResult` オブジェクトにマッピングします。
- **成功判定**: `failed` フラグが `false`（または未定義）の場合に成功とみなします。
- **変更の検知**: 戻り値の `changed` フィールドが `true` の場合、システムの変更があったと判断します。`TaskResult.success(Map<String, Object> data)` メソッドにより、安全に `changed` ステータスを抽出します。

## 4. Pythonモジュールの実行 (GraalPy)
既存のAnsible Pythonモジュールとの互換性を維持するため、GraalVM上のPythonランタイムである **GraalPy** を利用します。

- **統合方法**: Javaコード内から GraalVM SDK の Polyglot API を介して Python スクリプトを直接呼び出します。
- **メリット**: 
    - 外部の Python インタプリタのインストールが不要。
    - Java オブジェクトと Python オブジェクト間での高速なデータ交換。
    - Native Image に Python ランタイムを内包可能。
