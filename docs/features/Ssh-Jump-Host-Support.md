# SSH 踏み台サーバー経由接続仕様 (SSH Jump Host / Bastion Support)

本ドキュメントでは、`graal-ansible` における、踏み台サーバー（Bastion / Jump Host）を経由した安全なターゲットノードへの SSH 接続仕様および設計アーキテクチャについて定義します。

## 1. 概要 (Overview)

セキュリティの重要性が高まる現代のインフラストラクチャ環境において、ターゲットノードはプライベートサブネットに隔離され、パブリックインターネットからの直接の SSH 接続が制限されているのが一般的です。このような環境では、外部からアクセス可能な唯一の入り口として「踏み台サーバー（Bastion または Jump Host）」を介して各ターゲットノードへ接続します。

`graal-ansible` では、外部の `ssh` コマンドや `ProxyCommand` 設定に直接依存せず、Java の標準 SSH 接続ライブラリである **Apache MINA SSHD** の能力をネイティブに活用して、安全かつ高速な多段（マルチホップ）SSH トンネリング（ローカルポートフォワード）を実現・サポートしています。これにより、Native Image 化された単一バイナリ配布時でも動作可能な、ポータブルかつ高パフォーマンスな接続環境を提供します。

## 2. ビジネスルールとユースケース (Business Rules & Use Cases)

本機能は、以下の条件やシナリオにおいて自動的または明示的に適用されます。

### 2.1 発動条件と判定フロー
1.  **インベントリ変数またはプレイブック変数の定義**:
    - 対象のホストに `ansible_ssh_common_args` または `ansible_ssh_extra_args` 内に `-o ProxyJump=...` または `ProxyCommand` 形式の記述が検出された場合。
    - もしくは、`graal-ansible` 独自拡張の踏み台サーバー専用変数（例: `ansible_bastion_host`）が定義されている場合。
2.  **接続モデルの動的切り替え**:
    - `ConnectionFactory` は上記の設定を検知すると、通常の単一 SSH 接続ではなく、多段トンネリングに対応した SSH コネクション（内部に踏み台セッションとポートフォワード情報を持つ `SshConnection`）を選択・インスタンス化します。

### 2.2 主要ユースケース
- **プライベートクラウド / VPC 環境**: パブリック IP アドレスを持たないデータベースサーバーや内部アプリケーションサーバーへの Playbook 実行。
- **マルチステージプロキシ（マルチホップ）**: セキュリティポリシーにより、複数の踏み台サーバー（例: 外側 Bastion -> 内側 Bastion -> ターゲット）を経由する必要がある環境での一貫したプロキシチェーン。

## 3. 接続パラメータおよび変数定義 (Connection Parameters)

Ansible 互換の接続設定、および `graal-ansible` でサポートされる独自の拡張パラメータを以下のように定義します。

| 変数名 | 型 | デフォルト値 | 説明 |
| :--- | :--- | :--- | :--- |
| `ansible_ssh_common_args` | `String` | なし | `-o ProxyJump="user@bastion:port"` または `-o ProxyCommand="..."` を含む Ansible 標準の接続引数。 |
| `ansible_ssh_extra_args` | `String` | なし | 同上。`common_args` より優先して評価・マージされます。 |
| `ansible_bastion_host` | `String` | なし | [独自拡張] 踏み台サーバーのホスト名または IP アドレス。 |
| `ansible_bastion_port` | `Integer` | `22` | [独自拡張] 踏み台サーバーの SSH ポート番号。 |
| `ansible_bastion_user` | `String` | (接続ユーザー) | [独自拡張] 踏み台サーバー接続用のユーザー名。 |
| `ansible_bastion_password` | `String` | なし | [独自拡張] 踏み台サーバー接続用のパスワード（鍵認証を使用しない場合）。 |
| `ansible_bastion_private_key_file` | `String` | なし | [独自拡張] 踏み台サーバー接続用の秘密鍵ファイルのパス。 |

## 4. トンネリングアーキテクチャ設計 (Tunneling Architecture)

Apache MINA SSHD ライブラリを用いた、ネイティブ Java によるポートフォワーディング接続のシーケンスおよび管理手法は以下の通りです。

### 4.1 ポートフォワードを仲介する接続確立シーケンス
1.  **踏み台接続（第1ステップ）**:
    - `SshClient` を使用して、踏み台サーバー（`ansible_bastion_host`）の指定ポート（`ansible_bastion_port`）へ、踏み台専用の資格情報（`ansible_bastion_user`, `private_key` または `password`）を用いて SSH 接続を確立（`bastionSession`）します。
2.  **ローカルポートフォワードの開始（第2ステップ）**:
    - 確立した `bastionSession` に対して、ローカルの空きポート（動的ポート、例: `10000`〜`65535` から自動検出）から、ターゲットホスト（`ansible_host`）の SSH ポート（デフォルト `22`）へのポートフォワーディングを開始します。
    - MINA SSHD での記述例:
      ```java
      SshdSocketAddress localAddr = new SshdSocketAddress("localhost", 0); // 0を指定して空きポートを自動割当
      SshdSocketAddress remoteAddr = new SshdSocketAddress(targetHost, targetPort);
      SshdSocketAddress boundAddr = bastionSession.startLocalPortForwarding(localAddr, remoteAddr);
      int tunnelPort = boundAddr.getPort();
      ```
3.  **ターゲットホストへの接続（第3ステップ）**:
    - 取得した `tunnelPort` を宛先ポートとして、別の `session`（`targetSession`）を `localhost` に対して接続開始します。
    - 認証には、ターゲットホスト用の資格情報（`ansible_user`, `ansible_password` または `ansible_ssh_private_key_file`）を使用します。これにより、トラフィックは自動的に踏み台上の SSH トンネルを経由して暗号化され、ターゲットホストへ届きます。

### 4.2 トンネルライフサイクルの管理とリソースリーク防止
多段の接続を安全に維持し、例外発生時やタスク完了時に接続リソースが解放されずに残る「ファイル記述子リーク（FD Leak）」を防ぐため、以下のクローズポリシーを徹底します。

- **接続クローズ時のカスケード解除**:
  - `targetSession` のクローズ時、およびコネクション全体の `close()` 呼び出し時に、以下の順序で確実にリソースを終了させます。
    1.  ターゲットホストへの SSH セッション（`targetSession`）のクローズ。
    2.  `bastionSession` に対するポートフォワーディング（`stopLocalPortForwarding`）の停止。
    3.  踏み台サーバーへの SSH セッション（`bastionSession`）のクローズ。
  - いずれかのステップで例外が発生した場合でも、残りのクローズ処理が確実に実行されるよう、`try-finally` 構造または適切な例外マスクを用いて実装します。

## 5. エラーハンドリング方針 (Error Handling Policy)

多段接続における接続エラーの発生箇所を特定しやすくするため、エラーハンドリングは以下のケース別に詳細な例外にマッピングされます。

1.  **踏み台サーバー自体の到達不能**:
    - ホスト名の名前解決失敗、ポート遮断、タイムアウト時は、エラーメッセージに `[Bastion]` プレフィックスを付与した `UnreachableException` をスローします。
2.  **踏み台サーバーの認証エラー**:
    - パスワード不一致、公開鍵の拒否などは、`[Bastion Auth Failed]` を付与した例外とし、ターゲットの認証エラーと明確に区別します。
3.  **ポートフォワーディングの確立失敗**:
    - 踏み台サーバー上でのポリシー制限（`AllowTcpForwarding no` 等の設定）によりトンネリングが拒否された場合は、`[Bastion Port Forward Denied]` という明確な理由を示して `UnreachableException` とします。
4.  **ターゲットノードへの到達不能・認証エラー**:
    - トンネル開設後にターゲット側で発生したエラーは、通常の `UnreachableException` として処理されます。
