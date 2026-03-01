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
│   │   │       ├── connection/       # 接続プラグイン (Local, SSH)
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

### 2.2 Execution Engine 層
- **PlaybookExecutor**：Playbook 全体の実行フローを制御します。
- **TaskExecutor**：個別のタスクを実行し、結果（changed, ok, failed等）を収集します。
- **VariableManager**：変数のスコープ管理と、Jinja2ライクなテンプレート展開を担当します。

### 2.3 Provider / Plugin 層
- **InventoryManager**：ホスト情報およびグループ変数を管理します。
- **ModuleExecutor**：本家 Ansible の Python モジュールを GraalPy を介して実行します。実際のコレクションやモジュールを直接利用することで、高い互換性を確保します。
- **Python Runtime (GraalPy)**：既存の Python 製 Ansible モジュールを実行するためのランタイム環境を提供します。
- **ConnectionPlugin**：対象ホストへの接続（Local, SSH等）を抽象化します。

## 3. デザイン方針
- **OS Abstraction**：ファイル操作やプロセス実行は、Javaの `java.nio.file` や `ProcessBuilder` を活用し、特定のOS（Windows/Linux/macOS）に依存しない抽象化レイヤーを介して行います。
- **Native Image Ready**：リフレクションを最小限に抑え、GraalVM Native Image での高速な起動と低メモリフットプリントを実現します。
- **Immutability**：タスク定義やインベントリデータには `record` を活用し、並行実行時の安全性を確保します。
