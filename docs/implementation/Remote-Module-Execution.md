# リモートノードでのモジュール実行仕様

本ドキュメントでは、`graal-ansible` がターゲットノード（リモートホスト）に対して Ansible モジュールを実行する際の仕組みについて詳述します。

## 1. 実行モデル：制御ノード側実行とリモート委譲

Ansible 本家では、モジュール（Python スクリプト）をターゲットノードに転送し、ターゲット上の Python インタプリタで実行します。
対して `graal-ansible` では、**「モジュール本体は制御ノード上の GraalPy で実行し、システム操作のみをリモートに委譲する」**というモデルを採用しています。

### メリット
- ターゲットノードに Python インタプリタをインストールする必要がない。
- モジュールの転送・クリーンアップのオーバーヘッドがない。
- 制御ノード側の Java リソース（接続プール、変数管理）を直接利用できる。

### 仕組み
1. **Python 実行**: `PythonModule` が制御ノードの JVM 内で GraalPy を起動し、モジュールを実行。
2. **操作のインターセプト**: モジュールが `AnsibleModule.run_command` などのメソッドを呼び出した際、それをモンキーパッチにより捕捉。
3. **リモート実行**: 捕捉したコマンドを、Java 側の `Connection` オブジェクト（SSH 等）を経由してターゲットノードで実行。
4. **結果の還元**: コマンドの標準出力・標準エラー・終了コードを Python 側に返し、モジュールのロジックを継続。

## 2. Java と Python のブリッジ

`PythonModule.java` は実行時に以下のオブジェクトを Python コンテキストに注入します。

- `connection_java`: `org.example.ansible.connection.Connection` インターフェースの実装。
- `become_context_java`: 権限昇格設定（`BecomeContext`）。

これらは `ansible_launcher.py` 内で利用されます。

## 3. `ansible_launcher.py` によるモンキーパッチ

リモート実行を実現するため、`ansible_launcher.py` は `AnsibleModule` に対して以下のパッチを適用します。

### `run_command` の委譲
モジュールが外部コマンドを実行しようとすると、以下の関数が呼ばれます。

```python
def mocked_run_command(self, args, **kwargs):
    if connection_java:
        if isinstance(args, list):
            command = " ".join(args)
        else:
            command = args
        # Java 側の接続オブジェクトを使用してリモートで実行
        res = connection_java.execCommand(command, become_context_java)
        return (res.exitCode(), res.stdout(), res.stderr())
    return (0, '', '')
```

## 4. SSH 接続の実装 (`SshConnection`)

SSH 経由のリモート実行は `SshConnection.java` で実装されています。

- **ライブラリ**: Apache MINA SSHD を使用。
- **認証**: パスワード認証および公開鍵認証（基本実装）をサポート。
- **実行方式**: `ChannelExec` を使用してコマンドを非対話的に実行。
- **ファイル転送**: SCP（`ScpClient`）を使用して `putFile` / `fetchFile` を実現。

## 5. モジュールごとの対応状況と制限

このモデル（制御ノード側実行）には、モジュールの実装に依存した制限があります。

- **コマンドベースのモジュール**: `apt`, `yum`, `command`, `shell` など、主に外部コマンドを呼び出して動作するモジュールは、`run_command` がパッチされているため正しく動作します。
- **Python ネイティブ操作を行うモジュール**: Python の `os` や `shutil` モジュールを直接使用してファイル操作（`os.mkdir`, `os.chown` 等）を行う場合、それらは**制御ノード側に対して実行されてしまいます**。
- **対応策**:
    - 重要なモジュール（`file`, `copy`, `template` 等）については、モジュール内部で `run_command` を使用するように誘導するか、あるいは `os` モジュール自体をパッチすることでリモート操作に変換する検討が行われています。
    - 現在、`file` モジュールの `state=touch` など、一部の機能は `run_command` を介してリモートで動作することが確認されています。

## 6. 関連ドキュメント
- [GraalPy 統合の詳細](../tech/GraalPy-Integration.md)
- [接続プラグイン実装](Connection-Plugins.md)
- [Ansible モジュールの初期化と設定](Ansible-Module-Initialization.md)
