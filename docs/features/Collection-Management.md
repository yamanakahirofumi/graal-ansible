# コレクションの管理と取得方法

本ドキュメントでは、`graal-ansible` で実行する Playbook が依存する Ansible コレクションを、ユーザーがどのように取得・管理し、本アプリケーションがそれらをどのように認識するかについて定義します。

## 1. コレクションの取得方法

`graal-ansible` 自体はコレクションのダウンロード機能（`ansible-galaxy` 相当）を内蔵していません。実行に必要なコレクションは、事前にホスト環境、またはプロジェクト固有のディレクトリに準備する必要があります。

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
├── inventory.ini        # インベントリファイル
├── playbook.yml         # 実行する Playbook
├── requirements.yml     # 依存コレクションの定義
└── .venv/               # ansible-core 等を含む Python 仮想環境
```

## 3. アプリケーションによるコレクションの認識

`graal-ansible` は、以下の順序でコレクションの探索パスを決定します。

### 3.1 環境変数 `ANSIBLE_COLLECTIONS_PATH`

最も優先される方法です。複数のパスをコロン（Windows はセミコロン）区切りで指定可能です。

```bash
export ANSIBLE_COLLECTIONS_PATH="./collections:/usr/share/ansible/collections"
graal-ansible playbook.yml
```

### 3.2 デフォルトパスの探索

環境変数が指定されていない場合、以下のデフォルト位置を自動的に探索します。

1.  実行ディレクトリ直下の `collections` フォルダ
2.  `~/.ansible/collections`
3.  `/usr/share/ansible/collections`

### 3.3 `ansible.builtin` の解決

`ansible.builtin` などのコアモジュールについては、以下のパスから解決を試みます。

1.  Python インタプリタ（GraalPy）の `sys.path` (インストール済みの `ansible-core` 内)
2.  `ANSIBLE_COLLECTIONS_PATH` 内の `ansible_collections/ansible/builtin`

## 4. 手順のまとめ

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
    # GraalPy のバイナリパスを指定して実行（実装状況に応じる）
    graal-ansible playbook.yml
    ```

## 5. 今後の検討事項

- `ansible.cfg` 内の `collections_paths` 設定への対応。
- `graal-ansible` 自身で `requirements.yml` を解析し、自動的にコレクションをダウンロードする機能の検討。
- Native Image 実行時に、特定のコレクションをバイナリに埋め込む（静的リンク）オプションの検討。
