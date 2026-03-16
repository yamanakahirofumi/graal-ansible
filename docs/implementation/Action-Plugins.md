# Action Plugin 実装仕様 (将来の設計指針)

本ドキュメントでは、`graal-ansible` における Ansible Action Plugin の実行メカニズムの設計方針について詳述します。 **(注意: 本ドキュメントは将来の実装に向けた設計仕様であり、現時点では未実装です)**

## 1. Action Plugin の概要

Ansible のタスク実行には、大きく分けて以下の 2 種類があります。

1.  **Module (通常モジュール)**: ターゲットノードに転送され、そこで実行される（例: `command`, `apt`, `yum`）。
2.  **Action Plugin**: 管理ノード（制御ノード）上で実行され、必要に応じてターゲットノードに対して 1 回以上のモジュール実行を指示する（例: `template`, `copy`, `debug`）。

`graal-ansible` では、本家 Ansible の Python 実装の Action Plugin を管理ノード側の GraalPy 上でそのまま動作させることで、高い互換性を維持します。

## 2. 検知ロジック

Worker Process (`TaskExecutor`) は、タスクの `action` 名に基づき、それが Action Plugin であるかどうかを以下の手順で判定します。

1.  **プラグイン検索**: `ansible-core` のコレクション（`ansible.builtin` 等）内の `plugins/action/` ディレクトリから、アクション名に一致する Python スクリプト（例: `template.py`）を検索します。
2.  **優先判定**: アクション名に一致する Action Plugin が存在する場合、通常のモジュール実行（`ansible/modules/` 配下）よりも優先して Action Plugin として実行します。

## 3. 実行フロー

Action Plugin の実行は、以下のプロセスで行われます。

### 3.1 Java 側 (Worker / PythonModule)
1.  **判別**: `isActionPlugin` ロジックにより Action Plugin であることを確認します。
2.  **コンテキスト準備**:
    - 現在のタスク変数（`task_vars`）を Python コンテキストにバインドします。
    - Java の `ITaskExecutor` および `Connection` オブジェクトへのブリッジ（`connection_java` 等）を用意します。
3.  **ランチャー起動**: `ansible_action_launcher.py` を使用して、対象の Action Plugin クラスをインスタンス化し、`run()` メソッドを呼び出します。

### 3.2 Python 側 (ansible_action_launcher.py)
1.  **Ansible コアクラスのモック**: Action Plugin が依存する `Task`, `Connection`, `PlayContext`, `DataLoader`, `Templar` 等のコアクラスを、Java ブリッジを利用するようにモック化します。
2.  **プラグインのロード**: 指定された Action Plugin モジュールをインポートし、プラグインクラス（例: `ActionModule`）を生成します。
3.  **実行**: プラグインの `run(task_vars)` メソッドを実行します。

## 4. GraalPy-Java ブリッジ (`_execute_module`)

Action Plugin の最大の特徴は、自身の内部から別のモジュールを実行できる点です。

- **仕組み**: モック化された `ActionBase._execute_module` メソッドが呼び出されると、Python 側は引数（モジュール名、引数等）を Java 側のブリッジメソッドへルーティングします。
- **再帰的実行**: Java 側は受け取ったリクエストに基づき、[処理フロー](../features/Process-Flow.md)に従って通常のモジュール実行フロー（ターゲットへの転送・実行）を開始します。
- **結果の還元**: モジュールの実行結果は Java から Python へ返され、Action Plugin はその結果を元に後続の処理を継続します。

## 5. モック化が必要なコンポーネント

Action Plugin を GraalPy 上で動作させるために、以下の Ansible 内部コンポーネントの高度なモック化が必要です。

| クラス / モジュール | モックの役割 |
| :--- | :--- |
| `ansible.plugins.action.ActionBase` | `_execute_module`, `_low_level_execute_command` 等を Java ブリッジへルーティング。 |
| `ansible.playbook.task.Task` | タスク情報、変数、タグ等のプロパティを提供。 |
| `ansible.executor.playbook_executor` | 実行コンテキストのシミュレーション。 |
| `ansible.template.Templar` | 制御ノード側でのテンプレートレンダリング機能（Java 側の `VariableResolver` と連携）。 |

## 6. 関連ドキュメント
- [GraalPy 統合の詳細](../tech/GraalPy-Integration.md)
- [タスク実行エンジン](Task-Executor.md)
- [リモートノードでのモジュール実行仕様](Remote-Module-Execution.md)
- [Ansible モジュールの初期化と設定](Ansible-Module-Initialization.md)
