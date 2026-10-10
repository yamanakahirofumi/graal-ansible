# ロギング方針

デバッグおよび保守のため、適切なロギングを行います。

## 1. 基本方針

本プロジェクト (`graal-ansible`) では、管理ノード側の内部動作状況、タスク実行ループ、接続プラグイン処理、および例外情報を正確に記録するため、Java 標準のロギング API (`java.util.logging`) を使用します。

### 1.1 目的と設計原則
- **標準出力と標準エラーの分離**: Ansible Playbook の実行出力（`Callback` 経由の PLAY RECAP やタスク結果）は `stdout` へ出力されます。システムログ（JUL）は `stderr` または独立したログファイルに分離して出力し、Playbook CLI 出力の視認性を妨げないように設計します。
- **統一定量ログ管理**: 実行エンジン (Engine)、インベントリ解析 (Inventory)、接続プラグイン (Connection)、GraalPy ブリッジ (Module/Bridge) の各レイヤーで一貫したログレベル定義とフォーマットを採用します。
- **zero-dependency**: 外部ログライブラリ (Logback, Log4j2 等) に依存せず、Java 標準ライブラリ (`java.util.logging`) のみで構成することで、GraalVM Native Image のビルドサイズ削減と起動高速化を図ります。

---

## 2. ロガーの定義と初期化規約

すべての Java クラスにおいて、ロガーのインスタンス化および保持方法は以下の統一基準に従います。

### 2.1 インスタンス化の定型句
クラスごとにクラス名を識別子とする定数フィールドとして `Logger` を保持します。

```java
package org.example.ansible.engine;

import java.util.logging.Logger;

public class TaskQueueManager {
    private static final Logger LOGGER = Logger.getLogger(TaskQueueManager.class.getName());

    // ...
}
```

### 2.2 定型規則
- **フィールド命名**: 必ず `private static final Logger LOGGER` と命名します。
- **ロガー名**: 必ず `ClassName.class.getName()` を使用し、ハードコードされた文字列による名前付けを禁止します。
- **カプセル化**: 外部クラスからのアクセスを防止するため、他パッケージへのロガー共有を行わずクラス単位で保持します。

---

## 3. ログレベルと Ansible 冗長性オプション (-v) のマッピング

`ansible-playbook` コマンドライン引数の冗長性フラグ (`-v`, `-vv`, `-vvv`, `-vvvv`) に応じて、Java `java.util.logging.Level` のログ出力レベルを動的に調整します。

### 3.1 ログレベルマッピングマトリクス

| CLI オプション | JUL ログレベル (`Level`) | 主な出力対象・メッセージ区分 |
| :------------- | :----------------------- | :------------------------------------------------------------------------------------------------- |
| (オプション無)  | `WARNING` / `SEVERE`     | 非推奨警告、構成上の軽微な不備 (`WARNING`)、システム停止に伴う致命的例外 (`SEVERE`)。              |
| `-v`           | `INFO`                   | Playbook 実行開始/終了、Play/Task の進行状況、インベントリ読み込み完了等のハイレベルなマイルストーン。|
| `-vv`          | `CONFIG` / `FINE`        | 実行コンテキスト、全 22 段階の変数優先順位解決結果、`BecomeContext` 評価結果、ファイルパス解決情報。 |
| `-vvv`         | `FINER`                  | 接続プラグイン (SSH, Docker, WinRM, Local) の低レイヤー実行コマンド、GraalPy ブリッジ呼び出し引数。 |
| `-vvvv`        | `FINEST`                 | 転送スクリプトの生ペイロード、標準入力/標準出力バイナリバッファ、Jinja2 AST 評価詳細トレース。      |

### 3.2 コマンドライン設定によるレベル動的適用
`PlaybookCli` オプション解析時に、指定された Verbosity フラグに基づいてルートロガー (`Logger.getLogger("org.example.ansible")`) のレベルを更新します。

```java
public void configureLogging(int verbosity) {
    Logger rootLogger = Logger.getLogger("org.example.ansible");
    Level level = switch (verbosity) {
        case 0 -> Level.WARNING;
        case 1 -> Level.INFO;
        case 2 -> Level.FINE;
        case 3 -> Level.FINER;
        default -> Level.FINEST;
    };
    rootLogger.setLevel(level);
    for (Handler handler : rootLogger.getHandlers()) {
        handler.setLevel(level);
    }
}
```

---

## 4. ログフォーマットとハンドラー構成

システムログは、標準エラー出力 (`ConsoleHandler`) およびファイル出力 (`FileHandler`) を通じて出力・保存されます。

### 4.1 フォーマットパターン
出力されるログメッセージは、可読性とログ解析を容易にするため、ISO-8601 形式のタイムスタンプと発生元クラスを含めます。

```text
2026-10-24 14:32:10.123 [INFO] org.example.ansible.engine.PlaybookExecutor - Playbook execution started: site.yml
2026-10-24 14:32:10.456 [FINE] org.example.ansible.inventory.InventoryManager - Resolved 12 hosts from inventory/hosts
```

- **フォーマット文字列パターン**: `%1$tF %1$tT.%1$tL [%4$s] %3$s - %5$s%6$s%n`
  - `%1$tF %1$tT.%1$tL`: 日時 (`YYYY-MM-DD HH:MM:SS.mmm`)
  - `%4$s`: ログレベル (e.g., `INFO`, `FINE`, `SEVERE`)
  - `%3$s`: ロガー名 (クラス名)
  - `%5$s`: ログメッセージ
  - `%6$s`: 例外スタックトレース (存在時)

### 4.2 ログファイル保存 (`ANSIBLE_LOG_PATH`)
環境変数 `ANSIBLE_LOG_PATH` が指定されている場合、`FileHandler` を自動的に構成し、ログを指定されたファイルパスに永続化します。

- **デフォルト設定**: `ANSIBLE_LOG_PATH` 未指定時はコンソール (`System.err`) のみの出力とします。
- **回転・アペンド**: ログファイルは追記モード (`append = true`) でオープンされ、複数回実行時のログ履歴を維持します。

---

## 5. GraalPy Python ログとのブリッジ統合

管理ノード上で GraalPy を介して実行される Python コード (Action Plugin, Inventory Plugin, Callback) のログを、Java 側の `java.util.logging` システムへ統合します。

### 5.1 Python `logging` ブリッジ構成
`ansible_bridge.py` 内で Python 標準の `logging` モジュールにカスタムロギングハンドラー (`JavaJULHandler`) を登録します。

```python
import logging
import java # GraalPy Polyglot

class JavaJULHandler(logging.Handler):
    def emit(self, record):
        msg = self.format(record)
        logger_name = record.name
        # Java 側の Logger を取得して発行
        java_logger = java.type('java.util.logging.Logger').getLogger(logger_name)
        if record.levelno >= logging.ERROR:
            java_logger.severe(msg)
        elif record.levelno >= logging.WARNING:
            java_logger.warning(msg)
        elif record.levelno >= logging.INFO:
            java_logger.info(msg)
        else:
            java_logger.fine(msg)
```

### 5.2 ログコンテキストの保持
Python 側で発生したログレコード（モジュール内部警告やデバッグ情報）は、`ansible.module_utils` や `ansible.plugins` などのロガー名を保持したまま Java 側へ直接伝播され、一元管理されます。

---

## 6. 例外ログのハンドリングとスタックトレース出力

エラーハンドリング時におけるログ出力の禁止事項と適切な記録方法を定義します。

### 6.1 ログ記録規約
- **標準エラーへの直接出力の禁止**: `System.err.println()` や `e.printStackTrace()` の直接呼び出しは全コードベースで禁止します。必ず `LOGGER` を使用してください。
- **例外オブジェクトの添付**: 例外捕獲時にログを記録する場合は、例外オブジェクト `e` を第 3 引数に渡してスタックトレースを含めます。

```java
// 正しい記述例
try {
    connection.connect();
} catch (IOException e) {
    LOGGER.log(Level.SEVERE, "Failed to establish SSH connection to " + host.getName(), e);
    throw new UnreachableException("Connection failed for " + host.getName(), e);
}
```

### 6.2 回復可能例外と不可逆例外の分離
- **回復可能なエラー (WARNING)**: 再試行可能（`until/retries`）なタスク失敗や、フォールバックが機能する接続エラーは `Level.WARNING` で記録し、スタックトレースの出力レベルを抑制します。
- **不可逆な致命的エラー (SEVERE)**: Playbook 解析失敗、未定義変数参照（`strict` モード）、システムリソース不足等の実行中断エラーは `Level.SEVERE` でフルスタックトレースを記録します。

---

## 7. スレッドセーフとパフォーマンス最適化

並列実行戦略 (`FreeStrategy`) や非同期処理 (`ThreadPoolExecutor`) におけるログ出力の安全性とパフォーマンス最適化について規定します。

### 7.1 マルチスレッド実行におけるスレッドセーフ
- `java.util.logging.Logger` および `Handler` は Java 標準でスレッドセーフが保証されています。
- 複数のホストタスクが同時にログを出力しても、コンソール出力やファイル書き込みの競合・破損は防止されます。

### 7.2 パフォーマンス最適化 (文字列結合のガード)
ログレベルが無効化されている場合（例: 無効な `Level.FINE`）における無駄な文字列結合や `String.format` の計算コストを回避するため、以下の最適化ルールを適用します。

1. **Guard 条件チェック**: 重いオブジェクトの `toString()` や complex formatting を伴うログ出力では、事前チェックを実施します。
   ```java
   if (LOGGER.isLoggable(Level.FINE)) {
       LOGGER.fine("Resolved variables for host " + host.getName() + ": " + variableMap.dumpAll());
   }
   ```
2. **パラメータ付きログメソッドの利用**: 単純なパラメータ挿入には `LOGGER.log(Level, msg, Object[])` を使用します。
   ```java
   LOGGER.log(Level.FINER, "Executing command: {0} on host: {1}", new Object[]{command, host.getName()});
   ```
