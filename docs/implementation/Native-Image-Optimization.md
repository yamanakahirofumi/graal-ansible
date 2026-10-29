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
        <buildArg>--initialize-at-build-time=org.example.ansible.util.OSHandlerFactory</buildArg>
        <buildArg>-H:+ReportExceptionStackTraces</buildArg>
        <buildArg>-H:ConfigurationFileDirectories=src/main/resources/META-INF/native-image/org.example/graal-ansible</buildArg>
    </buildArgs>
</configuration>
```

### 2.2 オプションパラメータの意味と用途

| コンパイラ引数 | 種別 | 仕様と用途 |
| :--- | :--- | :--- |
| `--no-fallback` | 必須 | JVM フォールバックバイナリ（JVM を要求する形式）の生成を禁止し、純粋な Native Image バイナリの構築を保証します。 |
| `--initialize-at-build-time` | 最適化 | 不変な定数・ファクトリクラス（`OSHandlerFactory.java` 等）や純粋ユーティリティクラスをビルド時に初期化し、実行時オーバーヘッドを排除します。 |
| `--initialize-at-run-time` | 互換性 | ランダムシード生成や OS 状態に依存するセキュリティライブラリ（Apache MINA SSHD や JCA プロバイダ等）の初期化を実行時まで遅延させます。 |
| `-H:ConfigurationFileDirectories` | 設定 | `reflect-config.json`, `resource-config.json`, `native-image.properties` が配置されたディレクトリ（`src/main/resources/META-INF/native-image/org.example/graal-ansible/`）を明示指定します。 |
| `-H:+ReportExceptionStackTraces` | デバッグ | ビルド時例外発生時に詳細なスタックトレースを出力します。 |

## 3. リフレクション構成仕様 (reflect-config.json)

Java の動的リフレクション機能（クラスロード、フィールドアクセス、動的メソッド呼び出し、プロキシ生成）は、AOT コンパイル時に静的解析できるように `reflect-config.json` へ明示的に登録する必要があります。

### 3.1 登録対象クラスとカテゴリ

| カテゴリ | 該当クラス / ライブラリ | 登録内容と用途 |
| :--- | :--- | :--- |
| **Java Records / Data Models** | `Playbook`, `Play`, `Task`, `TaskResult` | SnakeYAML 2.x および Jackson がデシリアライズ・リフレクションアクセスするために全フィールド・コンストラクタを登録。 |
| **CLI / Options** | `org.example.ansible.cli.PlaybookCli` | Picocli によるアノテーション解釈およびコマンドライン引数マッピング用 (`run`, `call` メソッド)。 |
| **JSON / SerDe** | `com.fasterxml.jackson.databind.ObjectMapper`, `java.util.LinkedHashMap`, `java.util.ArrayList`, `java.util.HashMap` | モジュール実行結果 (JSON) およびインベントリ動的データの Java オブジェクトマッピング用。 |

### 3.2 リフレクション構成 (JSON 構造)

`src/main/resources/META-INF/native-image/org.example/graal-ansible/reflect-config.json`:

```json
[
  {
    "name": "org.example.ansible.engine.Playbook",
    "allDeclaredConstructors": true,
    "allPublicConstructors": true,
    "allDeclaredFields": true,
    "allPublicMethods": true
  },
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
    "name": "org.example.ansible.engine.TaskResult",
    "allDeclaredConstructors": true,
    "allPublicConstructors": true,
    "allDeclaredFields": true,
    "allPublicMethods": true
  },
  {
    "name": "org.example.ansible.cli.PlaybookCli",
    "allDeclaredFields": true,
    "allDeclaredConstructors": true,
    "methods": [
      { "name": "run", "parameterTypes": [] },
      { "name": "call", "parameterTypes": [] }
    ]
  },
  {
    "name": "java.util.LinkedHashMap",
    "allDeclaredConstructors": true,
    "allPublicConstructors": true
  },
  {
    "name": "java.util.ArrayList",
    "allDeclaredConstructors": true,
    "allPublicConstructors": true
  },
  {
    "name": "java.util.HashMap",
    "allDeclaredConstructors": true,
    "allPublicConstructors": true
  },
  {
    "name": "com.fasterxml.jackson.databind.ObjectMapper",
    "allDeclaredConstructors": true,
    "methods": [ { "name": "<init>", "parameterTypes": [] } ]
  }
]
```

## 4. リソースアクセス構成仕様 (resource-config.json)

Native Image バイナリ内に含めるリソースファイルおよび動的ロード対象を `resource-config.json` に定義します。

### 4.1 内蔵が必要なリソース一覧

- **YAML 設定・Playbook テンプレートファイル**: `.*\.yml`, `.*\.yaml`
- **Python ランチャーおよびスクリプト**: `.*\.py` (`ansible_bridge.py`, `ansible_launcher.py`, `ansible_action_launcher.py` 等)

### 4.2 リソース構成 (JSON 構造)

`src/main/resources/META-INF/native-image/org.example/graal-ansible/resource-config.json`:

```json
{
  "resources": {
    "includes": [
      { "pattern": ".*\\.yml" },
      { "pattern": ".*\\.yaml" },
      { "pattern": ".*\\.py" }
    ]
  },
  "bundles": []
}
```

## 5. JNI およびシリアライゼーション構成仕様 (jni-config.json / serialization-config.json)

### 5.1 JNI 構成 (`jni-config.json`)
GraalPy の Truffle C 拡張バインディングや、POSIX/Windows OS ネイティブ API 呼び出しに必要な C-JNI 参照を必要に応じて登録します。

### 5.2 シリアライゼーション構成 (`serialization-config.json`)
Java の標準オブジェクトシリアライゼーションやキャッシュ等で永続化される例外型・状態オブジェクトを登録します。

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
   生成された JSON ファイル群を `src/main/resources/META-INF/native-image/org.example/graal-ansible/` へマージ（`config-merge-dir`）し、バージョン管理下へ配置します。

## 8. 関連ドキュメント

- [検討事項・TODOリスト](../TODO-Details.md)
- [GraalPy 統合の詳細](../tech/GraalPy-Integration.md)
- [CI/CD 設定](../tech/CI-Setting.md)
