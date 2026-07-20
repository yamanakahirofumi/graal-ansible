# コネクションプラグインの設計仕様

`graal-ansible` におけるターゲットノードへの接続方法と、それを抽象化するコネクションプラグインの仕様を定義します。

## 1. 概要

Ansible と同様に、ターゲットノードに対する実行環境（ローカル実行、SSH経由のリモート実行など）をプラグイン形式で切り替え可能にします。Java での実装を前提とし、GraalVM Native Image での動作を最適化します。

## 2. サポートされているコネクションタイプ

| タイプ | 状態 | 説明 | 実装ライブラリ / 手法 |
| :--- | :--- | :--- | :--- |
| `ssh` | **実装済** | 標準的なリモート接続 (OpenSSH 互換) | [Apache MINA SSHD](https://mina.apache.org/sshd-project/) |
| `local` | **実装済** | 管理ノード（制御ノード）自身での実行 | `java.lang.ProcessBuilder` |
| `docker` | **実装済** | 稼働中の Docker コンテナ内での実行 | Docker CLI (`docker exec`, `docker cp`) |
| `winrm` | 未実装 | Windows ターゲットノードへの接続 | [WinRM4J](https://github.com/CloudBees-Community/winrm4j) |

## 3. インターフェース定義

すべてのコネクションプラグインは、以下の主要なメソッドを持つ共通インターフェースを実装します。

### 3.1 主要メソッド

- `connect()`: ターゲットノードへの接続を確立。
- `exec_command(command, become_context=null, environment=null)`: 指定されたコマンドを実行し、標準出力・標準エラー・終了コードを返す。
    - `become_context`: 権限昇格の情報。詳細は [権限昇格 (become)](Privilege-Escalation.md) を参照。
    - `environment`: タスク固有の環境変数 (`Map<String, String>`)。評価済みの値が渡されます。
- `put_file(local_path, remote_path)`: ファイルをターゲットノードへ転送。
- `fetch_file(remote_path, local_path)`: ファイルをターゲットノードから取得。
- `close()`: 接続を終了。

## 4. コネクション管理と委譲 (`delegate_to`)

`PlaybookExecutor` は、タスクの実行対象に応じて適切なコネクションプラグインのインスタンスを管理します。

- **デフォルト接続**: インベントリで定義されたターゲットノードに対して接続を確立します。
- **委譲 (delegate_to)**:
    - `delegate_to` キーが指定されている場合、`PlaybookExecutor` は委譲先となるターゲットノード用のコネクション（通常は `LocalConnection` または別のホストへの SSH）を新たに取得または作成します。
    - タスクの実行が完了するまで、この委譲先コネクションが使用されます。
- **コネクションの再利用**:
    - 同一プレイ内で同じターゲットノードに対する接続は、可能な限り再利用（キャッシュ）し、オーバーヘッドを削減します。

## 5. SSH コネクションの詳細設計

### 5.1 ライブラリ選定
GraalVM Native Image との相性を考慮し、純粋な Java 実装である **Apache MINA SSHD** を第一候補とします。外部の `ssh` バイナリに依存しないことで、配布サイズとポータビリティを向上させます。

### 5.2 認証方式
以下の認証方式をサポートします。
- 公開鍵認証 (`~/.ssh/id_rsa` 等、および `ssh-agent`)
- パスワード認証 (インタラクティブな入力または変数経由)

## 6. ローカルコネクションの詳細設計

管理ノード（制御ノード）上で直接コマンドを実行します。
- `sudo` が指定された場合、`sudo -n` (non-interactive) を付与して実行します。
- `exec_command` で渡された `environment` マップを、`ProcessBuilder` の `environment()` にマージして実行します。

## 7. 実装上の注意

- **タイムアウト管理**: 接続およびコマンド実行に対して、Ansible 互換のタイムアウト設定を適用可能にします。
- **リソース解放**: 実行完了後（またはエラー発生時）に確実に接続をクローズする仕組み（Try-with-resources 等）を徹底します。
- **Native Image 対応**: SSH ライブラリが使用する暗号化アルゴリズムのリフレクション/JNI設定を `reflect-config.json` 等に含める必要があります。

## 8. コネクションファクトリと解決ロジック

インベントリ変数（`ansible_connection` 等）に基づき動的に `Connection` インスタンスを生成するため、`ConnectionFactory` を導入しています。

### 8.1 ConnectionFactory インターフェース

```java
public interface ConnectionFactory {
    /**
     * ホストと変数セットに基づき、適切な Connection インスタンスを生成または取得します。
     * @param host ターゲットホスト
     * @param variables 解決済みの変数マップ (ansible_connection 等を含む)
     * @return Connection インターフェースの実装体
     */
    Connection createConnection(Host host, Map<String, Object> variables);
}
```

### 8.2 解決に使用する主要な変数

コネクションの決定には、以下の変数の優先順位（Host Vars > Group Vars > All Vars）を考慮して解決された値を使用します。

| 変数名 | 用途 | デフォルト値 |
| :--- | :--- | :--- |
| `ansible_connection` | 使用するプラグイン名 (`local`, `ssh`, `smart` 等) | `smart` (または `ssh`) |
| `ansible_host` | 実際の接続先ホスト名または IP アドレス | (インベントリのホスト名) |
| `ansible_port` | 接続ポート番号 | `22` (ssh の場合) |
| `ansible_user` | 接続ユーザー名 | (現在の実行ユーザー) |
| `ansible_password` | 接続パスワード | なし |
| `ansible_ssh_private_key_file`| 認証用秘密鍵パス | なし |

### 8.3 解決フロー

1. **変数の集約**: `VariableManager` を用いて、対象ホストの `ansible_connection` 等の変数を解決します。
2. **プラグインの特定**: `ansible_connection` の値に基づき、対応する `Connection` 実装クラス（`LocalConnection`, `SshConnection` 等）を選択します。
3. **インスタンス化とパラメータ注入**: 解決された接続情報（ホスト、ポート、ユーザー等）をインスタンスに注入します。
4. **接続のキャッシュ**: 同一ホスト・同一パラメータのコネクションは `TaskQueueManager` レベルで保持（Map等）し、プレイ内のタスク間で再利用することでオーバーヘッドを削減します。

## 9. Docker コネクションの詳細設計

`DockerConnection` は、すでに稼働している Docker コンテナ内でタスクを実行するためのコネクションプラグインです。コンテナオーケストレーションやローカル検証環境でのテストなどで利用されます。

### 9.1 CLI インタラクション

Docker デーモンや API ライブラリへの直接的な依存を避け、ポータビリティと互換性を最大化するため、システムにインストールされている **Docker CLI バイナリ** を直接呼び出して操作します。

- **接続確認 (`connect`)**:
  - `docker inspect -f "{{.State.Running}}" <container_name>` を実行し、終了コードが `0` かつ出力が `true` であるかを検証することで、指定されたコンテナが存在し、かつ実行中であることを保証します。コンテナが見つからない、または停止している場合は `UnreachableException` をスローします。
- **ファイル転送 (`putFile` / `fetchFile`)**:
  - ファイルのアップロードおよびダウンロードには、`docker cp` コマンドを使用します。
  - `putFile`: `docker cp <local_path> <container_name>:<remote_path>`
  - `fetchFile`: `docker cp <container_name>:<remote_path> <local_path>`
- **コマンド実行 (`execCommand`)**:
  - `docker exec` を使用してコンテナ内でシェルコマンド（デフォルトで `/bin/sh -c`）を実行します。

### 9.2 環境変数の伝播

Jinja2 テンプレート等で評価されたタスク固有の環境変数 (`Map<String, String>`) は、`docker exec` コマンドの実行時に、一つずつ `-e KEY=VALUE` オプションとして付与され、コンテナ内の実行プロセスへ透過的に伝播されます。

### 9.3 権限昇格 (Become) への対応

`BecomeContext` に応じて、コンテナ内での適切な実行ユーザーや権限の切り替えをサポートします。

- **`sudo` モード**:
  - ターゲットコマンドを `sudo -H -S -n -p BECOME-PROMPT [-u <user>] /bin/sh -c '<command>'` にラップして `docker exec` 内で実行します。
- **`su` モード**:
  - ターゲットコマンドを `su [<user>] -c '<command>'` にラップして実行します。
- **ネイティブ Docker ユーザー上書き (become_method=runas または become_user 指定時)**:
  - `docker exec` コマンドの `-u` オプションに `become_user`（指定がない場合は `root`）を直接指定することで、Docker レベルで実行ユーザーをオーバーライドします。

### 9.4 Mockito によるテスト戦略

外部の Docker デーモンが起動していない環境でも、継続的インテグレーション（CI）環境で一貫したテストを実行できるよう、`DockerConnectionTest.java` では Mockito を使用した高度なプロセスモッキングテストスイートが構築されています。

- **テスト用サブクラス (`TestableDockerConnection`)**:
  - `DockerConnection` 内のプロセス起動メソッド (`startProcess`) をパッケージプライベートで定義し、テストコード側でプロセス呼び出しを横取りしてモック化。
- **シミュレーション**:
  - 正常接続時の `inspect` の出力 (`true`)、コンテナ未検出エラー（終了コード `1`、エラー出力）、各種 become 構文に変換された `docker exec` CLI 引数のアサーション、標準入出力バッファのデッドロック防止を考慮した並行ストリーム読み込みのモック処理を網羅しています。
