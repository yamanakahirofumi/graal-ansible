# Java Action Plugin 実装ガイド

本ドキュメントでは、`graal-ansible` において Java で実装される「軽量 Action Plugin エミュレータ」の設計パターンと実装方法について解説します。

## 1. 概要

[Action Plugin 実行ロジックの実装調査報告](Action-Plugins-Investigation.md) で述べた通り、Ansible Core の重厚な依存関係を GraalPy 上で完全に解決することは困難です。

本プロジェクトの基本方針は「本物の Python プラグインを動かす」ことですが、以下のケースに限り Java による軽量エミュレータを導入します。
- **高パフォーマンス要件**: `debug` や `set_fact` のように、頻繁に呼び出され、Python インタプリタのオーバーヘッドを避けたい場合。
- **依存解決の困難性**: 依存ライブラリのスタブ化が極めて困難で、Java で再実装したほうが安定性が高い場合。

Java で実装することで、以下のメリットが得られます。
- **高速な起動**: Python インタプリタの初期化や大量のライブラリロードを回避。
- **安定性**: GraalPy と C 拡張の互換性問題に左右されない。
- **メンテナンス性**: Java の型安全性を活かした実装が可能。

## 2. インターフェース定義 (`ActionPlugin`)

すべての Java Action Plugin は、以下の `org.example.ansible.plugin.ActionPlugin` インターフェースを実装します。

```java
public interface ActionPlugin {
    /**
     * 管理ノード上でアクションを実行します。
     * @param task 実行対象のタスク（未解決の引数を含む）
     * @param variables 現在の解決済み変数セット
     * @param taskExecutor タスク実行エンジン (ITaskExecutor)
     * @return 実行結果 (TaskResult)
     */
    TaskResult execute(Task task, Map<String, Object> variables, ITaskExecutor taskExecutor);
}
```

## 3. 実装パターン

### 3.1 引数の解決

`ActionPlugin.execute` に渡される `task.args()` は、まだ Jinja2 テンプレートが展開されていない状態です。プラグイン内部で `taskExecutor.getVariableResolver()` を使用して解決する必要があります。

```java
// 単一の値の解決
Object src = taskExecutor.getVariableResolver().resolveValue(task.args().get("src"), variables);

// Map 全体の再帰的解決
Map<String, Object> resolvedArgs = taskExecutor.getVariableResolver().resolve(task.args(), variables);
```

### 3.2 モジュール実行の委譲 (`_execute_module`)

Action Plugin は、自身の処理の一部としてターゲットノード上で通常のモジュールを実行させることがよくあります。これは `ITaskExecutor.execute` を通じて行います。

```java
// 例: copy アクションの中でターゲットノードの 'stat' モジュールを呼び出す
Task statTask = new Task("stat", "stat", Map.of("path", dest), ...);
TaskResult statResult = taskExecutor.execute(statTask, becomeContext, connection, environment);
```

### 3.3 ファイル転送

ファイル転送が必要な場合（`copy` 等）、`TaskExecutor` が保持する現在の `Connection` を使用します。

```java
Connection connection = TaskExecutor.getCurrentConnection();
connection.putFile(localPath, remotePath);
```

## 4. 主要プラグインの実装ガイド

### 4.1 `copy` プラグイン
1. **引数解決**: `src`, `dest`, `owner`, `group`, `mode` 等を解決。
2. **ソースの特定**: `src` が相対パスの場合、プロジェクトのベースディレクトリからの絶対パスに変換。
3. **ターゲット状態確認**: `stat` モジュールを呼び出し、リモート側のファイルの有無やチェックサムを確認。
4. **転送要否判定**: ファイルが変更されている場合、または強制上書き設定の場合に転送。
5. **転送**: `connection.putFile` を実行。
6. **属性設定**: `file` モジュールを呼び出し、パーミッションや所有者を設定。

### 4.2 `template` プラグイン
1. **引数解決**: `src`, `dest` 等を解決。
2. **レンダリング**: 管理ノード側で `VariableResolver` を使用して、テンプレートファイルをレンダリング。
   ```java
   String templateContent = Files.readString(localTemplatePath);
   String rendered = taskExecutor.getVariableResolver().resolveValue(templateContent, variables).toString();
   ```
3. **一時ファイル作成**: レンダリング結果を管理ノード上の一時ファイルに書き出す。
4. **以降の処理**: `copy` プラグインと同様のフロー（転送要否判定、転送、属性設定）を辿る。

## 5. 登録方法

実装したプラグインは、`TaskExecutor` のコンストラクタで `builtInActionPlugins` マップに登録します。

```java
public TaskExecutor(...) {
    // ...
    this.builtInActionPlugins.put("copy", new CopyAction());
    this.builtInActionPlugins.put("template", new TemplateAction());
}
```

## 6. エラーハンドリング

Action Plugin 内で発生した例外は適切にキャッチし、`TaskResult.failure(message)` として返却してください。これにより、Ansible の標準的なエラーレポートに統合されます。
