# モジュール互換性 (Module Compatibility)

Ansible モジュールの `graal-ansible` 上での実行における互換性の定義です。基本方針については [モジュールの開発方針](Module-Development-Policy.md) を参照してください。

## 1. 実行方式

本プロジェクトでは、本家 Ansible の Python モジュールを GraalPy 上で実行します。

- **GraalPy Module**: 本家 Ansible の Python モジュールを GraalPy 上で実行。高い互換性を持ちますが、実行コストは Java Native よりも高くなる可能性があります。
- **Java Native Module**: (非推奨) パフォーマンス上の制約等により、どうしても Java で実装されたモジュール。原則として新規開発は行いません。

## 2. 実装時にブレる恐れがある仕様 (実装上の留意点)

### 2.1 Python `ansible.module_utils` の依存解決
- **懸念点**: 多くの Python モジュールが `ansible.module_utils` 下の共通ライブラリに依存しています。
- **方針**: GraalPy 環境内に、これらのユーティリティライブラリをあらかじめロードしておく必要があります。どの範囲のライブラリを標準で含めるか（バイナリサイズとのトレードオフ）がブレるポイントになります。

### 2.2 モジュールの戻り値形式 (JSON)
- **懸念点**: モジュールから返される JSON データ構造の厳密な型定義。
- **方針**: `changed`, `failed`, `msg` などの必須フィールドに加え、各モジュール固有の戻り値を Java Record でどう受けるか。未知のフィールドを許容する柔軟なパーサー（`Jackson` 等の活用）が必要です。

### 2.3 Check Mode (`--check`) の対応
- **懸念点**: 各モジュールが `check_mode` をサポートしているかどうかの判定と、Java 再実装版での再現。
- **方針**: Java 再実装モジュールでは `check_mode` フラグを必須の実装項目とします。Python 版については、GraalPy 経由で `check_mode` 引数を正しく渡せるかを検証します。

### 2.4 OS 固有の挙動の抽象化
- **懸念点**: `file` モジュールのパーミッション処理など、OS ごとに異なる挙動（POSIX vs Windows ACL）。
- **方針**: `docs/implementation/OS-Abstraction.md` に基づき、インターフェースで抽象化しますが、Windows 対応時にどこまで本家と合わせるか（あるいは WSL2 経由に限定するか）が議論の対象となります。
