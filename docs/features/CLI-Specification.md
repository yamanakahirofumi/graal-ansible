# CLI仕様

`graal-ansible` は、`ansible-playbook` と互換性のあるコマンドラインインターフェースを提供することを目指します。

## 1. 基本コマンド形式

```bash
graal-ansible [options] playbook.yml
```

## 2. サポート予定の主要オプション

| オプション | 短縮形 | 説明 | 実装状況 |
| :--- | :--- | :--- | :---: |
| `--inventory` | `-i` | インベントリファイルのパス（ターゲットノードの定義）を指定 | ◎ |
| `--extra-vars` | `-e` | 追加の変数を設定 (key=value。JSON/YAML/@file は将来対応) | ○/△ |
| `--limit` | `-l` | 実行対象のターゲットノードを制限 | ◎ |
| `--tags` | `-t` | 特定のタグが付いたタスクのみ実行 | ◎ |
| `--skip-tags` | - | 特定のタグが付いたタスクをスキップ | ◎ |
| `--check` | `-C` | 変更を加えずに実行（ドライラン） | ◎ |
| `--diff` | `-D` | ファイルの変更内容を表示 | △ |
| `--verbose` | `-v` | 詳細ログを表示 (`-vvv` 等の複数指定をサポート) | ◎ |
| `--become` | `-b` | 権限昇格を有効にする | ◎ |
| `--become-method` | - | 権限昇格に使用するメソッドを指定 (sudo, su等) | ◎ |
| `--become-user` | - | 昇格後のユーザーを指定 (デフォルト: root) | ◎ |
| `--become-flags` | - | 権限昇格に使用するフラグを指定 | ◎ |
| `--ask-become-pass` | `-K` | 権限昇格パスワードをプロンプトで問い合せる | ○ |
| `--version` | - | バージョン情報を表示 | ◎ |
| `--collections-path` | - | コレクションの探索パスを指定 | ○ |
| `--help` | `-h` | ヘルプメッセージを表示 | ◎ |

※ ◎: 実装済み、○: 計画中、△: 部分的/検討中

## 3. 権限昇格 (become) オプションの詳細仕様

本家 Ansible との互換性を確保するため、CLI で指定された権限昇格フラグは以下の通り内部変数へマッピングされ、Playbook 内の定義よりも高い優先順位で扱われます。

| CLI オプション | 内部変数名 | 説明 |
| :--- | :--- | :--- |
| `-b`, `--become` | `ansible_become` | `true` の場合、全タスクでデフォルトで権限昇格を有効にします。 |
| `--become-method` | `ansible_become_method` | `sudo`, `su` 等のメソッドを指定します。 |
| `--become-user` | `ansible_become_user` | 昇格後のユーザーを指定します。 |
| `--become-flags` | `ansible_become_flags` | 昇格コマンドに渡す追加フラグを指定します。 |

これらの変数は、`VariableManager` において「CLI変数 (Level 1)」として保持され、Play や Task で明示的に `become: no` 等が指定されない限り、実行コンテキストに適用されます。

## 4. エクストラ変数 (`--extra-vars`) の制限事項

現在、`--extra-vars` / `-e` オプションは標準的な `key=value` 形式のみをサポートしています。
以下の高度な構文については、将来的な拡張課題（○/△）として `docs/TODO-Details.md` に記載されています。

- `@file.yml` または `@file.json`: ファイルからの変数の読み込み。
- インライン JSON/YAML: `{"key": "value"}` 形式の直接指定。

## 5. 実装方針

- **解析ライブラリ**: [picocli](https://picocli.info/) を採用。
- **Native Image 対応**: GraalVM Native Image での実行時にリフレクション設定が必要になるため、picocli のアノテーションプロセッサを活用する。
- **互換性**: 戻り値（終了コード）についても Ansible 本家と同一の仕様とする。
    - `0`: 正常終了
    - `1`: 一般的なエラー
    - `2`: ターゲットノードの一部が失敗
    - `4`: 構文エラー
