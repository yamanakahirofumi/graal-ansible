# Native Image 最適化の実装詳細 (Native Image Optimization)

`graal-ansible` を GraalVM Native Image でコンパイル・ビルドし、高速かつ低メモリフットプリントで完全スタンドアロン動作させるための最適化手法、コンパイラフラグ、リフレクション/リソース/JNI/シリアライゼーション設定、および GraalPy ポポリグロット組込み仕様について詳述します。

## 1. 概要と最適化目標

本プロジェクトでは、`ansible-playbook` 互換の自動化エンジンを単一のネイティブバイナリとして配布・実行可能にすることを目的としています。Native Image コンパイル（事前コンパイル: AOT）において、以下の目標を達成するための構成を定めます。

- **起動オーバーヘッドの極小化**: JVM 起動時間および JIT コンパイルのウォームアップ処理を排除し、コマンド実行時の応答速度をサブセカンドレベルへ高速化します。
- **メモリ消費量の削減**: GraalPy ポポリグロットランタイムを含め、初期メモリ使用量を最小限に抑制します。
- **完全スタンドアロン化**: 外部 JDK や別個の Python インタプリタ環境への非依存性を保証し、`--no-fallback` による純粋な Native Image バイナリを生成します。

## 2. Maven ビルドプロファイルと Native Image コンパイラオプション仕様

Native Image ビルドは、Maven の `-Pnative` プロファイルを通じて `native-image-maven-plugin`（または `org.graalvm.buildtools:native-maven-plugin`）を実行することで制御されます。

### 2.1 推奨ビルド引数 (Compiler Arguments)

`pom.xml` 内の `<imageName>graal-ansible</imageName>` および `<buildArgs>` に設定されるコンパイラオプションの仕様です。

```xml
<configuration>
    <imageName>graal-ansible</imageName>
    <mainClass>org.example.ansible.Main</mainClass>
    <buildArgs>
        <buildArg>--no-fallback</buildArg>
        <buildArg>--initialize-at-build-time=org.example.ansible.util.Constants</buildArg>
        <buildArg>--initialize-at-run-time=org.apache.sshd.common.util.security.SecurityUtils</buildArg>
        <buildArg>-H:+ReportExceptionStackTraces</buildArg>
        <buildArg>-H:ConfigurationFileDirectories=src/main/resources/META-INF/native-image/org.example.ansible</buildArg>
        <buildArg>-H:+AddAllCharsets</buildArg>
        <buildArg>--enable-http</buildArg>
        <buildArg>--enable-https</buildArg>
        <buildArg>--enable-url-protocols=http,https,jar,file</buildArg>
    </buildArgs>
</configuration>
```

### 2.2 オプションパラメータの意味と用途

| コンパイラ引数 | 種別 | 仕様と用途 |
| :--- | :--- | :--- |
| `--no-fallback` | 必須 | JVM フォールバックバイナリ（JVM を要求する形式）の生成を禁止し、純粋な Native Image バイナリの構築を保証します。 |
| `--initialize-at-build-time` | 最適化 | 不変な定数クラス（`Constants.java` 等）や純粋ユーティリティクラスをビルド時に初期化し、実行時オーバーヘッドを排除します。 |
| `--initialize-at-run-time` | 互換性 | ランダムシード生成や OS 状態に依存するセキュリティライブラリ（Apache MINA SSHD や JCA プロバイダ等）の初期化を実行時まで遅延させます。 |
| `-H:ConfigurationFileDirectories` | 設定 | `reflect-config.json`, `resource-config.json`, `jni-config.json`, `serialization-config.json` が配置されたディレクトリを明示指定します。 |
| `-H:+AddAllCharsets` | 文字コード | 日本語環境（UTF-8, Shift_JIS/Windows-31J, EUC-JP 等）のエンコーディング変換をバイナリ内に内蔵させます。 |
| `--enable-http` / `--enable-https` | ネットワーク | `WinRMConnection` (HTTPS) や `uri`/`get_url` モジュール等の通信用ソケットプロトコルを有効化します。 |

## 3. リフレクション構成仕様 (reflect-config.json)

Java の動的リフレクション機能（クラスロード、フィールドアクセス、動的メソッド呼び出し、プロキシ生成）は、AOT コンパイル時に静的解析できるように `reflect-config.json` へ明示的に登録する必要があります。

### 3.1 登録対象クラスとカテゴリ

| カテゴリ | 該当クラス / ライブラリ | 登録内容と用途 |
| :--- | :--- | :--- |
| **Java Records** | `Play`, `Task`, `Host`, `TaskResult`, `BecomeContext`, `BastionConfig`, `VaultDecryptedValue`, `Role`, `ExecutionReport` | SnakeYAML 2.x および Jackson がデシリアライズ・リフレクションアクセスするために全フィールド・コンストラクタを登録。 |
| **CLI / Options** | `org.example.ansible.cli.PlaybookCli`, `Main` | Picocli によるアノテーション解釈およびコマンドライン引数マッピング用。 |
| **JSON / SerDe** | `com.fasterxml.jackson.databind.ObjectMapper`, `java.util.LinkedHashMap`, `java.util.ArrayList` | モジュール実行結果 (JSON) およびインベントリ動的データの Java オブジェクトマッピング用。 |
| **YAML 解析** | `org.yaml.snakeyaml.constructor.Constructor`, `AnsibleYamlConstructor`, `YamlUtil` | SnakeYAML 2.x カスタムタグ (`!vault` 等) および AST ノードリフレクション生成用。 |
| **テンプレート** | `org.example.ansible.engine.filter.*`, `org.example.ansible.engine.lookup.*`, `Jinjava` | Jinjava カスタムフィルター・ルックアップ機能の動的メソッド参照用。 |
| **接続プラグイン** | `SshConnection`, `LocalConnection`, `DockerConnection`, `WinRMConnection`, Apache MINA SSHD, WinRM4J | JCA 暗号プロバイダ、動的ソケット・SSL コンテキスト生成、WinRM 内部クラス用。 |

### 3.2 代表的設定例 (JSON 構造)

#### Java Record クラスおよび AST コンストラクタ
```json
[
  {
    "name": "org.example.ansible.engine.Play",
    "allDeclaredConstructors": true,
    "allPublicConstructors": true,
    "allDeclaredFields": true,
    "allPublicMethods": true
  },
  {
    "name": "org.example.ansible.engine.Task",
    "allDeclaredConstructors": true,
    "allPublicConstructors": true,
    "allDeclaredFields": true,
    "allPublicMethods": true
  },
  {
    "name": "org.example.ansible.engine.ExecutionReport",
    "allDeclaredConstructors": true,
    "allPublicFields": true,
    "allPublicMethods": true
  }
]
```

#### CLI オプション解析 (Picocli)
```json
[
  {
    "name": "org.example.ansible.cli.PlaybookCli",
    "allDeclaredFields": true,
    "allDeclaredConstructors": true,
    "methods": [
      { "name": "run", "parameterTypes": [] }
    ]
  }
]
```

#### Jinjava カスタムフィルター・ルックアッププラグイン
```json
[
  {
    "name": "org.example.ansible.engine.filter.CombineFilter",
    "allDeclaredConstructors": true,
    "allPublicMethods": true
  },
  {
    "name": "org.example.ansible.engine.lookup.FileLookup",
    "allDeclaredConstructors": true,
    "allPublicMethods": true
  }
]
```

## 4. リソースアクセス構成仕様 (resource-config.json)

Native Image バイナリ内に含めるリソースファイルおよび動的ロード対象を `resource-config.json` に定義します。

### 4.1 内蔵が必要なリソース一覧

- **Python ランチャーおよびブリッジスクリプト**:
  - `ansible_bridge.py`: ランタイムブリッジスクリプト。
  - `ansible_launcher.py`: 標準モジュール実行ランチャー。
  - `ansible_action_launcher.py`: Action Plugin 実行ランチャー。
  - `ansible_mock_launcher.py`: モックモジュール実行ランチャー。
- **Python 標準ライブラリおよび `ansible-core` バンドル**:
  - GraalPy が参照する `site-packages/ansible/` 配下のコアファイルおよび `module_utils` ZIP バンドル (`ansible_lib.zip`)。
- **設定ファイルとログ定義**:
  - デフォルトの `ansible.cfg` 設定テンプレート、logging.properties、META-INF 記述子。

### 4.2 設定具体例 (JSON 構造)

```json
{
  "resources": {
    "includes": [
      { "pattern": "python/.*\\.py$" },
      { "pattern": "python/.*\\.zip$" },
      { "pattern": "ansible\\.cfg$" },
      { "pattern": "META-INF/native-image/.*" }
    ]
  },
  "bundles": []
}
```

## 5. JNI およびシリアライゼーション構成仕様 (jni-config.json / serialization-config.json)

### 5.1 JNI 構成 (`jni-config.json`)
GraalPy の Truffle C 拡張バインディングや、POSIX/Windows OS ネイティブ API 呼び出しに必要な C-JNI 参照を登録します。

```json
[
  {
    "name": "com.oracle.truffle.polyglot.PolyglotImpl",
    "allDeclaredFields": true,
    "allDeclaredConstructors": true
  },
  {
    "name": "org.example.ansible.util.PythonOSMock",
    "allDeclaredFields": true,
    "allPublicMethods": true
  }
]
```

### 5.2 シリアライゼーション構成 (`serialization-config.json`)
Java の標準オブジェクトシリアライゼーションや Jinjava キャッシュで永続化される例外型・状態オブジェクトを登録します。

```json
[
  { "name": "java.lang.RuntimeException" },
  { "name": "java.util.HashMap" },
  { "name": "java.util.ArrayList" }
]
```

## 6. GraalPy ポポリグロット組込み最適化仕様

Native Image 内で Python ランタイム（GraalPy）を起動する際のパフォーマンス最適化およびフラグ制御です。

### 6.1 コンテキスト構築とオプションフラグ

`TaskExecutor` において GraalPy コンテキストを構築する際、Native Image 環境では以下のシステムプロパティおよび Context オプションが適用されます。

- **`python.CoreHome` / `python.StdLibHome`**: Native Image リソース内に埋め込まれた GraalPy のコアライブラリリソースパスを設定します。
- **`python.IsolateNativeModules=true`**: ネイティブモジュール分離を維持し、管理ノードのクラッシュを防止します。
- **`python.PosixModuleBackend=native`**: Linux/macOS 環境での高速な POSIX システム呼び出しを有効にします。

### 6.2 Pre-initialization (ビルド時事前初期化)

GraalVM Native Image コンパイラは、ビルド時に Python コンテキストを一部事前初期化 (`Context.newBuilder("python").build()`) してイメージ内に組み込む機能（Context Pre-initialization）をサポートします。

- **効果**: 実行時における初回の `ansible_bridge.py` 読み込み・初期化時間を大幅に短縮し、タスク起動応答速度を向上させます。

## 7. GraalVM Tracing Agent による自動構成生成パイプライン

手動によるリフレクション設定の漏れを防ぐため、`native-image-configure-plugin` を用いた自動設定生成パイプラインが導入されています。

### 7.1 Tracing Agent 実行フロー

1. **JUnit テスト実行時**:
   Maven Surefire プラグインに `-agentlib:native-image-agent=config-output-dir=target/native-image-config` オプションを付与してテストスイートを実行します。
2. **実行パスの自動収集**:
   `PlaybookExecutorTest`, `BuiltinModulesIntegrationTest`, `SshConnectionTest`, `WinRMConnectionTest` 等の統合テストが実行される中で、実際に使用されたリフレクション、JNI、リソースアクセス、動的プロキシがトレースエージェントにより自動記録されます。
3. **マージとリポジトリ同期**:
   生成された JSON ファイル群を `src/main/resources/META-INF/native-image/org.example.ansible/` へマージ（`config-merge-dir`）し、バージョン管理下へ配置します。

## 8. 関連ドキュメント

- [検討事項・TODOリスト](../TODO-Details.md)
- [GraalPy 統合の詳細](../tech/GraalPy-Integration.md)
- [CI/CD 設定](../tech/CI-Setting.md)
