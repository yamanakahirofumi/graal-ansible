# OS 抽象化レイヤーの仕様

ターゲット OS (Linux, macOS, Windows など) による挙動やコマンドの差異を吸収し、タスク実行の一貫性を保つための抽象化設計および実装詳細を定義します。

## 1. 概要

Ansible モジュールやタスクが、実行対象 OS の違いを意識せずに動作できるよう、共通のインターフェースおよび OS ハンドラ実装を提供します。これにより、OS 非依存の実行制御と高い保守性を実現します。

## 2. 抽象化の対象

以下の項目について、OS ごとの差異を抽象化します。

| 項目 | 抽象化の目的 | 例 (Linux / macOS vs Windows) |
| :--- | :--- | :--- |
| **ファイルパス** | パス区切り文字、ドライブレター、エスケープの扱い | `/etc/config` vs `C:\ProgramData\config` |
| **一時ディレクトリ** | OS 標準の一時ファイル作成パスの返却 | `/tmp` vs `C:\Temp` |
| **権限管理 (sudo)** | 特権昇格 (sudo) のサポート判定 | `sudo` 対応 (`true`) vs 非対応 (`false`) |
| **シェル実行** | コマンド実行時の基本シェルおよび実行フラグ | `/bin/sh -c` vs `cmd.exe /c` |

## 3. 設計と主要コンポーネント

### 3.1 OSHandler インターフェース (`OSHandler.java`)

`OSHandler` インターフェースは、OS 依存処理を共通化するための主要メソッドを定義します。

| メソッド名 | 戻り値の型 | デフォルト動作 / 説明 |
| :--- | :--- | :--- |
| `getTempDir()` | `String` | `System.getProperty("java.io.tmpdir")`（または OS 固有のパス）を返します。 |
| `getSeparator()` | `String` | OS 固有のパス区切り文字（`/` または `\`）を返します。 |
| `getJoinPath(String... parts)` | `String` | 引数で指定されたパス要素を `getSeparator()` で結合して返します。 |
| `getOSFamily()` | `String` | OS ファミリ名（例: `"Linux"`, `"Windows"`, `"Darwin"`）を返します。 |
| `getShellExecutable()` | `List<String>` | コマンド実行用シェルの実行ファイルとフラグ（例: `["/bin/sh", "-c"]`）を返します。 |
| `supportsSudo()` | `boolean` | `sudo` による権限昇格をサポートするかどうかを返します（デフォルト `true`）。 |

### 3.2 具象 OS ハンドラ実装

#### LinuxHandler (`LinuxHandler.java`)
- **`getTempDir()`**: `"/tmp"`
- **`getSeparator()`**: `"/"`
- **`getOSFamily()`**: `"Linux"`
- **`getShellExecutable()`**: `["/bin/sh", "-c"]`
- **`supportsSudo()`**: `true`

#### WindowsHandler (`WindowsHandler.java`)
- **`getTempDir()`**: `"C:\\Temp"`
- **`getSeparator()`**: `"\\"`
- **`getOSFamily()`**: `"Windows"`
- **`getShellExecutable()`**: `["cmd.exe", "/c"]`
- **`supportsSudo()`**: `false`

#### MacOSHandler (`MacOSHandler.java`)
- `LinuxHandler` を継承し、macOS 固有の OS ファミリ名を返却します。
- **`getOSFamily()`**: `"Darwin"`
- その他のパス・シェル設定および `supportsSudo()` は `LinuxHandler` の実装をそのまま継承します。

### 3.3 OSHandlerFactory (`OSHandlerFactory.java`)

実行環境のシステムプロパティ `os.name` を参照し、動的に適切な `OSHandler` インスタンスを生成・返却します。

```java
public class OSHandlerFactory {
    public static OSHandler getHandler() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("linux")) {
            return new LinuxHandler();
        } else if (osName.contains("win")) {
            return new WindowsHandler();
        } else if (osName.contains("mac")) {
            return new MacOSHandler();
        }
        return new LinuxHandler(); // 汎用 POSIX フォールバック
    }
}
```

### 3.4 PythonOSMock (`PythonOSMock.java`)

GraalPy 実行環境（`ansible_bridge.py`）において、Python の `os` モジュール関数（`os.stat`, `os.path`, `os.makedirs` 等）を Java 側でエミュレートするためのブリッジクラスです。

- **`OSHandler` の委譲**: 内部で保持する `OSHandler` インスタンスに基づいてパス正規化（`normalizePath`）を行い、OS ごとのパス区切り文字やドライブレターの自動補正を行います。
- **マルチプラットフォームテストでの活用**: ユニットテスト時（例: `ModuleLoadVerificationTest.java`）に `PythonOSMock(new WindowsHandler())` や `PythonOSMock(new MacOSHandler())` を明示的に渡すことで、特定の OS 環境をシミュレートしたモジュールロード・実行検証が可能です。

## 4. 実装上の考慮事項

- **パス表現の統一と正規化**: 内部的には `java.nio.file.Path` や `Paths.get` を活用しつつ、ターゲット OS の違いに応じて `/` と `\` の自動置換を行います。
- **権限昇格 (Become) との連携**: `LocalConnection` や `DockerConnection` は `OSHandler.supportsSudo()` の値を参照し、Windows 環境などで不要な `sudo` ラップ処理を行わないよう制御します。
- **Native Image 対応**: システムプロパティ（`os.name`, `java.io.tmpdir` 等）の読み込みは GraalVM Native Image の実行時にも正しく評価されるように設計されています。
