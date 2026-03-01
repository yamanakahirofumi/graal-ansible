# OS 抽象化レイヤーの仕様

ターゲット OS (Linux, macOS, Windows など) による挙動やコマンドの差異を吸収し、タスク実行の一貫性を保つための抽象化設計を定義します。

## 1. 概要

Ansible モジュールやタスクが、実行対象 OS の違いを意識せずに動作できるよう、共通のインターフェースを提供します。これにより、OS 非依存の実行制御と保守性を高めます。

## 2. 抽象化の対象

以下の項目について、OS ごとの差異を抽象化します。

| 項目 | 抽象化の目的 | 例 (Linux vs Windows) |
| :--- | :--- | :--- |
| **ファイルパス** | パス区切り文字、ドライブレターの扱い | `/etc/config` vs `C:\ProgramData\config` |
| **パッケージ管理** | 共通の「パッケージインストール」操作 | `apt/yum` vs `winget/choco` |
| **サービス管理** | サービスの開始、停止、状態確認 | `systemd/init` vs `Service Control Manager` |
| **権限管理** | 特権昇格の手順 | `sudo/su` vs `runas` |
| **シェル実行** | 共通のコマンド実行環境 | `/bin/sh` vs `PowerShell/cmd.exe` |

## 3. 設計方針：OS 固有ハンドラ

`OSHandler` インターフェースを定義し、ターゲット OS ごとに具体的な実装（`LinuxHandler`, `MacOSHandler`, `WindowsHandler`）を提供します。

### インターフェースのメソッド例

- `getTempDir()`: OS 固有の一時ディレクトリパスを返す。
- `getJoinPath(String... parts)`: OS に適したパス区切り文字で結合する。
- `getPackageManager()`: その OS で利用可能な標準パッケージマネージャのインスタンスを返す。
- `getServiceManager()`: その OS で利用可能なサービス管理システムのインスタンスを返す。

## 4. ターゲット OS の判定メカニズム

タスク実行開始時（または `setup` モジュール実行時）に、ターゲットホストのファクト（Facts）を収集し、適切な `OSHandler` を選択します。

### 判定基準
- `ansible_os_family`
- `ansible_distribution`
- `ansible_system` (uname -s 等の結果)

## 5. 実装上の考慮事項

- **パス表現の統一**: 内部的には `java.nio.file.Path` を活用しつつ、リモートホストの OS が制御ノード（Local）と異なる場合に備え、独自のパス処理ロジックを用意します。
- **冪等性の確保**: OS ごとに異なる「状態確認コマンド」を抽象化し、モジュール側で一貫した冪等性チェックロジックを書けるようにします。
- **Native Image 対応**: OS 判定においてシステムプロパティ（`os.name` 等）を利用する場合、実行時の値が適切に反映されるよう注意します。
