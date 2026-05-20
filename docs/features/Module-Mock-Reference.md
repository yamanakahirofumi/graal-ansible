# モジュールとモックの対応リファレンス (Module & Mock Reference)

`graal-ansible` は、Ansible のオリジナル Python コードを GraalPy 上で実行するために、多層的なモック（依存関係のエミュレーション）を提供しています。本ドキュメントでは、各モジュールが実際に依存しているモック関数・コンポーネントを詳細な一覧（機能リファレンス）としてまとめます。

各モックの具体的な内部設計やデータ変換、OS 抽象化などの実装詳細については、[Mock 実装リファレンス (Technical Reference)](../implementation/Mock-Implementation-Reference.md) を参照してください。

## 1. モックアーキテクチャの概要

モックは以下の 3 つの主要コンポーネントで構成されています。

1.  **Bridge Mocks (Python)**: `ansible_bridge.py` で定義。Ansible 内部クラス (`Display`, `Templar`) や標準ライブラリ (`fcntl`, `grp`, `pwd`) の置換。
2.  **AnsibleModule Mocks (Java)**: `PythonAnsibleModuleMock.java` で定義。`AnsibleModule` クラスの主要メソッドの実装。
3.  **OS Mocks (Java)**: `PythonOSMock.java` で定義。`os` モジュールの低レベル関数を Java でエミュレート。

---

## 2. モジュール別・依存モック詳細一覧

各モジュールが動作するために最低限必要とする、または頻繁に利用するモックの詳細です。

| モジュール | Bridge Mocks (Python) | AnsibleModule Mocks (Java) | OS Mocks (Java) |
| :--- | :--- | :--- | :--- |
| **ping** | - | `exit_json` | - |
| **debug** | `Display` | `exit_json` | - |
| **fail** | - | `fail_json` | - |
| **assert** | `Templar` (条件評価) | `exit_json`, `fail_json` | - |
| **command / shell** | - | `run_command` | - |
| **file** | - | `exit_json`, `set_file_attributes_if_different`, `load_file_common_arguments` | `stat`, `exists`, `chown` |
| **copy** | `ActionBase` (Action型) | `atomic_move`, `sha1`, `get_file_attributes` | `stat`, `exists`, `makedirs` |
| **stat** | - | `exit_json`, `get_file_attributes` | `stat`, `exists` |
| **find** | - | `exit_json` | `stat`, `exists` |
| **tempfile** | - | `exit_json` | `makedirs` |
| **template** | `Templar` (展開), `ActionBase` | `run_command` (内部的) | `stat`, `exists`, `makedirs` |
| **lineinfile** | - | `run_command` (sed/grep等) | `stat`, `exists` |
| **replace** | - | `run_command` | `stat`, `exists` |
| **blockinfile** | - | `run_command` | `stat`, `exists` |
| **user** | `pwd`, `grp` | `run_command` (useradd/getent) | - |
| **group** | `grp` | `run_command` (groupadd/getent) | - |
| **getent** | - | `run_command` (getent エミュレーション) | - |
| **slurp** | - | `exit_json` | `stat`, `exists` |
| **uri / get_url** | `ActionBase` (一部) | `run_command` (curl/wget), `exit_json` | `stat`, `exists` |
| **fetch** | `ActionBase` | `exit_json` | `stat`, `exists` |
| **unarchive** | `ActionBase` | `run_command` (tar/unzip) | `stat`, `exists`, `makedirs` |
| **setup** | - | `exit_json` | `stat` (facts収集用) |
| **set_fact** | `Templar` | `exit_json` | - |
| **include_vars** | `MockLoader` (YAML) | `exit_json` | `exists` |
| **add_host** | - | `exit_json` (結果返却) | - |
| **group_by** | - | `exit_json` (結果返却) | - |
| **apt / package / package_facts** | - | `run_command` (apt-get等), `exit_json` | - |
| **apt_key** | - | `run_command` (apt-key), `exit_json` | - |
| **apt_repository** | - | `run_command` (apt-add-repository), `exit_json` | - |
| **service / systemd / systemd_service / service_facts** | - | `run_command` (systemctl等), `exit_json` | - |
| **assemble** | `codecs.escape_decode` | `run_command`, `exit_json` | - |
| **cron** | - | `run_command` (crontab), `exit_json` | - |
| **deb822_repository** | - | `run_command`, `exit_json` | - |
| **debconf** | - | `run_command` (debconf-set-selections), `exit_json` | - |
| **dpkg_selections** | - | `run_command` (dpkg), `exit_json` | - |
| **expect** | - | `run_command` (pexpect使用), `exit_json` | - |
| **git** | - | `run_command` (git), `exit_json` | - |
| **hostname** | - | `run_command` (hostnamectl), `exit_json` | - |
| **iptables** | - | `run_command` (iptables), `exit_json` | - |
| **known_hosts** | - | `run_command` (ssh-keygen), `exit_json` | - |
| **mount_facts** | - | `run_command` (mount/findmnt), `exit_json` | - |
| **pause** | - | `exit_json` | - |
| **pip** | - | `run_command` (pip), `exit_json` | - |
| **script** | - | `run_command`, `exit_json` | - |
| **subversion** | - | `run_command` (svn), `exit_json` | - |
| **sysvinit** | - | `run_command` (service), `exit_json` | - |
| **validate_argument_spec** | - | `exit_json` | - |
| **wait_for** | - | `exit_json` | - |
| **wait_for_connection** | `Proxy.reset`, `Proxy.get_option` | `exit_json` | - |
| **import_tasks / include_tasks** | - | `exit_json` | - |
| **gather_facts** | - | `exit_json` | `stat` |
| **set_stats** | - | `exit_json` | - |
| **raw** | - | - | - |

---

## 3. モックコンポーネントの機能詳細

### 3.1 Bridge Mocks (Python)
- **`Display`**: `v`, `vv`, `debug`, `warning` 等のメソッドを Java 出力へブリッジ。
- **`Templar`**: `template()`, `evaluate_conditional()` を提供。
- **`MockLoader`**: `get_text_file_contents()`, `load_from_file()` による Playbook パス解決。
- **`ActionBase`**: 管理ノード側ロジックを持つモジュール (`copy`, `template`, `fetch` 等) のための基底クラス。

### 3.2 AnsibleModule Mocks (Java)
- **`run_command(args)`**: ターゲットへの SSH コマンド実行。`getent` の特殊なエミュレーション（rootユーザー情報の返却等）を含む。
- **`atomic_move(src, dest)`**: `java.nio.file.Files.move` を使用したアトミックな書き換え。
- **`hashFile(path, alg)`**: `SHA-1`, `MD5`, `SHA-256` の計算。
- **`get_file_attributes(path)`**: モード、オーナー、グループ等のメタデータ取得。

### 3.3 OS Mocks (Java)
- **`stat(path)`**: `os.stat_result` と互換性のある構造体を返却。
- **`normalizePath(path)`**: Windows 形式 (`\`) と Linux 形式 (`/`) の相互変換、および `b'...'` リテラルの処理。
- **`makedirs(name, mode, exist_ok)`**: ディレクトリの再帰的作成。

---

## 4. 今後のモック拡張予定 (Roadmap)

現在、ロード確認済み (△) または検証予定 (？) のモジュールをサポートするために、以下のモック強化が計画されています。

- **パッケージ管理モック (`dnf`, `yum`)**:
    - 各パッケージマネージャーの Python ライブラリ呼び出しを Java 側のパッケージ管理サービスへ委譲。
- **Windows 固有モック**:
    - `win_` 系のモジュールが必要とする Windows レジストリや権限操作のエミュレーション。
- **Native Image 対応の強化**:
    - リフレクションを必要とするモックポイントの静的定義への移行。
