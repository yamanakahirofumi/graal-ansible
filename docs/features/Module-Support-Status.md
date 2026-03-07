# モジュールサポート状態 (Module Support Status)

`graal-ansible` における各モジュールのテスト完了状態と、現在の動作確認状況のサマリーです。最終的な目標については [モジュール互換性の目標](Module-Compatibility.md) を参照してください。

## 1. テスト完了状態 (Verified Support Matrix)

下表は、自動テスト（ユニットテストおよび統合テスト）によって動作が確認されている状態を示します。

- **○**: テスト通過済み (Verified) - 実際のモジュールが GraalPy 上で期待通りに動作することを確認。
- **△**: ロード確認済み (Loaded) - モジュールのインポートと初期化は成功するが、詳細な機能テストは未実施。
- **？**: 検証予定 (Planned) - ロードまたは実行に課題がある、あるいは未着手。
- **-**: 対象外 (N/A) - 本家 Ansible がその OS をサポートしていない。

表記: **[管理ノード OS] : [ステータス]**

| コレクション / モジュール | Target: Linux | Target: macOS | Target: Windows | 備考 |
| :--- | :---: | :---: | :---: | :--- |
| **ansible.builtin** | L:△, M:？, W:？ | L:？, M:？, W:？ | L:？, M:？, W:？ | Linux 上で全 72 モジュールのロードを確認済。 |
| - `debug` | L:○, M:○, W:○ | L:○, M:○, W:○ | L:○, M:○, W:○ | Java 内部実装と Python 実装の両方でテスト済。 |
| - `command` | L:○, M:？, W:？ | L:？, M:○, W:？ | - | 外部プロセスの起動と結果取得を確認済。 |
| - `shell` | L:○, M:？, W:？ | L:？, M:○, W:？ | - | シェル経由のコマンド実行を確認済。 |
| - `ping` | L:○, M:？, W:？ | L:？, M:○, W:？ | - | 基本的な疎通確認テスト済。 |
| - `copy` | L:○, M:？, W:？ | L:？, M:○, W:？ | - | ファイル転送とパーミッションの検証済。 |
| - `file` | L:○, M:？, W:？ | L:？, M:○, W:？ | - | ファイル状態管理の検証済。 |
| - `template` | L:○, M:？, W:？ | L:？, M:○, W:？ | - | Jinja2 テンプレート展開の検証済。 |
| - `stat` | L:○, M:？, W:？ | L:？, M:○, W:？ | - | ファイル情報取得の検証済。 |
| **ansible.posix** | L:？, M:？, W:？ | L:？, M:？, W:？ | - | コレクション全体のロードを検証中。 |
| **ansible.utils** | L:？, M:？, W:？ | L:？, M:？, W:？ | L:？, M:？, W:？ | 基本的なフィルタの動作を確認中。 |
| **community.general** | L:？, M:？, W:？ | L:？, M:？, W:？ | × | 依存ライブラリの解決を順次実施中。 |

## 2. 自動テストの実施状況 (Automated Testing Status)

### 2.1 継続的インテグレーション (CI)
- **Linux (Ubuntu)**: GitHub Actions にて毎プッシュ時に全統合テストを実行。
- **macOS**: 基本的なエンジン動作テストを実施。
- **Windows**: `LocalConnection` および `debug` モジュールの動作を確認中。

### 2.2 テストカテゴリ
- **Module Load Test**: `ansible.builtin` の全モジュールがインポートエラーなく読み込めるかを確認（Linux では 100% 完了）。
- **Integration Test**: 実際の Playbook を使用して、ターゲットノードの状態が正しく変更されるかを検証。

## 3. 実装フェーズと進捗状況 (Implementation Phases)
100% の互換性と最適化を目指し、以下の 3 つのフェーズで開発を進めています。詳細は [コレクション実装ロードマップ](../implementation/Collection-Implementation-Roadmap.md) を参照してください。

- **フェーズ 1: Ansible 本体の完全ロードと基本動作の実現 (Completed)**
  - Linux/macOS 上での主要モジュールの動作確認。
- **フェーズ 2: ハイブリッド実装による Windows サポート (Current)**
  - Windows 管理ノード対応の強化と、OS 固有の問題解決。
- **フェーズ 3: Ansible 本体のロード排除と最適化 (Planned)**
  - 起動速度の向上と依存関係の最小化。

## 4. 関連ドキュメント
- [モジュール互換性の目標（最終系）](Module-Compatibility.md)
- [OS 抽象化レイヤーの仕様](../implementation/OS-Abstraction.md)
- [コレクション実装ロードマップ](../implementation/Collection-Implementation-Roadmap.md)
