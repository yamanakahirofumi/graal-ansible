# TODOリスト（検討事項）

本プロジェクトにおける、機能追加、技術的課題、未確定事項の一覧です。
**※本ファイルには、設計面（アーキテクチャ、アルゴリズム、データ構造、インターフェース定義など）に関する事項のみを記載してください。**

## 1. 技術面 (Technical)

### 1.1 [ ] CI における Native Image ビルドの安定化
- **概要**: 優先度低。GitHub Actions 上での Native Image コンパイル時間の短縮とリソース最適化。
- **検討内容**:
    - Build Cache の有効活用。
    - Windows/macOS 環境でのビルドエラーの監視と修正。


## 2. 実装時の詳細事項

### 2.1 [ ] Ansible 本体の完全ロードと基本動作の実現 (フェーズ1)
- **概要**: `ansible-core` を完全にロードし、Linux/macOS での全 72 モジュールの動作確認。
- **詳細は [Module-Support-Status.md](features/Module-Support-Status.md) を参照。** (現在 67/72 モジュール検証済み: 61 ◎, 4 ○, 2 ●)
- **テスト拡充戦略については [Test-Expansion-Strategy.md](tech/Test-Expansion-Strategy.md) を参照。**
- **注意**: `python.IsolateNativeModules` と `python.PosixModuleBackend` はフェーズ 1 においては原則として固定（Linuxでは安定のため True/Native）とする。
- **備考**: 検証には必要に応じて **Testcontainers** を**ターゲットノード**として活用する。全モジュールの検証完了をもってフェーズ 1 完了とする。

### 2.2 [ ] ハイブリッド実装による Windows サポート (フェーズ2)
- **概要**: ハイブリッド実装（モンキーパッチ等）による Windows サポート。
- **詳細は [Module-Support-Status.md](features/Module-Support-Status.md) を参照。**

### 2.3 [ ] Ansible 本体のロード排除と最適化 (フェーズ3)
- **概要**: `ansible-core` 全体のロードを排除し、最適化と安定化を図る。
- **詳細は [Module-Support-Status.md](features/Module-Support-Status.md) を参照。**

## 3. 完了済みの項目 (Completed)

完了済みの項目、および整理・調整済みの履歴については、[Completed-Details.md](Completed-Details.md) を参照してください。

## 4. 今後のリファクタリング検討事項 (Future Refactoring Items)

### 4.1 [ ] PlaybookExecutor および実行エンジンのさらなる整理
- **概要**: `PlaybookExecutor` および `TaskQueueManager`, `TaskExecutor` 内に存在する課題の継続的な改善。
- **検討内容**:
    - 実行結果レポートのさらなる詳細化とフィルタリング機能。

## 5. 今後の設計・拡張事項 (Future Design and Extensions)

### 5.1 [ ] コネクション・プラグインの拡充 (WinRM)
- **概要**: SSH/Local/Docker 以外の接続プロトコルのサポート。
- **検討内容**:
    - **WinRM**: `winrm4j` 等を利用した Windows ターゲットノードへの接続。詳細な設計仕様については、[コネクションプラグインの設計仕様](implementation/Connection-Plugins.md#10-winrm-コネクションの詳細設計) を参照。
