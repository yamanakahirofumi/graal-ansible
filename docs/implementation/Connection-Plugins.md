# コネクションプラグインの設計仕様

`graal-ansible` におけるターゲットノードへの接続方法と、それを抽象化するコネクションプラグインの仕様を定義します。

## 1. 概要

Ansible と同様に、ターゲットノードに対する実行環境（ローカル実行、SSH経由のリモート実行など）をプラグイン形式で切り替え可能にします。Java での実装を前提とし、GraalVM Native Image での動作を最適化します。

## 2. サポート予定のコネクションタイプ

| タイプ | 説明 | 実装ライブラリ案 | 優先度 |
| :--- | :--- | :--- | :--- |
| `ssh` | 標準的なリモート接続 (OpenSSH 互換) | [Apache MINA SSHD](https://mina.apache.org/sshd-project/) または `ssh` コマンド呼び出し | 高 |
| `local` | 管理ノード（制御ノード）自身での実行 | `java.lang.ProcessBuilder` | 高 |
| `docker` | 稼働中の Docker コンテナ内での実行 | Docker CLI 呼び出し | 中 |
| `winrm` | Windows ターゲットノードへの接続 | [WinRM4J](https://github.com/CloudBees-Community/winrm4j) | 低 |

## 3. インターフェース定義

すべてのコネクションプラグインは、以下の主要なメソッドを持つ共通インターフェースを実装します。

### 主要メソッド

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

### ライブラリ選定
GraalVM Native Image との相性を考慮し、純粋な Java 実装である **Apache MINA SSHD** を第一候補とします。外部の `ssh` バイナリに依存しないことで、配布サイズとポータビリティを向上させます。

### 認証方式
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

## 8. コネクションの解決ロジック (将来の設計)

現在は `LocalConnection` または `SshConnection` がコード内で明示的に選択されていますが、将来的にはインベントリ変数に基づいて動的に解決する仕組みを導入します。

### 解決に使用する主要な変数
- `ansible_connection`: 使用するプラグイン名（`local`, `ssh`, `smart` 等）。
- `ansible_host`: 実際の接続先ホスト名または IP アドレス（未指定の場合はインベントリのホスト名を使用）。
- `ansible_port`: 接続ポート番号。
- `ansible_user`: 接続ユーザー名。
- `ansible_password` / `ansible_ssh_pass`: 接続パスワード。
- `ansible_ssh_private_key_file`: 認証用秘密鍵パス。

### 解決フロー (設計案)
1. **変数の取得**: `VariableManager` を用いて、対象ホストの `ansible_connection` 等の変数を解決します。
2. **ファクトリによる生成**: `ConnectionFactory`（仮称）を介して、プラグイン名に対応するクラスをインスタンス化します。
3. **パラメータ設定**: 解決された接続情報をコンストラクタまたはセッター経由で注入します。
4. **キャッシュ**: 同一ホスト・同一パラメータのコネクションは `TaskQueueManager` または `PlaybookExecutor` レベルで保持し、タスクごとに再利用します。
