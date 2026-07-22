# Ansible Vault 暗号化・復号化サポート (Ansible Vault Support)

本ドキュメントでは、`graal-ansible` における機密データ保護のための **Ansible Vault** 統合仕様および暗号化・復号化サポート機能について提案します。

---

## 1. 概要

Ansible Vault は、Playbook や変数ファイル内のパスワードや鍵などの機密情報を暗号化して保存する機能です。
`graal-ansible` において、`!vault` タグで保護されたデータや暗号化ファイルを透過的に復号化し、タスク実行エンジンへ安全に引き渡す仕組みを設計します。

---

## 2. ビジネスルールと要件

- **下位互換性の維持**: Ansible 本家 (ansible-core 2.17+) と同一の暗号化フォーマット（主に `AES256`）をサポートします。
- **ファイルレベルおよび変数レベルのサポート**:
  - **Vault暗号化ファイル**: `vars_files` や `include_vars` で指定された全体が暗号化されている YAML ファイルの自動復号化。
  - **インライン・Vault（`!vault` タグ）**: YAML ファイル内の一部のみが暗号化されている個別の変数の自動復号化。
- **マルチ・Vault対応 (`--vault-id`)**: 開発環境（`dev`）、本番環境（`prod`）など、複数の Vault パスワード（Vault ID）を切り替えて使用するマルチ Vault 構成をサポートします。

---

## 3. CLI 引数および環境変数仕様

本家 Ansible と互換性のある以下のオプションおよび環境変数をサポートします。

### 3.1 CLI オプション

| オプション | 短縮形 | 説明 |
| :--- | :--- | :--- |
| `--vault-password-file` | - | Vault パスワードが記述されたファイルのパスを指定します。 |
| `--ask-vault-pass` | - | 実行時にプロンプトを介してインタラクティブにパスワードの入力を求めます。 |
| `--vault-id` | - | `id@source` 形式（例: `dev@/path/to/pass` や `prod@prompt`）で複数の Vault パスワードを指定します。 |

### 3.2 環境変数

| 環境変数 | 説明 |
| :--- | :--- |
| `ANSIBLE_VAULT_PASSWORD_FILE` | デフォルトで使用する Vault パスワードファイルのパスを定義します。 |
| `ANSIBLE_VAULT_IDENTITY_LIST` | カンマ区切りの Vault ID リスト（例: `dev@~/.vault_dev,prod@prompt`）を定義します。 |

---

## 4. 処理フローと設計アーキテクチャ

Ansible Vault の復号化プロセスは、管理ノード（制御ノード）上で行われます。

```
[ 暗号化YAML / !vault変数 ]
          │
          ▼
┌─────────────────────────────────┐
│     YamlParser (SnakeYAML)      │ ──► !vaultタグを「Vaultシリアライズ文字列」として保持
└─────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────┐
│        VariableResolver         │ ──► テンプレート展開時、または値の参照時に復号化をトリガー
└─────────────────────────────────┘
          │ (パスワード・VaultIDの参照)
          ▼
┌─────────────────────────────────┐
│      VaultDecryptionEngine      │ ──► PBKDF2/AES256復号アルゴリズムの適用
└─────────────────────────────────┘
          │
          ▼
   [ プレーンテキスト ]
```

### 4.1 暗号化データの識別

Ansible Vault で暗号化されたデータは、以下のヘッダー（マジックワード）から開始される特徴を持っています。
`YamlParser` および `VariableResolver` は、文字列がこのヘッダーで開始されている場合、暗号化データとして認識します。

```text
$ANSIBLE_VAULT;1.1;AES256
```

### 4.2 復号化エンジンのハイブリッドアプローチ

復号化処理を確実かつ高速に実行するため、以下の **ハイブリッドアプローチ** を採用します。

1.  **Java ネティブ復号化 (Primary)**:
    - **手法**: Java 標準の暗号ライブラリ (`javax.crypto`) を使用して、PBKDF2 でパスワードから鍵を生成（HMAC-SHA256）し、AES-256-CTR モードで復号化を行います。
    - **メリット**: C 拡張等への依存がなく、**GraalVM Native Image** ビルド時においても追加の設定なしで高速かつ安定して動作します。
2.  **GraalPy 連携復号化 (Fallback / Python-first)**:
    - **手法**: Python アクションプラグイン内での実行時など、複雑な型（バイナリなど）の復号化において、必要に応じて GraalPy 内の `ansible.parsing.vault` モジュールを呼び出して復号化を委譲します。
    - **メリット**: 本家 Ansible と 100% 同一の動作を保証します。

---

## 5. 開発ロードマップと実装フェーズ

本機能は、以下のフェーズに分けて段階的に実装を進めます。

### フェーズ 1: パスワード入力と基本復号化 (JCEベース)
- **目標**: `--vault-password-file` のパース、および単一の `AES256` 暗号化文字列の Java 側での復号化の実装。
- **検証**: ユニットテストにて、特定のパスワードで暗号化されたテストデータを Java 側で正確に復号化できることを確認。

### フェーズ 2: YAMLパースおよび変数解決への統合
- **目標**: `YamlParser` で `!vault` タグを検知した際に `VaultEncryptedValue` オブジェクトを構築し、`VariableResolver` が値をプレーンテキストへ遅延評価する仕組みの実装。
- **検証**: 暗号化された変数を含む Playbook の正常実行。

### フェーズ 3: マルチVault (`--vault-id`) およびプロンプト対応
- **目標**: `--vault-id` によるラベル付きパスワードの解決、および `--ask-vault-pass` 指定時のインタラクティブプロンプト (`PromptProvider` の拡張) の実装。

---

## 6. 関連ドキュメント

- [YAML解析エンジン (YAML-Parser.md)](../implementation/YAML-Parser.md)
- [変数とテンプレートの実装詳細 (Variables-Templating.md)](../implementation/Variables-Templating.md)
- [CLI仕様 (CLI-Specification.md)](CLI-Specification.md)
