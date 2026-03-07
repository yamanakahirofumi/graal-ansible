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
| - `template` | ？ | ？ | - | 本家は Windows 非対応 (`ansible.windows.win_template` を推奨)。 |
| - `stat` | ？ | ？ | - | 本家は Windows 非対応 (`ansible.windows.win_stat` を推奨)。 |
| **community.general** | ？ | ？ | × | 今後の拡張対象。 |

## 2. 実装フェーズとの関係

本プロジェクトの [コレクション実装ロードマップ](../implementation/Collection-Implementation-Roadmap.md) に基づき、サポート範囲を順次拡大しています。

- **Phase 1**: Linux/macOS における `ansible-core` (ansible.builtin) の動作を優先。
- **Phase 2**: Windows 環境における Python モジュールの互換性向上（現在進行中）。
- **Phase 3**: 依存ライブラリの最小化と Native Image 最適化。

## 3. 関連ドキュメント
- [モジュール互換性](Module-Compatibility.md)
- [OS 抽象化レイヤーの仕様](../implementation/OS-Abstraction.md)
- [コレクション実装ロードマップ](../implementation/Collection-Implementation-Roadmap.md)
