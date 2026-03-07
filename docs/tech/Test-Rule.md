# テストケース作成ルール

本プロジェクトにおけるテストケース作成の一般的なガイドラインです。品質維持のため、以下の原則に従ってテストを記述してください。

## 1. テストの構成 (AAAパターン)
テストメソッド内は、原則として以下の3つのセクションに分けて記述してください。

1. **Arrange (準備)**：Playbookオブジェクトの生成やインベントリの設定。
2. **Act (実行)**：テスト対象の実行エンジン呼び出し。
3. **Assert (検証)**：タスクの実行結果（changed=trueなど）の確認。

```java
// 例
// Arrange (準備)
Task task = new Task("debug", Map.of("msg", "hello"));

// Act (実行)
TaskResult result = executor.execute(task);

// Assert (検証)
assertTrue(result.isSuccess());
```

## 2. カバレッジの取得
本プロジェクトでは、コード網羅率（カバレッジ）を測定し、テストの十分性を確認します。

- **使用ツール**：JaCoCo (Java Code Coverage Library)
- **目標値**：[品質方針](Quality-Policy.md) を参照
- **実行方法**：
  ```bash
  mvn verify
  ```
  実行後、`target/site/jacoco/index.html` にレポートが生成されます。

## 3. 注意事項
- **独立性**：各テストケースは独立しており、実行順序に依存しないようにしてください。
- **カバレッジ**：正常系だけでなく、異常系（境界値、エラーケース）も網羅するようにしてください。
- **可読性**：1つのテストメソッドで検証する内容は、原則として1つの目的に絞ってください。

## 4. クロスプラットフォームテストの方針 (Cross-platform Testing Policy)
本プロジェクトは、管理ノード（エンジン）が Windows を含むマルチプラットフォームで動作することを目標としています。一方で、実行される Ansible モジュール自体は、各モジュールがサポートする OS でのみ動作すればよいものとします。

- **エンジン・コア機能**: Playbook 解析、変数展開、タスク実行制御などは、全 OS (Linux, macOS, Windows) でテストを実施します。
- **モジュール・コレクション**:
  - `ansible.builtin` などの実際のモジュールを用いたテストは、そのモジュールが本家 Ansible でサポートしている OS 環境でのみ実施します。
  - JUnit の `@EnabledOnOs` を使用して、非対応 OS でのテスト実行を適切にスキップしてください。

## 5. SSH 接続のテスト (Testing SSH Connections)
SSH を介した接続（`SshConnection` 等）をテストする場合は、**Testcontainers** の SSH モジュールを使用して、一時的な SSH サーバーコンテナを起動して検証を行います。

- **使用ライブラリ**: `org.testcontainers:ssh`
- **目的**: 実際のネットワーク経由での認証、コマンド実行、ファイル転送が正しく行われるかを検証する。
- **実装例**:
  ```java
  @Testcontainers
  class SshConnectionTest {
      @Container
      private static final SshContainer sshd = new SshContainer()
          .withPassword("ansible")
          .withCommand("sleep infinity");

      @Test
      void testExecCommand() {
          // sshd コンテナの情報を利用して SshConnection を構築しテスト
      }
  }
  ```
