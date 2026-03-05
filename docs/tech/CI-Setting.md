# CI/CD 設定

本プロジェクトでは、GitHub Actions を使用してビルドとテストの自動化を行っています。

## 1. GitHub Actions 設定

### ワークフローの概要
GitHub へのプッシュ（Push）またはプルリクエスト（Pull Request）が作成された際に、以下のプロセスが自動的に実行されます。

1. **チェックアウト**：リポジトリのソースコードを取得します。
2. **GraalVM のセットアップ**：ネイティブビルドに必要な GraalVM JDK をセットアップします。
3. **マルチプラットフォーム・マトリックス**：Ubuntu, Windows, macOS の各環境でテストを実行し、OS非依存性を検証します。
4. **ビルドとテスト**：`mvn verify` を実行し、ユニットテストおよび結合テストを実施します。
5. **Native Image ビルド**：各OS向けのネイティブバイナリを生成し、動作確認を行います。

### 設定ファイルの例 (`.github/workflows/build.yml`)
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
        os: [ubuntu-latest, windows-latest, macos-latest]
    runs-on: ${{ matrix.os }}
    steps:
    - uses: actions/checkout@v4
    - name: Set up GraalVM
      uses: graalvm/setup-graalvm@v1
      with:
        java-version: '21'
        distribution: 'graalvm'
        version: '23.1.2'
        github-token: ${{ secrets.GITHUB_TOKEN }}
        native-image-job-reports: 'true'
    - name: Build and Test
      run: mvn -B verify
    - name: Build Native Image
      run: mvn -Pnative native:compile
```

## 2. CI の目的
- **OS非依存性の検証**：マルチプラットフォーム・マトリックスにより、全サポートOSでの動作を毎コミットごとに保証します。
- **Native Imageの継続的検証**：AOTコンパイル特有の問題を早期に発見します。
- **自動テスト**：JUnit によるテストを自動実行し、ロジックの正しさを検証します。
