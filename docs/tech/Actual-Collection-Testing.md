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

## 2. テストの全体像 (フェーズ構成)
上記テストを支えるため、以下の3つのフェーズで構成されます。

1.  **依存関係の準備 (Setup)**:
    *   **環境の分離 (Virtualenv)**: ホスト環境の汚染を防ぐため、GraalPy 上で仮想環境 (venv) を構築します。
    *   **ansible-core のインストール**: `pip install ansible-core` を実行し、`ansible.builtin` モジュールや `ansible.module_utils` を取得します。
    *   **コレクションのインストール**: `ansible-galaxy collection install` を使用して必要な外部コレクションを `target/ansible_collections` にインストールします。
    *   Maven の `exec-maven-plugin` または JUnit 5 の `@BeforeAll` を活用して、これらの構築を自動化します。
2.  **テスト実行 (Execute)**:
    *   構築した仮想環境内の Python インタプリタ、またはライブラリパスを GraalPy コンテキストに紐づけます。
    *   `ANSIBLE_COLLECTIONS_PATH` を環境変数として設定します。
    *   モジュールが必要とする引数を JSON 形式で作成し、Ansible 互換の一時ファイル経由で渡します。
3.  **状態検証 (Verify)**:
    *   モジュールが標準出力（JSON）に返した実行結果を検証します。
    *   ファイル操作（`copy` など）を伴う場合は、実際に副作用が発生したかを Java 側で検証します。
    *   冪等性のテスト（2回実行して `changed=false`）も実施します。

## 2. 実行環境の動的構築フロー

ホストマシンに Ansible がインストールされていない環境を前提とし、以下の手順でテスト環境を自動構築します。

### 2.1 仮想環境の構築例 (Java/JUnit 側)
```java
@BeforeAll
static void setupAnsibleEnvironment() throws Exception {
    Path venvPath = Paths.get("target/test-venv");
    if (!Files.exists(venvPath)) {
        // 1. venv の作成
        new ProcessBuilder("graalpy", "-m", "venv", venvPath.toString()).start().waitFor();
        
        // 2. ansible-core と依存ライブラリのインストール
        String pipPath = venvPath.resolve("bin/pip").toString();
        new ProcessBuilder(pipPath, "install", "ansible-core==2.18.0").start().waitFor();
    }
}
```

### 2.2 GraalPy コンテキストの設定
インストールしたライブラリを Java から利用するため、GraalPy コンテキストの `python.executable` や `python.path` を設定します。

```java
Context context = Context.newBuilder("python")
    .option("python.executable", venvPath.resolve("bin/graalpy").toString())
    .allowAllAccess(true)
    .build();
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
