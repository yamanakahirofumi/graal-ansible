# 権限昇格 (Privilege Escalation / become)

`graal-ansible` における権限昇格（`become`）の仕様を定義します。本家 Ansible の `become` 機能を Java 実行エンジンおよび接続プラグインでどのように扱うかを詳述します。

## 1. 概要

ターゲットノード上で、ログインユーザーとは別のユーザー（通常は `root`）としてタスクを実行するための仕組みです。接続プラグイン（SSH等）を通じて、`sudo` や `su` などのコマンドを用いて実現します。

## 2. サポートするキーワード

プレイ（Play）およびタスク（Task）レベルで、以下のキーワードをサポートします。

| キーワード | 説明 | デフォルト値 |
| :--- | :--- | :--- |
| `become` | 権限昇格を有効にするかどうか（`yes`/`true` または `no`/`false`） | `false` |
| `become_method` | 使用する権限昇格ツール（`sudo`, `su`, `pbrun`, `runas` 等） | `sudo` |
| `become_user` | 昇格後のユーザー名 | `root` |
| `become_flags` | 昇格用コマンドに渡す追加のフラグ（例: `-H -S`） | なし |

## 3. CLI インテグレーションと優先順位

コマンドライン引数（CLI）で指定された権限昇格設定は、Playbook 内の定義を上書き、あるいはデフォルト値として機能します。

### 3.1 CLI 変数の注入
`PlaybookCli` は、受け取った引数を以下の「CLI 変数（優先度 Level 1）」として `VariableManager` に登録します。

- `-b` / `--become` -> `ansible_become` (Boolean)
- `--become-method` -> `ansible_become_method` (String)
- `--become-user` -> `ansible_become_user` (String)
- `--become-flags` -> `ansible_become_flags` (String)

### 3.2 優先順位の解決ロジック
`VariableResolver.resolveBecomeContext` は、以下の順序で設定を解決します（下に行くほど優先）。

1. **CLI 変数**: コマンドラインで指定されたデフォルト設定。
2. **Play レベル**: Playbook の `become: ...` 定義。
3. **Task レベル**: 各タスクの `become: ...` 定義。

ただし、Ansible の仕様に基づき、**CLI で `--become` (`-b`) が明示的に指定された場合、Playbook 側に `become` の記述がなくても、すべてのタスクで昇格が有効になります。** 一方で、特定のタスクで `become: no` が指定されている場合は、CLI の指定に関わらずそのタスクでは昇格を行いません。

## 4. 実装方針

### 4.1 コネクションプラグインとの連携
権限昇格は、[コネクションプラグイン](Connection-Plugins.md) の `execCommand` メソッド内で処理されます。

- `execCommand` の引数として `BecomeContext`（昇格要否、メソッド、ユーザー等の情報を保持する Record）を導入し、統合済みです。
- コネクションプラグインは、指定された `become_method` に基づいて実行コマンドをラップします。

#### sudo の例
```bash
# 元のコマンド
/usr/bin/python3 /tmp/ansible_module.py

# sudo ラップ後
sudo -p "BECOME-PROMPT" -u root /bin/sh -c "/usr/bin/python3 /tmp/ansible_module.py"
```

### 4.2 OS 抽象化レイヤーとの連携
[OS 抽象化レイヤー](OS-Abstraction.md) の `OSHandler` が、その OS で利用可能な権限昇格メソッドを提供します。

- Linux: `sudo`, `su`
- Windows: `runas`
- macOS: `sudo`

### 4.3 パスワードのハンドリング
- `-K` / `--ask-become-pass` オプションが指定された場合、ユーザーにパスワードをプロンプトで問い合せます。
- 取得したパスワードは、メモリ内で安全に保持し、実行時に権限昇格コマンドの標準入力（または `sudo -S` 等の引数）を介して渡します。

## 5. 実行順序における適用タイミング

[PlaybookExecutor](Task-Control.md) および [タスク実行エンジン](Task-Executor.md) において、以下のタイミングで適用されます。

1. 変数解決後、タスク引数を確定。
2. Play レベルおよび Task レベルの `become` 設定をマージ・評価（`Truthiness` による判定）。
3. `Connection.execCommand` を呼び出す際、`BecomeContext` として昇格情報を付与。
4. モジュール実行完了後、結果を取得。

## 6. 制約事項と留意点

- **インターラクティブな入力**: 本プロジェクトでは、権限昇格時のプロンプト応答（パスワード入力等）を自動化することを前提とします。
- **セキュリティ**: パスワードの平文でのログ出力や、一時ファイルへの保存は厳禁とします。
- **Native Image 対応**: `sudo` 等の外部コマンドを呼び出す際の `ProcessBuilder` 実行が GraalVM 環境で正しく動作することを確認する必要があります。
