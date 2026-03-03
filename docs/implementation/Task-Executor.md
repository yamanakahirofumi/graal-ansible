# タスク実行エンジン

タスク実行エンジン（TaskExecutor）は、Playbook内の個別のタスクを解釈し、適切なモジュールを実行して結果を収集する責務を持ちます。

## 1. 実行フロー
1. 変数の展開（Variable Managerとの連携）
2. モジュール実行環境の準備
3. モジュールの実行（JavaモジュールまたはPythonモジュール）
4. 実行結果の解析と収集

## 2. 実行戦略 (Strategy)
初期実装では、Ansible のデフォルトである `linear` 戦略を採用しています。
- **Linear 戦略**: 1つのタスクが全ターゲットホストで完了（または失敗）してから、次のタスクに進みます。
- **失敗ホストの追跡**: あるタスクで失敗したホストは、同じ Play 内の以降のタスク実行から除外されます。

## 3. 実行結果の解析 (TaskResult)
モジュールからの戻り値を `TaskResult` オブジェクトにマッピングします。
- **成功判定**: `failed` フラグが `false`（または未定義）の場合に成功とみなします。
- **変更の検知**: 戻り値の `changed` フィールドが `true` の場合、システムの変更があったと判断します。`TaskResult.success(Map<String, Object> data)` メソッドにより、安全に `changed` ステータスを抽出します。

## 4. コアJavaモジュール (Core Java Modules)
パフォーマンスの向上や、低レイヤーのシステム操作を効率的に行うため、一部の基本モジュールは Java で直接実装されています。

- **実装モジュール**: `command`, `shell`
- **特徴**: `Connection` 抽象化レイヤー（`LocalConnection` 等）を直接利用し、OS 固有のプロセス実行を安全に制御します。

## 5. Pythonモジュールの実行 (GraalPy)
既存のAnsible Pythonモジュールとの互換性を維持するため、GraalVM上のPythonランタイムである **GraalPy** を利用します。

- **統合方法**: Javaコード内から GraalVM SDK の Polyglot API を介して Python スクリプトを直接呼び出します。
- **メリット**: 
    - 外部の Python インタプリタのインストールが不要。
    - Java オブジェクトと Python オブジェクト間での高速なデータ交換。
    - Native Image に Python ランタイムを内包可能。
