# モジュールサポート状態 (Module Support Status)

`graal-ansible` における各モジュールのテスト完了状態と、現在の動作確認状況のサマリーです。最終的な目標については [モジュール互換性の目標](Module-Compatibility.md) を参照してください。

## 1. テスト完了状態 (Verified Support Matrix)

下表は、自動テスト（ユニットテストおよび統合テスト）によって動作が確認されている状態を示します。

- **○**: テスト通過済み (Verified) - 実際のモジュールが GraalPy 上で期待通りに動作することを確認。
- **△**: ロード確認済み (Loaded) - モジュールのインポート成功を確認（引数エラー等で停止するが、初期化は完了）。
- **？**: 検証予定 (Planned) - ロードまたは実行に課題がある、あるいは未着手。
- **-**: 対象外 (N/A) - 本家 Ansible がその OS をサポートしていない。
- **×**: 非対応 (Unsupported) - 現時点でサポートの予定なし。

| コレクション / モジュール | Target: Linux | Target: macOS | Target: Windows | 備考 |
| :--- | :---: | :---: | :---: | :--- |
| **ansible.builtin** | △ | ？ | ？ | Linux で 34/72 モジュール (実行成功 6, ロード成功 28) を確認済。 |
| - `debug` | ○ | ○ | ○ | Java 内部実装と Python 実装の両方でテスト済。 |
| - `ping` | ○ | ○ | - | 基本的な疎通確認テスト済。 |
| - `copy` | ○ | ○ | - | ファイル転送とパーミッションの検証済。 |
| - `file` | ○ | ○ | - | ファイル状態管理の検証済。 |
| - `template` | ○ | ○ | - | Jinja2 テンプレート展開の検証済。 |
| - `stat` | ○ | ○ | - | ファイル情報取得の検証済。 |
| - `command` | ○ | ○ | - | 高速な Java 実装により検証済（※暫定実装）。 |
| - `shell` | ○ | ○ | - | 高速な Java 実装により検証済（※暫定実装）。 |
| - `setup` | ○ | ○ | - | 高速な Java 実装により検証済。 |
| - `lineinfile` | △ | △ | - | ロード確認済。検証を優先的に進める予定。 |
| **ansible.posix** | ？ | ？ | - | コレクション全体のロードを検証中。 |
| **ansible.utils** | ？ | ？ | ？ | 基本的なフィルタの動作を確認中。 |
| **community.general** | ？ | ？ | × | 依存ライブラリの解決を順次実施中。 |

## 2. 自動テストの実施状況 (Automated Testing Status)

### 2.1 継続的インテグレーション (CI)
- **Linux (Ubuntu)**: GitHub Actions にて毎プッシュ時に全統合テストを実行。
- **macOS**: 基本的なエンジン動作テストを実施。
- **Windows**: `LocalConnection` および `debug` モジュールの動作を確認中。

### 2.2 テストカテゴリ
- **Module Load Test**: `ansible.builtin` の各モジュールがインポートエラーなく読み込めるかを確認（Linux では約 50% のロードを確認済。依存関係を順次解決中）。
- **Integration Test**: 実際の Playbook を使用して、ターゲットノードの状態が正しく変更されるかを検証。必要に応じて **Testcontainers** を**ターゲットノード**として用いる。

## 3. 実装フェーズと進捗状況 (Implementation Phases)
100% の互換性と最適化を目指し、以下の 3 つのフェーズで開発を進めています。詳細は [コレクション実装ロードマップ](../implementation/Collection-Implementation-Roadmap.md) を参照してください。

- **フェーズ 1: Ansible 本体の完全ロードと基本動作の実現 (Current)**
  - Linux/macOS 上での主要モジュール（[Module-Compatibility.md](Module-Compatibility.md) の対象コレクションすべて）の動作確認。
  - **完了条件**:
    - ビルド時の `pip` による `ansible-core` 取得の自動化。
    - GraalPy へのライブラリパス統合。
    - 不足している Python 依存パッケージの特定。
    - タスク制御キーワード（`check_mode` 等）の基盤実装。
    - **GraalPy コンテキスト設定の固定**: `python.IsolateNativeModules` と `python.PosixModuleBackend` を変更せずに検証を完了させる。
- **フェーズ 2: ハイブリッド実装による Windows サポート (Planned)**
  - Windows 管理ノード対応の強化と、OS 固有の問題解決。
  - **検討内容**:
    - Windows で動作を阻害する Python モジュールの特定とモンキーパッチ。
    - `OSHandler` と連携した、Windows 特有のパス処理やシェル実行の調整。
- **フェーズ 3: Ansible 本体のロード排除と最適化 (Planned)**
  - 起動速度の向上と依存関係の最小化。
  - **検討内容**:
    - 必要なライブラリ（`module_utils` のコア部分など）の依存関係解析とバンドル化。
    - 実行エンジンの最適化。
    - 起動スピードの高速化と Native Image のビルド安定化。

- **Coverage**: モジュールごとに「ロード済 (△)」「正常系検証済 (○)」「異常系検証済 (◎)」のステータスを `Module-Support-Status.md` で管理。

## 4. 関連ドキュメント
- [テストケース拡充戦略](../tech/Test-Expansion-Strategy.md)
- [モジュール互換性の目標（最終系）](Module-Compatibility.md)
- [OS 抽象化レイヤーの仕様](../implementation/OS-Abstraction.md)
- [コレクション実装ロードマップ](../implementation/Collection-Implementation-Roadmap.md)
