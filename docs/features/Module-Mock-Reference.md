# モジュールとモックの対応リファレンス (Module & Mock Reference)

`graal-ansible` は、Ansible のオリジナル Python コードを GraalPy 上で実行するために、多層的なモック（依存関係のエミュレーション）を提供しています。本ドキュメントでは、各モジュールが利用する主要なモックコンポーネントとその役割をまとめます。

## 1. モックアーキテクチャの概要

モックは大きく分けて、Python 側のブリッジ層 (`ansible_bridge.py`) と、Java 側のネイティブ実装層 (`PythonOSMock`, `PythonAnsibleModuleMock`) の 2 段階で構成されています。

### 1.1 Python 側のブリッジ (`ansible_bridge.py`)
Ansible が依存する外部ライブラリや内部モジュールを Python レベルで置換します。
- **システムモジュール置換**: `fcntl`, `resource`, `termios`, `syslog`, `grp`, `pwd` などのネイティブ拡張が必要な標準ライブラリをダミーまたは Java ブリッジに置換。
- **Ansible 内部クラスの置換**: `Display`, `Templar`, `MockLoader`, `MockShell` など、実行エンジンと密結合するクラスを軽量なモックに置換。
- **Action Plugin 基盤**: `ActionBase` を提供し、`_execute_module` を通じて Java 側の `TaskExecutor` を再帰的に呼び出す仕組みを構築。

### 1.2 Java 側のネイティブ実装
重厚な処理や OS 固有の操作を Java 側で安全かつ高速に実行します。
- **PythonOSMock**: `os` モジュールの低レベル関数 (`stat`, `exists`, `makedirs` 等) を Java の `java.nio.file` を用いて再実装。クロスプラットフォームなパス正規化も担当。
- **PythonAnsibleModuleMock**: `AnsibleModule` クラスの主要メソッド (`exit_json`, `fail_json`, `run_command`, `atomic_move` 等) を実装。

---

## 2. モジュールカテゴリ別のモック利用状況

| モジュールカテゴリ | 主要なモジュール | 利用される主要モック・エミュレーション |
| :--- | :--- | :--- |
| **ファイル操作** | `file`, `copy`, `stat`, `find`, `tempfile` | `PythonOSMock` (stat, exists), `AnsibleModule` (atomic_move, get_file_attributes) |
| **コマンド実行** | `command`, `shell` | `AnsibleModule.run_command` (Java 側の `Connection` インターフェース経由) |
| **システム管理** | `user`, `group`, `getent` | `grp`, `pwd` (Python 側モック), `getent` (Java 側の `run_command` 内でのエミュレーション) |
| **テンプレート・変数** | `template`, `set_fact`, `include_vars` | `Templar` (Jinja2 エミュレーション), `MockLoader` (YAML/ファイル読み込み) |
| **ネットワーク・通信** | `uri`, `get_url`, `ping` | `AnsibleModule.run_command` (curl/wget エミュレーション等), `Connection` プロキシ |
| **制御・デバッグ** | `debug`, `assert`, `fail` | `Display` (標準出力ブリッジ), `AnsibleModule.exit_json/fail_json` |

---

## 3. 主要なモックコンポーネントの詳細

### 3.1 AnsibleModule Mock (Java)
`ansible.module_utils.basic.AnsibleModule` の実体として機能します。
- **`run_command`**: ターゲットノードに対するコマンド実行を、Java 側の `Connection` オブジェクトへ委譲します。
- **`exit_json` / `fail_json`**: 実行結果を JSON 形式でシリアライズし、Java 側の実行エンジンへ返します。
- **`sha1` / `md5` / `sha256`**: ファイルのチェックサム計算を Java 側で実行します。

### 3.2 OS Mock (Java)
Python の `os` および `os.path` モジュールの一部の関数をオーバーライドします。
- **パス正規化**: Windows と Linux のパス区切り文字の差異を吸収します。
- **Stat 互換性**: `os.stat_result` と互換性のあるオブジェクトを Java 側で生成して返却します。

### 3.3 Bridge Mocks (Python)
Ansible のプラグインシステムやユーティリティをエミュレートします。
- **Templar**: Jinja2 テンプレートの評価を、環境に合わせて軽量に実行します。
- **Display**: Ansible の冗長度 (`-vvv` 等) に応じたログ出力を制御します。
- **ActionBase**: `copy` や `template` などのアクションプラグインが、管理ノード側で実行される際の基底クラスを提供します。
