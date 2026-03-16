# タスク実行エンジン (Worker Process)

タスク実行エンジン（`TaskExecutor`）は、Ansible の **Worker Process** に相当し、Playbook 内の個別のタスクを解釈・実行して結果を収集する責務を持ちます。

## 1. 実行フロー

`TaskExecutor` は、`PlaybookExecutor` から依頼された特定のホストに対するタスク実行ユニットを処理します。全体図は [処理フロー (Process-Flow.md)](../features/Process-Flow.md) を参照してください。

1. **引数のテンプレート展開**: `VariableResolver` を用いて、モジュール引数（`args`）に含まれる Jinja2 テンプレートを評価します。
2. **Action Plugin 判定**: 実行対象が Action Plugin か通常 Module かを判定します。
3. **Action Plugin の実行 (管理ノード)**: Action Plugin の場合、管理ノードの GraalPy 上で実行します。ファイル転送が必要な場合などは、内部から `Connection` を介して操作を行います。
4. **モジュール実行 (ターゲットノード)**: 通常モジュール、または Action Plugin からの指示がある場合、Ansiballz パッケージを作成し、`Connection` プラグインを介してターゲットノードで実行します。
5. **結果の解析と変換**: 実行結果（JSON）を `TaskResult` オブジェクトに変換し、`PlaybookExecutor` へ返却します。

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
