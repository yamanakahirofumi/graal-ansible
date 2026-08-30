# Playbook 実行仕様 (Playbook Execution)

本ドキュメントでは、`graal-ansible` における Playbook の解析と実行フローについて定義します。

## 1. 実行フロー (Linear 戦略)

本プロジェクトの実行フローは、[処理フロー (Process-Flow.md)](Process-Flow.md) を正として以下の通り行われます。

1.  **YAML 解析 (PE)**: `SnakeYAML 2.x` を使用して YAML ファイルを読み込み、Java Record にマッピング。
2.  **実行依頼 (PE -> TQM)**: ターゲットホストごとにタスクを割り当てます。
3.  **変数の解決 (Worker)**: Jinjava を用いて、タスク実行直前にテンプレートを展開します。
4.  **Action Plugin チェック (Worker)**:
    - 実行対象が Action Plugin（例: `template`, `copy`）の場合、管理ノード上で Python スクリプトを実行します。
    - 必要に応じて、Action Plugin 内からターゲットノードへのモジュール実行指示（`_execute_module`）が行われます。
5.  **モジュール転送・実行 (Worker -> CP -> TN)**:
    - 通常モジュール（例: `command`, `ping`）の場合、または Action Plugin から指示された場合、Ansiballz 形式でパッケージングを行い、ターゲットノードへ転送して実行します。
6.  **結果集計 (TQM / PE)**: 各ホストの実行結果を収集し、`ExecutionReport` として統計情報やレポートを生成。

## 2. 実装時にブレる恐れがある仕様 (実装上の留意点)

実装の際、Ansible 本家との挙動の差異や、技術的な制約により判断が分かれる可能性がある項目を以下に挙げます。

### 2.1 変数のスコープと優先順位
- **懸念点**: Ansible は非常に複雑な変数の優先順位（22段階以上）を持っています。
- **方針**: 現在、Ansible 互換の全 22 段階の変数優先順位が完全に実装され、検証済みです。具体的な優先順位の定義については、[変数とテンプレートの実装詳細](../implementation/Variables-Templating.md#2-変数優先順位-variable-precedence) を参照してください。

### 2.2 Jinja2 テンプレートの互換性
- **状況**: `Jinjava` を採用し、Ansible 互換のフィルターおよびルックアッププラグインを Java で実装しています。
- **実装済み**:
    - **フィルター (27種類)**: `b64decode`, `b64encode`, `basename`, `bool`, `combine`, `default`, `difference`, `dict2items`, `dirname`, `flatten`, `intersect`, `ipaddr`, `items2dict`, `mandatory`, `quote`, `realpath`, `regex_replace`, `splitext`, `symmetric_difference`, `ternary`, `to_json`, `to_nice_json`, `to_yaml`, `to_nice_yaml`, `union`, `unique`, `urlencode` をサポート（詳細なパラメータ仕様および使用例は [変数とテンプレートの実装詳細](../implementation/Variables-Templating.md#4-独自フィルターとテストの拡張) を参照）。
    - **ルックアッププラグイン (7種類)**: `dict`, `env`, `file`, `first_found`, `pipe`, `template`, `vars` をサポート（詳細なパラメータ仕様および使用例は [変数とテンプレートの実装詳細](../implementation/Variables-Templating.md#7-lookup-プラグイン-lookup-plugins) を参照）。
- **方針**: 未実装のフィルターやルックアップ、あるいはテンプレートのレンダリングエラー（未定義変数の参照等）が発生した場合は、原則として `RuntimeException` をスローし、該当ホストのタスクを失敗（failed）として処理します。

### 2.3 ループ (`loop`, `with_items`) の処理
- **懸念点**: `loop` と `with_X` 系では挙動が異なり、特に複雑なデータ構造に対するループ処理のパースがブレやすいです。
- **方針**: `loop` を基本とし、`with_items` などのレガシーな構文は内部で `loop` に変換して処理します（実装済み）。
    - **with_items**: `loop: "{{ list | flatten(levels=1) }}"` に変換。
    - **with_dict**: `loop: "{{ dict | dict2items }}"` に変換。

### 2.4 タスクの並列実行 (Strategy)
- **状況**: `linear`（デフォルト）および `free` の実行戦略が完全に実装され、動作検証済みです。
- **実装済み**:
    - **Linear 戦略**: 1つのタスクが全ホストで完了してから次のタスクに進む標準的な戦略。
    - **Free 戦略**: ホストごとに独立してタスクを順次実行する並列戦略。
- **詳細**: 実装の詳細については、[ストラテジ・プラグインの実装仕様](../implementation/Strategy-Plugins.md) を参照してください。

### 2.5 独自タグの処理 (Implemented)
- **概要**: `!vault` などの YAML 独自タグが含まれている場合、通常の YAML パーサーではエラーになります。
- **実装内容**: `org.example.ansible.util.YamlUtil` を通じて `SnakeYAML` をカスタマイズし、未知のタグをその基底となる YAML 型（Scalar, Sequence, Mapping）として透過的に扱う仕組みを実装済みです。これにより、特別な設定なしに未知のタグを含む YAML を解析可能です。

### 2.6 並列実行制御 (`forks` / `serial` / `throttle` / `max_fail_percentage`)
- **状況**: 実装済み。
- **詳細**:
    - `forks`: CLI オプション `-f` / `--forks` または設定ファイルにより指定可能です。`FreeStrategy` においてこの設定値が適用され、ホスト間の並列実行（スレッドプールサイズ）を制御します。
    - `serial`: Playbook 内で指定され、`LinearStrategy` においてホストをバッチ分割して実行します。整数、パーセンテージ（例: "50%"）、およびリスト形式（例: `[1, 5, "20%"]`）をサポートしています。
        - **リスト指定時の挙動**: リスト形式で指定された場合、各数値が順次バッチサイズとして適用されます。リストの要素数よりも多くのバッチが必要な場合は、リストの**最後の値**が残りのすべてのバッチサイズとして継続的に使用されます。
        - **バッチ処理**: バッチ実行ごとにハンドラーのフラッシュとマジック変数 `ansible_play_batch` の更新が行われます。
    - `throttle`: 特定のタスク、ブロック、またはプレイにおいて、同時に実行できるホスト数を制限します。`FreeStrategy` において適用され、指定された数以上のホストが並列で実行されないよう制御します。
    - `max_fail_percentage`: 失敗が許容されるホストの割合を指定します。この閾値を超えた場合、プレイ全体の実行が停止されます。`linear` および `free` の両方の戦略、およびロール実行時において完全にサポートされています。

### 2.7 非同期タスク (`async`, `poll`)
- **状況**: 実装済み。
- **詳細**: `async` キーワードによるバックグラウンド実行と、`poll` によるポーリング間隔の指定が可能です。ジョブの状態は管理ノードの `~/.ansible_async/` に保存され、`async_status` モジュールにより後続のタスクから状態を確認することも可能です。

### 2.8 ストラテジ固有のコールバック最適化 (Implemented)
- **状況**: 実装済み。
- **詳細**: `free` 戦略などの並列実行環境において、標準出力の混在やタスクヘッダーの重複出力を防ぐための最適化が行われています。`DefaultCallback` は `synchronized` メソッドによるスレッドセーフな出力と、`ConcurrentHashMap` を使用したタスクヘッダーの重複排除機能を備えています。

### 2.9 Python ベースのコールバックのサポート (Implemented)
- **状況**: 実装済み。
- **詳細**: GraalPy を活用し、Ansible 本家のコールバックプラグインをそのまま実行可能です。環境変数 `ANSIBLE_STDOUT_CALLBACK` を通じて動的にプラグインを選択できます。

### 2.10 実行結果レポートと統計情報 (ExecutionReport)
- **状況**: 実装済み。
- **詳細**: `PlaybookExecutor.executeAndReport` を通じて、Playbook 実行完了時にホスト別・全体共通のメトリクス（ok, changed, unreachable, failed, skipped, total_tasks）を集計した `ExecutionReport` オブジェクトを生成します。
    - **統計情報**: ホスト単位 (`getHostStats`) および全体集計 (`getOverallStats`) の統計データを取得可能であり、`isSuccess()` により全体としての成功判定が可能です。
    - **ホストフィルタリング**: `getFailedHosts()`, `getChangedHosts()`, `getSkippedHosts()` または Predicate (`filterHosts`) による柔軟なホストの抽出に対応します。
    - **タスク結果フィルタリング**: `getFailedTaskResults()`, `getUnreachableTaskResults()`, `getChangedTaskResults()` または Predicate (`filterTaskResults`) により特定状態の `TaskResult` を抽出可能です。
    - **エクスポート**: `toSummaryMap()` メソッドにより、ホストごとの実行結果サマリーを Map 構造（`Map<String, Map<String, Integer>>`）で出力・連携可能です。
    - **実装詳細**: クラス設計や各種 API の詳細については、[タスク実行エンジンの実装詳細](../implementation/Task-Executor.md#12-実行結果レポートと統計管理-executionreport) を参照してください。

## 3. タスクのフィルタリング (Tags and Limit)

Playbook の実行範囲を制御するためのフィルタリング機能を提供します。

### 3.1 タグによる実行制御 (Tags)
- **タグの付与**: Play, Block, Task レベルで `tags` キーを使用してタグを付与できます。
- **実行対象の指定**: CLI オプション `--tags` (または `-t`) で指定されたタグを持つタスクのみを実行します。
- **スキップ対象の指定**: CLI オプション `--skip-tags` で指定されたタグを持つタスクをスキップします。
- **特殊なタグ**:
    - `always`: 常に実行されるタグ。`--skip-tags always` が指定されない限り実行されます。
    - `never`: 常にスキップされるタグ。`--tags never` が明示的に指定されない限り実行されません。
- **継承とブロック構造での評価**:
    - **Play / Block 継承**: Play レベルのタグは、その Play 内のすべてのタスクに継承されます。Block レベルのタグも同様に、その Block 内（`block`, `rescue`, `always` 内のタスク）へ透過的に継承されます。
    - **再帰的評価**: ブロック構造（`block`, `rescue`, `always`）が含まれるタスクでは、ブロック内の配下タスク（`task.block()`, `task.rescue()`, `task.always()`）に対して再帰的にタグ評価が適用されます。`rescue` および `always` セクション内のタスクにおいても個別フィルタリングが行われ、指定条件に一致するタスクのみが実行されます。
    - **実装詳細**: タグの具体的な判定アルゴリズム（`isTaskToBeExecuted`）やマッチング優先順位については、[タスク制御の実装詳細](../implementation/Task-Control.md#14-タグ評価とブロック継承仕様-tags-and-block-inheritance) を参照してください。

### 3.2 ホストによる実行制限 (Limit)
- **実行ホストの制限**: CLI オプション `--limit` (または `-l`) を使用して、Play で定義された `hosts` の中からさらに実行対象を絞り込むことができます。
- **指定方法**: ホスト名、グループ名、またはカンマ区切りのリストをサポートします。

## 4. 将来的な拡張事項 (Future Extensions)

今後の設計・拡張事項については、[検討事項・TODOリスト](../TODO-Details.md#5-今後の設計・拡張事項-future-design-and-extensions) を参照してください。
