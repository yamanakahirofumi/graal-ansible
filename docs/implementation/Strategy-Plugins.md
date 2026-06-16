# ストラテジ・プラグインの実装仕様 (Strategy Plugins Implementation)

本ドキュメントでは、`graal-ansible` におけるタスク実行戦略（ストラテジ）の設計方針について詳述します。

## 1. 概要

ストラテジ・プラグインは、Playbook 内の各 Play において、複数のホストに対してどのようにタスクを配信・実行するかを制御するための仕組みです。Ansible 本家と同様に、`linear`（デフォルト）や `free` などの戦略を切り替え可能にします。

## 2. インターフェース定義

Java で実装されるストラテジ・プラグインは、以下の `Strategy` インターフェース（仮称）を実装します。

```java
public interface Strategy {
    /**
     * 指定された Play を実行します。
     * @param play 実行対象の Play
     * @param targetHosts 対象ホストリスト
     * @param tqm TaskQueueManager (実行コンテキストの共有用)
     * @param variableManager 変数管理
     * @param results 結果集計用 Map
     */
    void run(Play play, List<Host> targetHosts, TaskQueueManager tqm, VariableManager variableManager, Map<String, List<TaskResult>> results);

    /**
     * 戦略名を返します（例: "linear", "free"）。
     */
    String getName();
}
```

## 3. 主要な実行戦略

### 3.1 Linear 戦略 (Linear Strategy)
Ansible のデフォルト戦略であり、現在の `graal-ansible` の標準的な挙動です。

- **動作**: 1つのタスクが全ターゲットホストで完了（または失敗）してから、次のタスクに進みます。
- **実装状況**: 現在 `TaskQueueManager.executePlay` 内に直接記述されているロジックを、このクラスへ委譲（リファクタリング）する計画です。
- **エラー処理**: `any_errors_fatal` が有効な場合、あるホストでの失敗が即座に全ホストの次タスク実行の中断に繋がります。

### 3.2 Free 戦略 (Free Strategy)
ホストごとに独立して、可能な限り早くタスクを順番に実行する戦略です。

- **動作**: 各ホストは、他のホストの進捗を待つことなく、自分に割り当てられたタスクを次々と実行します。
- **実装方針**:
    - ホストごとに `TaskExecutor` を実行するスレッドを割り当てます。
    - `TaskQueueManager` は共通の実行コンテキスト（失敗ホストのリスト、ハンドラー通知等）をスレッドセーフに管理します。
- **用途**: ホスト間の同期が不要で、全体のスループットを向上させたい場合に適しています。

## 4. 実行エンジンへの統合

### 4.1 登録と選択
- `Play` レコードに `strategy` フィールド（デフォルト: "linear"）を保持します。
- `StrategyFactory` を導入し、プレイブック内で指定された戦略名に基づき、適切な `Strategy` 実装クラスのインスタンスを生成します。

### 4.2 責務の委譲
- `TaskQueueManager.executePlay` は、現在自身で行っているタスク実行ループの制御を、選択された `Strategy.run` メソッドに委譲します。
- これにより、`TaskQueueManager` はコネクション管理や結果の集計といった「実行インフラ」に専念し、実行の「順序制御」をストラテジ・プラグインが担うようになります。

## 5. 今後の課題

- **ホストの並列実行数の制限 (forks)**: `linear` 戦略においても、全ホストではなく一定数（デフォルト 5）ずつ並列にタスクを実行する「バッチ処理」のサポートが必要です。
- **シリアル実行 (serial)**: `linear` 戦略において、一度に実行するホストの数を制限する機能への対応。
- **ストラテジ固有のコールバック**: `free` 戦略などにおいて、ホストごとに進捗が異なる場合の出力形式の調整。
