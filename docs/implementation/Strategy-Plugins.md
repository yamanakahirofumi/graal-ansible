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
- **バッチ実行 (serial)**:
    - `serial` キーワードに基づき、ホストを複数のバッチに分割して実行します。
    - **指定形式**: 整数（例: `3`）、パーセンテージ（例: `"50%"`）、またはこれらが混在したリスト（例: `[1, 5, "20%"]`）をサポートします。リスト指定の場合、最後の要素が残りの全ホストに適用されるバッチサイズとなります。
    - **コンテキスト同期**: 各バッチの実行開始時に `VariableManager.setBatchContext` を呼び出し、マジック変数 `ansible_play_batch` が現在実行中のバッチ内のホストのみを指すよう動的に更新します。
    - **ハンドラー制御**: 各バッチの全タスク完了後に、そのバッチ内で通知されたハンドラーをフラッシュします。
- **エラー処理**: `any_errors_fatal` が有効な場合、あるホストでの失敗が即座に全ホストの次タスク実行の中断に繋がります。

### 3.2 Free 戦略 (Free Strategy)
ホストごとに独立して、可能な限り早くタスクを順番に実行する戦略です。

- **動作**: 各ホストは、他のホストの進捗を待つことなく、自分に割り当てられたタスクを次々と実行します。
- **実装方針**:
    - **並列実行**: `ThreadPoolExecutor` を使用し、ホストごとに `TaskExecutor` による実行を並列化しています。
    - **フォーク数**: 並列数はデフォルトで `5` であり、CLI オプション `--forks` (`-f`) で指定可能です。
    - **コンテキスト管理**: `TaskQueueManager` は、失敗ホストのリストやハンドラー通知などの共通コンテキストを、スレッドセーフなコレクション（`ConcurrentHashMap`, `Collections.synchronizedSet` 等）を使用して管理します。
    - **run_once**: `run_once: true` タスクについては、スレッドセーフなセットを用いて重複実行を防止しています。※現在の実装ではバッチ（serial）単位ではなくプレイ単位での重複排除となります。
    - **同時実行制限 (throttle)**:
        - `throttle` キーワードによる同時実行ホスト数の制限をサポートしています。
        - **実装詳細**: 内部的には `java.util.concurrent.Semaphore` を使用し、タスクオブジェクトをキーとした `ConcurrentHashMap` でセマフォを管理します。指定された数以上のホストが同時にタスクを実行しないよう、`acquire()` / `release()` により厳密に制御しています。また、`InterruptedException` を適切にハンドリングし、割り込み時には中断します。
- **用途**: ホスト間の同期が不要で、全体のスループットを向上させたい場合に適しています。

## 4. 実行エンジンへの統合

### 4.1 登録と選択
- `Play` レコードに `strategy` フィールド（デフォルト: "linear"）を保持しています。
- `StrategyFactory` が導入されており、プレイブック内で指定された戦略名に基づき、適切な `Strategy` 実装クラスのインスタンスを生成します。

### 4.2 責務の委譲
- `TaskQueueManager.executePlay` は、タスク実行ループの制御を、選択された `Strategy.run` メソッドに委譲します。
- これにより、`TaskQueueManager` はコネクション管理や結果の集計といった「実行インフラ」に専念し、実行の「順序制御」をストラテジ・プラグインが担う構成となっています。

## 5. 今後の課題

### 5.1 その他
その他の設計・拡張事項については、[検討事項・TODOリスト](../TODO-Details.md#5-今後の設計・拡張事項-future-design-and-extensions) を参照してください。
