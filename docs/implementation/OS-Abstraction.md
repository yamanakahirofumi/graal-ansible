# OS 抽象化レイヤーの仕様

ターゲット OS (Linux, macOS, Windows など) による挙動やコマンドの差異を吸収し、タスク実行の一貫性を保つための抽象化設計および実装詳細を定義します。

## 1. 概要

Ansible モジュールやタスクが、実行対象 OS の違いを意識せずに動作できるよう、共通のインターフェースおよび OS ハンドラ実装を提供します。これにより、OS 非依存の実行制御と高い保守性を実現します。

なお、本プロジェクトにおける **Windows サポートの主要ターゲットはサーバー側（ターゲットノード）の管理**であり、管理ノード（制御ノード/クライアント側）の動作構造は従来通り（Linux / macOS 中心）とします。また、ターゲットノード上で OS 非互換のタスク（POSIX 固有のシステム操作等）や未サポートのモジュールが実行された場合は、過度な無理のあるエミュレーションは行わず、本家 Ansible 仕様に準拠して失敗（エラー）として安全に検出・停止させる方針を採用しています。

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

- **`OSHandler` の委譲とパス正規化 (`normalizePath`)**:
  - 内部で保持する `OSHandler` インスタンス（`LinuxHandler`, `WindowsHandler`, `MacOSHandler`）に基づいて、Java-Python 境界で受け渡されるパス文字列を正規化します。
  - **正規化ルール**:
    1. **スラッシュ前置補正**: Java から渡される `/C:\path\...` や `/\\server\share` のように先頭に過剰なスラッシュが付与された絶対パスを検知し、ドライブレター直前または UNC パス直前の不要スラッシュを除去します。
    2. **セパレータ統一**: Windows ハンドラ使用時は全スラッシュをバックスラッシュ（`\`）へ、POSIX ハンドラ使用時は全バックスラッシュをスラッシュ（`/`）へ統一します。
    3. **バイト列処理 (`b'...'`)**: Python からデコードされずに渡された `b'...'` 形式の文字列リフレクション表現を自動的にデコード・クリーンアップします。

- **`statPython(path)` と `os.stat_result` フィールドマッピング**:
  - Java の `java.nio.file.Files.readAttributes` を使用して物理ファイルのファイル属性を取得し、Python の `os.stat_result` 互換のタプル/オブジェクト構造体として返却します。

| Python `os.stat_result` フィールド | Java 取得元属性 (`BasicFileAttributes` / `PosixFileAttributes`) | デフォルト/フォールバック値 |
| :--- | :--- | :--- |
| `st_mode` | パーミッションビット (`PosixFilePermissions`) + ファイル種別フラグ (`S_IFREG` / `S_IFDIR`) | `0100644` (ファイル) / `0040755` (ディレクトリ) |
| `st_ino` | `fileKey()` (inode 番号) | `0` |
| `st_dev` | デバイス ID | `0` |
| `st_nlink` | ハードリンク数 | `1` |
| `st_uid` | `owner()` (POSIX ユーザー ID) | `0` (root 相当) |
| `st_gid` | `group()` (POSIX グループ ID) | `0` (root 相当) |
| `st_size` | `size()` (バイト長) | `0` |
| `st_atime` | `lastAccessTime().toMillis() / 1000.0` (エポック秒) | 実行時現在時刻 |
| `st_mtime` | `lastModifiedTime().toMillis() / 1000.0` (エポック秒) | 実行時現在時刻 |
| `st_ctime` | `creationTime().toMillis() / 1000.0` (エポック秒) | 実行時現在時刻 |

- **`_raise_file_not_found` による Polyglot 例外伝播メカニズム**:
  - `statPython` 実行時に指定されたファイルが存在しない場合、Java 側から直接 Python の `FileNotFoundError` 例外インスタンスをスローすることは Polyglot 境界の制約により不可能です。
  - そのため、`ansible_bridge.py` 側で定義された `_raise_file_not_found(path)` ヘルパー関数を Java から逆呼び出し（Callback）することで、Python ランタイムレベルの `FileNotFoundError(errno.ENOENT, "No such file or directory", path)` を安全かつ確実に発生させます。

- **POSIX 関数の Windows モック (Windows 実行コンテキスト用スタブ)**:
  - Windows 環境では Unix 固有の POSIX システムコール（`geteuid`, `getuid`, `chown`, `lchown`, `lchmod`, `setegid`, `seteuid`, `setgid`, `setuid` 等）が存在しないため、Ansible Core 内の POSIX 依存コードの呼び出しで例外が発生するのを防止します。
  - これらの関数は、固定値（`0`）または成功を示す No-op (何もしない) スタブとして動作させます。

- **マルチ OS シミュレーションテスト機構**:
  - `PythonOSMock` はコンストラクタで任意の `OSHandler` を受け入れる設計となっています（`new PythonOSMock(new WindowsHandler())`）。
  - これにより、Linux や macOS 上で動作しているビルドマシン（CI 環境等）であっても、Windows 実行コンテキスト（`WindowsHandler`）を明示的に指定することで、Windows 特有のパス正規化やシェルプロンプト処理をエミュレートした単体テスト（例: `ModuleLoadVerificationTest.java`）を 100% 決定論的に実行可能です。

## 4. 実装上の考慮事項と GraalPy 相互運用

- **パス表現の統一と正規化**: 内部的には `java.nio.file.Path` や `Paths.get` を活用しつつ、ターゲット OS の違いに応じて `/` と `\` の自動置換を行います。
- **権限昇格 (Become) との連携**: `LocalConnection` や `DockerConnection` は `OSHandler.supportsSudo()` の値を参照し、Windows 環境などで不要な `sudo` ラップ処理を行わないよう制御します。
- **Native Image 対応**: システムプロパティ（`os.name`, `java.io.tmpdir` 等）の読み込みは GraalVM Native Image の実行時にも正しく評価されるように設計されています。
- **ターゲットノード Windows サポート方針**: Windows ターゲットノード（サーバー）制御を主軸とし、タスク自体が Windows 未対応である場合はエミュレーションせず自然にエラー終了させます。
- **関連ドキュメント**:
  - [動作環境仕様](../features/System-Requirements.md)
  - [モジュールとモックの対応リファレンス](../features/Module-Mock-Reference.md)
  - [Mock 実装リファレンス](Mock-Implementation-Reference.md)
  - [GraalPy 統合の詳細](../tech/GraalPy-Integration.md)
