# Ansible モジュールの初期化と設定

本ドキュメントでは、`graal-ansible` が Ansible の Python モジュールを実行する際、どのように引数を渡し、`AnsibleModule` インスタンスを構成しているかについて詳述します。

## 1. 概要

Ansible モジュールは通常、標準入力経由で引数（JSON または key=value 形式）を受け取りますが、`graal-ansible` では GraalPy の Polyglot API を利用して、Java 側から Python コンテキストへ直接データを注入します。これにより、外部プロセスの起動オーバーヘッドを削減し、Java 側の接続オブジェクトなどを Python 側から直接利用することを可能にしています。

## 2. Java から Python へのデータ受け渡し

`PythonModule.java` において、以下のデータが GraalVM Context の bindings にセットされます。

| 変数名 | 型 (Java) | 説明 |
| :--- | :--- | :--- |
| `complex_args_java` | `Map<String, Object>` | モジュールに渡される引数。 |
| `module_name` | `String` | 実行対象のモジュール名（例: `ping`, `copy`）。 |
| `site_packages_java` | `List<String>` | `ansible-core` 等がインストールされているパス。 |
| `connection_java` | `Connection` | 現在のターゲットへの接続オブジェクト。 |
| `become_context_java`| `BecomeContext` | 権限昇格の設定。 |

## 3. `ansible_launcher.py` による初期化フロー

モジュール本体を実行する前に、`ansible_launcher.py` が実行環境の動的な構築（モンキーパッチ）を行います。

1.  **`sys.path` の設定**: `site_packages_java` を `sys.path` に追加し、Ansible ライブラリをロード可能にします。
2.  **引数の変換**: `complex_args_java` を Python の辞書型 `complex_args` に変換します。
3.  **システムモジュールのモック**: GraalPy 環境で問題となるモジュールや、Java 側で処理を代替したいモジュールをモック化します。
4.  **`AnsibleModule` のパッチ**: `ansible.module_utils.basic.AnsibleModule` クラスのメソッドを上書きし、本プロジェクト独自の実行モデルに適合させます。

## 4. グローバルスコープ (`__main__`) への属性注入

Ansible の一部のモジュール（`apt`, `package_facts` など）は、メタデータや引数を取得するために自分自身（`__main__`）をインポートしたり、特定のグローバル変数が存在することを期待したりします。

`ansible_launcher.py` では、モジュールの実行直前に `sys.modules['__main__']` に対して以下の属性を注入します。

| 属性名 | 説明 |
| :--- | :--- |
| `_module_fqn` | モジュールの完全修飾名（例: `ansible.builtin.ping`）。 |
| `complex_args` | 変換済みの引数辞書。 |
| `_modlib_path` | モジュールライブラリのパス（本プロジェクトでは原則 `None`）。 |

また、`exec()` を用いてモジュールコードを実行する際、以下の設定を行っています。

- **`__name__` の設定**: グローバル辞書の `__name__` を `'__main__'` に設定することで、多くのモジュールに含まれる `if __name__ == '__main__':` ブロックが正しく実行されるようにしています。
- **`module` インスタンスの参照**: 通常、Ansible モジュール内では `module = AnsibleModule(...)` のようにインスタンス化が行われます。この `module` 変数は通常そのスコープ（`main()` 関数内など）のローカル変数ですが、一部の共通コード（`module_utils`）がグローバルの `module` インスタンスを直接参照しようとするケースに備え、環境を構築しています。

## 5. `AnsibleModule` へのモンキーパッチ

Ansible モジュールの基盤となる `AnsibleModule` クラスに対して、以下のパッチを適用しています。

### 5.1 引数のロード (`_load_params`)
通常は標準入力から読み取る引数を、Java から渡された `complex_args` を直接使用するように変更します。
```python
ansible.module_utils.basic.AnsibleModule._load_params = lambda self: (complex_args, 'main')
```

### 5.2 コマンド実行 (`run_command`)
モジュール内でのコマンド実行を、Java 側の `Connection` オブジェクトを経由するように変更します。これにより、SSH 経由の実行などが透過的に行われます。
```python
def mocked_run_command(self, args, **kwargs):
    # Java の Connection.execCommand を呼び出す
    res = connection_java.execCommand(command, become_context_java)
    return (res.exitCode(), res.stdout(), res.stderr())
```

### 5.3 バイナリパスの検索 (`get_bin_path`)
システム探索を避け、パフォーマンスと安全性のために `/usr/bin/` 以下のパスを優先的に返すように固定します。

### 5.4 結果の記録 (`_record_module_result`)
モジュールの実行結果（辞書型）を JSON シリアライズし、Java 側がキャプチャ可能な形式で出力（標準出力または内部変数への代入）します。

## 6. システムモジュールのモック

GraalPy の制限や、Ansible 内部の複雑な依存関係を回避するため、以下のモジュールをモックに差し替えています。

-   **`ansible.utils.display`**: `Display` クラスをスタブ化し、不必要な依存関係や表示処理を抑制します。
-   **`grp`, `pwd`**: POSIX ユーザー/グループ情報を返すスタブを `sys.modules` に注入します。
-   **`termios`**: 端末制御関連の定数とメソッドをスタブ化します。
-   **`cryptography`, `selinux`**: ネイティブ依存が強い、あるいは不要なモジュールを `None` に設定し、インポートエラーを制御します。

## 7. チェックモードの制御

チェックモード（ドライラン）の動作は、Playbook レベルおよびタスクレベルの設定に基づき、`PlaybookExecutor` によって決定されます。

-   **フラグの注入**: チェックモードが有効な場合、`_ansible_check_mode: true` という引数が `complex_args` に注入されます。
-   **モジュールの動作**: `AnsibleModule` インスタンスは、自身に渡された `_ansible_check_mode` 引数を参照し、実際の変更を伴う処理をスキップするかどうかを判断します（これは Ansible 本来の動作と同じです）。
-   **一貫性の維持**: Java 側でチェックモードの判定（テンプレート評価等）を完結させ、Python 側には最終的な真偽値のみを渡すことで、複雑な条件分岐の二重実装を防いでいます。

## 8. 関連ドキュメント
- [タスク実行エンジン](Task-Executor.md)
- [タスク制御の実装詳細](Task-Control.md)
- [GraalPy 統合の詳細](../tech/GraalPy-Integration.md)
