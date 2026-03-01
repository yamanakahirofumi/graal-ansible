# Native Image 最適化 (Native Image Optimization)

`graal-ansible` を GraalVM Native Image としてビルドし、高速な起動と低メモリフットプリントを実現するための最適化戦略と設定について定義します。

## 1. リフレクション設定 (`reflect-config.json`)

Native Image では実行時のリフレクションが制限されるため、以下のコンポーネントに対してリフレクション定義を明示的に行う必要があります。

- **YAML 解析 (SnakeYAML)**: Playbook をマッピングする Java Record クラス。
- **テンプレートエンジン (Jinjava)**: コンテキストとして渡されるカスタムオブジェクトや Record のメソッド。
- **CLI (picocli)**: コマンドライン引数をバインドするフィールドやメソッド。
- **SSH (Apache MINA SSHD)**: 内部で使用される暗号化アルゴリズムやプロバイダ。

### 1.1 設定の生成方針
- `native-image-configure` エージェントを活用し、テスト実行時のアクセス履歴からベースとなる `reflect-config.json` を自動生成します。
- 必要に応じて、`META-INF/native-image/reflect-config.json` に手動でエントリを追加します。

## 2. リソースの埋め込み (`resource-config.json`)

実行時に必要な外部ファイルをバイナリ内に埋め込みます。

- **GraalPy ランタイム**: Python モジュールの実行に必要な Python 標準ライブラリの一部。
- **テンプレートファイル**: デフォルトで提供する Jinja2 テンプレートや設定ファイル。
- **ログ設定**: `logback.xml` 等のロギング構成ファイル。

## 3. ビルド時初期化と実行時初期化

起動速度を向上させるため、副作用のないクラスはビルド時に初期化（Build-time initialization）します。

- **ビルド時初期化**: 
    - ユーティリティクラス
    - ログライブラリのファクトリクラス
    - 不変な定数を持つクラス
- **実行時初期化**: 
    - ネットワーク接続（Apache MINA SSHD）に関連するクラス
    - OS 固有の情報を保持するクラス

## 4. GraalPy の最適化

Native Image 内で Python スクリプトを高速に実行するための設定です。

- **Polyglot API の活用**: Java と Python 間のデータ交換において、JSON へのシリアライズを避け、Polyglot 共有オブジェクトを直接操作します。
- **プリコンパイル**: 頻用される Python モジュールをビルド時にあらかじめ解析・最適化する手法を検討します。

## 5. モニタリングとデバッグ

Native Image 特有の問題（セグメンテーションフォールトやリフレクション不足）を特定するため、以下の機能を有効にします。

- **ダンプ出力**: `---help` オプションなどで、Native Image ビルド時の情報を出力可能にします。
- **JFR (JDK Flight Recorder) 対応**: ネイティブバイナリ上でのパフォーマンス分析を可能にします。
