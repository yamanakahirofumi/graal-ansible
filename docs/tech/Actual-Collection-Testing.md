# 実際のコレクションを用いた自動テストの実施方法

本ドキュメントでは、GraalPy 環境で実際の Ansible コレクション（`ansible.builtin` や外部コレクション）を使用して動作確認を行うための具体的なテスト実施方法について述べます。

## 1. テストの目的と分類

実際のコレクションを用いたテストは、検証の目的に応じて以下の2つのカテゴリに分類して実施します。

### 1.1 ランタイム互換性テスト (Runtime Compatibility Test)
`graal-ansible` の実行エンジン（Playbook 解析等）を介さず、**「対象の Python モジュールが GraalPy 上で単独で動作するか」**のみを検証します。
*   **目的**: Ansible モジュールの Python コードが、GraalPy の制限（C拡張の互換性、特定ライブラリの非互換など）に抵触しないかを早期に発見する。
*   **方法**: `PythonModule` ラッパーを直接呼び出し、最小限の引数で実行結果（JSON）が返ってくるかを確認する。

### 1.2 機能充足テスト (Functional Sufficiency Test)
`graal-ansible` の全機能を組み合わせて、**「Playbook が期待通りに実行され、ターゲットの状態が変更されるか」**を検証します。
*   **目的**: YAML 解析、変数展開、タスク実行エンジンの整合性を含めたエンドツーエンドの品質を確認する。
*   **方法**: 実際の Playbook ファイルを読み込み、`PlaybookExecutor` を通じて実行し、ファイルシステム等の副作用を検証する。

## 2. 実行環境の自動構築 (Setup)

本プロジェクトでは、ビルドプロセスの一環として Python 実行環境を自動構築します。

1.  **依存関係のインストール**:
    *   Maven ビルド時に `scripts/setup-python-env.sh` が実行されます。
    *   `requirements.txt` に記載された `ansible-core` および依存ライブラリが `target/python-packages` にインストールされます。
2.  **ライブラリパスの自動検出**:
    *   Java 側の `PythonEnv.getSitePackagesFromEnv()` が、`target/python-packages` を自動的に検出し、GraalPy コンテキストの `sys.path` に追加します。
3.  **ターゲットノードの準備 (Testcontainers)**:
    *   統合テストでは、**Testcontainers** を使用して SSH 接続可能な Docker コンテナ（`mokojarasi/test-python-sshd`）を起動し、ターゲットノードとして利用します。これにより、ホスト環境を汚染することなく、クリーンな状態でモジュール実行の結果（ファイルの作成、ユーザー追加等）を検証できます。

## 3. テストクラスの構造 (JUnit 5 + Testcontainers)

`ActualModuleIntegrationTest.java` における標準的なテスト構造を以下に示します。

```java
@Testcontainers
@EnabledOnOs(OS.LINUX)
class ActualModuleIntegrationTest {

    @Container
    private GenericContainer<?> targetNode = new GenericContainer<>(DockerImageName.parse("mokojarasi/test-python-sshd:latest"))
            .withExposedPorts(22)
            .withEnv("USER_PASSWORD", "testuser")
            .waitingFor(Wait.forListeningPort());

    private TaskExecutor taskExecutor;
    private SshConnection connection;

    @BeforeEach
    void setUp() {
        taskExecutor = new TaskExecutor();
        connection = new SshConnection(
                targetNode.getHost(),
                targetNode.getMappedPort(22),
                "testuser",
                "testuser"
        );
        connection.connect();
    }

    @Test
    void testActualFileModule() {
        String remotePath = "/tmp/touch-test.txt";
        Task task = new Task("test_file", "file", Map.of(
                "path", remotePath,
                "state", "touch"
        ));

        // Actual SSH connection を使用してターゲットノードで実行
        TaskResult result = taskExecutor.execute(task, BecomeContext.empty(), connection, null);

        assertTrue(result.success());
        assertTrue(result.changed());

        // ターゲットノードの状態を直接確認
        var execResult = connection.execCommand("ls " + remotePath, BecomeContext.empty(), null);
        assertEquals(0, execResult.exitCode());
    }
}
```

## 4. GraalPy コンテキストでのモジュール実行インターフェース

Action Plugin やモジュールの実行は、管理ノード側の GraalPy 上で行われます。

1.  **引数の受け渡し**: Java から `complex_args` (Map形式) を Python コンテキストにバインドします。
2.  **実行ブリッジ**: `ansible_bridge.py` および `ansible_action_launcher.py` が、Java の `ITaskExecutor` や `Connection` と Python 側の Action Plugin を橋渡しします。
3.  **結果のキャプチャ**: モジュールが標準出力に書き出した JSON を Java 側で `TaskResult` に変換します。

## 5. 冪等性の検証

実際のコレクションを使用する最大の利点は、冪等性の挙動を正確に確認できることです。

```java
@Test
void testIdempotency() {
    var task = new Task("test_copy", "copy", Map.of(
        "content", "hello",
        "dest", "/tmp/hello.txt"
    ));

    // 1回目の実行: changed=true
    TaskResult firstResult = taskExecutor.execute(task, BecomeContext.empty(), connection, null);
    assertTrue(firstResult.isChanged());

    // 2回目の実行: changed=false
    TaskResult secondResult = taskExecutor.execute(task, BecomeContext.empty(), connection, null);
    assertFalse(secondResult.isChanged(), "Second execution should not change anything");
}
```

## 6. 注意事項

- **Docker の必要性**: 統合テストの実行には Docker 環境が必要です。
- **プラットフォーム制限**: 一部のモジュール（`user`, `group` 等）はターゲットが Linux であることを前提としているため、テスト実行環境の OS 制約に注意してください。

## 7. システムレベルの依存関係 (System level dependencies)

一部のモジュールは、ターゲットノードまたは管理ノードにおいて追加のシステムパッケージを必要とします。統合テストでは、これらのパッケージを事前にインストールする必要があります。

| モジュール | 必要なシステムパッケージ / ライブラリ | 備考 |
| :--- | :--- | :--- |
| `deb822_repository` | `python3-debian` | APT 形式の解析に必要。 |
| `expect` | `python3-pexpect` | インタラクティブな応答制御に必要。 |
| `subversion` | `subversion` | SVN リポジトリ操作に必要。 |
| `pip` | `python3-pip` | Debian Bookworm 以降では `--break-system-packages` が必要。 |
| `git` | `git` | Git リポジトリ操作に必要。 |
| `cron` | `cron` | cron ジョブの管理に必要。 |
| `iptables` | `iptables` | ファイアウォールルールの操作に必要。 |
