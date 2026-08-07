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
- **PlaybookExecutor (PE)**：Playbook 全体の実行フローを管理します。Play, Block, Task の階層構造を辿ります。
- **TaskQueueManager (TQM)**：各ホストへのタスク配信や結果の集計を制御します（将来的な拡張ポイント）。
- **TaskExecutor (Worker Process)**：個別のタスク実行を担当します。変数の解決 (Jinja2) やプラグインの呼び出しを行います。
- **VariableManager**：変数のスコープ管理と、Jinja2ライクなテンプレート展開を担当します。

### 2.3 Provider / Plugin 層 (管理ノード / Control Node)
- **InventoryManager**：ホスト情報およびグループ変数を管理します。
- **Action Plugin**：管理ノード上で動作し、ファイル転送の準備やテンプレートのレンダリング、必要に応じてターゲットノードへのモジュール実行指示を行います。
- **Connection Plugin**：ターゲットノードとの通信を担当し、ファイルの転送やリモートコマンド実行を抽象化します（Local, SSH, Docker, WinRM をサポート）。
- **Python Runtime (GraalPy)**：管理ノード側で Action Plugin や local 接続モジュールを実行するためのランタイム環境を提供します。

### 2.4 ターゲット実行層 (ターゲットノード / Target Node)
- **Ansible Module**：管理ノードから転送された Ansiballz パッケージをターゲット側の Python で実行します。

## 3. デザイン方針
- **OS Abstraction**：ファイル操作やプロセス実行は、Javaの `java.nio.file` や `ProcessBuilder` を活用し、特定のOS（Windows/Linux/macOS）に依存しない抽象化レイヤーを介して行います。
- **Native Image Ready**：リフレクションを最小限に抑え、GraalVM Native Image での高速な起動と低メモリフットプリントを実現します。
- **Immutability**：タスク定義やインベントリデータには `record` を活用し、並行実行時の安全性を確保します。
