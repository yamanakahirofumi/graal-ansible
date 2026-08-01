# Ansible Vault 復号サポートの設計と提案 (Ansible Vault Decryption Support)

本ドキュメントでは、`graal-ansible` における Ansible Vault 暗号化データの復号機能に関する仕様および設計方針を提案します。

## 1. 背景と目的

Ansible では、パスワード、API キー、秘密鍵などの機密情報を安全に管理するため、**Ansible Vault** を使用して変数やファイル全体を暗号化することが一般的です。
現在、`graal-ansible` では `YamlUtil.java` を通じて `!vault` タグを含む YAML を解析エラーを出さずに透過的に解析する仕組み（未評価のまま文字列として保持）は構築されていますが、**実際の復号処理および変数へのマッピング機能は未実装**です。

本プロジェクトがプロダクション環境のプレイブックやインベントリ変数をシームレスに処理可能にするため、標準的な `$ANSIBLE_VAULT;1.1;AES256` 形式の暗号化データの復号機能を拡張設計し、その実装ロードマップを明確にします。

---

## 2. ターゲット仕様とサポート範囲

本提案においてサポートを目標とする Ansible Vault の仕様範囲は以下の通りです。

### 2.1 暗号化形式（Vault 形式）
- **形式**: `$ANSIBLE_VAULT;1.1;AES256`
- **暗号化アルゴリズム**: AES256-CTR (Counter Mode)
- **鍵導出関数 (KDF)**: PBKDF2 with HMAC-SHA256 (10,000回イテレーション)
- **改ざん防止**: HMAC-SHA256

### 2.2 処理対象データ
1. **Vault 挿入（インライン暗号化 / !vault タグ）**:
   - YAML の一部として暗号化された変数。
   ```yaml
   secret_key: !vault |
     $ANSIBLE_VAULT;1.1;AES256
     33623336336436323030303030336263613765666336353935396561393666353434613264626131
     3131653331613531303535306631623136373738363630650a363435333534343130313539303337
   ```
2. **暗号化された変数ファイル（vars_files, group_vars, host_vars等）**:
   - ファイル全体が `$ANSIBLE_VAULT` で開始されるファイル。

---

## 3. コマンドライン引数 (CLI) 仕様

`PlaybookCli` において、暗号化データの解除に必要なパスワード（またはシークレット）を供給するため、以下のオプションをサポートします。

| オプション | 記述形式 | 説明 |
| :--- | :--- | :--- |
| `--vault-id` | `[vault_id@]path_or_prompt` | 特定 of Vault ID に対応するパスワードソースを指定します。 |
| `--vault-password-file` | `file_path` | Vault パスワードが記述されたファイルのパスを指定します。 |
| `--ask-vault-pass` | (フラグ) | 実行開始時にプロンプトで Vault パスワードの入力を求めます。 |

### 3.1 優先度とバリデーション
- `--vault-password-file` が指定され、かつファイルが存在しない場合は、即座に例外を発生させて起動を中断します。
- `--ask-vault-pass` が指定された場合、`PromptProvider` 経由で対話的に入力を取得します。

---

## 4. アーキテクチャと設計方針

復号処理を実現するにあたり、以下の2つのアプローチが考えられます。

### 4.1 アプローチの比較

| 評価軸 | A. Java Native 実装 (推奨) | B. GraalPy (Python-first) 連携 |
| :--- | :--- | :--- |
| **概要** | Java の `javax.crypto` 等の標準 API を用いて復号を内製化する。 | GraalPy 上で `ansible-core` の復号モジュールを直接呼び出す。 |
| **起動パフォーマンス** | **極めて高速**。JVM / Native Image の起動時点でオーバーヘッドなし。 | **中〜低**。復号のたびに Python コンテキストへの Marshalling やライブラリロードが生じる。 |
| **AOT (Native Image) 互換性**| **良好**。追加の設定なしに標準の Java セキュリティプロバイダで動作。 | **課題あり**。Python の `cryptography` パッケージ（C言語拡張）の Native Image 内での安定動作が複雑。 |
| **依存関係** | ゼロ（外部依存なし）。 | `ansible-core` への厳密な依存。 |

### 4.2 採用方針
本プロジェクトは **GraalVM Native Image によるバイナリ化と、起動の超高速化** を重要な目標としているため、**「A. Java Native 実装」をメインアプローチとして採用**します。
これにより、Python コンテキストが初期化される前の早いフェーズ（インベントリロードや YAML 解析の段階）でも、オーバーヘッドなしに高速な復号が可能になります。

---

## 5. 復号処理フローとデータ構造

### 5.1 暗号化データの解析仕様

Ansible Vault 形式のデータは以下のように符号化されています。

1. **ヘッダーのスキップ**: `$ANSIBLE_VAULT;1.1;AES256` の文字列を検出。
2. **Hex デコード**: 改行を除くペイロード文字列は、生データのバイナリを Hex 表現（16進数）にしてから ASCII エンコードしたものです。復号前にこれをバイト配列にデコードします。
3. **ペイロード構造**: Hex デコード後のデータには、以下の3つのブロックが改行文字 (`\n`) で区切られて格納されています。
   - `salt` (ソルト: 32バイトの Hex 表現)
   - `hmac` (検証用 HMAC: 32バイトの Hex 表現)
   - `ciphertext` (暗号文の Hex 表現)

### 5.2 鍵導出と復号アルゴリズム（Java 実装詳細）

```java
public class VaultDecryptor {
    private static final int ITERATION_COUNT = 10000;
    private static final int KEY_LENGTH = 256; // bits
    private static final int IV_LENGTH = 16;   // bytes

    public byte[] decrypt(byte[] salt, byte[] hmac, byte[] ciphertext, String password) throws Exception {
        // 1. PBKDF2 により 2つの 256bit 鍵を導出 (暗号鍵用 32B, HMAC検証用 32B, IV用 16B の計 80B)
        byte[] derivedBytes = deriveKeys(password, salt, 80);

        byte[] cipherKey = Arrays.copyOfRange(derivedBytes, 0, 32);
        byte[] hmacKey = Arrays.copyOfRange(derivedBytes, 32, 64);
        byte[] iv = Arrays.copyOfRange(derivedBytes, 64, 80);

        // 2. HMAC の整合性検証
        byte[] calculatedHmac = calculateHmac(ciphertext, hmacKey);
        if (!MessageDigest.isEqual(hmac, calculatedHmac)) {
            throw new SecurityException("Vault decryption failed: HMAC mismatch. Incorrect password?");
        }

        // 3. AES256-CTR による復号
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(cipherKey, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

        return cipher.doFinal(ciphertext);
    }
}
```

---

## 6. 実行エンジンへの統合

### 6.1 VariableManager での透過的復号
- **変数ロード時**: `VariableManager.loadVarsFile` またはインベントリパース時において、読み込んだ値が `$ANSIBLE_VAULT` で開始される文字列（または YAML の `!vault` タグオブジェクト）であるかを走査します。
- **オンデマンド（遅延）復号**:
  - パフォーマンスを最大化するため、変数が `VariableResolver` (Jinja2 テンプレートエンジン) で実際に参照、評価される瞬間に自動的に復号する仕組み（`VaultEncryptedVariable` プロキシオブジェクト等）を導入します。

### 6.2 例外ハンドリング
- パスワードファイルが存在しない場合、またはパスワードが間違っており HMAC 検証に失敗した場合は、プレイブックの実行を即時中断し、明確なエラーメッセージを出力します。

---

## 7. 実装ロードマップ

本機能は以下のフェーズで順次実装することを提案します。

- **フェーズ 1 (CLIと復号コア)**:
  - `VaultDecryptor.java` による復号ロジックの実装。
  - `PlaybookCli` への `--vault-password-file` 引数の追加と、パスワードのオンメモリ管理。
- **フェーズ 2 (パーサー・変数解決の統合)**:
  - `YamlUtil` / `YamlParser` において、`!vault` タグを文字列ではなく `VaultValue` オブジェクトとしてマッピングするよう強化。
  - `VariableResolver` で `VaultValue` を評価する直前に、自動的に復号を実行するインフラの構築。
- **フェーズ 3 (Native Image 最適化)**:
  - `javax.crypto` および PBKDF2/SHA256 プロバイダが Native Image 環境で正しく静的リンクされるためのセキュリティ設定の構成。
