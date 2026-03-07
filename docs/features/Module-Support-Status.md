# モジュールサポート状態 (Module Support Status)

`graal-ansible` における各 OS ごとのコレクションおよびモジュールのサポート状況のサマリーです。

## 1. サポート状況一覧

下表の記号は以下の意味を表します。
- **○**: サポート済み (Supported / Verified)
- **？**: 計画中 または 動作未確認 (Planned / Experimental / Unverified)
- **×**: 未サポート (Not Supported)
- **-**: 対象 OS 自体が本家 Ansible のサポート対象外 (Not Supported by original Ansible)

| コレクション / モジュール | Linux | macOS | Windows | 備考 |
| :--- | :---: | :---: | :---: | :--- |
| **ansible.builtin** (Collection) | ○ | ○ | ？ | コアコレクション。Linux/macOS は Phase 1 で対応済。 |
| - `debug` | ○ | ○ | ○ | Java による内部実装。 |
| - `command` | ○ | ○ | - | 本家は Windows 非対応。本プロジェクトでは OS 抽象化レイヤーで限定的に動作可。 |
| - `shell` | ○ | ○ | - | 本家は Windows 非対応。本プロジェクトでは OS 抽象化レイヤーで限定的に動作可。 |
| - `ping` | ○ | ○ | - | 本家は Windows 非対応 (`ansible.windows.win_ping` を推奨)。 |
| - `copy` | ○ | ○ | - | 本家は Windows 非対応 (`ansible.windows.win_copy` を推奨)。 |
| - `file` | ○ | ○ | - | 本家は Windows 非対応 (`ansible.windows.win_file` を推奨)。 |
| - `template` | ○ | ○ | - | 本家は Windows 非対応 (`ansible.windows.win_template` を推奨)。 |
| - `stat` | ○ | ○ | - | 本家は Windows 非対応 (`ansible.windows.win_stat` を推奨)。 |
| **ansible.posix** | ○ | ○ | - | POSIX 固有の機能を提供するコレクション。 |
| **ansible.utils** | ○ | ○ | ○ | ユーティリティ機能を提供するコレクション。 |
| **community.general** | ○ | ○ | × | 多くのサードパーティ製モジュールを含む汎用コレクション。 |

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
