# CI/CD 設定

本プロジェクトでは、GitHub Actions を使用してビルドとテストの自動化を行っています。

## 1. GitHub Actions 設定

### 1.1 ワークフローの概要
GitHub へのプッシュ（Push）またはプルリクエスト（Pull Request）が作成された際に、以下のプロセスが自動的に実行されます。

1. **チェックアウト**：リポジトリのソースコードを取得します。
2. **GraalVM のセットアップ**：ネイティブビルドに必要な GraalVM JDK をセットアップします。`python` コンポーネントを明示的に指定して、Ansible コアの実行環境を構築します。
3. **マルチプラットフォーム・マトリックス**：Ubuntu, Windows の各環境でテストを実行し、OS非依存性を検証します。
4. **ビルドとテスト**：`mvn verify` を実行し、ユニットテストおよび結合テストを実施します。
5. **テスト結果の送信**: JUnit 形式のテスト結果（XML）を Codecov へ転送します（Ubuntu 環境のみ）。
6. **Native Image ビルド**：各OS向けのネイティブバイナリを生成し、動作確認を行います。

### 1.2 設定ファイルの例 (`.github/workflows/build.yml`)
以下は、Maven を使用した標準的なワークフロー構成です。

```yaml
name: Java CI with Maven

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    strategy:
      matrix:
        os: [ubuntu-latest, windows-latest]
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
        distribution: 'graalvm-community'
        version: 'latest'
        components: 'python'
        github-token: ${{ secrets.GITHUB_TOKEN }}
        native-image-job-reports: 'true'
    - name: Build and Test
      run: mvn -B verify
    - name: Upload test results to Codecov
      if: always() && runner.os == 'Linux'
      uses: codecov/test-results-action@v1
      with:
        token: ${{ secrets.CODECOV_TOKEN }}
        directory: ./target/surefire-reports/
    - name: Build Native Image
      run: mvn -Pnative native:compile
```

## 2. テスト結果の可視化
本プロジェクトでは、JUnit 形式のテスト結果を Codecov に送信することで、テストの実行状況を可視化しています。

### 2.1 測定と転送の仕組み
1. `mvn verify` 実行時に Maven Surefire Plugin がテストを実行し、`target/surefire-reports/` に XML レポートを生成します。
2. GitHub Actions 上で、これらの XML レポートを Codecov サービスにアップロードします。
3. Codecov 上でテストの成功率、失敗数、実行時間などを確認し、品質管理に役立てます。

## 3. CI の目的
- **OS非依存性の検証**：マルチプラットフォーム・マトリックスにより、全サポートOSでの動作を毎コミットごとに保証します。
- **Native Imageの継続的検証**：AOTコンパイル特有の問題を早期に発見します。
- **自動テスト**：JUnit によるテストを自動実行し、ロジックの正しさを検証します。
