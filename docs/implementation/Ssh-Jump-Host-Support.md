# SSH 踏み台サーバー経由接続の実装詳細 (SSH Jump Host / Bastion Implementation Details)

本ドキュメントでは、`graal-ansible` における、Apache MINA SSHD を用いた踏み台サーバー（Bastion / Jump Host）経由接続のネイティブ Java 実装に関する詳細設計、クラス構成、オプションのパース、およびリソース管理について定義します。

## 1. 概要とアーキテクチャ

踏み台サーバー経由の SSH 接続（多段 SSH）は、制御ノード（Java 実行環境）から第1踏み台サーバーへのセッションを確立し、そのセッション上で次段の踏み台（またはターゲットホスト）へのローカルポートフォワーディング（LFP）トンネルを作成し、そのトンネルポートを介して次段のセッションを順次確立する「多段（カスケード）接続モデル」として実装します。

本機能は、外部プロセス（OpenSSH の `ssh` コマンドなど）を呼び出すことなく、純粋な Java コード（Apache MINA SSHD）のみで完結するため、GraalVM Native Image 化された環境でも動作可能です。

## 2. クラス設計と責務の拡張

既存の接続アーキテクチャへの統合を最小限の変更で実現するため、`SshConnection` および `SshJumpHostParser` により多段トンネリングをサポートします。

### 2.1 クラス構成
- **`SshConnection`**:
  - 従来の単一 SSH 接続に加えて、内部に `List<BastionConfig> bastionConfigs` およびアクティブなトンネル情報を管理する `List<ActiveBastion> activeBastions` を保持します。
  - `connect()` 呼び出し時に、踏み台サーバーの設定が存在する場合は、順次踏み台サーバーへの接続とローカルポートフォワーディングを確立します。
- **`SshJumpHostParser`**:
  - `ansible_ssh_extra_args`、`ansible_ssh_common_args`、および `ansible_bastion_host` などの独自変数をパースし、多段の踏み台接続情報 (`List<BastionConfig>`) を組み立てるユーティリティクラス。
- **`DefaultConnectionFactory`**:
  - `SshJumpHostParser.getBastionConfigs(variables)` を呼び出し、抽出された踏み台構成リストを `SshConnection` へ渡してインスタンス化します。

### 2.2 接続用データ構造
踏み台サーバーの設定情報およびアクティブなセッション状態を保持するため、以下のデータ構造を定義します。

```java
public record BastionConfig(
    String host,
    int port,
    String user,
    String password,
    String privateKeyFile
) {
    public BastionConfig withPort(int port) {
        return new BastionConfig(host, port, user, password, privateKeyFile);
    }
}

private static class ActiveBastion {
    final ClientSession session;
    final SshdSocketAddress localAddr;

    ActiveBastion(ClientSession session, SshdSocketAddress localAddr) {
        this.session = session;
        this.localAddr = localAddr;
    }
}
```

## 3. 踏み台設定のパースとパラメータ解決

Ansible の標準オプション（`ProxyJump` / `-J` / `ProxyCommand`）との互換性を担保しつつ、独自拡張パラメータも透過的に解決します。

### 3.1 ProxyJump および ProxyCommand オプションのパースルール
`SshJumpHostParser` は `ansible_ssh_extra_args` または `ansible_ssh_common_args` から `-J`、`-o ProxyJump` または `ProxyCommand` 記述をパースします。

- **正規表現パターン**:
  - `ProxyJump` / `-J`: `(?i)(?:-o\s+ProxyJump\s*=\s*|-J\s+)(?:\"([^\"]+)\"|'([^']+)'|([^\\s\\n\\r]+))`
  - `ProxyCommand`: `(?i)ProxyCommand\s+(.+)`
- **パース時の仕様**:
  - `ProxyJump` または `-J` 内にカンマ区切りで複数のホストが指定されている場合（例: `-J jumpuser@jumphost1:22,jumpuser@jumphost2:3333`）、`String.split(",")` により全ホップを順次展開して `List<BastionConfig>` へ追加します。
  - ユーザー名やポートが省略された場合は、接続のデフォルト値（ユーザー名はターゲットホストの `ansible_user`、ポートは `22`）を補完適用します。
  - `ProxyCommand` 形式の記述（例: `ProxyCommand ssh -W %h:%p bastion`）に対しても、コマンドライン引数を解析して該当するホスト名・ポート・ユーザーを抽出します。

### 3.2 パラメータ優先順位
踏み台情報の解決は、以下の優先順位に従ってマージされます（1 が最優先）。

1. `ansible_bastion_host` などの独自明示設定（単一 Bastion）
2. `ansible_ssh_extra_args` 内の `-o ProxyJump` / `-J` / `ProxyCommand` パース結果
3. `ansible_ssh_common_args` 内の `-o ProxyJump` / `-J` / `ProxyCommand` パース結果

## 4. トンネリング確立プロシージャ (MINA SSHD API)

`SshConnection.connect()` 内において、踏み台設定が存在する場合の実行シーケンスおよびコード設計は以下の通りです。

### 4.1 シーケンス詳細
```java
int n = bastionConfigs.size();
for (int i = 0; i < n; i++) {
    BastionConfig bastion = bastionConfigs.get(i);
    ClientSession bSession;
    if (i == 0) {
        // 最初の踏み台へ直接接続
        bSession = client.connect(bastion.user(), bastion.host(), bastion.port())
                .verify(timeout).getSession();
    } else {
        // 直前のローカルポートフォワード経由で接続
        int prevLocalPort = activeBastions.get(i - 1).localAddr.getPort();
        bSession = client.connect(bastion.user(), "localhost", prevLocalPort)
                .verify(timeout).getSession();
    }

    // 認証設定 (パスワードまたは公開鍵)
    if (bastion.password() != null) {
        bSession.addPasswordIdentity(bastion.password());
    }
    if (bastion.privateKeyFile() != null) {
        addPrivateKeyIfPresent(bSession, bastion.privateKeyFile());
    }
    bSession.auth().verify(timeout);

    // 次の宛先 (次段の踏み台またはターゲットホスト)
    String destHost = (i == n - 1) ? this.host : bastionConfigs.get(i + 1).host();
    int destPort = (i == n - 1) ? this.port : bastionConfigs.get(i + 1).port();

    SshdSocketAddress localAddr = new SshdSocketAddress("localhost", 0);
    SshdSocketAddress remoteAddr = new SshdSocketAddress(destHost, destPort);
    SshdSocketAddress boundAddr = bSession.startLocalPortForwarding(localAddr, remoteAddr);

    activeBastions.add(new ActiveBastion(bSession, boundAddr));
}

// 最終ターゲットホストへの接続 (踏み台が存在する場合は最後のトンネルポート経由)
if (n > 0) {
    int lastLocalPort = activeBastions.get(n - 1).localAddr.getPort();
    session = client.connect(username, "localhost", lastLocalPort).verify(timeout).getSession();
} else {
    session = client.connect(username, host, port).verify(timeout).getSession();
}
// ターゲット認証およびセッション確立...
```

## 5. リソース管理とクローズ仕様 (Cascading Close)

ネットワーク切断やリクエスト終了時にファイル記述子（FD）のリークを防ぐため、クローズ処理を確立時と**逆順**でカスケード実行します。

### 5.1 クローズ順序
`SshConnection.close()` 時に、以下のコードの通り確実にリソースを終了させます。

```java
@Override
public void close() {
    // 1. ターゲットセッションのクローズ
    if (session != null) {
        try {
            session.close();
        } catch (Exception ignored) {}
        session = null;
    }

    // 2. 確立時と逆順でローカルポートフォワードの停止と踏み台セッションのクローズ
    for (int i = activeBastions.size() - 1; i >= 0; i--) {
        ActiveBastion ab = activeBastions.get(i);
        if (ab.session != null) {
            if (ab.localAddr != null) {
                try {
                    ab.session.stopLocalPortForwarding(ab.localAddr);
                } catch (Exception ignored) {}
            }
            try {
                ab.session.close();
            } catch (Exception ignored) {}
        }
    }
    activeBastions.clear();

    // 3. SSH クライアントの停止
    if (client != null) {
        try {
            client.stop();
        } catch (Exception ignored) {}
        client = null;
    }
}
```

## 6. エラーハンドリングと例外マッピング

多段接続の失敗箇所を明示的に識別し、デバッグ性を向上させます。

| 失敗シナリオ | 検知方法 | スローする例外とプレフィックス |
| :------------------------------- | :--------------------------------------- | :------------------------------------------------------------------------------------- |
| 踏み台への名前解決/到達不能 | `bastionSession` 確立失敗 (timeout/IOEx) | `UnreachableException("[Bastion] Failed to connect to bastion host:port")`              |
| 踏み台の認証失敗 | `bastionSession.auth().verify()` 失敗 | `UnreachableException("[Bastion Auth Failed] Failed to authenticate to bastion host:port")` |
| ポートフォワードのバインド失敗 | `startLocalPortForwarding` 内の IOEx | `UnreachableException("[Bastion Port Forward Denied] Failed to start local port forwarding on bastion host:port")` |
| ターゲットの到達不能/認証失敗 | `targetSession` 接続/認証失敗 | `UnreachableException("Failed to connect/authenticate to host:port")` |

## 7. テスト設計とモッキング

外部の SSH サーバーおよび踏み台サーバーを用意せずに、単体テストで動作を保証するための設計。

- **モック対象**:
  - `SshClient`
  - `ClientSession` (多段踏み台用およびターゲット用セッションのモック)
  - `SshdSocketAddress`
- **検証項目 (`SshConnectionTest.java`)**:
  - `-o ProxyJump=...`, `-J user@host:port` およびカンマ区切り多段ホスト (`bastion1,bastion2`) のパース結果が正しく `BastionConfig` リストへマッピングされること (`testSshJumpHostParserJOption`, `testSshJumpHostParserJOptionMultiHop`)。
  - `startLocalPortForwarding` が呼び出され、各段階で返されたランダムなポートを用いて次段セッションの `connect` が `localhost` 宛てに呼ばれること。
  - `close()` 時に、ターゲット、LFP停止、踏み台セッションの順（逆順）で例外が発生しても確実に全リソースのクローズ処理が走ること。
