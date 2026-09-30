# コレクションの管理と取得方法 (Collection Management Specification)

本ドキュメントでは、`graal-ansible` で実行する Playbook が依存する Ansible コレクションを、ユーザーがどのように取得・管理し、本アプリケーションがそれらをどのように認識・名前解決（Name Resolution）するかについての詳細な技術仕様を定義します。

## 1. コレクションの取得方法

`graal-ansible` 自体はコレクションのダウンロード機能（`ansible-galaxy` 相当）を内蔵していません。実行に必要なコレクションは、事前に管理ノードの環境、またはプロジェクト固有のディレクトリに準備する必要があります。

### 1.1 `ansible-galaxy` コマンドによる取得

Ansible コレクションを取得する標準的な方法は、既存の `ansible-galaxy` コマンドを使用することです。

**基本コマンド:**
```bash
# 特定のコレクションをインストール
ansible-galaxy collection install <collection_name> -p ./collections

# requirements.yml を使用して一括インストール
ansible-galaxy collection install -r requirements.yml -p ./collections
```

`-p` オプションを使用して、プロジェクトローカルなディレクトリ（例: `./collections`）にインストールすることを推奨します。

### 1.2 `ansible.builtin` (ansible-core) の取得

`ansible.builtin` などの標準モジュールを使用する場合、`ansible-core` パッケージに含まれる Python コードが必要です。これらは通常、Python の `site-packages` 以下にインストールされます。

**推奨される準備手順 (venv 使用時):**
```bash
# 仮想環境の作成
python3 -m venv .venv
source .venv/bin/activate

# ansible-core のインストール
pip install ansible-core
```

## 2. 推奨されるプロジェクトフォルダ構造

プロジェクトごとに使用するコレクションのバージョンを固定するため、以下の構造を推奨します。

```text
my-ansible-project/
├── ansible.cfg          # (任意) 設定ファイル
├── collections/         # ansible-galaxy で取得したコレクション
│   └── ansible_collections/
│       └── community/
│           └── general/
│               ├── galaxy.yml
│               ├── plugins/
│               │   ├── modules/
│               │   ├── action/
│               │   ├── lookup/
│               │   └── filter/
│               └── roles/
├── inventory.ini        # インベントリファイル
├── playbook.yml         # 実行する Playbook
├── requirements.yml     # 依存コレクションの定義
└── .venv/               # ansible-core 等を含む Python 仮想環境
```

## 3. アプリケーションによるコレクションの認識と探索パスの優先順位

`graal-ansible` は、以下の優先順位（数字が小さいほど高優先）でコレクションの探索パス (`collections_path`) を決定します。

1.  **CLI オプション (`--collections-path` / `-c`)**: コマンドラインで明示的に指定されたパスが最優先されます。
2.  **環境変数 (`ANSIBLE_COLLECTIONS_PATH` / `ANSIBLE_COLLECTIONS_PATHS`)**: CLI オプションが指定されていない場合、環境変数の値（コロン区切り、Windows はセミコロン区切り）が使用されます。
3.  **`ansible.cfg` 設定**: カレントディレクトリの `ansible.cfg`、ユーザーホームの `~/.ansible.cfg`、または `/etc/ansible/ansible.cfg` 内の `[defaults]` セクションで定義された `collections_paths` または `collections_path` の値が使用されます。
4.  **デフォルトパス**: 上記がいずれも指定されていない場合、以下の標準パスを順次探索します。
    - カレント実行ディレクトリ直下の `./collections`
    - `~/.ansible/collections`
    - `/usr/share/ansible/collections`

### 3.1 `ansible.builtin` (組み込みモジュール) の特殊解決パス

`ansible.builtin` などのコアモジュールについては、以下のパスから優先して解決を試みます。

1.  Python インタプリタ（GraalPy）の `sys.path`（インストール済みの `ansible-core` 等を含む依存ライブラリパス）。この探索パスは以下の優先順位で決定され、`sys.path` に登録されます：
    - 環境変数 `ANSIBLE_SITE_PACKAGES` で指定されたコロン（Windows はセミコロン）区切りのパス。
    - Java システムプロパティ `-Dansible.site.packages` で指定されたパス。
    - デフォルトパス（`target/python-packages` が存在する場合はその絶対パス）。
2.  `ANSIBLE_COLLECTIONS_PATH` 内の `ansible_collections/ansible/builtin`

## 4. FQCN (Fully Qualified Collection Name) 解析と名前解決ルール

Ansible のタスク、モジュール、Action Plugin、Lookup Plugin、Jinja2 フィルター、ロール指定においては、**FQCN (完全修飾コレクション名)** 形式がサポートされます。

### 4.1 FQCN の標準フォーマット

FQCN は `<namespace>.<collection_name>.<plugin_name>` の 3 パート構造で構成されます。

| 要素 | 構文ルール | 具体例 |
| :--- | :--- | :--- |
| **Namespace** | 英小文字、数字、アンダースコア (`[a-z0-9_]+`) | `ansible`, `community`, `amazon` |
| **Collection** | 英小文字、数字、アンダースコア (`[a-z0-9_]+`) | `builtin`, `general`, `aws` |
| **Plugin / Resource** | 英小文字、数字、アンダースコア (`[a-z0-9_]+`) | `copy`, `s3_bucket`, `json_query` |

**指定例:**
- モジュール: `ansible.builtin.copy`, `community.general.git_config`
- Lookup Plugin: `lookup('community.general.passwordstore', ...)`
- Jinja2 フィルター: `{{ val | ansible.builtin.bool }}`

### 4.2 非 FQCN (短縮形) 名の標準プレフィックス補完ルール

タスク定義で `copy` や `ping` などの短縮名（Simple Name）が使用された場合、エンジンは以下の順序で名前解決を行います。

1.  現在のタスクコンテキストにおける `collections` 検索スコープ（後述）に登録されているコレクション順。
2.  スコープに存在しない場合、デフォルトで `ansible.builtin.<name>` への代替補完を試行。

## 5. `collections` キーワードによるスコープ検索 (Scope Resolution Hierarchy)

Playbook 内では、Play、Block、Task の各レベルで `collections` キーワードを用いて短縮名の探索順序を制御できます。

### 5.1 スコープ解決の優先順位

`collections` スコープは上位（Play）から下位（Block, Task）へ継承・拡張され、最も内側の定義が最優先されます。

```yaml
- name: Play level collection scope
  hosts: all
  collections:
    - community.general
    - my_namespace.my_collection
  tasks:
    - name: Block level collection scope
      block:
        - name: Task with short module name
          git_config: # community.general.git_config に自動解決
            name: user.name
            scope: global
      collections:
        - community.general
```

### 5.2 名前解決アルゴリズム (Resolution Algorithm)

ある短縮名 `<short_name>` （例: `git_config`）を解決する場合、アルゴリズムは以下のように機能します。

1.  **コンテキストスコープ検索**: 有効な `collections` リスト（例: `['my_ns.my_coll', 'community.general']`）の各要素 `<ns>.<coll>` について、`<collections_path>/ansible_collections/<ns>/<coll>/plugins/<plugin_type>/<short_name>.py` （または対応する実装）が存在するか探索します。
2.  **最初の一致の採用**: 最初に一致したコレクション内のプラグイン・モジュールを採用します。
3.  **組み込みフォールバック**: どのコレクション内にも見つからなかった場合、`ansible.builtin.<short_name>` として解決を試みます。
4.  **未解決エラー**: いずれのパスでも検出できなかった場合、`CollectionNotFoundException` をスローして実行を中断します。

## 6. コレクション内部構造とプラグイン探索アルゴリズム

各コレクションディレクトリ配下は、Ansible 標準のディレクトリ構造に従ってプラグインおよびリソースが配置されます。

### 6.1 ディレクトリ構造と対応プラグイン

```text
ansible_collections/<namespace>/<collection>/
├── galaxy.yml                 # コレクションのメタデータ（バージョン、依存関係等）
├── plugins/
│   ├── modules/               # ターゲットノード実行モジュール (.py)
│   ├── action/                # 管理ノード実行 Action Plugin (.py)
│   ├── lookup/                # Lookup Plugin (.py)
│   ├── filter/                # Jinja2 カスタムフィルター (.py)
│   ├── connection/            # Connection Plugin (.py)
│   └── callback/              # Callback Plugin (.py)
└── roles/                     # コレクション同梱ロール
```

### 6.2 プラグイン種別ごとの探索ルール

| プラグイン種別 | 探索ディレクトリ | 解決対象ファイル拡張子 | 備考 |
| :--- | :--- | :--- | :--- |
| **Module** | `plugins/modules/` | `.py`, `.ps1` | Python モジュールまたは PowerShell モジュール |
| **Action Plugin** | `plugins/action/` | `.py` | モジュールと同名の Action Plugin があれば優先実行 |
| **Lookup Plugin** | `plugins/lookup/` | `.py` | `lookup('<fqcn>', ...)` で呼び出し |
| **Filter Plugin** | `plugins/filter/` | `.py` | Python 内の `FilterModule` クラスを登録 |
| **Role** | `roles/` | ディレクトリ構造 | `<fqcn>` 指定により `roles/<role_name>` をロード |

## 7. `galaxy.yml` メタデータ解析と依存関係バリデーション

インストールされた各コレクションの直下にある `galaxy.yml` ファイルは、コレクションの識別情報および他のコレクションへの依存関係を保持しています。

### 7.1 `galaxy.yml` 解析仕様

SnakeYAML を用いて `galaxy.yml` を読み込み、以下の主要フィールドを検証・保持します。

```yaml
namespace: community
name: general
version: 8.5.0
readme: README.md
authors:
  - Ansible Community
dependencies:
  ansible.netcommon: ">=2.5.0"
  community.crypto: ">=2.15.0"
```

### 7.2 依存関係バリデーション

- コレクションのロード時、`dependencies` マップに記載された依存コレクションが `collections_path` 内に存在するかチェックします。
- 依存コレクションが存在しない場合、デバッグ/警告ログを出力し、実際のプラグイン呼び出し時に未検出エラー（`CollectionNotFoundException`）として捕捉します。

## 8. GraalPy Polyglot 統合と `ansible_collections` パッケージバインディング

`graal-ansible` では、GraalPy 上で Python 製のコレクションモジュールや Action Plugin をインポートして実行するために、`sys.path` および Python ネームスペースパッケージ（`ansible_collections`）の自動登録を行います。

### 8.1 ネームスペースパッケージの初期化シーケンス

1.  **探索パスの収集**: Java 側の `CollectionManager` が解決した全 `collections_path`（例: `/path/to/collections`）を取得します。
2.  **GraalPy `sys.path` への注入**: Polyglot Context 起動時、各 `collections_path` を Python の `sys.path` の先頭に追加します。
3.  **`ansible_collections` の動的インポート**:
    - 各 `collections_path/ansible_collections` ディレクトリが、Python の PEP 420 ネームスペースパッケージとして認識されるようにします。
    - Python 実行環境上で `import ansible_collections.<namespace>.<collection>.plugins.modules.<module_name>` 形式での動的インポートを可能にします。

### 8.2 コレクション内 Action Plugin / Module Utils の共有

コレクション内のモジュールが同コレクションの `module_utils`（例: `ansible_collections.community.general.plugins.module_utils.net_tools`）に依存している場合、GraalPy の Python インポート機構を通じて透過的に依存が解決されます。

## 9. エラーハンドリングと例外マッピング

コレクションの解析および名前解決において問題が発生した場合、以下の例外をスローしてデバッグ性を高めます。

| 発生シナリオ | 例外クラス | エラーメッセージ形式 |
| :--- | :--- | :--- |
| 不正な FQCN 形式（ドットが 2 つ未満等） | `IllegalArgumentException` | `Invalid FQCN format: 'invalid_name'. Expected '<namespace>.<collection>.<plugin>'` |
| 指定されたコレクションが未検出 | `CollectionNotFoundException` | `Collection '<namespace>.<collection>' not found in collections_paths: [...]` |
| コレクション内に指定プラグインが未検出 | `PluginNotFoundException` | `Plugin '<plugin_name>' (type: <type>) not found in collection '<namespace>.<collection>'` |
| `galaxy.yml` 解析エラー | `YamlParseException` | `Failed to parse galaxy.yml in collection '<namespace>.<collection>': <reason>` |

## 10. 手順のまとめ

プロジェクトで新しいコレクションを導入して実行するまでの具体的な手順は以下の通りです。

1.  **依存関係の定義**: `requirements.yml` を作成し、必要なコレクションを記述する。
2.  **コレクションの取得**:
    ```bash
    ansible-galaxy collection install -r requirements.yml -p ./collections
    ```
3.  **環境の準備 (コアモジュール用)**:
    ```bash
    python3 -m venv .venv
    source .venv/bin/activate
    pip install ansible-core
    ```
4.  **実行**:
    ```bash
    export ANSIBLE_COLLECTIONS_PATH="./collections"
    # graal-ansible を使用して Playbook を実行
    graal-ansible playbook.yml
    ```

## 11. 今後の検討事項

- `graal-ansible` 自身で `requirements.yml` を解析し、自動的にコレクションをダウンロードする機能の検討 (`[提案]`)。
- Native Image 実行時に、特定のコレクションをバイナリに埋め込む（静的リンク）オプションの検討。
