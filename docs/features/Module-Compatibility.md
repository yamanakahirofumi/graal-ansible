# モジュール互換性の目標 (Module Compatibility Goals)

Ansible モジュールの `graal-ansible` 上での実行における最終的な互換性の目標と設計指針です。

## 1. 最終的な目標 (Ultimate Goals)

本プロジェクトの最終的な目標は、**`ansible-core` に含まれる標準モジュールが、OS を問わず GraalPy 上で透過的に実行できること** です。

- **100% のモジュールロード互換性**: `ansible.builtin` コレクションの全 72 モジュールが、どの OS（Linux, macOS, Windows）でもロード可能であること。
- **主要モジュールの完全動作**: ファイル操作、コマンド実行、テンプレート展開などの基本モジュールが、本家 Ansible と同等の結果を返すこと。
- **マルチプラットフォーム対応**: 管理ノード (Control Node) として Linux, macOS, Windows のすべてをサポートすること。

## 2. 実行方式の基本方針 (Design Principles)

本プロジェクトでは、本家 Ansible の Python モジュールを GraalPy 上で実行することで、最高水準の互換性を維持します。

- **GraalPy Module (Primary)**: 本家 Ansible の Python モジュールやコレクションを GraalPy 上でそのまま実行します。ロジックの Java による再実装は原則として行わず、本物の Python コードを使用することで動作の信頼性を確保します。
- **Java Bridge/Native (Internal)**: モジュールの入出力を Java 側で適切に制御するためのブリッジ。Ansible の内部仕様（`ansible.module_utils` 等）との結合を最小限に抑えつつ、効率的なデータ交換を実現します。

## 3. サポート対象の範囲 (Target Scope)

以下のコレクションを、最終的なサポート対象として定義します。

### 3.1 対象コレクション (Target Collections)
- **ansible.builtin (ansible-core)**: 全モジュール (72種類)。
- **ansible.posix**: `patch`, `mount` などの POSIX 固有機能。
- **ansible.utils**: IPアドレス操作やデータ構造の加工などのユーティリティ。
- **community.general**: 主要なサードパーティ製モジュール（優先順位を付けて順次対応）。

## 4. 実装における技術的課題と方針

### 4.1 Python `ansible.module_utils` の完全解決
- **目標**: すべてのモジュールが依存する共通ライブラリを GraalPy 環境内で過不足なくロードできるようにします。
- **方針**: 必要なユーティリティを抽出し、バイナリサイズと互換性のバランスを最適化します。

### 4.2 厳密な戻り値の互換性
- **目標**: モジュールから返される JSON データ構造を Java 側で正確にパースし、Playbook 実行エンジンへ引き渡します。
- **方針**: `Jackson` 等を活用し、未知のフィールドを許容しつつ、`changed`, `failed`, `msg` などの必須フィールドを保証します。

### 4.3 Check Mode (`--check`) のネイティブサポート
- **目標**: すべてのモジュールで `check_mode` 引数が正しく機能することを保証します。

### 4.4 OS 抽象化の統合
- **目標**: ファイルパーミッションやパス区切り文字など、OS 固有の差異を `docs/implementation/OS-Abstraction.md` に基づき完全に吸収します。

## 5. 関連ドキュメント
- [モジュールサポート状態（現在の進捗）](Module-Support-Status.md)
- [モジュールの開発方針](Module-Development-Policy.md)
- [OS 抽象化レイヤーの仕様](../implementation/OS-Abstraction.md)
