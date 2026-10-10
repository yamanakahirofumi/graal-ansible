# ビルド・配布方法 (Distribution Method)

本ドキュメントでは、`graal-ansible` を GraalVM Native Image によりコンパイルし、スタンドアロンなネイティブバイナリパッケージとして構築・配布するための詳細な仕様について記述します。

## 1. 概要と基本コンセプト

`graal-ansible` は、Ansible 互換の CLI 実行エンジンとして高速な起動速度とポータビリティを実現するため、GraalVM Native Image を用いた単一ネイティブバイナリ形式で配布されます。

- **完全スタンドアロン動作**: ターゲット環境に Java Runtime (JRE/JDK) やシステム Python インタプリタが事前インストールされていない環境でも、単一バイナリのみで即座に実行可能です（`--no-fallback` ビルド）。
- **サブセカンド起動**: AOT (Ahead-Of-Time) コンパイルにより JIT コンパイルのウォームアップ時間を排除し、コマンド実行からタスク開始までの応答速度を極小化します。
- **クロスプラットフォーム配布**: Linux, macOS, Windows の各 OS/アーキテクチャ向けに自動ビルドされたアーカイブパッケージを GitHub Releases 経由で提供します。

## 2. ネイティブバイナリとアーカイブ命名規則

ビルド成果物はターゲット OS および CPU アーキテクチャに応じた一貫した命名規則に従って作成・パッケージングされます。

| ターゲット OS | アーキテクチャ | ネイティブバイナリ名 | アーカイブパッケージ名 | 形式 |
| :--- | :--- | :--- | :--- | :--- |
| **Linux** | x86_64 (amd64) | `graal-ansible` | `graal-ansible-v<version>-linux-amd64.tar.gz` | tar.gz |
| **Linux** | ARM64 (aarch64) | `graal-ansible` | `graal-ansible-v<version>-linux-arm64.tar.gz` | tar.gz |
| **macOS** | x86_64 (amd64) | `graal-ansible` | `graal-ansible-v<version>-darwin-amd64.tar.gz` | tar.gz |
| **macOS** | Apple Silicon (arm64) | `graal-ansible` | `graal-ansible-v<version>-darwin-arm64.tar.gz` | tar.gz |
| **Windows** | x86_64 (amd64) | `graal-ansible.exe` | `graal-ansible-v<version>-windows-amd64.zip` | zip |

## 3. リリースパッケージ内部構造 (Package Layout)

配布アーカイブを展開すると、以下のディレクトリ構成に従って実行可能バイナリ、サンプル設定ファイル、ライセンス文書が配置されます。

```
graal-ansible-v1.0.0-linux-amd64/
├── graal-ansible          # 実行可能ネイティブバイナリ本体
├── ansible.cfg.example    # デフォルト設定ファイルのサンプルテンプレート
├── LICENSE                # オープンソースライセンス文書
├── README.md              # クイックスタートおよび利用ガイド
└── VERSION                # リリースバージョン情報ファイル
```

## 4. GitHub Releases 自動化リリースパイプライン仕様

GitHub Actions の CI/CD パイプライン（`.github/workflows/release.yml`）により、リリースビルドおよび配布アーカイブのアップロードが完全自動化されます。

### 4.1 パイプライン実行トリガー
- バージョンタグ（例: `v1.0.0`, `v1.2.3-rc1`）のプッシュ（`git push origin v*`）によりリリースワークフローが自動起動します。

### 4.2 ビルドおよびアセット作成ステップ

1. **マトリックスビルド**: `ubuntu-latest`, `macos-latest`, `windows-latest`（および ARM64 ランナー）上で並列実行されます。
2. **GraalVM セットアップ**: Java 21 対応 GraalVM JDK を構成します。
3. **AOT コンパイル**: `mvn -B -Pnative native:compile` を実行し、`target/graal-ansible` バイナリを生成します。
4. **パッケージング**: 各 OS 用の `tar.gz` または `.zip` 配布アーカイブを作成します。
5. **チェックサム算出**: 各アセットに対して SHA-256 チェックサムを計算し、`SHA256SUMS` ファイルを自動生成します。
6. **GitHub Release 作成**: Tag 名に対応する Release を草稿 (Draft) または正式リリースとして作成し、全 OS のアーカイブおよび `SHA256SUMS` をアタッチします。

## 5. チェックサム・セキュリティと権限設定

### 5.1 チェックサム検証
配布アセットの改ざんや破損を防止するため、利用者はダウンロード後に `SHA256SUMS` を用いてハッシュ値を検証することが推奨されます。

```bash
# Linux / macOS での検証例
sha256sum -c SHA256SUMS --ignore-missing

# Windows PowerShell での検証例
Get-FileHash -Algorithm SHA256 .\graal-ansible-v1.0.0-windows-amd64.zip
```

### 5.2 実行権限およびセキュリティ保護
- **POSIX 環境 (Linux / macOS)**: 解凍後、バイナリに実行権限を付与します (`chmod +x graal-ansible`)。
- **macOS ゲートキーパー**: 署名未設定のバイナリを実行する際は、必要に応じて `xattr -d com.apple.quarantine graal-ansible` によるセキュリティ属性のクリアを行います。

## 6. 配布バイナリの動作検証仕様

配布パッケージの品質を保証するため、リリース前に以下の動作確認項目を自動・手動で検証します。

1. **バージョン情報出力**:
   ```bash
   ./graal-ansible --version
   ```
   システム上に Java または Python が存在しない状態でも、エンジンバージョンおよび GraalPy ポポリグロットランタイム情報が正しく出力されることを確認します。
2. **独立性検証**:
   `JAVA_HOME` や `PATH` から Java/Python を除外したクリーンなコンテナ環境で Playbook 実行をテストします。
3. **設定ファイルおよび環境変数の解決**:
   `ANSIBLE_CONFIG` 環境変数やカレントディレクトリの `ansible.cfg` が正しく認識・反映されることを検証します。

## 7. 関連ドキュメント

- [Native Image 最適化の実装詳細](../implementation/Native-Image-Optimization.md)
- [動作環境 (System Requirements)](../features/System-Requirements.md)
- [CI/CD 設定](CI-Setting.md)
- [CLI 仕様](../features/CLI-Specification.md)
