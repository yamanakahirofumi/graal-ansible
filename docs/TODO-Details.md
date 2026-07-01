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
- **詳細は [Module-Support-Status.md](features/Module-Support-Status.md) を参照。** (現在 65/72 モジュール検証済み: 61 ◎, 4 ○)
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

### 5.1 [ ] 最大失敗率 (max_fail_percentage) のサポート
- **概要**: プレイ内で失敗が許容されるホストの最大割合を定義する機能。
- **検討内容**:
    - `LinearStrategy` および `FreeStrategy` における失敗率の計算タイミング。
    - `serial` 実行時におけるバッチ単位またはプレイ全体での判定基準。
    - パーセンテージ計算時の端数処理（Ansible 互換）。

### 5.2 [ ] Python ベースのコールバックのサポート
- **概要**: GraalPy を利用して Ansible 本家の Python 製コールバックプラグインをそのまま実行する仕組みの導入。
- **検討内容**:
    - `ActionPlugin` と同様のブリッジメカニズム（`ansible_bridge.py`）の適用.
    - Python 側のイベントフックを Java 側の `Callback` インターフェースへマッピングする方法。

### 5.3 [ ] ストラテジ固有のコールバック最適化
- **概要**: `free` 戦略などにおいて、ホストごとに進捗が異なる場合の出力形式のさらなる最適化。
- **検討内容**:
    - ホストごとの並列実行状況をより分かりやすく表示するための `DefaultCallback` の拡張。
