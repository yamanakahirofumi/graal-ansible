# タスク実行エンジン

タスク実行エンジン（TaskExecutor）は、Playbook内の個別のタスクを解釈し、適切なモジュールを実行して結果を収集する責務を持ちます。

## 1. 実行フロー
1. 変数の展開（Variable Managerとの連携）
2. モジュール実行環境の準備
3. モジュールの実行（JavaモジュールまたはPythonモジュール）
4. 実行結果の解析と収集

## 2. Pythonモジュールの実行 (GraalPy)
既存のAnsible Pythonモジュールとの互換性を維持するため、GraalVM上のPythonランタイムである **GraalPy** を利用します。

- **統合方法**: Javaコード内から GraalVM SDK の Polyglot API を介して Python スクリプトを直接呼び出します。
- **メリット**: 
    - 外部の Python インタプリタのインストールが不要。
    - Java オブジェクトと Python オブジェクト間での高速なデータ交換。
    - Native Image に Python ランタイムを内包可能。
