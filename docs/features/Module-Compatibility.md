# モジュール互換性 (Module Compatibility)

Ansible モジュールの `graal-ansible` 上での実行における互換性の定義です。基本方針については [モジュールの開発方針](Module-Development-Policy.md) を参照してください。

## 1. 実行方式

本プロジェクトでは、本家 Ansible の Python モジュールを GraalPy 上で実行します。

- **GraalPy Module (Primary)**: 本家 Ansible の Python モジュールやコレクションを GraalPy 上でそのまま実行します。これにより、実際のものを使用する設計指針に基づき、最高水準の互換性と動作の信頼性を確保します。
- **Java Bridge/Native (Internal)**: モジュールの入出力を Java 側で適切に制御するためのブリッジ。Ansible モジュールのロジック自体の Java による再実装は、原則として行いません。

## 2. サポート対象のコアモジュール (Supported Core Modules)

以下のモジュールを初期のサポート対象とし、Java-Python ブリッジ経由での動作検証を行います。「サポート対象」とは、対象モジュールが GraalPy 上で正しくロードされ、引数の受け渡しおよび実行結果の返却が本家 Ansible と同等に行われることを指します。

| モジュール名 | 分類 | 動作確認状況 | 備考 |
| :--- | :--- | :--- | :--- |
| `ansible.builtin.debug` | Core | 確認済 | メッセージ出力、変数表示の基本機能。 |
| `ansible.builtin.command` | Core | 予定 | 任意のコマンド実行。標準出力のキャプチャ。 |
| `ansible.builtin.shell` | Core | 予定 | シェルを介したコマンド実行。 |
| `ansible.builtin.copy` | Core | 予定 | ファイルのコピー。パーミッション維持。 |
| `ansible.builtin.template` | Core | 予定 | Jinja2 テンプレートの展開と配置。 |
| `ansible.builtin.file` | Core | 予定 | ファイル・ディレクトリの状態管理（作成、削除、権限）。 |
| `ansible.builtin.stat` | Core | 予定 | ファイル情報の取得。 |

## 3. 実装時にブレる恐れがある仕様 (実装上の留意点)

### 3.1 Python `ansible.module_utils` の依存解決
- **懸念点**: 多くの Python モジュールが `ansible.module_utils` 下の共通ライブラリに依存しています。
- **方針**: GraalPy 環境内に、これらのユーティリティライブラリをあらかじめロードしておく必要があります。どの範囲のライブラリを標準で含めるか（バイナリサイズとのトレードオフ）がブレるポイントになります。

### 3.2 モジュールの戻り値形式 (JSON)
- **懸念点**: モジュールから返される JSON データ構造の厳密な型定義。
- **方針**: `changed`, `failed`, `msg` などの必須フィールドに加え、各モジュール固有の戻り値を Java Record でどう受けるか。未知のフィールドを許容する柔軟なパーサー（`Jackson` 等の活用）が必要です。

### 3.3 Check Mode (`--check`) の対応
- **懸念点**: 各モジュールが `check_mode` をサポートしているかどうかの判定。
- **方針**: 本家 Python モジュールを実行する際、GraalPy 経由で `check_mode` 引数を正しく渡せるかを検証します。外殻となる Java 側のブリッジでも `check_mode` フラグを適切にハンドリングします。

### 3.4 OS 固有の挙動の抽象化
- **懸念点**: `file` モジュールのパーミッション処理など、OS ごとに異なる挙動（POSIX vs Windows ACL）。
- **方針**: `docs/implementation/OS-Abstraction.md` に基づき、インターフェースで抽象化しますが、Windows 対応時にどこまで本家と合わせるか（あるいは WSL2 経由に限定するか）が議論の対象となります。
