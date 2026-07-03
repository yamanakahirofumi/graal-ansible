# タスク実行エンジン (Worker Process)

タスク実行エンジン（`TaskExecutor`）は、Ansible の **Worker Process** に相当し、Playbook 内の個別のタスクを解釈・実行して結果を収集する責務を持ちます。

## 1. 実行フロー

`TaskExecutor` は、`PlaybookExecutor` から依頼された特定のホストに対するタスク実行ユニットを処理します。全体図は [処理フロー (Process-Flow.md)](../features/Process-Flow.md) を参照してください。

1. **変数の集約**: `VariableManager` を通じて、Play, Host, Task 各レベルの変数を集約します。
2. **ループの展開**: `loop` フィールドを評価し、アイテムごとのイテレーションを開始します。
3. **引数のテンプレート展開**: 各イテレーションにおいて、`VariableResolver` を用いて、モジュール引数（`args`）に含まれる Jinja2 テンプレートを評価します。
4. **実行条件の評価**: `when` 句を評価し、タスクを実行すべきか判断します。
5. **Action Plugin 判定**: 実行対象が Action Plugin か通常 Module かを判定します。
6. **Action Plugin の実行 (管理ノード)**: Action Plugin の場合、管理ノードの GraalPy 上で実行します。ファイル転送が必要な場合などは、内部から `Connection` を介して操作を行います。
7. **モジュール実行 (ターゲットノード)**: 通常モジュール、または Action Plugin からの指示がある場合、Ansiballz パッケージを作成し、`Connection` プラグインを介してターゲットノードで実行します。
8. **結果の解析と変換**: 実行結果（JSON）を `TaskResult` オブジェクトに変換し、`TaskQueueManager` へ返却します。

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

## 5. 並列実行とスレッドセーフティ

`free` 戦略や `forks` 設定によるマルチホスト並列実行において、`TaskExecutor` は以下の仕組みで実行コンテキストの一貫性と安全性を確保しています。

- **コレクションパスの管理**:
    - `TaskExecutor` は `ThreadLocal` を使用して、スレッドごとに独立したコレクション探索パスを保持します。
    - これにより、並列実行されるタスク間で、コレクションの解決コンテキストが混ざることを防ぎます。
- **コネクションの隔離**:
    - 非同期タスク (`async`) の実行時には、バックグラウンドでの安定性を確保するため、そのタスク専用の `Connection` インスタンスを新規に生成して実行します。
- **変数の分離**:
    - 各タスクの実行前に、そのホスト固有の変数セットを `VariableManager` から取得し、スレッド固有のスタック上でテンプレート展開を行います。
