# Action Plugin 実装仕様

本ドキュメントでは、`graal-ansible` における Ansible Action Plugin の実行メカニズムの設計方針について詳述します。

> [!NOTE]
> **現在のステータス**: Action Plugin の**検知ロジック**（`ansible/plugins/action` からの検索）および**実行ブリッジ**（`ansible_action_launcher.py` を介した Python コードの呼び出し）は `TaskExecutor` に実装済みです。ただし、Ansible Core の重厚な依存関係に起因する GraalPy 上での実行互換性の課題があり、詳細は [Action Plugin 実行ロジックの実装調査報告](Action-Plugins-Investigation.md) を参照してください。

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
    - Java の `ITaskExecutor` および `Connection` オブジェクトへのブリッジ（`connection_java`, `task_executor_java`）を用意します。
3.  **ランチャー起動**: `ansible_action_launcher.py` を使用して、対象の Action Plugin クラスをインスタンス化し、`run()` メソッドを呼び出します。

### 3.2 Python 側 (ansible_action_launcher.py)
1.  **Ansible コアクラスのモック**: Action Plugin が依存する `Task`, `Connection`, `PlayContext`, `DataLoader`, `Templar` 等のコアクラスを、Java ブリッジを利用するようにモック化します。
2.  **プラグインのロード**: 指定された Action Plugin モジュールをインポートし、プラグインクラス（例: `ActionModule`）を生成します。
3.  **実行**: プラグインの `run(task_vars)` メソッドを実行します。

### 3.3 Python ランチャー (実行ブリッジ) の詳細仕様
Action Plugin を実行するための `ansible_action_launcher.py` は、以下のバインディングとインターフェースを期待します。

| バインディング名 | Java 型 | 用途 |
| :--- | :--- | :--- |
| `connection_java` | `Connection` | ターゲットノードへのファイル転送やコマンド実行の委譲。 |
| `task_executor_java` | `ITaskExecutor` | `_execute_module` 呼び出し時のタスク再帰実行。 |
| `task_vars_java` | `Map<String, Object>` | 実行コンテキストにおける変数セット。 |
| `action_name` | `String` | 実行対象のアクション名 (例: `copy`, `template`)。 |

#### 実行インターフェースのパッチ
Python 側の `ansible.plugins.action.ActionBase._execute_module` を以下の形式でモンキーパッチします：

```python
def mocked_execute_module(self, module_name=None, module_args=None, tmp=None, task_vars=None, *args, **kwargs):
    # Java の ITaskExecutor.execute_from_python を呼び出してモジュールを実行
    # 戻り値を Python の辞書形式に変換して返却
    result = task_executor_java.execute_from_python(module_name, module_args, task_vars)
    return result
```

## 4. GraalPy-Java ブリッジ仕様

Action Plugin の最大の特徴は、自身の内部から別のモジュールを実行できる点です。この再帰的な呼び出しを実現するため、Java 側は Python からの呼び出し専用のインターフェースを提供します。

### 4.1 ITaskExecutor の拡張メソッド

`ITaskExecutor` は Python 側からのリクエストを受け取るため、以下のメソッドを実装する必要があります。

```java
public interface ITaskExecutor {
    /**
     * Python (Action Plugin) から呼び出され、指定されたモジュールを実行します。
     * @param moduleName モジュール名 (例: "copy", "apt")
     * @param moduleArgs モジュール引数 (Map形式)
     * @param taskVars 現在のタスク変数
     * @return 実行結果 (Map形式、Ansible互換の辞書)
     */
    Map<String, Object> execute_from_python(String moduleName, Map<String, Object> moduleArgs, Map<String, Object> taskVars);
}
```

### 4.2 データマッピングとシリアライズ

GraalPy の Polyglot API を介したデータ交換では、以下のルールを適用します。

- **Python -> Java**: Python の `dict` は Java の `Map<String, Object>` として、`list` は `List<Object>` として透過的にアクセス可能です。
- **Java -> Python**: Java の `Map` や `List` を Python 側へ返す際、Ansible モジュールが期待する純粋な Python オブジェクト（辞書、リスト）として扱えるよう、必要に応じて変換（`host_to_guest` 等）を行います。
- **型変換**: 数値、文字列、真偽値は Polyglot 共通型として相互に自動変換されます。

### 4.3 実行コンテキストの同期

`execute_from_python` が呼び出された際、Java 側は以下の状態を維持したままモジュールを実行する必要があります。

1.  **接続の維持**: `ActionBase` が保持している `connection_java` (SSH/Local) をそのまま使用します。
2.  **変数の同期**: Python 側で変更された `task_vars` がある場合、それを Java 側の実行コンテキスト（`VariableManager`）へ反映させる仕組みを検討します。
3.  **チェックモード/Become**: Action Plugin 起動時の設定（`check_mode`, `become` 等）を、再帰的に呼び出されるモジュール実行にも伝播させます。

## 5. モック化が必要なコンポーネント

Action Plugin を GraalPy 上で動作させるために、以下の Ansible 内部コンポーネントの高度なモック化が必要です。

| クラス / モジュール | モックの役割 |
| :--- | :--- |
| `ansible.plugins.action.ActionBase` | `_execute_module`, `_low_level_execute_command` 等を Java ブリッジへルーティング。 |
| `ansible.playbook.task.Task` | タスク情報、変数、タグ等のプロパティを提供。 |
| `ansible.executor.playbook_executor` | 実行コンテキストのシミュレーション。 |
| `ansible.template.Templar` | 制御ノード側でのテンプレートレンダリング機能（Java 側の `VariableResolver` と連携）。 |

## 6. 関連ドキュメント
- [Action Plugin 実行ロジックの実装調査報告](Action-Plugins-Investigation.md)
- [GraalPy 統合の詳細](../tech/GraalPy-Integration.md)
- [タスク実行エンジン](Task-Executor.md)
- [リモートノードでのモジュール実行仕様](Remote-Module-Execution.md)
- [Ansible モジュールの初期化と設定](Ansible-Module-Initialization.md)
