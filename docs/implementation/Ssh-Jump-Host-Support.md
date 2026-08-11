# SSH 踏み台サーバー経由接続の実装詳細 (SSH Jump Host / Bastion Implementation Details)

本ドキュメントでは、`graal-ansible` における、Apache MINA SSHD を用いた踏み台サーバー（Bastion / Jump Host）経由接続のネイティブ Java 実装に関する詳細設計、クラス構成、オプションのパース、およびリソース管理について定義します。

## 1. 概要とアーキテクチャ

踏み台サーバー経由の SSH 接続（多段 SSH）は、制御ノード（Java 実行環境）から踏み台サーバーへの第1セッションを確立し、そのセッション上でターゲットホストへのローカルポートフォワーディング（LFP）トンネルを作成し、そのトンネルポートを介してターゲットホストへの第2セッションを確立する「2段階の接続モデル」として実装します。

本機能は、外部プロセス（OpenSSH の `ssh` コマンドなど）を呼び出すことなく、純粋な Java コード（Apache MINA SSHD）のみで完結するため、GraalVM Native Image 化された環境でも動作可能です。

## 2. クラス設計と責務の拡張

既存の接続アーキテクチャへの統合を最小限の変更で実現するため、`SshConnection` 自体を拡張して多段トンネリングをサポートします。

### 2.1 クラス構成
- **`SshConnection`**:
  - 従来の単一 SSH 接続に加えて、内部にオプションとして `bastionSession` (ClientSession 型) および `tunnelPort` (int 型) を保持します。
  - `connect()` 呼び出し時に、踏み台サーバーの設定が存在する場合は、まず踏み台サーバーへの接続とローカルポートフォワーディングを確立します。
- **`DefaultConnectionFactory`**:
  - `ansible_ssh_common_args`、`ansible_ssh_extra_args`、または `ansible_bastion_host` などの独自変数をパースし、踏み台接続情報を組み立てて `SshConnection` に渡します。

### 2.2 接続用データ構造
踏み台サーバーの設定情報を保持するため、内部レコードまたはクラス `BastionConfig` を定義します。

```java
public record BastionConfig(
    String host,
    int port,
    String user,
    String password,
    String privateKeyFile
) {}
```

## 3. 踏み台設定のパースとパラメータ解決

Ansible の標準オプション（`ProxyJump`）との互換性を担保しつつ、独自拡張パラメータも透過的に解決します。

### 3.1 ProxyJump オプションのパースルール
`ansible_ssh_common_args` または `ansible_ssh_extra_args` から `-J` または `-o ProxyJump` を抽出します。

- **正規表現パターン**:
  - `-o ProxyJump=...`: `(?i)-o\s+ProxyJump\s*=\s*(?:(?<user>[a-zA-Z0-9_.-]+)@)?(?<host>[a-zA-Z0-9_.-]+)(?::(?<port>\d+))?`
  - `-J ...`: `(?i)-J\s+(?:(?<user>[a-zA-Z0-9_.-]+)@)?(?<host>[a-zA-Z0-9_.-]+)(?::(?<port>\d+))?`
- **パース時の仕様**:
  - `ProxyJump` 設定内に複数のホストがカンマ区切りで指定されている場合（例: `bastion1,bastion2`）、フェーズ 1 においては最も外側の最初の踏み台（`bastion1`）のみを対象とし、残りの多段ジャンプはサポート外として無視するか、警告ログを出力します。
  - ユーザー名やポートが省略された場合は、接続のデフォルト値（ユーザー名は実行ユーザーまたはターゲットホストの `ansible_user`、ポートは `22`）を適用します。

### 3.2 パラメータ優先順位
踏み台情報の解決は、以下の優先順位に従ってマージされます（1 が最優先）。

1. `ansible_ssh_extra_args` 内の `-o ProxyJump` / `-J` のパース結果
2. `ansible_ssh_common_args` 内の `-o ProxyJump` / `-J` のパース結果
3. `graal-ansible` 独自の踏み台変数:
   - ホスト: `ansible_bastion_host`
   - ポート: `ansible_bastion_port` (デフォルト: 22)
   - ユーザー: `ansible_bastion_user`
   - パスワード: `ansible_bastion_password`
   - 秘密鍵: `ansible_bastion_private_key_file`

## 4. トンネリング確立プロシージャ (MINA SSHD API)

`SshConnection.connect()` 内において、踏み台設定が有効な場合の実行シーケンスおよびコード設計は以下の通りです。

### 4.1 シーケンス詳細
1. **踏み台サーバーへの接続 (Step 1)**:
   ```java
   ClientSession bastionSession = client.connect(bastionUser, bastionHost, bastionPort)
           .verify(timeout)
           .getSession();
   if (bastionPrivateKeyFile != null) {
       // 鍵認証の設定
   } else if (bastionPassword != null) {
       bastionSession.addPasswordIdentity(bastionPassword);
   }
   bastionSession.auth().verify(timeout);
   ```
2. **ローカルポートフォワーディングの開始 (Step 2)**:
   - 動的ポート（OS 自動割り当て）を指定してローカルポートフォワードをバインドします。
   ```java
   SshdSocketAddress localAddr = new SshdSocketAddress("localhost", 0);
   SshdSocketAddress remoteAddr = new SshdSocketAddress(targetHost, targetPort);
   SshdSocketAddress boundAddr = bastionSession.startLocalPortForwarding(localAddr, remoteAddr);
   int tunnelPort = boundAddr.getPort();
   ```
3. **ターゲットへの接続 (Step 3)**:
   - ターゲット接続のホストに `"localhost"`、ポートに `tunnelPort` を指定してセッションを開始します。
   ```java
   ClientSession targetSession = client.connect(targetUser, "localhost", tunnelPort)
           .verify(timeout)
           .getSession();
   if (targetPrivateKeyFile != null) {
       // 鍵認証
   } else if (targetPassword != null) {
       targetSession.addPasswordIdentity(targetPassword);
   }
   targetSession.auth().verify(timeout);
   ```

## 5. リソース管理とクローズ仕様 (Cascading Close)

ネットワーク切断やリクエスト終了時にファイル記述子（FD）のリークを防ぐため、クローズ処理を以下のようにカスケード実行します。

### 5.1 クローズ順序
`SshConnection.close()` 時に、以下の順序で確実にリソースを終了させます。

```java
@Override
public void close() {
    try {
        if (targetSession != null) {
            targetSession.close();
        }
    } finally {
        try {
            if (bastionSession != null && tunnelPort > 0) {
                bastionSession.stopLocalPortForwarding(new SshdSocketAddress("localhost", tunnelPort));
            }
        } catch (IOException e) {
            // ログ出力のみで続行
        } finally {
            try {
                if (bastionSession != null) {
                    bastionSession.close();
                }
            } finally {
                if (client != null) {
                    client.stop();
                }
            }
        }
    }
}
```

## 6. エラーハンドリングと例外マッピング

多段接続の失敗箇所を明示的に識別し、デバッグ性を向上させます。

| 失敗シナリオ | 検知方法 | スローする例外とプレフィックス |
| :--- | :--- | :--- |
| 踏み台への名前解決/到達不能 | `bastionSession` 確立失敗 (timeout/IOEx) | `UnreachableException("[Bastion] Connection timed out / unreachable")` |
| 踏み台の認証失敗 | `bastionSession.auth().verify()` 失敗 | `UnreachableException("[Bastion Auth Failed] Invalid credentials")` |
| ポートフォワードのバインド失敗 | `startLocalPortForwarding` 内の IOEx | `UnreachableException("[Bastion Port Forward Denied] Failed to bind local port")` |
| ターゲットの到達不能/認証失敗 | `targetSession` 接続/認証失敗 | `UnreachableException("[Target] ...")` |

## 7. テスト設計とモッキング

外部の SSH サーバーおよび踏み台サーバーを用意せずに、単体テストで動作を保証するための設計。

- **モック対象**:
  - `SshClient`
  - `ClientSession` (踏み台用とターゲット用の2つをモック)
  - `SshdSocketAddress`
- **検証項目**:
  - `ProxyJump` 設定をパースした結果、ホスト名、ユーザー、ポートが正しく `BastionConfig` にマッピングされること。
  - `startLocalPortForwarding` が呼び出され、返されたランダムなポートを用いてターゲットセッションの `connect` が `localhost` 宛てに呼ばれること。
  - `close()` 時に、ターゲット、LFP停止、踏み台セッションの順で例外が発生しても確実にクローズメソッドが全件走ること。
