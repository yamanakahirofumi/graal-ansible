# ビルド・配布方法

本プロジェクトでは、Ansible互換のCLIツールとして高速な起動と配布の容易さを実現するため、GraalVM Native Imageを用いたネイティブバイナリ形式で配布します。

## 1. 配布形式
- **ネイティブバイナリ**：GraalVMの `native-image` を用いて、JavaプログラムをOSごとの実行可能バイナリ（Windows: .exe, Linux/macOS: バイナリ）にコンパイルします。
- **実行形式**：
    - **単一バイナリ**：ランタイムを含まない単一のバイナリファイルとして配布します。ユーザー環境にJavaをインストールする必要はありません。
    - **GitHub Releases**：CI(GitHub Actions)によりビルドされた各OS向けのバイナリをGitHubのリリースページから提供します。

## 2. メリット
- **高速な起動**：JITコンパイルが不要なため、コマンド実行からタスク開始までのオーバーヘッドが極めて小さくなります。
- **低メモリフットプリント**：JVM全体の起動を必要としないため、メモリ消費を抑えられます。
- **導入の容易さ**：依存ライブラリやランタイムが全てバイナリに内包されるため、パスを通すだけで即座に利用可能です。

## 3. 関連ツール
- [GraalVM Native Image](https://www.graalvm.org/native-image/)：ネイティブバイナリ生成エンジン
- [Maven Native Plugin](https://graalvm.github.io/native-build-tools/latest/maven-plugin.html)：Mavenビルド工程への統合
