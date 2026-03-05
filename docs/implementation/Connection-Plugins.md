# コネクションプラグインの設計仕様

`graal-ansible` におけるターゲットホストへの接続方法と、それを抽象化するコネクションプラグインの仕様を定義します。

## 1. 概要

Ansible と同様に、ターゲットホストに対する実行環境（ローカル実行、SSH経由のリモート実行など）をプラグイン形式で切り替え可能にします。Java での実装を前提とし、GraalVM Native Image での動作を最適化します。

## 2. サポート予定のコネクションタイプ

| タイプ | 説明 | 実装ライブラリ案 | 優先度 |
| :--- | :--- | :--- | :--- |
| `ssh` | 標準的なリモート接続 (OpenSSH 互換) | [Apache MINA SSHD](https://mina.apache.org/sshd-project/) または `ssh` コマンド呼び出し | 高 |
| `local` | 制御ノード自身での実行 | `java.lang.ProcessBuilder` | 高 |
| `docker` | 稼働中の Docker コンテナ内での実行 | Docker CLI 呼び出し | 中 |
| `winrm` | Windows ホストへの接続 | [WinRM4J](https://github.com/CloudBees-Community/winrm4j) | 低 |

## 3. インターフェース定義

すべてのコネクションプラグインは、以下の主要なメソッドを持つ共通インターフェースを実装します。

### 主要メソッド

- `connect()`: ターゲットホストへの接続を確立。
- `exec_command(command, become_context=null)`: 指定されたコマンドを実行し、標準出力・標準エラー・終了コードを返す。詳細は [権限昇格 (become)](Privilege-Escalation.md) を参照。
- `put_file(local_path, remote_path)`: ファイルをターゲットホストへ転送。
- `fetch_file(remote_path, local_path)`: ファイルをターゲットホストから取得。
- `close()`: 接続を終了。

## 4. SSH コネクションの詳細設計

### ライブラリ選定
GraalVM Native Image との相性を考慮し、純粋な Java 実装である **Apache MINA SSHD** を第一候補とします。外部の `ssh` バイナリに依存しないことで、配布サイズとポータビリティを向上させます。

### 認証方式
以下の認証方式をサポートします。
- 公開鍵認証 (`~/.ssh/id_rsa` 等、および `ssh-agent`)
- パスワード認証 (インタラクティブな入力または変経由)

## 5. ローカルコネクションの詳細設計

制御ノード上で直接コマンドを実行します。
- `sudo` が指定された場合、`sudo -n` (non-interactive) を付与して実行します。
- 環境変数の継承を適切に制御します。

## 6. 実装上の注意

- **タイムアウト管理**: 接続およびコマンド実行に対して、Ansible 互換のタイムアウト設定を適用可能にします。
- **リソース解放**: 実行完了後（またはエラー発生時）に確実に接続をクローズする仕組み（Try-with-resources 等）を徹底します。
- **Native Image 対応**: SSH ライブラリが使用する暗号化アルゴリズムのリフレクション/JNI設定を `reflect-config.json` 等に含める必要があります。
