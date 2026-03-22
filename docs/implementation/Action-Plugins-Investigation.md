# Action Plugin 実行ロジックの実装調査報告

**最終更新日: 2026-03-19**

## 1. 概要
Ansible Action Plugin を制御ノード（管理ノード）上の GraalPy で実行するための基盤整備を行いました。Action Plugin は制御ノード上で実行され、必要に応じてターゲットノードに対してモジュール実行を指示する特殊なプラグインです。

## 2. 実装した内容

### 2.1 Java 側の基盤整備
- **ITaskExecutor への callback 追加**: `execute_from_python` メソッドを追加し、Python 側から Java のタスク実行ロジックを再帰的に呼び出せるようにしました。
- **TaskExecutor での実行ロジック**: `executeActionPlugin` メソッドを実装し、GraalPy コンテキストの初期化、必要な Java オブジェクト（`Connection`, `TaskExecutor`）のバインド、および実行ブリッジ（`ansible_action_launcher.py`）の呼び出しを統合しました。
- **コンテキスト管理**: `ThreadLocal` を使用して、再帰呼び出し時にも `BecomeContext` や `Connection` を適切に維持する仕組みを導入しました。

### 2.2 Python 側の実行ブリッジ (`ansible_action_launcher.py`)
- **ActionBase のモンキーパッチ**: `ActionBase._execute_module` をパッチし、Java 側の `execute_from_python` を呼び出すように変更しました。これにより、Action Plugin 内部でのモジュール実行が Java 経由で行われます。
- **コアクラスのモック**: Action Plugin のインスタンス化に必要な `Task`, `PlayContext` などのクラスをモック化しました。

## 3. 調査結果と課題

### 3.1 発生している問題
`ActionPluginTest` の実行において、Ansible のコアモジュール（特に `ansible.plugins.action.ActionBase`）をインポートする際に、GraalPy が以下のエラーでクラッシュする現象が確認されました。

```
com.oracle.truffle.api.CompilerDirectives$ShouldNotReachHere
Caused by: com.oracle.graal.python.builtins.objects.cext.common.LoadCExtException$ApiInitException
```

### 3.2 調査した依存関係
以下のモジュールにおいて C 拡張の初期化失敗またはインポートエラーが発生し、対策（モック化）を試みましたが、完全な回避には至りませんでした。

1.  **cryptography / cffi**: 多くのコアコンポーネントが依存していますが、GraalPy 上でのネイティブライブラリのロードに失敗します。
2.  **PyYAML (_yaml)**: C 拡張のロードを回避するためにモック化が必要です。
3.  **MarkupSafe (_speedups)**: インポート時にクラッシュの原因となる可能性があります。
4.  **ansible.executor.module_common**: Action Plugin が依存する多くの内部クラスを保持していますが、そのインポート過程で連鎖的にクラッシュが発生します。

### 3.3 回避策の検討
- **アプローチ A (モック化の徹底)**: `ActionBase` 自体を継承せず、Java 側で完全にエミュレートしたプロパティを持つオブジェクトを Action Plugin に渡す。
    - *難易度*: 極めて高い。Ansible の既存 Action Plugin は `ActionBase` の内部実装に強く依存しているため。
- **アプローチ B (GraalPy 環境の改善)**: より互換性の高い GraalPy バージョンの利用や、ネイティブモジュールの適切なプリインストールを行う。
    - *難易度*: 環境に依存。

## 4. 今後の推奨事項
現在の環境では、Ansible の重厚なコアライブラリをそのまま GraalPy 上でロードすることに限界があります。Action Plugin の完全な互換性を維持するため、**「動かない import 先だけを最小限に実装・モック化する」** 方針を推奨します。

1.  **最小限の依存関係エミュレーション**:
    - `ansible.plugins.action.ActionBase` のように、Action Plugin が直接継承するクラスのメソッド（`_execute_module` 等）のみを、Java ブリッジを介して動作するように再実装します。
    - インポートエラーを引き起こすネイティブモジュール（`cryptography` 等）や、GraalPy 上で重いライブラリをスタブ（空のクラスや関数）に置き換えます。
2.  **本物の Action Plugin コードの利用**:
    - プラグイン本体（`copy.py`, `template.py` 等）には手を加えず、周辺環境を整えることでそのまま実行します。
3.  **軽量エミュレータ（Java）の活用**:
    - 性能要件が非常に厳しい場合や、スタブ化による対応が困難な場合に限り、[Java Action Plugin](Java-Action-Plugins.md) として完全に再実装します。

---
本調査により、Java-Python 間の双方向呼び出し基盤は確立されましたが、Ansible Core の重厚な依存関係が GraalPy 上での実行における主要な障壁であることが明らかになりました。
