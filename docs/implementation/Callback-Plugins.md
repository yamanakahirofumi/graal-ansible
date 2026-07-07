# コールバックプラグインの設計仕様 (Callback Plugins Implementation)

本ドキュメントでは、`graal-ansible` における実行結果の出力とイベント通知を制御する「コールバックプラグイン」の設計方針について詳述します。

## 1. 概要

コールバックプラグインは、Playbook 実行中の各ライフサイクルイベント（Playの開始、タスクの完了、失敗等）をフックし、標準出力へのログ表示や外部システムへの通知、結果の集計などを行うための仕組みです。現在、`graal-ansible` では Java ベースのコールバックシステムが完全に実装されています。

## 2. インターフェース定義

Java で実装されるコールバックプラグインは、以下の `Callback` インターフェースを実装する必要があります。メソッド名は Ansible 本家の Callback 互換（v2 API）を意識しています。

```java
public interface Callback {
    /** Playbook 実行開始時 */
    void v2_playbook_on_start(Playbook playbook);

    /** Play 実行開始時 */
    void v2_playbook_on_play_start(Play play);

    /** タスク実行開始時 */
    void v2_playbook_on_task_start(Task task, boolean isConditional);

    /** タスク成功時 (ok) */
    void v2_runner_on_ok(String host, TaskResult result);

    /** タスク失敗時 (failed) */
    void v2_runner_on_failed(String host, TaskResult result, boolean ignoreErrors);

    /** タスクスキップ時 (skipped) */
    void v2_runner_on_skipped(String host, TaskResult result);

    /** ホスト到達不能時 (unreachable) */
    void v2_runner_on_unreachable(String host, TaskResult result);

    /** ハンドラー実行開始時 */
    void v2_playbook_on_handler_stats(String handlerName);

    /** 最終統計情報の出力時 */
    void v2_playbook_on_stats(Map<String, Map<String, Integer>> stats);
}
```

## 3. 実行エンジンへの統合

### 3.1 登録メカニズム
- `TaskQueueManager` は、有効化された `Callback` インスタンスのリストを保持します。
- デフォルトでは、標準出力を行う `DefaultCallback` が登録されます。
- 環境変数 `ANSIBLE_STDOUT_CALLBACK` を使用して、使用するメインのコールバックプラグインを切り替えることができます。

### 3.2 イベントのトリガー
- `PlaybookExecutor` および `TaskQueueManager` 内の適切なタイミングで、登録されたすべてのコールバックの該当メソッドを呼び出します。
- **Linear 戦略との兼ね合い**: Linear 戦略では、1つのタスクが全ホストで完了するのを待つため、各ホストの結果が返却される都度 `v2_runner_on_ok` 等が呼び出されます。

## 4. 標準コールバック (DefaultCallback)

Ansible 本家のデフォルト出力に近い形式を Java で実装します。

- **PLAY [name]**: Play の開始。
- **TASK [name]**: タスクの開始。
- **ループ結果の表示**:
    - `loop` を含むタスクの実行時、イテレーションごとの結果を表示します。
    - アイテムのラベルとして、実行結果に含まれる `_ansible_item_label` フィールド（または `item` フィールド）を使用し、複雑なデータ構造のループ時にも簡潔な出力を提供します。
- **ok: [host]**, **changed: [host]**, **fatal: [host]**: 各ホストの実行結果。
- **PLAY RECAP**: 最終的な成功・変更・失敗数の統計。

## 5. 実装済みのプラグイン

### 5.1 DefaultCallback
Ansible 標準の出力形式を提供します。

### 5.2 JsonCallback
実行結果を構造化された JSON 形式で出力します。
- **有効化方法**: 環境変数 `ANSIBLE_STDOUT_CALLBACK=json` を設定して実行します。
- **出力内容**: プレイ、タスクごとの実行結果および最終的な統計情報を含みます。

## 6. 今後の拡張性

今後の設計・拡張事項については、[検討事項・TODOリスト](../TODO-Details.md#5-今後の設計・拡張事項-future-design-and-extensions) を参照してください。

## 7. 並列実行における出力の最適化 (Optimization for Parallel Execution) {#7-並列実行における出力の最適化-future-design}

`free` 戦略などの並列実行環境において、複数のホストが同時に同じタスクを実行する際、標準出力が混み合ったり、同じタスクヘッダーが何度も出力されたりする問題を解決するための設計方針です。

### 7.1 タスクヘッダーの重複排除 (Deduplication)
- **現状**: 各ホストがタスクを開始するたびに `v2_playbook_on_task_start` が呼ばれ、ヘッダー（`TASK [name] ***`）が出力されます。
- **設計方針**:
    - `DefaultCallback` 内で、既に出力されたタスクやハンドラーの識別子を保持するスレッドセーフなセット（`ConcurrentHashMap.newKeySet()`）を導入します。
    - 各イベントメソッドの開始時に、その識別子がセットに含まれているか確認し、未出力の場合のみヘッダーを表示してセットに追加します。
    - **クリーンアップ**: プレイブックおよびプレイの開始イベント (`v2_playbook_on_start`, `v2_playbook_on_play_start`) において、このセットをクリアすることで、異なるプレイ間での重複排除の誤作動を防ぎます。

### 7.2 スレッドセーフなコンソール出力
- **方針**: 複数のスレッドからの出力呼び出しによって、一行の出力が途中で混ざり合わない（インターリーブしない）ことを保証します。
- **設計方針**:
    - `DefaultCallback` のすべての公開メソッドを `synchronized` 修飾子で保護します。これにより、一度に一つのスレッドだけが出力処理を行うことが保証され、コンソール出力の整合性が保たれます。

## 8. Python ベースのコールバックのサポート (Python-based Callback Support) {#9-python-ベースのコールバックのサポート-future-design}

GraalPy を利用して、Ansible 本家の Python 製コールバックプラグインをそのまま実行するための設計方針です。

### 8.1 概要
- `ActionPlugin` と同様のブリッジメカニズム（`ansible_bridge.py`）を適用し、Python 側のイベントフックを Java 側の `Callback` インターフェースへマッピングします。

### 8.2 実装アプローチ
- **ProxyCallback (Java)**: `Callback` インターフェースを実装し、各メソッド呼び出しを GraalPy コンテキスト内の Python ランチャーへ転送するクラスを導入します。
- **Callback Launcher (Python)**: 指定された Python コールバックプラグインをロードし、Java 側からのイベント通知を Python プラグインの対応するメソッド（`v2_playbook_on_task_start` 等）に振り分けます。
- **コンテキストの共有**: Python コールバックが必要とする Playbook や Task の情報は、Java オブジェクトを Polyglot API 経由で Python 側に渡すことで共有します。

## 9. ロギング方針との関係
- `Logging-Policy.md` で定義される `java.util.logging` は、主に内部デバッグやシステムエラー用です。
- ユーザー向けの「実行結果レポート」は、本コールバックシステムが主導します。
