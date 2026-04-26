# モジュールサポート状態 (Module Support Status)

`graal-ansible` における各モジュールのテスト完了状態と、現在の動作確認状況のサマリーです。最終的な目標については [モジュール互換性の目標](Module-Compatibility.md) を参照してください。

## 1. テスト完了状態 (Verified Support Matrix)

下表は、自動テスト（ユニットテストおよび統合テスト）によって動作が確認されている状態を示します。

- **◎**: 統合テスト通過済み (Integration Verified) - `ActualModuleIntegrationTest` によるコンテナ環境での動作確認済み。
- **●**: 異常系・Unicode 検証済み (Negative/Unicode Verified) - `NegativeIntegrationTest` や `UnicodeIntegrationTest` による検証済み。
- **○**: テスト通過済み (Verified) - 実際のモジュールが GraalPy 上で期待通りに動作することを確認。
- **△**: ロード確認済み (Loaded) - モジュールのインポート成功を確認（引数エラー等で停止するが、初期化は完了）。
- **？**: 検証予定 (Planned) - ロードまたは実行に課題がある、あるいは未着手。
- **-**: 対象外 (N/A) - 本家 Ansible がその OS をサポートしていない。
- **×**: 非対応 (Unsupported) - 現時点でサポートの予定なし。

| コレクション / モジュール | Target: Linux | Target: macOS | Target: Windows | 備考 |
| :--- | :---: | :---: | :---: | :--- |
| **ansible.builtin** | △ | ？ | ？ | Linux で全 72 モジュールを一覧化。うち 57 モジュールの動作確認済。 |
| - `debug` | ◎ | ○ | ○ | オリジナル Python ソースコードにより検証済。 |
| - `ping` | ◎ | ○ | - | オリジナル Python ソースコードにより検証済。 |
| - `copy` | ◎ | ○ | - | オリジナル Python ソースコードにより検証済。 |
| - `file` | ◎ | ○ | - | オリジナル Python ソースコードにより検証済。 |
| - `template` | ◎ | ○ | - | オリジナル Python ソースコードにより検証済。 |
| - `stat` | ◎ | ○ | - | オリジナル Python ソースコードにより検証済。 |
| - `command` | ◎ | ○ | - | オリジナル Python ソースコードにより検証済。 |
| - `shell` | ◎ | ○ | - | オリジナル Python ソースコードにより検証済。 |
| - `setup` | ◎ | ○ | - | オリジナル Python ソースコードにより検証済。 |
| - `lineinfile` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `replace` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `user` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `group` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `find` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `tempfile` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `hostname` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `slurp` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `set_fact` | ◎ | ○ | ○ | オリジナル Python ソースコードにより検証済。 |
| - `assert` | ◎ | ○ | ○ | オリジナル Python ソースコードにより検証済。 |
| - `fail` | ◎ | ○ | ○ | オリジナル Python ソースコードにより検証済。 |
| - `gather_facts` | ◎ | ○ | - | オリジナル Python ソースコードにより検証済。 |
| - `add_host` | ◎ | ？ | ？ | オリジナル Python ソースコードにより検証済。 |
| - `apt` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `apt_key` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `apt_repository` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `assemble` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `async_status` | △ | ？ | ？ | ロード確認済。 |
| - `async_wrapper` | △ | ？ | ？ | ロード確認済。 |
| - `blockinfile` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `cron` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `deb822_repository` | △ | ？ | - | ロード確認済。 |
| - `debconf` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `dnf` | △ | ？ | - | ロード確認済。現在の開発・検証環境（Debian系）では動作確認が不可能。 |
| - `dnf5` | △ | ？ | - | ロード確認済。現在の開発・検証環境（Debian系）では動作確認が不可能。 |
| - `dpkg_selections` | △ | ？ | - | ロード確認済。 |
| - `expect` | △ | ？ | - | ロード確認済。 |
| - `fetch` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `get_url` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `getent` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `git` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `group_by` | ◎ | ？ | ？ | オリジナル Python ソースコードにより検証済。 |
 | - `import_playbook` | ○ | ？ | ？ | Java エンジンによるエミュレーション実行により検証済。 |
 | - `import_role` | ○ | ？ | ？ | Java エンジンによるエミュレーション実行により検証済。 |
| - `import_tasks` | ◎ | ？ | ？ | オリジナル Python ソースコードにより検証済。 |
 | - `include_role` | ○ | ？ | ？ | Java エンジンによるエミュレーション実行により検証済。 |
| - `include_tasks` | ◎ | ？ | ？ | オリジナル Python ソースコードにより検証済。 |
| - `include_vars` | ◎ | ○ | ○ | オリジナル Python ソースコードにより検証済。 |
| - `iptables` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `known_hosts` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
 | - `meta` | ○ | ？ | ？ | Java エンジンによるエミュレーション実行（`flush_handlers`, `noop`等）により検証済。 |
| - `mount_facts` | △ | ？ | - | ロード確認済。 |
| - `package` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `package_facts` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `pause` | ◎ | ？ | ？ | オリジナル Python ソースコードにより検証済。 |
| - `pip` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `raw` | ◎ | ？ | ？ | オリジナル Python ソースコードにより検証済。 |
| - `reboot` | △ | ？ | ？ | ロード確認済。 |
| - `rpm_key` | △ | ？ | - | ロード確認済。現在の開発・検証環境（Debian系）では動作確認が不可能。 |
| - `script` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `service` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `service_facts` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `set_stats` | ◎ | ？ | ？ | オリジナル Python ソースコードにより検証済。 |
| - `subversion` | △ | ？ | - | ロード確認済。 |
| - `systemd` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `systemd_service` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `sysvinit` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `unarchive` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `uri` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `validate_argument_spec` | ◎ | ？ | ？ | オリジナル Python ソースコードにより検証済。 |
| - `wait_for` | ◎ | ？ | - | オリジナル Python ソースコードにより検証済。 |
| - `wait_for_connection` | △ | ？ | ？ | ロード確認済。 |
| - `yum_repository` | △ | ？ | - | ロード確認済。現在の開発・検証環境（Debian系）では動作確認が不可能。 |
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

- **フェーズ 1: Ansible 本体の完全ロードと基本動作の実現 (In Progress)**
  - Linux/macOS 上での主要モジュール（[Module-Compatibility.md](Module-Compatibility.md) の対象コレクションすべて）の動作確認。
  - **進捗**: 57 / 72 モジュールの動作確認済（うち 54 モジュールは統合テスト ◎、3 モジュールはエンジン検証 ○）。
  - **完了条件**:
    - ビルド時の `pip` による `ansible-core` 取得の自動化。
    - GraalPy へのライブラリパス統合。
    - 不足している Python 依存パッケージの特定。
    - タスク制御キーワード（`check_mode` 等）の基盤実装。
    - **GraalPy コンテキスト設定の固定**: `python.IsolateNativeModules` と `python.PosixModuleBackend` を変更せずに検証を完了させる。
    - **全モジュールの検証**: `ansible.builtin` に含まれる全モジュールの動作確認。
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

- **Coverage**: モジュールごとに「ロード済 (△)」「正常系検証済 (○)」「異常系・Unicode 検証済み (●)」「統合テスト通過済み (◎)」のステータスを `Module-Support-Status.md` で管理。

## 4. 関連ドキュメント
- [テストケース拡充戦略](../tech/Test-Expansion-Strategy.md)
- [モジュール互換性の目標（最終系）](Module-Compatibility.md)
- [OS 抽象化レイヤーの仕様](../implementation/OS-Abstraction.md)
- [コレクション実装ロードマップ](../implementation/Collection-Implementation-Roadmap.md)
