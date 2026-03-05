# 権限昇格 (Privilege Escalation / become)

`graal-ansible` における権限昇格（`become`）の仕様を定義します。本家 Ansible の `become` 機能を Java 実行エンジンおよび接続プラグインでどのように扱うかを詳述します。

## 1. 概要

ターゲットホスト上で、ログインユーザーとは別のユーザー（通常は `root`）としてタスクを実行するための仕組みです。接続プラグイン（SSH等）を通じて、`sudo` や `su` などのコマンドを用いて実現します。

## 2. サポートするキーワード

プレイ（Play）およびタスク（Task）レベルで、以下のキーワードをサポートします。

| キーワード | 説明 | デフォルト値 |
| :--- | :--- | :--- |
| `become` | 権限昇格を有効にするかどうか（`yes`/`true` または `no`/`false`） | `false` |
| `become_method` | 使用する権限昇格ツール（`sudo`, `su`, `pbrun`, `runas` 等） | `sudo` |
| `become_user` | 昇格後のユーザー名 | `root` |
| `become_flags` | 昇格用コマンドに渡す追加のフラグ（例: `-H -S`） | なし |

## 3. 実装方針

### 3.1 コネクションプラグインとの連携
権限昇格は、[コネクションプラグイン](Connection-Plugins.md) の `exec_command` メソッド内で処理されます。

- `exec_command` の引数に `BecomeContext`（昇格要否、メソッド、ユーザー等の情報を保持するオブジェクト）を追加することを検討します。
- コネクションプラグインは、指定された `become_method` に基づいて実行コマンドをラップします。

#### sudo の例
```bash
# 元のコマンド
/usr/bin/python3 /tmp/ansible_module.py

# sudo ラップ後
sudo -p "BECOME-PROMPT" -u root /bin/sh -c "/usr/bin/python3 /tmp/ansible_module.py"
```

### 3.2 OS 抽象化レイヤーとの連携
[OS 抽象化レイヤー](OS-Abstraction.md) の `OSHandler` が、その OS で利用可能な権限昇格メソッドを提供します。

- Linux: `sudo`, `su`
- Windows: `runas`
- macOS: `sudo`

### 3.3 パスワードのハンドリング
- `-K` / `--ask-become-pass` オプションが指定された場合、ユーザーにパスワードをプロンプトで問い合せます。
- 取得したパスワードは、メモリ内で安全に保持し、実行時に権限昇格コマンドの標準入力（または `sudo -S` 等の引数）を介して渡します。

## 4. 実行順序における適用タイミング

[タスク実行エンジン](Task-Executor.md) において、以下のタイミングで適用されます。

1. 変数解決後、タスク引数を確定。
2. タスクまたはプレイの `become` 設定を確認。
3. `Connection.exec_command` を呼び出す際、昇格情報を付与。
4. モジュール実行完了後、結果を取得。

## 5. 制約事項と留意点

- **インターラクティブな入力**: 本プロジェクトでは、権限昇格時のプロンプト応答（パスワード入力等）を自動化することを前提とします。
- **セキュリティ**: パスワードの平文でのログ出力や、一時ファイルへの保存は厳禁とします。
- **Native Image 対応**: `sudo` 等の外部コマンドを呼び出す際の `ProcessBuilder` 実行が GraalVM 環境で正しく動作することを確認する必要があります。
