# ストラテジ・プラグインの実装仕様 (Strategy Plugins Implementation)

本ドキュメントでは、`graal-ansible` におけるタスク実行戦略（ストラテジ）の設計方針、コンポーネント構成、スレッド同期メカニズム、バッチ算定アルゴリズム、および実装詳細について詳述します。

## 1. 概要

ストラテジ・プラグインは、Playbook 内の各 Play において、複数のターゲットホストに対してどのようにタスクを配信・順序制御して実行するかを担うコンポーネントです。Ansible 本家と同様に、`linear`（順次同期実行）および `free`（ホスト独立並列実行）などの戦略を切り替え可能にします。`graal-ansible` では、これらの主要戦略が完全に実装され、実行エンジン（`TaskQueueManager`）に統合されています。

## 2. インターフェース定義とパラメータ仕様

Java で実装されるすべてのストラテジ・プラグインは、以下の `Strategy` インターフェースを実装します。

```java
public interface Strategy {
    /**
     * 指定された Play を実行します。
     *
     * @param play             実行対象の Play オブジェクト
     * @param targetHosts      対象ホストリスト
     * @param tqm              TaskQueueManager (実行インフラ・コンテキストの共有用)
     * @param variableManager  変数管理マネージャー
     * @param results          ホストごとの結果集計用 Map
     * @param globalCheckMode  グローバルチェックモード (ドライラン) フラグ
     * @param runTags          実行対象タグリスト
     * @param skipTags         スキップ対象タグリスト
     */
    void run(Play play,
             List<Host> targetHosts,
             TaskQueueManager tqm,
             VariableManager variableManager,
             Map<String, List<TaskResult>> results,
             boolean globalCheckMode,
             List<String> runTags,
             List<String> skipTags);

    /**
     * 戦略名を返します（例: "linear", "free"）。
     */
    String getName();
}
```

### 2.1 `Strategy.run` メソッドのパラメータ詳細

| パラメータ名 | Java 型 | 説明 |
| :--- | :--- | :--- |
| `play` | `Play` | 解析済みの Play レコード（`tasks`, `roles`, `pre_tasks`, `post_tasks`, `serial`, `max_fail_percentage`, `force_handlers`, `throttle` 等を保持）。 |
| `targetHosts` | `List<Host>` | インベントリおよび `--limit` 条件により抽出された Play 実行対象の初期ホストリスト。 |
| `tqm` | `TaskQueueManager` | コネクション解決、失敗ホスト管理、コールバック通知、およびハンドラー実行を司る中心プロセス。 |
| `variableManager` | `VariableManager` | ホスト変数・プレイ変数・エクストラ変数を一括管理するコンポーネント。 |
| `results` | `Map<String, List<TaskResult>>` | ホスト名をキーとし、そのホストで実行された全タスク結果のリストを値とする結果集計 Map。 |
| `globalCheckMode` | `boolean` | コマンドライン引数 (`-C` / `--check`) 等で指定されたグローバルなドライランフラグ。 |
| `runTags` | `List<String>` | `--tags` オプションで指定された実行対象タグリスト。 |
| `skipTags` | `List<String>` | `--skip-tags` オプションで指定された除外対象タグリスト。 |

---

## 3. Linear 戦略の詳細設計 (LinearStrategy Implementation)

`LinearStrategy` は Ansible のデフォルト戦略であり、全ターゲットホストで 1 つのタスクが完了（または失敗）してから次のタスクへ進む同期型実行ロジックを提供します。

### 3.1 概要と実行ライフサイクル

`LinearStrategy.run` の全体処理シーケンスは以下のライフサイクルに従って進行します。

```
[ Strategy.run ]
      │
      ├─► 1. calculateBatches(targetHosts, serial) によるバッチ分割
      │
      └─► 2. 各バッチ (batchHosts) ごとのループ処理:
            │
            ├─► 2.1 variableManager.setBatchContext(batchNames) の設定
            │
            ├─► 2.2 preTasks の実行 ─────────────► (完了後 flushHandlers)
            │
            ├─► 2.3 roles ディレクティブの実行
            │
            ├─► 2.4 tasks (メインタスク) の実行 ──► (完了後 flushHandlers)
            │
            └─► 2.5 postTasks の実行 ────────────► (完了後 flushHandlers)
```

### 3.2 バッチ算定アルゴリズム (`calculateBatches`)

`serial` キーワードが指定されている場合、ターゲットホストリストを複数のバッチ（サブセット）に分割して実行します。

#### 1. 入力フォーマットと解析型
- **単一数値**: `serial: 2` (2 ホストずつ実行)
- **パーセンテージ文字列**: `serial: "50%"` (全体の 50% ずつ実行)
- **数値/パーセンテージ混合リスト**: `serial: [1, "40%", 5]` (1回目は 1ホスト、2回目は 40%、以降は 5ホスト)
- **文字列数値**: `serial: "3"` (数値へパースして適用)

#### 2. バッチ算定ロジック (`calculateBatches`)
1. **総数と要素数の決定**: 全ターゲットホスト数 `targetHosts.size()` を取得します。
2. **パーセンテージ計算規則**: パーセンテージ値（例: `"50%"`）が指定された場合、`Math.floor((percent / 100.0) * targetHosts.size())` により実サイズを算出します。ただし算出結果が 1 未満となった場合は、最低 `1` ホストを保証します (`Math.max(1, ...)`）。
3. **リスト超過時の最終要素適用**: バッチの分割数が `serial` リストの要素数を超える場合、リストの**最後の要素の値**を以降すべてのバッチサイズとして繰り返し適用します (`Math.min(sizeIdx, batchSizes.size() - 1)`）。
4. **安全対策フォールバック**: 算定されたバッチサイズが `0` 以下となった場合（例: `0` や `"0%"` 指定）、無限ループを回避するため、残りの未処理ホストをすべて 1 つのバッチとして一括処理します。

### 3.3 タスクループとホスト動的フィルタリング (`executeTask`)

各タスクの実行時、`LinearStrategy` は現在アクティブなバッチホストリストから、過去のタスクで失敗したホストや到達不能（Unreachable）となったホストを動的に排除して実行します。

- **致命的エラーでの短絡評価**: `tqm.isPlayFatalError()` が真の場合、タスクの実行を即座に中断してリターンします。
- **タグ判定 (`isTaskToBeExecuted`)**: タスクが実行対象外の場合は、バッチ内の全ホストに対して `TaskResult.skipped("Skipped due to tags")` を記録して早期リターンします。
- **コールバック通知 (`v2_playbook_on_task_start`)**: タスク開始前にコールバックイベントを発行します。
- **`run_once` のアトミックフラグ**: タスクに `runOnce: true` が設定されている場合、バッチ内の最初に成功した 1 ホストのみで実行し、2 ホスト目以降はスキップします (`executedOnce` フラグ管理)。
- **チェックモード動的評価**: ホスト変数と Play / Global の設定を統合し、`VariableResolver.resolveCheckMode` によりタスク実行時のチェックモードを動的に解決します。
- **到達不能例外ハンドリング (`UnreachableException`)**:
  - `task.ignoreUnreachable()` が真の場合、`TaskResult.unreachable` を結果に追加します。
  - 偽の場合、`failedHosts.add(host.name())` へ記録し、`tqm.checkAnyErrorsFatal` を呼び出して必要に応じてプレイ全体を停止します。
- **`max_fail_percentage` の評価**: 各タスクで全ホストの処理完了直後、`tqm.checkMaxFailPercentage(play, task, batch, failedHosts, variableManager)` を呼び出し、失敗率が閾値を超えた場合にプレイの進行を中止します。

### 3.4 ハンドラーフラッシュ境界 (`flushHandlers`) と強制実行 (`force_handlers`)

`preTasks`, `tasks`, `postTasks` 各フェーズの実行直後、`flushHandlers` メソッドが呼び出されます。

- **`force_handlers` の動的解決**:
  `VariableResolver.resolveForceHandlers(play.forceHandlers(), vars)` により、Play レベルまたは CLI (`--force-handlers`) の強制実行設定を解決します。
- **失敗ホストの処理**:
  `failedHosts` に含まれるホストであっても、`force_handlers` が有効な場合はスキップせず、累積された通知ハンドラー (`tqm.flushHandlersForHost`) を強制実行します。

---

## 4. Free 戦略の詳細設計 (FreeStrategy Implementation)

`FreeStrategy` は、各ホストが他のホストの進捗を待つことなく、自分に割り当てられたタスクを独立して可能な限り高速に順次実行する並列実行戦略です。

### 4.1 概要とマルチスレッドアーキテクチャ

`FreeStrategy` は `java.util.concurrent.ThreadPoolExecutor` を使用して、ホストごとのタスク実行スレッドを並行稼働させます。

```
[ FreeStrategy.run ]
       │
       ├─► ExecutorService (スレッドプール: Math.min(targetHosts.size(), forks))
       │         │
       │         ├── Thread-1 (Host A): preTasks ──► roles ──► tasks ──► postTasks ...
       │         ├── Thread-2 (Host B): preTasks ──► roles ──► tasks ──► postTasks ...
       │         └── Thread-3 (Host C): preTasks ──► roles ──► tasks ──► postTasks ...
       │
       └─► 全スレッドの完了待機 (awaitTermination / Future.get)
```

### 4.2 フォーク数 (`forks`) とスレッドプール管理

- **並列度の決定**: `TaskQueueManager.getForks()` により設定値（デフォルト: `5`）を取得します。
- **スレッドプールの生成**:
  ```java
  int forks = tqm.getForks();
  ExecutorService executor = Executors.newFixedThreadPool(
      Math.min(targetHosts.size(), forks)
  );
  ```
- **ホストごとのタスク投入**: 各ターゲットホストについて、1 つの `Runnable` タスクを作成して `executor.submit` に投入します。スレッド内部では、該当ホストに対する全タスク（`preTasks`, `roles`, `tasks`, `postTasks`）が順番に実行されます。
- **例外ログ捕捉**: スレッド実行中の未捕獲例外は `LOGGER.log(Level.SEVERE, "Error executing tasks for host: " + host.name(), e)` により安全にログ出力されます。
- **終了待機**: `executor.shutdown()` 実行後、`executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)` により全スレッドの完了を確実につなぎ合わせます。

### 4.3 スレッドセーフなステート管理とコンテキスト同期

複数スレッドが同時に実行結果の書き込みやステートの参照を行うため、`FreeStrategy` は以下のスレッドセーフ構造を使用します。

- **`run_once` 重複判定**: `Collections.newSetFromMap(new ConcurrentHashMap<>())` を用いた `Set<Task> runOnceExecuted` により、全スレッド間で安全に同一タスクの重畳実行をアトミック排除します。
- **`throttle` セマフォ管理**: `Map<Task, Semaphore> taskSemaphores = new ConcurrentHashMap<>()` を用い、タスクごとの並列実行制限セマフォを管理します。

### 4.4 タスク重複制御 (`run_once`) のアトミック処理

`run_once: true` が指定されたタスクは、全ホストの中で**最初に到達した 1 つのスレッドでのみ 1 回だけ実行**され、他のスレッドでは即座にスキップされます。

- **判定ロジック**:
  ```java
  if (task.runOnce() && !runOnceExecuted.add(task)) {
      return; // 既に別スレッドで追加・実行済みのためスキップ
  }
  ```

### 4.5 並列制限 (`throttle`) のセマフォ制御ライフサイクル

タスクまたは Play レベルで `throttle` キーワード（最大同時実行数）が指定されている場合、`FreeStrategy` は `java.util.concurrent.Semaphore` を用いて並行実行数を厳格に制御します。

- **動的評価と取得フロー**:
  1. `VariableResolver.resolveThrottle(task.throttle(), vars, ...)` により、Task > Play の優先順位で `throttle` 数値を評価します。
  2. 評価された `throttle` 値が 1 以上の場合は、`taskSemaphores.computeIfAbsent(task, t -> new Semaphore(throttle))` によりセマフォを取得します。
  3. `semaphore.acquire()` を実行して実行権限を獲得します。
  4. タスク実行完了後、`finally` ブロック内で確実に `semaphore.release()` を呼び出してパーミットを解放します。

```java
Semaphore semaphore = null;
if (throttle != null && throttle > 0) {
    semaphore = taskSemaphores.computeIfAbsent(task, t -> new Semaphore(throttle));
    try {
        semaphore.acquire();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
    }
}
try {
    // タスクの実行
} finally {
    if (semaphore != null) {
        semaphore.release();
    }
}
```

### 4.6 動的失敗率評価 (`max_fail_percentage`) と実行停止メカニズム

`FreeStrategy` では、いずれかのホストでタスクを実行・失敗するたびに、リアルタイムで `max_fail_percentage` 閾値チェックが行われます。

- **リアルタイム判定**:
  各スレッドがタスク実行を終えた直後、`tqm.checkMaxFailPercentage(play, task, targetHosts, failedHosts, variableManager)` を実行します。
- **後続タスクの即時抑制**:
  各スレッドは次のタスクに進む前に `tqm.isPlayFatalError()` および `failedHosts.contains(host.name())` をチェックし、失敗率超過や致命的エラーが検知されている場合は残りのタスクを起動せずに早期終了します。

---

## 5. 実行エンジンへの統合とファクトリパターン (`StrategyFactory`)

実行エンジン（`TaskQueueManager`）は、Playbook の定義に基づいて適切な `Strategy` 実装クラスを動的に選択・生成します。

### 5.1 登録と選択フロー (`StrategyFactory.java`)

1. **`Play` レコードのフィールド**: `Play.strategy()` フィールド（デフォルト: `"linear"`）の文字列を取得します。
2. **`StrategyFactory` による解決**:
   ```java
   public class StrategyFactory {
       public static Strategy getStrategy(String strategyName) {
           if ("free".equalsIgnoreCase(strategyName)) {
               return new FreeStrategy();
           }
           return new LinearStrategy(); // デフォルトフォールバック
       }
   }
   ```
3. **`TaskQueueManager.executePlay` での委譲**:
   `TaskQueueManager` は Play の実行制御自体を行わず、`StrategyFactory.getStrategy(play.strategy()).run(...)` を呼び出してストラテジ・プラグインへ制御を移譲します。

---

## 6. 新規ストラテジ・プラグインの追加手順

新しいカスタムタスク実行戦略を `graal-ansible` に追加するための標準手順です。

1. **`Strategy` インターフェースの実装**:
   - `org.example.ansible.engine` パッケージ配下に新規クラス（例: `HostPinnedStrategy`）を作成し、`Strategy` インターフェースを実装します。
   - `run(...)` メソッド内に、目的の配信・同期ロジックを記述します。
2. **`StrategyFactory` への登録**:
   - `StrategyFactory.getStrategy` メソッドに新しい戦略名（例: `"host_pinned"`）の分岐を追加します。
3. **テストの作成**:
   - `StrategyFactoryTest.java` および `LinearStrategyTest.java` / `FreeStrategyTest.java` を参考にして単体・統合テストを作成し、Playbook 内で新戦略を指定した際の期待動作（タスク実行順序、ハンドラーフラッシュ、エラー中断等）をアサーション検証します。

---

## 7. 関連ドキュメント

- [Playbook 実行仕様](../features/Playbook-Execution.md)
- [タスク実行エンジン (Worker Process)](Task-Executor.md)
- [タスク制御の実装詳細](Task-Control.md)
- [コールバックプラグインの設計仕様](Callback-Plugins.md)
