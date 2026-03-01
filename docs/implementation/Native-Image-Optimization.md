# Native Image 最適化の詳細設計 (Native Image Optimization)

`graal-ansible` を GraalVM Native Image でビルドし、高速かつ低メモリで動作させるための最適化手法と設定について定義します。

## 1. リフレクション設定 (Reflection Config)

Java の動的な機能（リフレクション、動的プロキシ、リソースアクセス）は、Native Image ビルド時に静的に解析される必要があります。解析できないものは `reflect-config.json` 等に記述します。

### 1.1 重点対象ライブラリ
- **SnakeYAML**: Playbook のパース時に Record クラスのコンストラクタをリフレクションで呼び出すため、全実行モデルクラスの登録が必要です。
- **Jackson**: モジュールの戻り値 (JSON) をパースする際に必要です。
- **Jinjava**: テンプレート展開時の動的なプロパティアクセスに必要です。
- **Picocli**: コマンドライン引数のマッピングに必要です（`picocli-codegen` による自動生成を推奨）。

### 1.2 設定の自動生成
- `native-image-configure-plugin` を活用し、JUnit テスト実行時にトレースエージェントを走らせて設定ファイルを自動生成するプロセスを導入します。

## 2. リソース管理 (Resource Management)

バイナリに内蔵すべきリソースファイルを `resource-config.json` に定義します。

- **Python スクリプト**: GraalPy で実行する Ansible モジュール本体および `module_utils`。
- **設定ファイル**: デフォルトの `ansible.cfg` 相当のファイルや、内部で使用するプロパティファイル。

## 3. GraalPy の最適化

Native Image 内で Python ランタイム（GraalPy）を効率的に動かすための設定です。

- **Pre-initialization**: ビルド時に Python コンテキストを初期化しておくことで、実行時の起動時間を短縮します。
- **Frozen Modules**: Python の標準ライブラリをバイナリに含め、起動時のファイル I/O を削減します。

## 4. ビルドプロファイル

Maven の `native` プロファイルを使用して、ビルドオプションを管理します。

```xml
<arg>--no-fallback</arg>
<arg>--initialize-at-build-time=org.example.ansible.util.Constants</arg>
<arg>-H:+ReportExceptionStackTraces</arg>
```

- `--no-fallback`: JVM なしで単体動作するバイナリを強制します。
- `--initialize-at-build-time`: ビルド時に初期化可能なクラスを指定し、実行時のオーバーヘッドを削減します。
