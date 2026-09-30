# CI/CD 設定

本プロジェクトでは、GitHub Actions を使用してビルドとテストの自動化を行っています。

## 1. GitHub Actions 設定

### 1.1 ワークフローの概要
GitHub へのプッシュ（Push）またはプルリクエスト（Pull Request）が作成された際に、以下のプロセスが自動的に実行されます。

1. **チェックアウト**：リポジトリのソースコードを取得します。
2. **テスト用 Docker イメージのビルド (Linux のみ)**：`src/test/docker/` 配下の Dockerfile から `test-python-sshd` イメージを自動ビルドします。
3. **GraalVM のセットアップ**：`graalvm/setup-graalvm@v1` アクションを使用し、Java 21 対応の GraalVM JDK ディストリビューションをセットアップします。
4. **マルチプラットフォーム・マトリックス**：`ubuntu-latest` および `windows-latest` の各環境でテストを実行し、OS 非依存性を検証します。
5. **ビルドとテスト**：`mvn -B verify` を実行し、ユニットテストおよび結合テストを実施します。
6. **テスト結果の送信 (Linux のみ)**：`codecov/codecov-action@v5` を用いて、`./target/surefire-reports/` 内の XML レポートを Codecov サービスへ転送します。
7. **Native Image ビルド**：`mvn -Pnative native:compile` を実行し、各 OS 向けのネイティブバイナリのコンパイルおよび動作確認を行います。

### 1.2 設定ファイルの構成 (`.github/workflows/build.yml`)

リポジトリで運用されている実際の設定ファイルの内容です。

```yaml
name: Java CI with Maven

on:
  push:
    branches: [ main ]
    paths:
      - 'src/**'
  pull_request:
    branches: [ main ]
    paths:
      - 'src/**'

jobs:
  build:
    strategy:
      matrix:
        os: [ ubuntu-latest, windows-latest ]
    runs-on: ${{ matrix.os }}
    steps:
      - uses: actions/checkout@v4
      - name: Build test Docker image
        if: runner.os == 'Linux'
        run: |
          docker build -t mokojarasi/test-python-sshd:latest src/test/docker/
      - name: Set up GraalVM
        uses: graalvm/setup-graalvm@v1
        with:
          java-version: '21'
          distribution: 'graalvm'
          github-token: ${{ secrets.GITHUB_TOKEN }}
          native-image-job-reports: 'true'
      - name: Build and Test
        run: mvn -B verify
      - name: Upload test results to Codecov
        if: always() && runner.os == 'Linux'
        uses: codecov/codecov-action@v5
        with:
          token: ${{ secrets.CODECOV_TOKEN }}
          report_type: test_results
          directory: ./target/surefire-reports/
      - name: Build Native Image
        run: mvn -Pnative native:compile
```

## 2. テスト結果の可視化

本プロジェクトでは、JUnit 形式のテスト結果を Codecov に送信することで、テストの実行状況を可視化しています。

### 2.1 測定と転送の仕組み
1. `mvn -B verify` 実行時に Maven Surefire Plugin がテストを実行し、`target/surefire-reports/` に XML レポートを生成します。
2. GitHub Actions 上で、これらの XML レポートを Codecov サービスにアップロードします。
3. Codecov 上でテストの成功率、失敗数、実行時間などを確認し、品質管理に役立てます。

## 3. CI の目的
- **OS 非依存性の検証**：マルチプラットフォーム・マトリックスにより、全サポート OS での動作を毎コミットごとに保証します。
- **Native Image の継続的検証**：AOT コンパイル特有の問題を早期に発見します。
- **自動テスト**：JUnit によるテストを自動実行し、ロジックの正しさを検証します。

## 4. バージョン管理の遵守
CI 環境で利用するツール（GraalVM 等）やコンテナイメージのバージョン指定については、[品質方針](Quality-Policy.md#5-バージョン管理方針)に基づき、**バージョンの引き下げ（ダウングレード）を禁止**します。常に安定性とセキュリティを考慮したバージョン選定を行ってください。
