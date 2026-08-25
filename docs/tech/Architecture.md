# アーキテクチャ設計

本プロジェクトでは、ansible-coreの機能をJavaおよびGraalVM Native Imageで効率的に実行するため、モジュール化されたクリーンなアーキテクチャを採用します。

## 1. ディレクトリ・パッケージ構造
標準的な Maven 構造に準拠した構成を推奨します。

```
.
├── pom.xml                # プロジェクト構成 (Maven)
├── src
│   ├── main
│   │   ├── java
│   │   │   └── org.example.ansible
│   │   │       ├── Main.java         # エントリーポイント (CLI受付)
│   │   │       ├── cli/              # コマンドライン引数解析
│   │   │       ├── parser/           # YAML/Playbook 解析 (SnakeYAML等)
│   │   │       ├── engine/           # Playbook 実行エンジン (タスク制御)
│   │   │       ├── inventory/        # インベントリ管理 (静的/動的)
│   │   │       ├── module/           # モジュール実行ブリッジ (GraalPy経由)
│   │   │       ├── connection/       # 接続プラグイン (Local, SSH, Docker, WinRM)
│   │   │       └── util/             # OS抽象化レイヤー、共通ユーティリティ
│   │   └── resources
│   │       └── META-INF/native-image # GraalVM Native Image 設定
│   └── test
│       └── java
│           └── org.example.ansible   # ユニットテスト・結合テスト
```

## 2. 主要コンポーネントの責務

### 2.1 CLI / Parser 層
- **CLI**：`ansible-playbook` 互換のオプションを解析し、実行コンテキストを初期化します。
- **Parser**：Playbook (YAML) を解析し、内部の実行モデル（Play, Taskのリスト）に変換します。

### 2.2 Execution Engine 層 (管理ノード / Control Node)
- **PlaybookExecutor (PE)**：Playbook 全体の実行フローを管理します。Play, Block, Task の階層構造を辿り、実行完了時にはホスト・全タスク統計（`ok`, `changed`, `unreachable`, `failed`, `skipped`, `total_tasks`）を保持し、各種条件フィルタリングおよび `toSummaryMap()` エクスポート機能を備えた `ExecutionReport` オブジェクトを集計・生成します。
- **TaskQueueManager (TQM)**：各ホストへのタスク配信や結果の集計、実行戦略（`LinearStrategy`, `FreeStrategy`）に基づく並列性・バッチ制御（`serial`, `forks`, `throttle`, `max_fail_percentage`）を担当します。
- **TaskExecutor (Worker Process)**：個別のタスク実行を担当します。`loop`（`loop_control`）, `until/retries`, `failed_when/changed_when`, `check_mode`, `environment`, `delegate_to` の動的制御および Jinja2 テンプレートの評価を行います。
- **VariableManager / VariableResolver**：全 22 段階の変数優先順位（CLI, Play, Host, Role, Extra-vars等）とスコープ管理、`Jinjava` によるテンプレート展開を担当します。27 種類の Ansible 互換 Jinja2 フィルター（`to_nice_json`, `to_nice_yaml`, 集合演算フィルター等）および 7 種類の Lookup プラグイン（`file`, `env`, `template`, `pipe`, `dict`, `vars`, `first_found`）をサポートします。

### 2.3 Provider / Plugin 層 (管理ノード / Control Node)
- **InventoryManager**：静的インベントリ（INI/YAML）、動的インベントリ（スクリプト / Python インベントリプラグイン）、およびインベントリ・ディレクトリの階層構造と変数を統合管理します。ブラケットを考慮した分割（Bracket-Aware Splitting）と範囲パターン展開（Range Pattern Expansion）によるホストパターン解決をサポートします。
- **Action Plugin**：管理ノード上で動作し、ファイル転送の準備やテンプレートのレンダリング、必要に応じてターゲットノードへのモジュール実行指示を行います。
- **Connection Plugin**：ターゲットノードとの通信を担当し、ファイルの転送やリモートコマンド実行を抽象化します（Local, SSH [多段 Bastion / Jump Host サポート含む], Docker, WinRM をサポート）。
- **Python Runtime (GraalPy)**：管理ノード側で Action Plugin や local 接続モジュールを実行するためのランタイム環境を提供します。

### 2.4 ターゲット実行層 (ターゲットノード / Target Node)
- **Ansible Module**：管理ノードから転送された Ansiballz パッケージをターゲット側の Python で実行します。

## 3. デザイン方針
- **OS Abstraction**：ファイル操作やプロセス実行は、Javaの `java.nio.file` や `ProcessBuilder` を活用し、特定のOS（Windows/Linux/macOS）に依存しない抽象化レイヤーを介して行います。
- **Native Image Ready**：リフレクションを最小限に抑え、GraalVM Native Image での高速な起動と低メモリフットプリントを実現します。
- **Immutability**：タスク定義やインベントリデータには `record` を活用し、並行実行時の安全性を確保します。
