# ストラテジ・プラグインの実装仕様 (Strategy Plugins Implementation)

本ドキュメントでは、`graal-ansible` におけるタスク実行戦略（ストラテジ）の設計方針および実装状況について詳述します。

## 1. 概要

ストラテジ・プラグインは、Playbook 内の各 Play において、複数のホストに対してどのようにタスクを配信・実行するかを制御するための仕組みです。Ansible 本家と同様に、`linear`（デフォルト）や `free` などの戦略を切り替え可能にします。`graal-ansible` では、これらの主要な戦略が完全に実装され、実行エンジンに統合されています。

## 2. インターフェース定義

Java で実装されるストラテジ・プラグインは、以下の `Strategy` インターフェースを実装しています。

```java
public interface Strategy {
    /**
     * 指定された Play を実行します。
     *
     * @param play             実行対象の Play
     * @param targetHosts      対象ホストリスト
     * @param tqm              TaskQueueManager (実行コンテキストの共有用)
     * @param variableManager  変数管理
     * @param results          結果集計用 Map
     * @param globalCheckMode  グローバルチェックモードの有無
     * @param runTags          実行対象タグ
     * @param skipTags         スキップ対象タグ
     */
    void run(Play play, List<Host> targetHosts, TaskQueueManager tqm, VariableManager variableManager, Map<String, List<TaskResult>> results, boolean globalCheckMode, List<String> runTags, List<String> skipTags);

    /**
     * 戦略名を返します（例: "linear", "free"）。
     */
    String getName();
}
```

## 3. 実装済みの実行戦略

### 3.1 Linear 戦略 (Linear Strategy)
Ansible のデフォルト戦略であり、`graal-ansible` における標準的な挙動です。

- **動作**: 1つのタスクが全ターゲットホストで完了（または失敗）してから、次のタスクに進みます。
- **実装状況**: 実装済み。`TaskQueueManager` からタスク実行ループの制御がこのクラスへ委譲されています。
- **バッチ実行**: `serial` キーワードが指定されている場合、ホストをバッチに分割して実行します。各バッチごとに全タスクの完了を待ち、ハンドラーをフラッシュします。
    - **リスト指定時の挙動**: `serial` がリスト形式（例: `[1, 5, "20%"]`）で指定された場合、各バッチごとに順次値が適用されます。バッチ数がリストの要素数を超える場合、リストの**最後の値**が残りのすべてのバッチサイズとして継続的に使用されます。
- **エラー処理**: `any_errors_fatal` が有効な場合、あるホストでの失敗が即座に全ホストの次タスク実行の中断に繋がります。

### 3.2 Free 戦略 (Free Strategy)
ホストごとに独立して、可能な限り早くタスクを順番に実行する戦略です。

- **動作**: 各ホストは、他のホストの進捗を待つことなく、自分に割り当てられたタスクを次々と実行します。
- **実装方針**:
    - **並列実行**: `ThreadPoolExecutor` を使用し、ホストごとに `TaskExecutor` による実行を並列化しています。
    - **フォーク数**: 並列数はデフォルトで `5` であり、CLI オプション `--forks` (`-f`) で指定可能です。
    - **コンテキスト管理**: `TaskQueueManager` は、失敗ホストのリストやハンドラー通知などの共通コンテキストを、スレッドセーフなコレクション（`ConcurrentHashMap`, `Collections.synchronizedSet` 等）を使用して管理します。
    - **run_once**: `run_once` タスクについては、スレッドセーフなセットを用いて重複実行を防止しています。
    - **同時実行制限 (throttle)**:
        - `throttle` キーワードによる同時実行ホスト数の制限をサポートしています。
        - 内部的には `java.util.concurrent.Semaphore` を使用し、タスクごとに `ConcurrentHashMap` でセマフォを管理することで、指定された数以上のホストが同時にタスクを実行しないよう制御しています。
- **用途**: ホスト間の同期が不要で、全体のスループットを向上させたい場合に適しています。

## 4. 実行エンジンへの統合

### 4.1 登録と選択
- `Play` レコードに `strategy` フィールド（デフォルト: "linear"）を保持しています。
- `StrategyFactory` が導入されており、プレイブック内で指定された戦略名に基づき、適切な `Strategy` 実装クラスのインスタンスを生成します。

### 4.2 責務の委譲
- `TaskQueueManager.executePlay` は、タスク実行ループの制御を、選択された `Strategy.run` メソッドに委譲します。
- これにより、`TaskQueueManager` はコネクション管理や結果の集計といった「実行インフラ」に専念し、実行の「順序制御」をストラテジ・プラグインが担う構成となっています。

