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
- **実装状況**: 実装済み。`TaskQueueManager` からタスク実行ループの制御がこのクラスへ委譲されています。現状、各タスクの実行はホストに対して順次（Sequential）行われます。
- **エラー処理**: `any_errors_fatal` が有効な場合、あるホストでの失敗が即座に全ホストの次タスク実行の中断に繋がります。

### 3.2 Free 戦略 (Free Strategy)
ホストごとに独立して、可能な限り早くタスクを順番に実行する戦略です。

- **動作**: 各ホストは、他のホストの進捗を待つことなく、自分に割り当てられたタスクを次々と実行します。
- **実装方針**:
    - `ThreadPoolExecutor` を使用し、ホストごとに `TaskExecutor` による実行を並列化しています。
    - 並列数はデフォルトで `5` であり、CLI オプション `--forks` (`-f`) で指定可能です。
    - `TaskQueueManager` は、失敗ホストのリストやハンドラー通知などの共通コンテキストを、スレッドセーフなコレクション（`ConcurrentHashMap`, `Collections.synchronizedSet` 等）を使用して管理します。
    - `run_once` タスクについては、スレッドセーフなセットを用いて重複実行を防止しています。
- **用途**: ホスト間の同期が不要で、全体のスループットを向上させたい場合に適しています。

## 4. 実行エンジンへの統合

### 4.1 登録と選択
- `Play` レコードに `strategy` フィールド（デフォルト: "linear"）を保持しています。
- `StrategyFactory` が導入されており、プレイブック内で指定された戦略名に基づき、適切な `Strategy` 実装クラスのインスタンスを生成します。

### 4.2 責務の委譲
- `TaskQueueManager.executePlay` は、タスク実行ループの制御を、選択された `Strategy.run` メソッドに委譲します。
- これにより、`TaskQueueManager` はコネクション管理や結果の集計といった「実行インフラ」に専念し、実行の「順序制御」をストラテジ・プラグインが担う構成となっています。

## 5. シリアル実行 (serial) の詳細設計

`linear` 戦略において、プレイ内のホストをバッチに分割して実行する機能（ローリングアップデート等）の設計仕様です。

### 5.1 バッチサイズの解析
`Play` レコードの `serial` フィールドに指定可能な値を以下のルールに従って解析します。
- **整数**: 固定のホスト数（例: `serial: 2`）。
- **パーセンテージ**: ターゲットホスト総数に対する割合（例: `serial: "50%"`）。切り捨てで算出されますが、最低 1 ホストは確保されます。
- **リスト**: 実行ステップごとにバッチサイズを変更（例: `serial: [1, 5, "20%"]`）。リストの末尾に達した後は、最後のバッチサイズが継続して適用されます。

### 5.2 実行ループの構造 (LinearStrategy)
`LinearStrategy.run` は、解決されたバッチサイズに基づき、以下の二重ループ構造でタスクを実行します。

1.  **バッチループ**: 全ホストが処理されるまで、計算されたサイズのホスト群（バッチ）を取得します。
    - 各バッチの開始時に `VariableManager.setBatchContext` を呼び出し、マジック変数 `ansible_play_batch` を現在のバッチ内のホストリストで更新します。
2.  **タスクループ**: プレイ内の各タスクを、現在のバッチ内の全ホストに対して順次実行します。
    - **ロール実行**: 各バッチに対して `executeRole` を呼び出します。
    - **ハンドラーのフラッシュ**: 各バッチの全タスク完了後、そのバッチ内の各ホストに対して `flushHandlersForHost` を実行します。

### 5.3 マジック変数の制御
`VariableManager` に以下のメソッドを追加し、実行エンジンからバッチの状態を伝播させます。

```java
/**
 * 現在の実行バッチのコンテキストを設定します。
 * @param batchHostNames 現在のバッチに含まれるホスト名のリスト。
 */
public void setBatchContext(List<String> batchHostNames) {
    this.batchHostNames = batchHostNames;
}
```

`ansible_play_batch` 変数は、常にこの `batchHostNames` を参照するように `getAllVariables` を修正します。

## 6. 今後の課題

### 6.1 同時実行制限 (throttle) のサポート
- **概要**: 特定のタスク、ブロック、またはプレイにおいて、同時に実行できるホスト数を制限する機能。
- **検討内容**:
    - `Task` / `Play` レコードへの `throttle` キーワードの追加。
    - ストラテジ内でのセマフォ等を用いた同時実行数の制御。

### 6.2 その他
その他の設計・拡張事項については、[検討事項・TODOリスト](../TODO-Details.md#6-今後の設計拡張事項-future-design-and-extensions) を参照してください。
