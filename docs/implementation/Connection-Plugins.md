# コネクションプラグインの設計仕様

`graal-ansible` におけるターゲットノードへの接続方法と、それを抽象化するコネクションプラグインの仕様を定義します。

## 1. 概要

Ansible と同様に、ターゲットノードに対する実行環境（ローカル実行、SSH経由のリモート実行など）をプラグイン形式で切り替え可能にします。Java での実装を前提とし、GraalVM Native Image での動作を最適化します。

## 2. サポートされているコネクションタイプ

| タイプ | 状態 | 説明 | 実装ライブラリ / 手法 |
| :--- | :--- | :--- | :--- |
| `ssh` | **実装済** | 標準的なリモート接続 (OpenSSH 互換) | [Apache MINA SSHD](https://mina.apache.org/sshd-project/) |
| `local` | **実装済** | 管理ノード（制御ノード）自身での実行 | `java.lang.ProcessBuilder` |
| `docker` | **実装済** | 稼働中の Docker コンテナ内での実行 | Docker CLI (`docker exec`, `docker cp`) |
| `winrm` | **実装済** | Windows ターゲットノードへの接続 | [WinRM4J](https://github.com/CloudBees-Community/winrm4j) |

## 3. インターフェース定義

すべてのコネクションプラグインは、以下の主要なメソッドを持つ共通インターフェースを実装します。

### 3.1 主要メソッド

- `connect()`: ターゲットノードへの接続を確立。
- `exec_command(command, become_context=null, environment=null)`: 指定されたコマンドを実行し、標準出力・標準エラー・終了コードを返す。
    - `become_context`: 権限昇格の情報。詳細は [権限昇格 (become)](Privilege-Escalation.md) を参照。
    - `environment`: タスク固有の環境変数 (`Map<String, String>`)。評価済みの値が渡されます。
- `put_file(local_path, remote_path)`: ファイルをターゲットノードへ転送。
- `fetch_file(remote_path, local_path)`: ファイルをターゲットノードから取得。
- `close()`: 接続を終了。

## 4. コネクション管理と委譲 (`delegate_to`)

`PlaybookExecutor` は、タスクの実行対象に応じて適切なコネクションプラグインのインスタンスを管理します。

- **デフォルト接続**: インベントリで定義されたターゲットノードに対して接続を確立します。
- **委譲 (delegate_to)**:
    - `delegate_to` キーが指定されている場合、`PlaybookExecutor` は委譲先となるターゲットノード用のコネクション（通常は `LocalConnection` または別のホストへの SSH）を新たに取得または作成します。
    - タスクの実行が完了するまで、この委譲先コネクションが使用されます。
- **コネクションの再利用**:
    - 同一プレイ内で同じターゲットノードに対する接続は、可能な限り再利用（キャッシュ）し、オーバーヘッドを削減します。

## 5. SSH コネクションの詳細設計

### 5.1 ライブラリ選定
GraalVM Native Image との相性を考慮し、純粋な Java 実装である **Apache MINA SSHD** を第一候補とします。外部の `ssh` バイナリに依存しないことで、配布サイズとポータビリティを向上させます。

### 5.2 認証方式
以下の認証方式をサポートします。
- 公開鍵認証 (`~/.ssh/id_rsa` 等、および `ssh-agent`)
- パスワード認証 (インタラクティブな入力または変数経由)

### 5.3 SSH 踏み台サーバー（Bastion / Jump Host）経由の接続
プライベートネットワーク等に配置されたターゲットホストに対して、踏み台サーバー経由で多段 SSH トンネリング接続を確立する仕様をサポートしています。
- **ProxyJump / ProxyCommand パース**: `ansible_ssh_common_args` や `ansible_ssh_extra_args` 内の `-J` / `ProxyJump` オプションおよび `ProxyCommand` 記述の自動解決。
- **ローカルポートフォワード**: Apache MINA SSHD の `startLocalPortForwarding` を使用したネイティブ Java トンネル処理。
- **詳細設計**: クラス設計、資格情報解決、リソースリークを防ぐカスケードクローズ等については [SSH 踏み台サーバー経由接続の実装詳細](Ssh-Jump-Host-Support.md) を参照してください。

## 6. ローカルコネクションの詳細設計 (LocalConnection Implementation)

`LocalConnection` は、管理ノード（制御ノード）自身の上で直接プロセスを起動してタスクを実行するためのコネクションプラグインです。`ansible_connection: local` 指定時や `delegate_to: localhost` 実行時に利用されます。

### 6.1 プロセス実行メカニズムと OS 抽象化 (`OSHandler`)

管理ノードの OS 差分（Linux/macOS vs Windows）を透過的に吸収するため、`OSHandlerFactory` 経由で取得した `OSHandler` インスタンスを使用します。

- **標準シェルの自動選択**:
  - `OSHandler.getShellExecutable()` を介して、Linux/macOS の場合は `/bin/sh -c`、Windows の場合は `cmd.exe /c` などの OS に適したシェルコマンド配列を自動的にプリペンド（前置）してコマンドラインを構築します。
- **ProcessBuilder による実行**:
  - 構築されたコマンドリストを `java.lang.ProcessBuilder` に渡し、`startProcess(pb)` メソッドを通じてローカルプロセスを起動します。

### 6.2 権限昇格 (Become) とパスワードストリーム自動注入

`BecomeContext` が有効（`become=true`）かつ `OSHandler.supportsSudo()` が真を返す場合、ローカル環境での権限昇格コマンド構築および認証処理を行います。

- **`sudo` メソッド (`become_method=sudo` またはデフォルト)**:
  - コマンドリストの先頭に `sudo` を付与します。
  - パスワード指定時（`becomePassword != null`）は、プロンプトからの標準入力読み込みを指示する `-S` オプションを追加します。
  - プロンプト識別用マーカーとして `-p BECOME-PROMPT` を指定します。
  - ユーザー指定時（`becomeUser != null`）は `-u <user>` を追加します。
  - フラグ指定時（`becomeFlags`）は空白区切りで各オプションフラグ（例: `-H` 等）を追加します。
- **`su` メソッド (`become_method=su`)**:
  - `su [<user>] -c` 構文のコマンドラインラッパーを構築します。
- **パスワードの標準入力書き込み**:
  - `process.getOutputStream()` を介して、UTF-8 エンコードされたパスワード（`becomePassword + "\n"`）を安全に子プロセスの標準入力へ即時書き込みし、`flush()` を行って入力を完了させます。

### 6.3 タスク環境変数 (`environment`) の伝播

Jinja2 テンプレートやタスク定義で評価された環境変数マップ (`Map<String, String> environment`) は、`ProcessBuilder.environment()` に直接マージされます。これにより、管理ノードの既存環境変数を維持しながら、タスク固有の環境変数を確実に子プロセスへ伝播させます。

### 6.4 非同期ストリーム読み込みによるデッドロック防止

子プロセスの標準出力（`stdout`）および標準エラー出力（`stderr`）のバッファ溢れによるデッドロックを防ぐため、`CompletableFuture.supplyAsync` を用いて並行スレッド上でそれぞれのストリームを即座に読み込みます。

- `readStreamAsync(InputStream)`: `process.getInputStream()` および `process.getErrorStream()` をバックグラウンドスレッドで全量キャプチャ（`is.readAllBytes()`）します。
- `process.waitFor()` でプロセスの終了コードを取得した後、`CompletableFuture.get()` で両ストリームの読み込み結果を確定させ、`ConnectionResult(stdout, stderr, exitCode)` として集計・返却します。

### 6.5 ファイル転送 (`putFile` / `fetchFile`) のローカルファイルコピー

ローカル環境におけるファイル転送は、リモート通信プロトコルを必要としないため、Java 標準の `java.nio.file.Files` API を用いて直接コピーします。

- **`putFile(localPath, remotePath)`**:
  - ターゲットパス (`remotePath`) が既存のディレクトリである場合、`localPath` のファイル名と結合して保存先パスを解決します。
  - `Files.copy(localPath, targetPath, StandardCopyOption.REPLACE_EXISTING)` により安全にファイルコピーを実行します。
- **`fetchFile(remotePath, localPath)`**:
  - ソースパス (`remotePath`) とターゲットパス (`localPath`) を解決し、同様に `Files.copy` を用いてコピーを実行します。

### 6.6 単体テスト・モッキング戦略 (`LocalConnectionTest.java`)

OS の実際の `sudo` や外部プロセスの依存なしに、継続的インテグレーション（CI）環境で一貫したテストを実行するため、`LocalConnection` にはパッケージプライベートなフックメソッドが設計されています。

- **`startProcess(ProcessBuilder pb)` メソッド**:
  - パッケージプライベートで定義されており、ユニットテストコード (`LocalConnectionTest`) 内でオーバライドすることで、外部プロセスの起動をインターセプトしてダミーの `Process` オブジェクトを注入可能です。
- **テスト検証範囲**:
  - `sudo` および `su` 構文に適用される各 CLI 引数（`-S`, `-p`, `-u`, `becomeFlags`）の構築アサーション。
  - パスワード入力ストリームへの正確なパスワード文字列の書き込みとフラッシュの検証。
  - `ProcessBuilder` への環境変数セットのマージ検証。
  - `IOException` や `InterruptedException` 発生時の例外メッセージラッピングと終了コード `1` の安全な返却検証。

## 7. 実装上の注意

- **タイムアウト管理**: 接続およびコマンド実行に対して、Ansible 互換のタイムアウト設定を適用可能にします。
- **リソース解放**: 実行完了後（またはエラー発生時）に確実に接続をクローズする仕組み（Try-with-resources 等）を徹底します。
- **Native Image 対応**: SSH ライブラリが使用する暗号化アルゴリズムのリフレクション/JNI設定を `reflect-config.json` 等に含める必要があります。

## 8. コネクションファクトリと解決ロジック

インベントリ変数（`ansible_connection` 等）に基づき動的に `Connection` インスタンスを生成するため、`ConnectionFactory` を導入しています。

### 8.1 ConnectionFactory インターフェース

```java
public interface ConnectionFactory {
    /**
     * ホストと変数セットに基づき、適切な Connection インスタンスを生成または取得します。
     * @param host ターゲットホスト
     * @param variables 解決済みの変数マップ (ansible_connection 等を含む)
     * @return Connection インターフェースの実装体
     */
    Connection createConnection(Host host, Map<String, Object> variables);
}
```

### 8.2 解決に使用する主要な変数

コネクションの決定には、以下の変数の優先順位（Host Vars > Group Vars > All Vars）を考慮して解決された値を使用します。

| 変数名 | 用途 | デフォルト値 |
| :--- | :--- | :--- |
| `ansible_connection` | 使用するプラグイン名 (`local`, `ssh`, `smart` 等) | `smart` (または `ssh`) |
| `ansible_host` | 実際の接続先ホスト名または IP アドレス | (インベントリのホスト名) |
| `ansible_port` | 接続ポート番号 | `22` (ssh の場合) |
| `ansible_user` | 接続ユーザー名 | (現在の実行ユーザー) |
| `ansible_password` | 接続パスワード | なし |
| `ansible_ssh_private_key_file`| 認証用秘密鍵パス | なし |

### 8.3 解決フロー

1. **変数の集約**: `VariableManager` を用いて、対象ホストの `ansible_connection` 等の変数を解決します。
2. **プラグインの特定**: `ansible_connection` の値に基づき、対応する `Connection` 実装クラス（`LocalConnection`, `SshConnection` 等）を選択します。
3. **インスタンス化とパラメータ注入**: 解決された接続情報（ホスト、ポート、ユーザー等）をインスタンスに注入します。
4. **接続のキャッシュ**: 同一ホスト・同一パラメータのコネクションは `TaskQueueManager` レベルで保持（Map等）し、プレイ内のタスク間で再利用することでオーバーヘッドを削減します。

## 9. Docker コネクションの詳細設計

`DockerConnection` は、すでに稼働している Docker コンテナ内でタスクを実行するためのコネクションプラグインです。コンテナオーケストレーションやローカル検証環境でのテストなどで利用されます。

### 9.1 CLI インタラクション

Docker デーモンや API ライブラリへの直接的な依存を避け、ポータビリティと互換性を最大化するため、システムにインストールされている **Docker CLI バイナリ** を直接呼び出して操作します。

- **接続確認 (`connect`)**:
  - `docker inspect -f "{{.State.Running}}" <container_name>` を実行し、終了コードが `0` かつ出力が `true` であるかを検証することで、指定されたコンテナが存在し、かつ実行中であることを保証します。コンテナが見つからない、または停止している場合は `UnreachableException` をスローします。
- **ファイル転送 (`putFile` / `fetchFile`)**:
  - ファイルのアップロードおよびダウンロードには、`docker cp` コマンドを使用します。
  - `putFile`: `docker cp <local_path> <container_name>:<remote_path>`
  - `fetchFile`: `docker cp <container_name>:<remote_path> <local_path>`
- **コマンド実行 (`execCommand`)**:
  - `docker exec` を使用してコンテナ内でシェルコマンド（デフォルトで `/bin/sh -c`）を実行します。

### 9.2 環境変数の伝播

Jinja2 テンプレート等で評価されたタスク固有の環境変数 (`Map<String, String>`) は、`docker exec` コマンドの実行時に、一つずつ `-e KEY=VALUE` オプションとして付与され、コンテナ内の実行プロセスへ透過的に伝播されます。

### 9.3 権限昇格 (Become) への対応

`BecomeContext` に応じて、コンテナ内での適切な実行ユーザーや権限の切り替えをサポートします。

- **`sudo` モード**:
  - ターゲットコマンドを `sudo -H -S -n -p BECOME-PROMPT [-u <user>] /bin/sh -c '<command>'` にラップして `docker exec` 内で実行します。
- **`su` モード**:
  - ターゲットコマンドを `su [<user>] -c '<command>'` にラップして実行します。
- **ネイティブ Docker ユーザー上書き (become_method=runas または become_user 指定時)**:
  - `docker exec` コマンドの `-u` オプションに `become_user`（指定がない場合は `root`）を直接指定することで、Docker レベルで実行ユーザーをオーバーライドします。

### 9.4 Mockito によるテスト戦略

外部の Docker デーモンが起動していない環境でも、継続的インテグレーション（CI）環境で一貫したテストを実行できるよう、`DockerConnectionTest.java` では Mockito を使用した高度なプロセスモッキングテストスイートが構築されています。

- **テスト用サブクラス (`TestableDockerConnection`)**:
  - `DockerConnection` 内のプロセス起動メソッド (`startProcess`) をパッケージプライベートで定義し、テストコード側でプロセス呼び出しを横取りしてモック化。
- **シミュレーション**:
  - 正常接続時の `inspect` の出力 (`true`)、コンテナ未検出エラー（終了コード `1`、エラー出力）、各種 become 構文に変換された `docker exec` CLI 引数のアサーション、標準入出力バッファのデッドロック防止を考慮した並行ストリーム読み込みのモック処理を網羅しています。

## 10. WinRM コネクションの詳細設計

`WinRMConnection` は、Windows ターゲットノードに接続し、PowerShell 経由でタスクを実行するためのコネクションプラグインです。

### 10.1 ライブラリ選定

Java からの WinRM 接続およびコマンド実行をサポートするため、Java 実装ライブラリである **[WinRM4J](https://github.com/CloudBees-Community/winrm4j)** を使用します。これにより、外部の `winrm` コマンドラインツールに依存することなく、純粋な Java コードから直接リモートの Windows OS 制御が可能になります。

### 10.2 PowerShell コマンド実行

WinRM を介したコマンド実行では、Windows の標準シェルである **PowerShell** を使用します。

- **実行ラッパー**:
  - 送信されたコマンドは、Jinja2 評価済みの環境変数（`environment`）を適用した上で、PowerShell スクリプトブロック（例: `powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command ...`）に適切にラップして実行します。
- **環境変数の伝播**:
  - タスク固有の環境変数 (`Map<String, String>`) は、コマンドの実行直前に PowerShell のセッション内環境変数（`$env:KEY = "VALUE"`）として定義、設定した上で、ターゲットコマンドを同一セッションでチェーン実行します。

### 10.3 Base64 によるファイル転送

Windows 環境では SSH の SCP のような標準的かつ高効率なファイル転送プロトコルがデフォルトで利用できない場合が多いため、WinRM 上のファイル転送（`putFile` / `fetchFile`）は **Base64 エンコードとチャンク転送** を組み合わせて実装します。

- **`putFile` (アップロード)**:
  1. ローカルファイルを読み込み、Base64 形式でエンコードします。
  2. メモリと帯域の消費を抑えるため、Base64 文字列を適切なサイズ（例: 8KB）のチャンクに分割します。
  3. 各チャンクを、PowerShell コマンド（例: `[System.IO.File]::AppendAllText`）を用いてリモートの一時ファイルに順次追記します。
  4. 追記完了後、PowerShell コマンド（例: `[System.Convert]::FromBase64String`）を用いて一時ファイルをデコードし、目的のリモートパスにデコード済みのバイナリを書き出します。
- **`fetchFile` (ダウンロード)**:
  1. PowerShell を用いてリモートファイルを Base64 エンコードして標準出力に出力させます。
  2. Java 側でその標準出力を取得し、デコードしてローカルパスに保存します。

### 10.4 権限昇格 (Become) への対応

Windows における実行権限やユーザーの切り替え（Become）について、`BecomeContext` に応じた適切なマッピングを行います。

- **`become_method=runas` または `runas` によるユーザー指定**:
  - `WinRM4J` で WinRM クライアントのセッションを作成する際、解決された `become_user` および `become_password` を使用して、接続時のアカウント認証情報そのものを上書きまたは動的に再生成してコマンドを実行します。
  - 特殊な become 設定がない場合は、`ansible_user` および `ansible_password` に基づいて通常の認証を行います。

### 10.5 Mockito によるテスト戦略

外部に本物の Windows ターゲットサーバーが存在しない環境でも一貫したテストを実行するため、Mockito を使用した詳細なモックテストスイート `WinRMConnectionTest.java` を設計します。

- **`winrm4j` クライアントのモッキング**:
  - `WinRM4J` の `WinRmTool` / `WinRmClient` などのコアインターフェースを Mockito でモック化します。
- **シミュレーションと検証**:
  - 接続成功・失敗（タイムアウト・認証エラー等）に応じた適切な例外ハンドリングの検証。
  - 各種 become パラメータに応じたクライアント生成時におけるユーザー資格情報アサーションの検証。
  - ファイル転送時に、ローカルファイルが正しく Base64 チャンクに分割され、想定される PowerShell コマンドが `execCommand` に渡されているかどうかのコール回数と引数のアサーションを網羅します。

### 10.6 依存ライブラリの定義 (Maven Coordinates)

`winrm4j` を Java エンジンで使用するための標準的な Maven 依存関係は以下の通り定義します。これらを `pom.xml` に追加してビルドおよびコンパイル環境を整備します。

```xml
<dependency>
    <groupId>io.cloudsoft.windows</groupId>
    <artifactId>winrm4j</artifactId>
    <version>0.12.3</version>
</dependency>
<dependency>
    <groupId>io.cloudsoft.windows</groupId>
    <artifactId>winrm4j-client</artifactId>
    <version>0.12.3</version>
</dependency>
```

### 10.7 サポートする接続パラメータ/変数定義

Ansible 互換の WinRM 接続およびクライアント動作を制御するために、以下のパラメータ（変数）をサポートします。

| 変数名 | 型 | デフォルト値 | 説明 |
| :--- | :--- | :--- | :--- |
| `ansible_winrm_scheme` | `String` | `https` | 接続スキーム（`http` または `https`）を指定します。 |
| `ansible_winrm_transport` | `String` | `negotiate` | 認証プロトコル（`basic`, `ntlm`, `kerberos`, `credssp`, `negotiate`）を指定します。 |
| `ansible_winrm_server_cert_validation` | `String` | `validate` | SSL/TLS 証明書の検証（`ignore` または `validate`）を指定します。 |
| `ansible_winrm_operation_timeout_sec` | `Integer` | `20` | WinRM 内部の操作タイムアウト時間（秒）を指定します。 |
| `ansible_winrm_read_timeout_sec` | `Integer` | `30` | ソケットの読み込みタイムアウト時間（秒）を指定します。 |
| `ansible_winrm_ca_trust_path` | `String` | なし | SSL証明書検証用の信頼ストア（CA証明書など）のファイルパス。 |

### 10.8 PowerShell 実行ポリシーの制御

Windows リモートホスト上でスクリプトやコマンドの実行を阻害する ExecutionPolicy（実行ポリシー）を安全に回避するため、以下の仕様に従ってコマンド実行を行います。

- システム全体のポリシーを恒久的に変更するのではなく、現在のセッションまたはプロセススコープでのみ適用される `-ExecutionPolicy Bypass` オプションを明示的に付与して PowerShell を呼び出します。
- コマンド呼び出し形式の基本設計：
  ```bash
  powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "<ラップされたスクリプトブロック>"
  ```

### 10.9 例外マッピングと接続結果の返却

WinRM の実行またはセッション管理時に発生する WinRM4J 起因のエラー、および基盤となるネットワークエラーを以下のように Java エンジン側の例外にマッピングします。

1. **`UnreachableException` へのマッピング**:
   - ホストへのネットワーク接続失敗、名前解決エラー、HTTP 401 などの認証エラー、および WinRM のサービス疎通確認失敗時は、`UnreachableException` をスローし、対象ホストを即時到達不能ステータスに移行させます。
2. **`ConnectionResult` へのマッピング**:
   - コマンド実行中にタイムアウトが発生した場合は、タイムアウトを示すエラーメッセージ、終了コード（非ゼロ値、例：`-1`）、および空でない `stderr` を保持する `ConnectionResult` を返却します。
   - 予期しない HTTP 500 等のエラー、または WinRM4J クライアントが検知したプロセス停止例外も同様に `ConnectionResult` でラップして返し、プレイブックレベルでの `ignore_errors` 等の制御ロジックを阻害しないようにします。

### 10.10 SSLおよび証明書の検証セキュリティ

自己署名証明書（Self-Signed Certificate）が一般的に使用される環境への接続ポータビリティを担保するため、証明書検証動作のバイパス仕様をサポートします。

- **証明書検証のスキップ (`ansible_winrm_server_cert_validation=ignore`)**:
  - `ansible_winrm_server_cert_validation` の値が `ignore` に設定されている場合、すべての証明書を無条件に検証成功とするダミーの `X509TrustManager` およびすべてのホスト名を検証成功とする `HostnameVerifier` をインジェクションして、HTTPS クライアントを構築します。
  - セキュリティ侵害を防ぐため、実運用時（検証・開発環境以外）においては `validate` を推奨する警告ログ（またはドキュメント上の注記）を出力するように設計します。
