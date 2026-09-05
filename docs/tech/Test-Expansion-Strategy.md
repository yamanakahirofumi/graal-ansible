# テストケース拡充戦略 (Test Expansion Strategy) - フェーズ 1

## 1. 目的

フェーズ 1 の完了条件である「Ansible 本体の完全ロードと基本動作の実現」を確実なものにするため、テストケースを拡充し、信頼性を向上させる。

## 2. 戦略の柱

以下の 4 つの観点からテストケースを拡充する。

### 2.1 モジュールロード互換性テスト (Module Load Verification)
- **現状**: `ModuleLoadVerificationTest.java` により、Linux、macOS、および Windows 実行コンテキスト（`PythonOSMock`）における `ansible.builtin` 全 72 モジュールの存在チェックおよび GraalPy によるコンイル・インポート確認が 100% 完了し、CI に統合済み。
- **実装内容**:
    - **マルチ OS 対応**: LinuxContext、macOSContext (`MacOSHandler`)、および WindowsContext (`WindowsHandler`) の各擬似環境下で全 72 モジュールの `py_compile` テストを自動化。
    - **依存関係の解決**: `ansible.module_utils.basic` および `text.converters` の正常インポートを `testVerifyModuleUtilsImportable` にて事前検証。
    - **自動化**: Polyglot Context を活用した軽量テストスイートとして JUnit 5 実行環境に組み込み済み。

### 2.2 主要モジュールの機能網羅テスト (Functional Integration Tests)
- **現状**: `ping`, `copy`, `file`, `stat`, `template` の正常系テストが CI 環境に統合済み。
- **拡充計画**:
    - **重要モジュールの追加**: `command`, `shell`, `setup` (facts 取得), `lineinfile` 等、Playbook 構築に不可欠なモジュールの追加。
    - **パラメータ・バリエーション**: 各モジュールの主要なオプション（`owner`, `group`, `mode`, `force`, `validate` 等）の組み合わせによる検証。
    - **Check Mode 検証**: 全ての主要モジュールにおいて、`check_mode: yes` 指定時にシステムに変更を加えず、期待される `changed` フラグが返ることを確認。

### 2.3 異常系・境界値テスト (Negative & Edge-case Testing)
- **現状**: 異常系および Unicode テストを `NegativeIntegrationTest.java` および `UnicodeIntegrationTest.java` として実装済み。
- **拡充計画**:
    - **パラメータエラー**: 不正な引数、型違い、必須引数の欠落時における適切なエラーメッセージの返却（実装済み）。
    - **環境エラー**: ターゲットファイルが存在しない、書き込み権限がない、ディスクフル、タイムアウト等の異常系挙動の検証。
    - **Testcontainers の活用**: 特定の OS 設定や権限（sudo 等）が必要なケースにおいて、Testcontainers を**ターゲットノード**として使用しテストを実施。
    - **Unicode 対応**: パス名やファイル内容、変数における Unicode 文字（日本語等）の適切な取り扱い（実装済み）。

### 2.4 エンジン・制御機能の統合検証 (Engine & Control Flow)
- **現状**: `when`, `loop` の基本機能に加え、`TaskControlAdvancedTest.java` により、`block`/`rescue`/`always` の入れ子構造、`register` 変数を利用した動的なループや条件分岐、ネストされた変数へのアクセス、および `ignore_errors` と `failed_when` の組み合わせ等の複雑な制御フローが検証済み。
- **拡充計画**:
    - **動的変数の解決**: さらなる複雑なデータ構造（Jinja2 フィルターを介した変形等）における再帰的な解決の検証。
    - **エラーハンドリング**: 特権昇格（become）失敗時や接続エラー（unreachable）がブロック構造に与える影響の網羅的テスト。
    - **Become (権限昇格)**: sudo/su の実行パスと、パスワード入力を必要としない構成（NOPASSWD）での確実な動作。

### 2.5 実行環境コンテキストの固定 (Execution Context Consistency)
- **現状**: 現在のフェーズ 1 では、GraalPy の Context 設定として `python.IsolateNativeModules` および `python.PosixModuleBackend` を使用している。
- **方針**: フェーズ 1 の完了までは、これらのネイティブモジュール分離および POSIX バックエンド設定を変更せず、現在の安定した実行環境を維持する。
- **理由**: 設定変更による予期せぬサイドエフェクトを避け、モジュールロードの互換性検証に集中するため。

## 3. 実装計画

| フェーズ | 項目 | 内容 | 優先度 |
| :--- | :--- | :--- | :--- |
| 完了 | ロードテストの OS 展開 | macOS/Windows での全モジュールインポート確認 (`ModuleLoadVerificationTest.java`) | 完了 |
| 短期 (1-2週) | 正常系テストの追加 | `command`, `shell`, `setup` の基本テスト実装 | 高 |
| 中期 (3-4週) | 異常系テストの導入 | エラーメッセージの検証、権限不足時の挙動確認 | 中 |
| 中期 (3-4週) | 変数・制御フローの拡充 | 複雑な Playbook 構造のテストセット作成 | 中 |
| 長期 | CI パフォーマンス改善 | テスト並列実行と Native Image キャッシュの活用 | 低 |

## 4. 実行・管理方法

- **Test Runner**: JUnit 5 と GraalPy Polyglot API を引き続き使用。
- **Infrastructure**: 必要に応じて **Testcontainers** を**ターゲットノード**として導入し、`become` (sudo) や特定のネットワーク状態を再現する。
- **Test Data**: `src/test/resources/playbooks` 配下に、再利用可能な Playbook スニペットと想定結果（YAML/JSON）を蓄積。
- **Coverage**: モジュールごとに「ロード済 (△)」「正常系検証済 (○)」「異常系検証済 (◎)」のステータスを `Module-Support-Status.md` で管理。
