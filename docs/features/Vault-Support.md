# Ansible Vault 復号サポート仕様 (Ansible Vault Decryption Support)

本ドキュメントでは、`graal-ansible` における Ansible Vault 暗号化データ（ファイルおよびインラインの `!vault` 独自タグ）を Java でネイティブに復号・統合するための機能提案およびアーキテクチャ設計について定義します。

## 1. 概要 (Overview)

Ansible Vault は、機密情報（パスワード、APIキー、秘密鍵など）を暗号化して Git リポジトリ等に安全に保管するための仕組みです。現在の `graal-ansible` は、YAML 解析時に `!vault` タグをエラーなく透過的にパースする設計になっていますが、値自体の復号には対応していません。

本提案は、外部の Python プロセスや GraalPy を介さず、Java の標準暗号ライブラリ（`javax.crypto`）を用いて安全かつ高速にネイティブ復号を実行する仕組みを導入するものです。これにより、プレイブック実行時における機密データの自動展開を可能にします。

## 2. 暗号仕様と復号アルゴリズム (Cryptographic Specifications)

Ansible Vault (Vault フォーマット 1.1) で使用される標準的な暗号化アルゴリズムおよび鍵生成手順は以下の通りです。

### 2.1 暗号化フォーマット
暗号化されたデータ（または YAML 値）は以下のヘッダーから始まるテキスト形式です。
```
$ANSIBLE_VAULT;1.1;AES256
```
ヘッダー以降の行は、暗号化データのヘキサ（16進数）エンコード文字列が 80 文字ごとに改行された状態で格納されます。

### 2.2 鍵誘導 (Key Derivation - PBKDF2)
ヘキサデコードされたデータブロックの先頭には、ソルト（Salt）、HMAC（署名）、暗号文（Ciphertext）が改行区切りで含まれています。
Vault パスワードから AES 復号鍵（および HMAC 検証用鍵）を導出するために **PBKDF2 (Password-Based Key Derivation Function 2)** を使用します。

- **アルゴリズム**: `PBKDF2WithHmacSHA256`
- **イテレーション数**: `10000`
- **導出キー長**: `total = 80 bytes` (AES-256 鍵用 32 バイト、HMAC-SHA256 鍵用 32 バイト、IV 用 16 バイト)

### 2.3 暗号モードと整合性検証 (AES-CTR & HMAC)
- **整合性検証 (MAC)**:
  - 復号処理を行う前に、導出した HMAC 鍵を用いて暗号文の HMAC-SHA256 署名を計算し、データ内の署名と一致するか検証します。
  - 署名が一致しない場合は、パスワード誤りまたはデータ改ざんと判断して復号を拒否します。
- **対称暗号**:
  - 導出した AES 鍵と初期化ベクトル（IV）を使用し、**AES/CTR/NoPadding** モードで復号します。

## 3. クラス設計とアーキテクチャ (Architecture and Class Design)

Java エンジン内において、Vault 復号処理は以下のコンポーネントに統合されます。

### 3.1 復号ユーティリティ (`VaultDecrypter.java`)
暗号データのパース、PBKDF2 による鍵導出、HMAC 検証、および AES-CTR 復号を担当するコアクラス。
```java
public class VaultDecrypter {
    /**
     * パスワードを用いて Vault 暗号化データを復号します。
     * @param encryptedRawText $ANSIBLE_VAULT ヘッダーを含む暗号文
     * @param password 復号用パスワード
     * @return 復号されたプレーンテキスト（またはバイト配列）
     */
    public byte[] decrypt(String encryptedRawText, String password) {
        // 1. ヘッダーの解析とヘキサデコード
        // 2. ソルト、HMAC、暗号文の抽出
        // 3. PBKDF2 による鍵導出 (AES 鍵、HMAC 鍵、IV)
        // 4. HMAC 整合性検証
        // 5. AES-CTR 復号の実行
    }
}
```

### 3.2 YAML 解析への統合 (`YamlParser` & `VaultConstructor`)
SnakeYAML の解析フェーズにおいて、`!vault` タグが検出された際に、自動的に `VaultDecryptedValue` などのプレースホルダーオブジェクト、または遅延復号用コンテキストにマッピングします。

- 参照リンク：[YAML解析エンジン](../implementation/YAML-Parser.md)
- `VaultConstructor` を SnakeYAML のカスタムコンストラクタとして追加し、`!vault` タグの値（ScalarNode）を保持。

### 3.3 変数解決時の動的復号 (`VariableResolver`)
暗号化された変数は、タスク実行直前の変数解決（Jinja2 テンプレート展開）フェーズで、提供されたパスワードを用いて動的に復号・文字列展開されます。

- 参照リンク：[変数とテンプレート](../implementation/Variables-Templating.md)
- `VariableResolver` は、解決対象の値が `VaultDecryptedValue` である場合、`VaultDecrypter` を呼び出してプレーンテキストに置換します。

## 4. パスワード管理とコマンドライン仕様 (CLI Specifications)

復号用パスワードは、セキュリティ要件に応じて以下の手段でエンジンに提供可能です。
`ansible-playbook` 互換の CLI 引数をサポートします。

- 参照リンク：[CLI仕様](CLI-Specification.md)

### 4.1 CLI 引数
- **`--vault-id`**: Vault ID とパスワードソースを指定します (例: `prod@/path/to/password_file` または `prompt`)。
- **`--vault-password-file`**: パスワードが記述されたファイルのパスを指定します。

### 4.2 環境変数
- **`ANSIBLE_VAULT_PASSWORD_FILE`**: パスワードファイルのパスを環境変数から取得します。

## 5. エラーハンドリング方針 (Error Handling Policy)

- 参照リンク：[エラーハンドリング方針](../tech/Error-Handling-Policy.md)

復号処理中にエラーが発生した場合、セキュリティおよび整合性を担保するため、以下の挙動とします。

1. **パスワード不一致 / HMAC 検証失敗**:
   - `VaultDecryptionException` (または `RuntimeException`) をスローし、プレイブックの実行を即座に安全に停止（any_errors_fatal 相当）または対象ホストを失敗とします。
2. **パスワードソース未指定**:
   - `!vault` データを検知したにもかかわらず、CLI や環境変数からパスワードが提供されていない場合、実行前チェック（Validation）段階で明確なエラーメッセージを表示して処理を中断します。
