# モジュールサポート状態 (Module Support Status)

`graal-ansible` における各 OS ごとのコレクションおよびモジュールのサポート状況のサマリーです。

## 1. サポート状況一覧 (Support Status Matrix)

下表の記号は以下の意味を表します。
- **○**: サポート済み (Supported / Verified)
- **？**: 計画中 または 動作未確認 (Planned / Experimental / Unverified)
- **×**: 未サポート (Not Supported)
- **-**: 対象 OS 自体が本家 Ansible のサポート対象外 (Not Supported by original Ansible)

また、各セル内での表記は **[実行環境 (Control Node)] : [サポート状況]** を表します。
- **L**: Linux
- **M**: macOS
- **W**: Windows
(例: `L:○, W:？` は Linux 実行環境ではサポート済み、Windows 実行環境では動作未確認を意味します)

| コレクション / モジュール | Target: Linux | Target: macOS | Target: Windows | 備考 |
| :--- | :---: | :---: | :---: | :--- |
| **ansible.builtin** (Collection) | L:○, M:？, W:？ | L:？, M:？, W:？ | L:？, M:？, W:？ | コアコレクション。Linux 実行環境では全 72 モジュールのロードを確認済。 |
| - `debug` | L:○, M:？, W:？ | L:？, M:○, W:？ | L:？, M:？, W:○ | Java による内部実装および Python 実装の両方に対応。 |
| - `command` | L:○, M:？, W:？ | L:？, M:○, W:？ | - | ロード確認済。本家は Windows 非対応。本プロジェクトでは OS 抽象化レイヤーで限定的に動作可。 |
| - `shell` | L:○, M:？, W:？ | L:？, M:○, W:？ | - | ロード確認済。本家は Windows 非対応。本プロジェクトでは OS 抽象化レイヤーで限定的に動作可。 |
| - `ping` | L:○, M:？, W:？ | L:？, M:○, W:？ | - | 動作確認済。本家は Windows 非対応 (`ansible.windows.win_ping` を推奨)。 |
| - `copy` | L:○, M:？, W:？ | L:？, M:○, W:？ | - | 動作確認済。本家は Windows 非対応 (`ansible.windows.win_copy` を推奨)。 |
| - `file` | L:○, M:？, W:？ | L:？, M:○, W:？ | - | 動作確認済。本家は Windows 非対応 (`ansible.windows.win_file` を推奨)。 |
| - `template` | L:○, M:？, W:？ | L:？, M:○, W:？ | - | 動作確認済。本家は Windows 非対応 (`ansible.windows.win_template` を推奨)。 |
| - `stat` | L:○, M:？, W:？ | L:？, M:○, W:？ | - | 動作確認済。本家は Windows 非対応 (`ansible.windows.win_stat` を推奨)。 |
| **ansible.posix** | L:？, M:？, W:？ | L:？, M:？, W:？ | - | POSIX 固有の機能を提供するコレクション。 |
| **ansible.utils** | L:？, M:？, W:？ | L:？, M:？, W:？ | L:？, M:？, W:？ | ユーティリティ機能を提供するコレクション。 |
| **community.general** | L:？, M:？, W:？ | L:？, M:？, W:？ | × | 多くのサードパーティ製モジュールを含む汎用コレクション。 |

## 2. 自動テストのスコープ (Automated Testing Scope)

各OSにおける自動テストの実施方針は以下の通りです。

- **管理ノード (Control Node)**: すべてのサポートOS（Linux, macOS, Windows）において、ビルドおよびコア機能のテストを CI で実施します。
- **ターゲットノード (Target Node / Modules)**: 各モジュールが本来サポートしている OS 環境においてのみ、結合テストを実施します。
  - 例: `ansible.builtin.copy` の結合テストは Linux/macOS でのみ実行し、Windows ではスキップされます。
  - Windows の管理ノードとしての動作は、OS 抽象化レイヤーやモックを用いたユニットテストで保証します。

## 3. 実装フェーズとの関係

本プロジェクトの [コレクション実装ロードマップ](../implementation/Collection-Implementation-Roadmap.md) に基づき、サポート範囲を順次拡大しています。

- **Phase 1**: Linux/macOS における `ansible-core` (ansible.builtin) の動作を優先。
- **Phase 2**: Windows 環境における Python モジュールの互換性向上（現在進行中）。
- **Phase 3**: 依存ライブラリの最小化と Native Image 最適化。

## 4. 関連ドキュメント
- [モジュール互換性](Module-Compatibility.md)
- [OS 抽象化レイヤーの仕様](../implementation/OS-Abstraction.md)
- [コレクション実装ロードマップ](../implementation/Collection-Implementation-Roadmap.md)
