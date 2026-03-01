# 実際のコレクションを用いた自動テストの実施方法

本ドキュメントでは、GraalPy 環境で実際の Ansible コレクション（`ansible.builtin` や外部コレクション）を使用して動作確認を行うための具体的なテスト実施方法について述べます。

## 1. テストの全体像

実際のコレクションを用いたテストは、モックを使用せずに GraalPy 上で本物の Python モジュールを実行する「結合テスト」の性質を持ちます。テストの信頼性を高めるため、以下の3つのフェーズで構成されます。

1.  **依存関係の準備 (Setup)**: `ansible-galaxy` を使用して必要なコレクションを一時ディレクトリにインストールする。
2.  **テスト実行 (Execute)**: JUnit 5 から GraalPy コンテキストを起動し、対象モジュールを実行する。
3.  **状態検証 (Verify)**: モジュールの戻り値（JSON）と、実際のファイルシステムへの副作用を検証する。

## 2. 依存関係の管理と動的取得

テストに必要なコレクションは、プロジェクト内の `src/test/resources/ansible/requirements.yml` に定義します。

### 2.1 requirements.yml の例
```yaml
collections:
  - name: ansible.posix
    version: 1.5.4
  - name: community.general
    version: 8.0.2
```

### 2.2 ansible-galaxy によるインストール
JUnit の `@BeforeAll` またはビルドスクリプト（Maven）の `process-test-resources` フェーズで、以下のコマンドを実行し、テスト用の一時ディレクトリにコレクションを配置します。

```bash
ansible-galaxy collection install -r requirements.yml -p ./target/ansible_collections
```

## 3. テストクラスの構造案

JUnit 5 と `JUnit @TempDir` を使用したテストクラスの構成案を以下に示します。

```java
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ActualCollectionTest {

    @TempDir
    Path testWorkDir;

    private Path collectionsPath;

    @BeforeAll
    void setupCollections() throws Exception {
        collectionsPath = testWorkDir.resolve("ansible_collections");
        // ansible-galaxy コマンドを実行してコレクションをインストール
        ProcessBuilder pb = new ProcessBuilder(
            "ansible-galaxy", "collection", "install", 
            "ansible.posix", "-p", collectionsPath.toString()
        );
        assertEquals(0, pb.start().waitFor(), "Collection installation failed");
    }

    @Test
    void testPosixPatchModule() {
        // 1. Arrange
        var task = new Task("ansible.posix.patch", Map.of(
            "src", "test.patch",
            "dest", testWorkDir.resolve("target_file").toString()
        ));

        // 2. Act
        // GraalPyコンテキストを通じてモジュールを実行
        TaskResult result = pythonExecutor.execute(task, collectionsPath);

        // 3. Assert
        assertTrue(result.isChanged());
        assertTrue(Files.exists(testWorkDir.resolve("target_file")));
    }
}
```

## 4. GraalPy コンテキストでのモジュール実行インターフェース

Ansible モジュールは標準入力から JSON を受け取り、標準出力に JSON を返します。Java 側では以下のステップでモジュールを呼び出します。

1.  **環境変数の設定**: `ANSIBLE_COLLECTIONS_PATH` を、`ansible-galaxy` でインストールしたパスに設定する。
2.  **モジュール実行用 Python スクリプトの生成**:
    GraalPy 上で Ansible の `AnsibleModule` クラスが正しく動作するように、引数ファイルを一時的に作成し、対象の `.py` ファイルをロードして実行するラッパーを用意します。

### 4.1 実行フローの詳細
1.  `args.json` の作成: タスク引数を JSON 形式で一時ファイルに書き出す。
2.  Python スクリプトの実行:
    ```python
    import sys
    import json
    # 引数ファイルのパスを sys.argv にセットする等の前処理
    # 対象モジュールの main() を呼び出す
    ```
3.  標準出力のキャプチャ: モジュールが `print(json.dumps(result))` した内容を Java 側で `TaskResult` に変換する。

## 5. 冪等性の検証

実際のコレクションを使用する最大の利点は、冪等性の挙動を正確に確認できることです。

```java
@Test
void testIdempotency() {
    var task = new Task("ansible.builtin.copy", Map.of(
        "content", "hello",
        "dest", testWorkDir.resolve("hello.txt").toString()
    ));

    // 1回目の実行: changed=true
    TaskResult firstResult = pythonExecutor.execute(task, collectionsPath);
    assertTrue(firstResult.isChanged());

    // 2回目の実行: changed=false
    TaskResult secondResult = pythonExecutor.execute(task, collectionsPath);
    assertFalse(secondResult.isChanged(), "Second execution should not change anything");
}
```

## 6. パフォーマンスへの配慮

実際のコレクションを用いたテストは実行時間が長くなるため、以下の対策を検討します。

- **コレクションのキャッシュ**: `target/` ディレクトリ配下に一度インストールしたコレクションは、`requirements.yml` に変更がない限り再利用する。
- **並列実行の制御**: ファイルシステムへの副作用を伴うため、同じディレクトリを共有するテストは並列実行を避けるか、個別に `@TempDir` を使用する。
