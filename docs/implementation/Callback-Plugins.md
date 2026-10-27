# コールバックプラグインの設計仕様 (Callback Plugins Implementation)

本ドキュメントでは、`graal-ansible` における実行結果の出力とイベント通知を制御する「コールバックプラグイン」の設計方針、コンポーネント構成、データマッピング、イベントライフサイクル、および実装詳細について詳述します。

## 1. 概要

コールバックプラグインは、Playbook 実行中の各ライフサイクルイベント（Playbook の開始、Play の開始、タスクの実行、イテレーション完了、成功/失敗/到達不能等）をフックし、標準出力へのログ表示や外部システムへの通知、構造化レポートの生成などを行うための仕組みです。現在、`graal-ansible` では Java ネイティブなコールバックシステムおよび Python ベースのコールバック連携が完全に実装されています。

## 2. インターフェース定義

Java で実装されるすべてのコールバックプラグインは、以下の `Callback` インターフェースを実装します。メソッド名は Ansible 本家の Callback 互換（v2 API）を完全に踏襲しています。

```java
public interface Callback {
    /** Playbook 実行開始時 */
    void v2_playbook_on_start(Playbook playbook);

    /** Play 実行開始時 */
    void v2_playbook_on_play_start(Play play);

    /** タスク実行開始時 */
    void v2_playbook_on_task_start(Task task, boolean isConditional);

    /** タスク成功時 (ok) */
    void v2_runner_on_ok(Task task, String host, TaskResult result);

    /** タスク失敗時 (failed) */
    void v2_runner_on_failed(Task task, String host, TaskResult result, boolean ignoreErrors);

    /** タスクスキップ時 (skipped) */
    void v2_runner_on_skipped(Task task, String host, TaskResult result);

    /** ホスト到達不能時 (unreachable) */
    void v2_runner_on_unreachable(Task task, String host, TaskResult result);

    /** ハンドラー実行開始時 */
    void v2_playbook_on_handler_stats(String handlerName);

    /** 最終統計情報の出力時 */
    void v2_playbook_on_stats(Map<String, Map<String, Integer>> stats);
}
```

## 3. 実行エンジンへの統合

### 3.1 登録メカニズム
- `TaskQueueManager` は、有効化された `Callback` インスタンスのリスト（`List<Callback>`）を保持します。
- デフォルトでは、標準出力を行う `DefaultCallback` が登録されます。
- `CallbackFactory` を介して、環境変数や設定ファイルに基づく動的なコールバックプラグインの選択が可能です。

### 3.2 イベントのトリガー
- `PlaybookExecutor` および `TaskQueueManager` 内の各ライフサイクルイベント発生時に、登録されたすべてのコールバックの該当メソッドを順次呼び出します。
- **Linear 戦略との兼ね合い**: Linear 戦略では、1つのタスクが全ホストで完了するのを待つため、各ホストの結果が返却される都度 `v2_runner_on_ok` 等が呼び出されます。
- **Free 戦略との兼ね合い**: Free 戦略では、各ホストが独立してタスクを順次実行するため、複数スレッドから非同期にコールバックメソッドが呼び出されます。

### 3.3 コールバック解決優先順位と CallbackFactory (Resolution Precedence & CallbackFactory)

実行時に使用されるメインの `stdout` コールバックプラグイン（標準出力プラグイン）は、`CallbackFactory.createStdoutCallback(String cliCallback, List<String> collectionPaths)` によって以下の優先順位に従って決定・インスタンス化されます。

1. **CLI パラメータ / 明示的引数 (`cliCallback`)**: `PlaybookCli` 経由またはプログラムから明示的に引き渡されたコールバック名。
2. **環境変数 `ANSIBLE_STDOUT_CALLBACK`**: システム環境変数 `ANSIBLE_STDOUT_CALLBACK`（例: `ANSIBLE_STDOUT_CALLBACK=json` や `ANSIBLE_STDOUT_CALLBACK=yaml`）。
3. **`ansible.cfg` 設定ファイル**: `PythonEnv.getStdoutCallbackFromCfg()` 経由で取得される `[defaults]` セクション配下の `stdout_callback` 設定項目。
4. **デフォルトフォールバック**: いずれも未指定の場合は `"default"` (`DefaultCallback`)。

#### 解決分岐と例外フォールバック (Lookup Matrix & Error Fallback)

`CallbackFactory` は、解決されたコールバック名（小文字トリム化）に基づき、以下のスイッチ処理を実行します：

- `"default"` $\rightarrow$ `new DefaultCallback()`
- `"json"` $\rightarrow$ `new JsonCallback()`
- `"minimal"` $\rightarrow$ `new MinimalCallback()`
- `"yaml"` $\rightarrow$ `new YamlCallback()`
- **その他 (カスタム/Python プラグイン)** $\rightarrow$ `new PythonCallback(callbackName, collectionPaths)` を試行します。Python 側のプラグインロード時や Polyglot Context 初期化時に例外が発生した場合は、標準エラーに警告を出力し、安全に `DefaultCallback` へフォールバックします。

### 3.4 イベント伝播シーケンスフロー (Sequence Flow)

以下は、`PlaybookExecutor` および `TaskQueueManager` から各コールバックプラグインへライフサイクルイベントが通知される全体シーケンス図です。

```mermaid
sequenceDiagram
    autonumber
    participant PE as PlaybookExecutor
    participant TQM as TaskQueueManager
    participant TE as TaskExecutor (Worker)
    participant CB as Callback (Default/Json/Yaml/Python)

    PE->>CB: v2_playbook_on_start(playbook)
    PE->>TQM: executePlay(play)
    TQM->>CB: v2_playbook_on_play_start(play)
    loop 各タスク (linear / free 戦略)
        TQM->>CB: v2_playbook_on_task_start(task, isConditional)
        TQM->>TE: executeSingleTask(task, host)
        TE-->>TQM: TaskResult (ok / failed / skipped / unreachable)
        alt 成功 (ok)
            TQM->>CB: v2_runner_on_ok(task, host, result)
        else 失敗 (failed)
            TQM->>CB: v2_runner_on_failed(task, host, result, ignoreErrors)
        else スキップ (skipped)
            TQM->>CB: v2_runner_on_skipped(task, host, result)
        else 到達不能 (unreachable)
            TQM->>CB: v2_runner_on_unreachable(task, host, result)
        end
    end
    TQM->>CB: v2_playbook_on_stats(summaryStats)
```

### 3.5 非ブロッキングエラーハンドリングと例外の分離

コールバックプラグイン内部で例外（`RuntimeException` や I/O 例外等）が発生した場合でも、Playbook の本体処理やターゲットノードでのタスク実行が中断・阻害されることを防ぐため、以下の分離機構が組み込まれています。

- **例外キャッチと隔離**: `TaskQueueManager` がコールバックメソッド（`v2_runner_on_ok` 等）を呼び出す際、個々のコールバック呼び出しを `try-catch (Throwable t)` ブロックで保護します。
- **エラー出力**: コールバックプラグイン内部でスローされた例外はデバッグログ（`LOGGER.log(Level.SEVERE, ...)`）にのみ記録され、他ホストのタスク処理や Playbook の実行状態には影響を及ぼしません。

## 4. 標準コールバック (DefaultCallback)

Ansible 本家のデフォルト出力形式に近い人間視認性の高いログをコンソール出力します。

- **`PLAY [name]`**: Play の開始ヘッダー出力。
- **`TASK [name]`**: タスクの開始ヘッダー出力。
- **ループ結果の表示**:
    - `loop` を含むタスクの実行時、イテレーションごとの結果を表示します。
    - アイテムのラベルとして、実行結果に含まれる `_ansible_item_label` フィールド（または `item` フィールド）を使用し、複雑なデータ構造のループ時にも簡潔な出力を提供します。
- **`ok: [host]`**, **`changed: [host]`**, **`fatal: [host]`**: 各ホストの実行結果ステータス。
- **`PLAY RECAP`**: 最終的な成功・変更・失敗・到達不能・スキップ数の統計サマリーテーブル。

## 5. 実装済みのプラグイン仕様 (Plugin Specifications)

`graal-ansible` に標準搭載されている 5 種類の主要コールバックプラグインの技術実装仕様およびデータマッピング規則です。

### 5.1 DefaultCallback
Ansible 標準の対話型コンソール出力形式を提供します。

- **スレッドセーフ制御**: 全公開イベントフックメソッドに `synchronized` を付与し、並列実行時（`free` 戦略等）のコンソール出力混在を防止します。
- **ヘッダー重複排除**: `ConcurrentHashMap.newKeySet()` による `printedTasks` セットを保持し、同一 Play 内での同一タスク/ハンドラーヘッダー表示を最大 1 回に抑制します。
- **ループイテレーション解析**: `TaskResult.data()` 内に `results` リストが含まれる場合、`_ansible_item_label` または `item` フィールドを取り出し、イテレーションごとのステータス（`ok`, `changed`, `fatal`, `skipping`, `unreachable`）を描画します。
- **`PLAY RECAP` サマリーテーブル**: 最終統計マップを `TreeMap` でソートしてホスト名順に `ok`, `changed`, `unreachable`, `failed`, `skipped` のアラインメント済みカウントを表示します。

### 5.2 JsonCallback
実行結果全般を階層化された単一の構造化 JSON オブジェクトとして出力します。

- **有効化方法**: 環境変数 `ANSIBLE_STDOUT_CALLBACK=json` または CLI 指定。
- **データ構造**:
  - Jackson `ObjectMapper` (`SerializationFeature.INDENT_OUTPUT`) を使用。
  - ルート配下に `plays` リストと `stats` マップを保持。
  - 各 Play に `play` (`name`, `id`) と `tasks` リストを格納し、各 Task 内の `hosts` マップにホスト名ごとの実行結果データ（`changed`, `failed`, `msg`, `ignore_errors` 等）を動的バインド。
  - 最終の `v2_playbook_on_stats` 呼び出し時に構造化 JSON を標準出力へ一括シリアライズ出力。

### 5.3 MinimalCallback
ホストごとの実行結果を最小限のワンライン（1行）形式でシンプルに出力するプラグインです。CI/CD パイプラインや大規模ログ収集環境に適しています。

- **有効化方法**: 環境変数 `ANSIBLE_STDOUT_CALLBACK=minimal` または CLI 指定。
- **出力形式仕様**:
  - Jackson `ObjectMapper` により `TaskResult.data()` をコンパクトな JSON 文字列へフォーマット。
  - **成功 (ok)**: `host | SUCCESS => {"changed": false, ...}`
  - **変更 (changed)**: `host | CHANGED => {"changed": true, ...}`
  - **失敗 (failed)**: `host | FAILED! => {"changed": false, "failed": true, ...}` (ignoreErrors 時は次行に `...ignoring` を付与)
  - **スキップ (skipped)**: `host | SKIPPED`
  - **到達不能 (unreachable)**: `host | UNREACHABLE! => {"failed": true, ...}`

### 5.4 YamlCallback
タスクごとの実行結果データを整形された構造化 YAML 形式で視認性高く出力するプラグインです。

- **有効化方法**: 環境変数 `ANSIBLE_STDOUT_CALLBACK=yaml` または CLI 指定。
- **出力形式仕様**:
  - `DefaultCallback` を継承し、タスクヘッダー出力・ヘッダー重複排除機構を共通利用。
  - SnakeYAML (`DumperOptions.FlowStyle.BLOCK`, インデント `2`, `prettyFlow=true`) を使用して `TaskResult.data()`、`changed`、`failed`、`msg` をブロック YAML へシリアライズ。
  - 出力行の先頭に `2` スペースのインデントを付与して描画（改行を含む `stdout` / `stderr` を可読性の高いスカラーブロックとして表示）。

### 5.5 PythonCallback (GraalPy Polyglot Bridge)
GraalPy 上で本家 Ansible の Python 製コールバックプラグインを実行するブリッジプラグインです。

- **有効化方法**: 環境変数 `ANSIBLE_STDOUT_CALLBACK=<python_plugin_name>` (例: `dense`, `actionable`, `oneline` 等)。
- **Polyglot コンテキスト初期化**:
  - GraalVM Polyglot `Context` (`python` 言語, `allowAllAccess(true)`) を生成。
  - `PythonOSMock` (`os_java`), `PythonAnsibleModuleMock.Factory` (`AnsibleModuleJava`) をバインド。
  - `ansible_bridge.py` および `ansible_callback_launcher.py` スクリプトをロード。
  - `callback_name`, `site_packages_java`, `collection_paths_java` を Python バインディングへ注入。
- **イベント転送**: Java 側の `v2_playbook_on_start` や `v2_runner_on_ok` 呼び出し時に、`bindings.getMember(methodName)` 経由で Python 側の対応メソッドを実行。
- **クリーンアップ**: `AutoCloseable.close()` 実装により、実行完了時に Polyglot Context を安全に破棄。

### 5.6 コールバック間における TaskResult フィールドマッピング規則

`TaskResult` オブジェクトが各コールバックプラグインへ渡された際、共通フィールドがどのように構造化・表示されるかのデータマッピング仕様です。

| TaskResult フィールド | データ型 | DefaultCallback | JsonCallback | MinimalCallback | YamlCallback | PythonCallback |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `rc` | `Integer` | 失敗時のみ表示 | `hosts[h].rc` | `rc: 0` | `rc: 0` (YAML) | Python 辞書内 `rc` |
| `stdout` | `String` | `-v` 以上で表示 | `hosts[h].stdout` | JSON 内出力 | ブロックテキスト (`\|`) | Python 辞書内 `stdout` |
| `stderr` | `String` | 失敗時または `-v` | `hosts[h].stderr` | JSON 内出力 | ブロックテキスト (`\|`) | Python 辞書内 `stderr` |
| `msg` | `String` | メッセージ表示 | `hosts[h].msg` | `msg: "..."` | `msg: "..."` | Python 辞書内 `msg` |
| `changed` | `boolean` | `changed: [host]` | `hosts[h].changed` | `CHANGED` ヘッダー | `changed: true` | Python 辞書内 `changed` |
| `failed` | `boolean` | `fatal: [host]` | `hosts[h].failed` | `FAILED!` ヘッダー | `failed: true` | Python 辞書内 `failed` |
| `results` (loop) | `List<Map>` | イテレーション表示 | `hosts[h].results` | JSON 内リスト | YAML リスト構造 | Python 辞書内 `results` |

## 6. 今後の拡張性

今後の設計・拡張事項については、[検討事項・TODOリスト](../TODO-Details.md#5-今後の設計・拡張事項-future-design-and-extensions) を参照してください。

## 7. 並列実行における出力の最適化 (Implemented) {#7-並列実行における出力の最適化}

`free` 戦略などの並列実行環境において、複数のホストが同時に同じタスクを実行する際、標準出力が混み合ったり、同じタスクヘッダーが何度も出力されたりする問題を解決するための実装です。

### 7.1 タスクヘッダーの重複排除 (Deduplication)
- **概要**: 同一タスクに対するヘッダー出力を 1 回のみに制限します。
- **実装**:
    - `DefaultCallback` 内で、`ConcurrentHashMap.newKeySet()` を使用して出力済みのタスク/ハンドラーを追跡します。
    - ヘッダー出力前にセットを確認し、未出力の場合のみ出力します。
    - `v2_playbook_on_play_start` でセットをクリアし、プレイごとの重複排除を実現しています。

### 7.2 スレッドセーフなコンソール出力
- **概要**: 複数のスレッドからの出力がインターリーブしないことを保証します。
- **実装**:
    - `DefaultCallback` の各公開メソッドに `synchronized` を付与し、1 つのライフサイクルイベントに対する複数行の出力がアトミックに行われるように保証しています。

## 8. ロギング方針との関係
- `Logging-Policy.md` で定義される `java.util.logging` は、主に内部デバッグやシステムエラー用です。
- ユーザー向けの「実行結果レポート」は、本コールバックシステムが主導します。

## 9. Python ベースのコールバックのサポート (Implemented) {#9-python-ベースのコールバックのサポート}

GraalPy を活用し、Ansible 本家の Python 製コールバックプラグインをそのまま実行可能にするための実装詳細です。

### 9.1 ブリッジメカニズム
- `ActionPlugin` と同様のブリッジメカニズム（`ansible_bridge.py`）を利用して、Python 側の実行環境を構成します。
- `ansible.plugins.callback` パッケージから指定されたプラグインを動的にロードします。

### 9.2 イベントのマッピング
Java 側の `Callback` インターフェースの各メソッド呼び出しを、Python 側のプラグインが持つ対応するメソッド（v2 API）へ転送します。

| Java (Callback interface) | Python (CallbackBase method) |
| :--- | :--- |
| `v2_playbook_on_start` | `v2_playbook_on_start` |
| `v2_playbook_on_play_start` | `v2_playbook_on_play_start` |
| `v2_runner_on_ok` | `v2_runner_on_ok` |
| `v2_runner_on_failed` | `v2_runner_on_failed` |
| `v2_runner_on_skipped` | `v2_runner_on_skipped` |
| `v2_runner_on_unreachable` | `v2_runner_on_unreachable` |
| `v2_playbook_on_handler_stats` | `v2_playbook_on_handler_stats` |
| `v2_playbook_on_stats` | `v2_playbook_on_stats` |

### 9.3 データ変換と Polyglot 連携
- Java 側の `TaskResult`, `Play`, `Task` などのオブジェクトを、Python 側が扱いやすい形式（辞書または Polyglot Proxy）に変換して渡します。
- Python 側での標準出力（`print` 等）は、Java 側の出力ストリームに適切にリダイレクトされます。
