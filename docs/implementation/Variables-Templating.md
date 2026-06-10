# 変数とテンプレートの実装詳細 (Variables and Templating Implementation)

本ドキュメントでは、`graal-ansible` における変数の管理、優先順位の解決、および Jinja2 互換テンプレートエンジンの統合について詳述します。

## 1. テンプレートエンジンの選定

Ansible の Jinja2 テンプレートとの高い互換性を Java で実現するため、**Jinjava** を採用します。

- **ライブラリ**: [Jinjava (HubSpot)](https://github.com/HubSpot/jinjava)
- **選定理由**:
    - Java ベースで Jinja2 構文を高度にサポートしている。
    - 独自フィルターやテストの追加が容易。
    - GraalVM Native Image での動作実績がある。

## 2. 変数優先順位 (Variable Precedence)

Ansible (ansible-core 2.17+) は 22 段階の非常に詳細な優先順位を定義しています。`graal-ansible` では、これらの互換性を維持しつつ、主要なレベルから順次実装を進めています。

下表は、低い順（上が低く、下が最高優先）の優先順位リストと、現在の `graal-ansible` におけるサポート状況です。

- **◎**: 実装済み (Implemented)
- **△**: 計画中 / 一部対応 (Planned / Partial)
- **×**: 未着手 (Not yet)

| 優先度 | 変数のソース | サポート状況 | 備考 |
| :--- | :--- | :---: | :--- |
| 1 | コマンドライン値 (例: `-u user` ※ `-e` 以外) | ◎ | `ansible_check_mode` 等の CLI オプションが `PlaybookExecutor` によって注入され、変数として統合済み。 |
| 2 | ロールデフォルト (`roles/x/defaults/main.yml`) | ◎ | `VariableManager` にて解決。 |
| 3 | インベントリファイル / スクリプトのグループ変数 | ◎ | `Inventory.java` にて解決。 |
| 4 | インベントリ `group_vars/all` | ◎ | `all` グループとして処理。 |
| 5 | プレイブック `group_vars/all` | ◎ | プレイブック相対パスの検索をサポート。 |
| 6 | インベントリ `group_vars/*` | ◎ | グループ階層パスに沿って解決。 |
| 7 | プレイブック `group_vars/*` | ◎ | プレイブック相対パスの検索をサポート。 |
| 8 | インベントリファイル / スクリプトのホスト変数 | ◎ | `host` 定義内の変数。 |
| 9 | インベントリ `host_vars/*` | ◎ | インベントリ相対パスの検索をサポート。 |
| 10 | プレイブック `host_vars/*` | ◎ | プレイブック相対パスの検索をサポート。 |
| 11 | ホストファクト / キャッシュされた `set_facts` | ◎ | `VariableManager.addFacts` にて管理。 |
| 12 | プレイ変数 (`vars`) | ◎ | `Play` レコードに保持。 |
| 13 | プレイ `vars_prompt` | ◎ | `PromptProvider` インターフェースを介した入力をサポート. |
| 14 | プレイ変数ファイル (`vars_files`) | ◎ | `VariableManager.loadVarsFile` にて解決。 |
| 15 | ロール変数 (`roles/x/vars/main.yml`) | ◎ | `VariableManager` にて解決。 |
| 16 | ブロック変数 (`block` 内の `vars`) | ◎ | 実行エンジンでのスコープ分離と伝播を実装済み。 |
| 17 | タスク変数 (`task` 内の `vars`) | ◎ | `Task` レコードに保持。 |
| 18 | `include_vars` | ◎ | Action Plugin として実装済み。 |
| 19 | `set_facts` / `register` 変数 | ◎ | `VariableManager.registerVariable` で実行時に保存。 |
| 20 | ロールパラメータ | ◎ | `VariableManager` にて解決済み。 |
| 21 | インクルードパラメータ | ◎ | `VariableManager` にて解決済み。 |
| 22 | エクストラ変数 (`-e` / `--extra-vars`) | ◎ | **最高優先。** `VariableManager.extraVars` に保持。 |

### 2.1 マージ戦略
- **原則**: 同じ変数名が異なるレベルで定義されている場合、高い優先度の値が低い優先度の値を完全に上書きします。
- **ハッシュマージ**: 辞書（Map）型の変数について、Ansible の `hash_behaviour=merge` 相当の再帰的マージをサポートするかは将来の検討事項です。

### 2.2 変数読み込みの最適化
- **キャッシュ機構**: `VariableManager` において、`vars_files` や `group_vars`, `host_vars` などのディレクトリ走査結果およびファイル内容をメモリ上にキャッシュし、マルチホスト実行時のファイル I/O オーバーヘッドを最小限に抑えています。

## 3. 遅延評価 (Lazy Evaluation)

Ansible と同様に、変数は定義時ではなく、実際に使用されるタイミングでテンプレート展開されます。

- **実装方法**:
    - 実行エンジン内の `VariableManager` が全変数を保持。
    - タスク実行直前に、そのタスクで使用される引数（`args`）に対して再帰的に `Jinjava` を適用。
    - 未定義の変数が参照された場合、原則として実行エラーとします（Ansible 互換）。
- **再帰的解決 (Recursive Resolution)**:
    - 文字列テンプレートの結果がさらに別のテンプレートを含む場合（例: `var_a: "{{ var_b }}"`, `var_b: "Hello {{ user }}"`）、`VariableResolver` は最終的な値が得られるまで再帰的に評価を継続します。
    - 無限ループを防止するため、デフォルトで**最大 20 段階**までの再帰深度制限を設けています。

## 4. 独自フィルターとテストの拡張

Ansible 特有のフィルターは、Jinjava の `Filter` インターフェースを実装して追加されています。現在、以下のフィルターが実装済みです。

- `b64decode`: Base64 デコード。
- `b64encode`: Base64 エンコード。
- `basename`: パスのベース名を取得。
- `bool`: 値を真偽値に変換。
- `combine`: 辞書（Map）をマージ。
- `default`: 未定義値に対するデフォルト値を設定。
- `dict2items`: 辞書をリスト形式に変換。
- `dirname`: パスのディレクトリ名を取得。
- `flatten`: ネストされたリストを平坦化。`levels` パラメータによる階層指定をサポート。
- `ipaddr`: IP アドレスの検証・操作。
- `items2dict`: リスト形式の辞書を一つの辞書に変換。
- `mandatory`: 変数が未定義または空の場合にエラーを発生させる。
- `quote`: シェルクォート処理。
- `realpath`: 絶対パスを取得。
- `regex_replace`: 正規表現による置換。
- `splitext`: パスを名前と拡張子に分割。
- `ternary`: 条件に応じて値を切り替える。
- `to_json`: オブジェクトを JSON 文字列に変換。
- `to_yaml`: オブジェクトを YAML 文字列に変換。
- `unique`: リストから重複する要素を排除。
- `urlencode`: URLエンコード処理。

### 4.1 独自フィルターの追加手順

新しい Jinja2 フィルターを Java で実装してエンジンに追加する手順は以下の通りです。

1.  **Filter インターフェースの実装**:
    - `com.hubspot.jinjava.lib.filter.Filter` インターフェースを実装するクラスを `org.example.ansible.engine.filter` パッケージに作成します。
    - `filter(Object var, JinjavaInterpreter interpreter, String... args)` メソッドをオーバーライドして、フィルタリングロジックを記述します。
    - `getName()` メソッドをオーバーライドして、テンプレート内で使用するフィルター名を返します。
2.  **エンジンのレジストリへの登録**:
    - `org.example.ansible.engine.VariableResolver.java` の `registerFilters()` メソッド内に、作成したフィルタークラスのインスタンスを登録するコードを追加します。
    - 例: `jinjava.getGlobalContext().registerFilter(new NewFilter());`
3.  **動作確認**:
    - Playbook 内で `{{ variable | new_filter }}` のように記述し、期待通りに動作することを確認します。

## 5. マジック変数 (Magic Variables)

Ansible において自動的に定義される特殊な変数（マジック変数）について、現在 `graal-ansible` では以下の変数をサポートしています。

- `inventory_hostname`: 現在実行中のターゲットホストの名前。
- `playbook_dir`: 実行中のプレイブックが配置されているディレクトリの絶対パス。
- `inventory_dir`: 使用しているインベントリファイルが配置されているディレクトリの絶対パス（指定されている場合）。
- `inventory_file`: 使用しているインベントリファイルのパス（現状 `inventory_dir` と同等）。
- `groups`: 全てのグループとそれらに属するホストのリストを含むマップ。
- `group_names`: 現在のホストが属しているグループ名のリスト。
- `ansible_check_mode`: チェックモード（ドライラン）が有効な場合に `true` となる真偽値。
- `ansible_verbosity`: 実行時の詳細度（`-v` オプションの数）。
- `ansible_run_tags`: 実行時に `--tags` で指定されたタグのリスト。
- `ansible_skip_tags`: 実行時に `--skip-tags` で指定されたタグのリスト。
- `omit`: モジュールの引数を省略するための特殊な変数。

これらの変数は、`VariableManager` によって自動的に各ホストの変数セットに注入され、テンプレート内で参照可能です。

### 5.1 サポートされているマジック変数 (Magic Variables)

互換性向上のため、以下のマジック変数が実装されています。

- **hostvars** (◎): 他のホストの変数を参照するためのマップ。
    - **実装詳細**: `VariableManager` を介して必要な時だけ特定のホストの変数を解決する「遅延読み込みプロキシ」として実装されています。無限再帰を避けるため、`hostvars` 経由の解決時には `hostvars` 自体は除外されます。
- **ansible_play_hosts** (◎): 現在のプレイの対象ホストのうち、まだ失敗していないホストのリスト。
- **ansible_play_batch** (◎): 現在のバッチ（シリアル）に含まれるホストのリスト（現状は `ansible_play_hosts` と同等）。
- **ansible_play_hosts_all** (◎): 失敗の有無に関わらず、現在のプレイの対象範囲に含まれるすべてのホストのリスト。
- **ansible_version** (◎): エンジンのバージョン情報（辞書形式）。
- **ansible_diff_mode** (◎): 差分表示モード（`--diff` / `-D`）が有効かどうかを示す真偽値。

### 5.2 omit の動作仕様
- **センチネルオブジェクト**: `omit` は内部的に `VariableManager.OMIT` という特殊なセンチネルオブジェクトとして定義されています。
- **引数の除去**: `TaskExecutor` は、テンプレート展開後のモジュール引数を走査し、その値が `VariableManager.OMIT` と一致するキーを発見した場合、その引数自体をモジュールに渡すパラメータリストから完全に除去します。

## 6. Native Image への対応

- Jinjava が内部で使用するリフレクション情報を `reflect-config.json` に定義する必要があります。
- 動的なクラスロードが発生する箇所を特定し、ビルド時に静的に解決されるよう設定します。

## 7. Lookup プラグイン (Lookup Plugins)

Ansible の Lookup プラグイン（`lookup`, `query`）を Java エンジンでサポートするための設計方針です。

### 7.1 概要
Lookup プラグインは、外部ソース（ファイル、環境変数、コマンド実行結果等）からデータを取得し、テンプレート内で利用するための仕組みです。

### 7.2 実装方針
- **Jinjava の拡張**: `com.hubspot.jinjava.lib.fn.ELFunction` またはカスタムタグを使用して、`lookup` および `query` 関数を実装します。
- **プラグインの解決**: 指定されたプラグイン名に基づき、Java 側で実装された対応するクラスを呼び出します。
- **実装済みの主要プラグイン**:
    - `file`: 指定されたパス（`playbook_dir` 相対または絶対パス）のファイルを読み込みます。
    - `env`: 管理ノードの環境変数を取得します。
    - `template`: 指定された Jinja2 テンプレートファイルを読み込み、現在の変数コンテキストでレンダリングした結果を返します。
    - `pipe`: 管理ノード上で指定されたコマンドを実行し、その標準出力を取得します。デフォルトでは出力を改行で分割したリストを返しますが、`wantlist=False` の場合は結合された文字列を返します。
    - `dict`: 渡された辞書（Map）を、`key` と `value` を持つリスト形式に変換して返却します。
    - `vars`: 指定された変数名の値を動的に取得します。

### 7.3 lookup と query の違い
- `lookup`: デフォルトではカンマ区切りの文字列を返します。
- `query` (または `wantlist=True` 指定時の `lookup`): 常にリストを返します。

### 7.4 独自Lookupプラグインの追加手順

新しい Lookup プラグインを Java で実装してエンジンに追加する手順は以下の通りです。

1.  **Lookup インターフェースの実装**:
    - `org.example.ansible.engine.lookup.Lookup` インターフェースを実装するクラスを作成します。
    - `execute(JinjavaInterpreter interpreter, List<Object> terms, Map<String, Object> kwargs)` メソッドを実装し、データの取得ロジックを記述します。

    ```java
    public interface Lookup {
        /**
         * Lookup プラグインを実行します。
         * @param interpreter 現在の JinjavaInterpreter
         * @param terms 位置引数 (例: lookup('file', 'path1', 'path2') の 'path1', 'path2')
         * @param kwargs 名前付き引数 (例: lookup(..., errors='ignore'))
         * @return 取得されたデータのリスト
         */
        List<Object> execute(JinjavaInterpreter interpreter, List<Object> terms, Map<String, Object> kwargs);

        /**
         * プラグイン名を返します。
         */
        String getName();
    }
    ```

2.  **エンジンのレジストリへの登録**:
    - `org.example.ansible.engine.VariableResolver.java` において、`lookup` および `query` カスタム関数から呼び出し可能な形式でプラグインを登録します。
    - 内部的には、指定されたプラグイン名（例: `file`）に対応する `Lookup` 実装を解決するマップ等で管理します。

3.  **動作確認**:
    - Playbook 内で `{{ lookup('my_lookup', 'arg1') }}` や `{{ query('my_lookup', 'arg1') }}` のように記述し、期待通りに動作することを確認します。
