# GraalPy 互換性テクニカルリファレンス (Technical Reference)

**最終更新日: 2026-03-22**

## 1. 概要
本ドキュメントでは、Ansible Action Plugin および各種モジュールを制御ノード（管理ノード）上の GraalPy で実行する際に直面した技術的な課題とその解決策（ワークアラウンド）を記録します。

## 2. インポート時のクラッシュ問題 (ApiInitException)

### 2.1 発生現象
`ActionPluginTest` などの実行において、Ansible のコアモジュール（特に `ansible.plugins.action.ActionBase`）をインポートする際に、GraalPy が以下のエラーで停止することがあります。

```
com.oracle.truffle.api.CompilerDirectives$ShouldNotReachHere
Caused by: com.oracle.graal.python.builtins.objects.cext.common.LoadCExtException$ApiInitException
```

### 2.2 原因
GraalPy が Python の C 拡張（ネイティブモジュール）をロードする際に、シンボルの不一致やライブラリの初期化失敗が発生するために起こります。Ansible が依存する `cryptography`, `PyYAML (_yaml)`, `MarkupSafe (_speedups)` などが代表的な例です。

### 2.3 解決策
`ansible_bridge.py` において、これらのモジュールを強制的に `None` またはスタブに置き換える（モンキーパッチ）ことで、ロード処理自体を回避します。

```python
# ansible_bridge.py での例
for mname in ['cryptography', 'yaml._yaml', 'markupsafe._speedups']:
    sys.modules[mname] = None
```

## 3. 実装上の工夫とブリッジ仕様

### 3.1 `ansible_bridge.py` の役割
Java と Python の間にあるギャップを埋めるためのランタイム初期化スクリプトです。
- **環境変数の同期**: Java 側の環境変数を `os.environ` へ反映。
- **標準ライブラリのモック化**: Linux 固有のモジュール（`grp`, `pwd` 等）を Windows 環境でも動作するようスタブを提供。
- **AnsibleModule のパッチ**: `_load_params` をパッチして、Java から渡された `complex_args` を直接利用。

### 3.2 `ansible_action_launcher.py` の役割
Action Plugin を起動するためのエントリポイントです。
- **ActionBase._execute_module のパッチ**: Action Plugin 内からのモジュール実行を Java の `ITaskExecutor.execute_from_python` へルーティング。
- **双方向呼び出し**: Java (Worker) -> Python (Action Plugin) -> Java (Worker/Module) という再帰的な実行を可能にします。

## 4. 課題と制限事項

### 4.1 動作しないライブラリ
- **cryptography**: 現時点では完全にモック化しており、暗号化/復号を伴うアクション（Vault 連携等）は制限されます。
- **native yaml**: C 拡張版は無効化しており、Pure Python 版を使用するためパフォーマンスに影響が出る可能性があります。

### 4.2 今後の課題
- **ansible.executor.module_common**: インポート時に複雑な連鎖を引き起こしやすく、より洗練されたモック化が必要です。
- **Jinja2 互換性**: GraalPy 上での Jinja2 実行において、一部のフィルターやグローバル関数が期待通りに動作しないケースがあり、継続的な調査が必要です。

## 5. 関連ソースコード
- `src/main/python/ansible_bridge.py`
- `src/main/python/ansible_action_launcher.py`
- `src/main/java/org/example/ansible/engine/TaskExecutor.java`
