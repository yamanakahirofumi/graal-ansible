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
- **原則**: 同じ変数名が異なるレベルで定義されている場合、高い優先度の値が低い優先度の値を完全に上書きします（`replace` 戦略）。
- **ハッシュマージ (`hash_behaviour=merge`)**:
    - **現状**: 現在、環境変数 `ANSIBLE_HASH_BEHAVIOUR=merge` が設定されている場合、辞書（Map）型の変数が重複した際に再帰的にマージする機能をサポートしています。
    - **動作**: `VariableManager` 内の `mergeVariables` メソッドが、`HashBehaviour` 設定に基づき、新旧の変数を適切にマージまたは置換します。

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

Ansible 特有のフィルターは、Javaによる独自実装、または基盤となるテンプレートエンジン **Jinjava** の組み込み機能によってサポートされています。現在、計 **27種類** のフィルターが動作確認または実装されています。

### 4.1 実装済みフィルター一覧

以下の 23 種類のフィルターが、Java の `Filter` インターフェースを実装する形で独自に追加されています。

- `b64decode`: Base64 デコード。
- `b64encode`: Base64 エンコード。
- `basename`: パスのベース名を取得。
- `bool`: 値を真偽値に変換。
- `combine`: 辞書（Map）をマージ。詳細および使用例は [### 4.3 主要フィルターの詳細仕様](#43-主要フィルターの詳細仕様) を参照。
- `default`: 未定義値に対するデフォルト値を設定。詳細および使用例は [### 4.3 主要フィルターの詳細仕様](#43-主要フィルターの詳細仕様) を参照。
- `dict2items`: 辞書をリスト形式に変換。詳細および使用例は [### 4.3 主要フィルターの詳細仕様](#43-主要フィルターの詳細仕様) を参照。
- `dirname`: パスのディレクトリ名を取得。
- `flatten`: ネストされたリストを平坦化。`levels` パラメータによる階層指定をサポート。詳細および使用例は [### 4.3 主要フィルターの詳細仕様](#43-主要フィルターの詳細仕様) を参照。
- `ipaddr`: IP アドレスの検証・操作。詳細および使用例は [### 4.3 主要フィルターの詳細仕様](#43-主要フィルターの詳細仕様) を参照。
- `items2dict`: リスト形式の辞書を一つの辞書に変換。詳細および使用例は [### 4.3 主要フィルターの詳細仕様](#43-主要フィルターの詳細仕様) を参照。
- `mandatory`: 変数が未定義または空の場合にエラーを発生させる。
- `quote`: シェルクォート処理。
- `realpath`: 絶対パスを取得。
- `regex_replace`: 正規表現による置換。詳細および使用例は [### 4.3 主要フィルターの詳細仕様](#43-主要フィルターの詳細仕様) を参照。
- `splitext`: パスを名前と拡張子に分割。
- `ternary`: 条件に応じて値を切り替える。詳細および使用例は [### 4.3 主要フィルターの詳細仕様](#43-主要フィルターの詳細仕様) を参照。
- `to_json`: オブジェクトを JSON 文字列に変換。
- `to_nice_json`: オブジェクトを整形された pretty JSON 文字列に変換（インデント、キーソートのカスタマイズ対応）。詳細および使用例は [### 4.2 to_nice_json / to_nice_yaml の詳細仕様](#42-to_nice_json--to_nice_yaml-の詳細仕様) を参照。
- `to_yaml`: オブジェクトを YAML 文字列に変換。
- `to_nice_yaml`: オブジェクトを整形された pretty YAML 文字列に変換（インデント、最大幅のカスタマイズ対応）。詳細および使用例は [### 4.2 to_nice_json / to_nice_yaml の詳細仕様](#42-to_nice_json--to_nice_yaml-の詳細仕様) を参照。
- `unique`: リストから重複する要素を排除。
- `urlencode`: URLエンコード処理。

また、以下の 4 種類の集合演算フィルターは、**Jinjava が標準で提供するビルトイン機能**を利用して透過的にサポートされています。

- `difference`: 2つのリスト間の差集合を返します。
- `intersect`: 2つのリスト間の積集合を返します。
- `union`: 2つのリスト間の和集合を返します。
- `symmetric_difference`: 2つのリスト間の対称差（排他的な要素）を返します。

### 4.2 to_nice_json / to_nice_yaml の詳細仕様

`to_nice_json` および `to_nice_yaml` フィルターは、Jinja2 テンプレート内でオブジェクトを人間が読みやすい形式に整形・出力するためのフィルターです。位置引数（Positional Arguments）と名前付き/キーワード引数（Keyword Arguments）の両方をサポートしています。

#### 1. to_nice_json
オブジェクト（Map、List等）を整形された pretty JSON 文字列に変換します。

- **パラメータ**:
  - `indent` (`Integer`, デフォルト: `4`): インデントのスペース数。
  - `sort_keys` (`Boolean`, デフォルト: `true`): オブジェクトのキーをアルファベット順にソートするかどうか。
- **使用例**:
  - **デフォルト（インデント4、キーソートあり）**:
    ```jinja2
    {{ data | to_nice_json }}
    ```
  - **位置引数によるインデント指定（インデント2、キーソートあり）**:
    ```jinja2
    {{ data | to_nice_json(2) }}
    ```
  - **位置引数によるインデント・キーソート指定（インデント4、キーソートなし）**:
    ```jinja2
    {{ data | to_nice_json(4, false) }}
    ```
  - **キーワード引数による指定（インデント3、キーソートなし）**:
    ```jinja2
    {{ data | to_nice_json(indent=3, sort_keys=false) }}
    ```
  - **キーワード引数（一部のみ指定）**:
    ```jinja2
    {{ data | to_nice_json(sort_keys=false) }}
    ```

#### 2. to_nice_yaml
オブジェクト（Map、List等）を整形された pretty YAML 文字列に変換します。

- **パラメータ**:
  - `indent` (`Integer`, デフォルト: `4`): インデントのスペース数。
  - `width` (`Integer`, デフォルト: `80`): 各行の最大文字幅（折り返し幅）。
- **使用例**:
  - **デフォルト（インデント4、最大幅80）**:
    ```jinja2
    {{ data | to_nice_yaml }}
    ```
  - **位置引数によるインデント指定（インデント2、最大幅80）**:
    ```jinja2
    {{ data | to_nice_yaml(2) }}
    ```
  - **位置引数によるインデント・最大幅指定（インデント4、最大幅120）**:
    ```jinja2
    {{ data | to_nice_yaml(4, 120) }}
    ```
  - **キーワード引数による指定（インデント2、最大幅100）**:
    ```jinja2
    {{ data | to_nice_yaml(indent=2, width=100) }}
    ```

### 4.3 主要フィルターの詳細仕様

`to_nice_json` / `to_nice_yaml` に加えて、`graal-ansible` において頻繁に使用される主要な Jinja2 フィルターについて、パラメータ仕様、位置引数（Positional Arguments）/ キーワード引数（Keyword Arguments）の処理ルール、および使用例を定義します。

#### 1. combine
複数の辞書（Map）オブジェクトをマージして単一の辞書を返します。

- **パラメータ**:
  - `*args` (`Map`): マージ対象となる 1 つ以上の追加辞書オブジェクト。位置引数として複数指定可能です。
  - `kwargs` (`Map`, オプション): キーワード引数で指定されるオプション群（オプションパラメータ）。
- **動作仕様**:
  - 対象オブジェクト（`var`）が Map でない場合は無変換で返却します。
  - 位置引数 `args` に渡された Map が順次ベースマップに上書きマージ（`putAll`）されます。
- **使用例**:
  - **基本マージ**:
    ```jinja2
    {{ default_config | combine(user_config) }}
    ```
  - **複数辞書の連結マージ**:
    ```jinja2
    {{ base_map | combine(map_a, map_b) }}
    ```

#### 2. regex_replace
正規表現パターンにマッチする部分文字列を指定された文字列に置換します。

- **パラメータ**:
  - `pattern` (`String`, 必須): 検索する Java 互換の正規表現パターン。
  - `replacement` (`String`, オプション, デフォルト: `""`): 置換後の文字列。指定しない場合はマッチ部分が削除されます。
- **動作仕様**:
  - 対象オブジェクトが `null` の場合は `null` を返します。引数が不足している場合は元のオブジェクト文字列を返します。
  - `replaceAll` による一括置換を行い、正規表現例外が発生した場合は安全に元の文字列を返します。
- **使用例**:
  - **パターン置換**:
    ```jinja2
    {{ "ansible-2.17" | regex_replace('^ansible-', 'graal-') }}
    {# 返り値: "graal-2.17" #}
    ```
  - **空文字列への置換（削除）**:
    ```jinja2
    {{ "foo123bar" | regex_replace('[0-9]+') }}
    {# 返り値: "foobar" #}
    ```

#### 3. ternary
条件式（真偽値または Truthiness 評価対象）に応じて、2 つまたは 3 つの値から適切な値を返します。

- **パラメータ**:
  - `true_val` (`Object`, 位置引数 1): 条件が真 (`true`) の場合に返却する値。
  - `false_val` (`Object`, 位置引数 2): 条件が偽 (`false`) の場合に返却する値。
  - `null_val` (`Object`, 位置引数 3, オプション): 条件オブジェクトが `null` の場合に返却する値。
- **動作仕様**:
  - 条件が `null` かつ `null_val` が指定されている場合、`null_val` を返します。
  - `Truthiness.isTrue(var)` による判定を行い、真なら `true_val`、偽なら `false_val` を返します。
- **使用例**:
  - **基本三項演算**:
    ```jinja2
    {{ is_enabled | ternary('enabled', 'disabled') }}
    ```
  - **null 値対応の三項演算**:
    ```jinja2
    {{ maybe_null_var | ternary('yes', 'no', 'unknown') }}
    ```

#### 4. ipaddr
IP アドレス文字列が有効な IPv4 または IPv6 アドレスであるかを検証し、有効な場合は IP アドレス文字列を返します。

- **パラメータ**: なし（文字列入力に対する直接検証）。
- **動作仕様**:
  - 入力文字列が IPv4（ドット区切り 4 段階、10進数 0-255、先頭ゼロ埋め禁止）、IPv6（16進数、`::` の単一短縮許可、ヘックスチェック）、または IPv4 マップ IPv6 (`::ffff:192.168.1.1`) であるかを厳格にチェックします。
  - 有効な IP アドレスの場合は元の文字列を返し、無効または空文字列の場合は `false` を返します。
- **使用例**:
  - **IP アドレスの検証**:
    ```jinja2
    {{ "192.168.1.1" | ipaddr }}
    {# 返り値: "192.168.1.1" #}

    {{ "invalid-ip" | ipaddr }}
    {# 返り値: false #}
    ```

#### 5. default
入力オブジェクトが未定義（`null`）または空文字列（`""`）の場合に、フォールバック用のデフォルト値を返します。

- **パラメータ**:
  - `default_value` (`Object`, 必須): 未定義・空文字時に返却するデフォルト値。
- **動作仕様**:
  - 入力が非 null かつ非空文字の場合はそのまま返します。入力が null または空文字列の場合は `default_value` を返します。
- **使用例**:
  - **未定義変数のデフォルト値設定**:
    ```jinja2
    {{ custom_port | default(8080) }}
    ```

#### 6. flatten
ネストされたリスト構造を平坦化（1次元化）します。

- **パラメータ**:
  - `levels` (`Integer`, オプション, デフォルト: `-1`): 平坦化する再帰ネスト階層数（`-1` はすべてのネストレベルを全平坦化）。
- **動作仕様**:
  - 入力が Iterable でない場合はそのまま返します。指定された `levels` の階層数まで再帰的にリスト要素を展開・統合します。
- **使用例**:
  - **全階層の平坦化**:
    ```jinja2
    {{ [[1, 2], [3, [4, 5]]] | flatten }}
    {# 返り値: [1, 2, 3, 4, 5] #}
    ```
  - **階層数を制限した平坦化**:
    ```jinja2
    {{ [[1, 2], [3, [4, 5]]] | flatten(1) }}
    {# 返り値: [1, 2, 3, [4, 5]] #}
    ```

#### 7. dict2items
辞書（Map）オブジェクトを、各要素が `key` と `value`（またはカスタマイズされたキー名）を持つリスト形式へ変換します。

- **パラメータ**:
  - `key_name` (`String`, オプション, デフォルト: `"key"`): キー名を保持するフィールド名。
  - `value_name` (`String`, オプション, デフォルト: `"value"`): 値を保持するフィールド名。
- **引数形式**:
  - キーワード引数（`key_name='...', value_name='...'`）および文字列形式（`"key_name=..."`）の両方に対応します。
- **使用例**:
  - **デフォルト変換**:
    ```jinja2
    {{ {'a': 1, 'b': 2} | dict2items }}
    {# 返り値: [{'key': 'a', 'value': 1}, {'key': 'b', 'value': 2}] #}
    ```
  - **カスタムフィールド名指定**:
    ```jinja2
    {{ {'web': 80} | dict2items(key_name='service', value_name='port') }}
    {# 返り値: [{'service': 'web', 'port': 80}] #}
    ```

#### 8. items2dict
`dict2items` の逆変換を行い、`key` と `value` を含むマップのリストを単一の辞書（Map）オブジェクトに復元・変換します。

- **パラメータ**:
  - `key_name` (`String`, オプション, デフォルト: `"key"`): キー名として参照するフィールド名。
  - `value_name` (`String`, オプション, デフォルト: `"value"`): 値として参照するフィールド名。
- **引数形式**:
  - キーワード引数（`key_name='...', value_name='...'`）、位置引数（`key_name, value_name`）、および `"key_name=..."` 形式をサポートします。
- **使用例**:
  - **デフォルト復元**:
    ```jinja2
    {{ [{'key': 'a', 'value': 1}, {'key': 'b', 'value': 2}] | items2dict }}
    {# 返り値: {'a': 1, 'b': 2} #}
    ```
  - **カスタムフィールド名での復元**:
    ```jinja2
    {{ [{'service': 'web', 'port': 80}] | items2dict('service', 'port') }}
    {# 返り値: {'web': 80} #}
    ```

#### 9. b64encode
文字列またはバイト配列を Base64 エンコード文字列に変換します。

- **パラメータ**: なし。
- **動作仕様**:
  - 入力が `null` の場合は `null` を返します。
  - 入力がバイト配列（`byte[]`）の場合はそのバイト列を、それ以外の場合は UTF-8 文字列として Base64 エンコードします。
- **使用例**:
  - **文字列の Base64 エンコード**:
    ```jinja2
    {{ "hello world" | b64encode }}
    {# 返り値: "aGVsbG8gd29ybGQ=" #}
    ```

#### 10. b64decode
Base64 エンコードされた文字列を UTF-8 テキスト文字列にデコードします。

- **パラメータ**: なし。
- **動作仕様**:
  - 入力が `null` の場合は `null` を返します。
  - Base64 デコードを試み、デコードに失敗した場合は例外を発生させず安全に入力文字列をそのまま返します。
- **使用例**:
  - **Base64 デコード**:
    ```jinja2
    {{ "aGVsbG8gd29ybGQ=" | b64decode }}
    {# 返り値: "hello world" #}
    ```

#### 11. basename
ファイルパス文字列からベース名（末尾のファイル名部）を抽出して返します。

- **パラメータ**: なし。
- **動作仕様**:
  - 入力が `null` の場合は `null` を返します。
  - パス内の最後のスラッシュ（`/`）またはバックスラッシュ（`\`）以降のサブ文字列を返します。区切り文字が存在しない場合は入力文字列をそのまま返します。
- **使用例**:
  - **パスのベース名抽出**:
    ```jinja2
    {{ "/etc/nginx/nginx.conf" | basename }}
    {# 返り値: "nginx.conf" #}

    {{ "C:\\Users\\ansible\\file.txt" | basename }}
    {# 返り値: "file.txt" #}
    ```

#### 12. dirname
ファイルパス文字列からディレクトリ部を抽出して返します。

- **パラメータ**: なし。
- **動作仕様**:
  - 入力が `null` の場合は `null` を返します。
  - パス内の最後のスラッシュ（`/`）またはバックスラッシュ（`\`）より前のディレクトリパスを返します。区切り文字がない場合はカレントディレクトリを示す `"."` を返します。ルート直下（インデックス 0）の場合は区切り文字 1 文字を返します。
- **使用例**:
  - **ディレクトリ名の抽出**:
    ```jinja2
    {{ "/etc/nginx/nginx.conf" | dirname }}
    {# 返り値: "/etc/nginx" #}

    {{ "file.txt" | dirname }}
    {# 返り値: "." #}
    ```

#### 13. splitext
ファイルパス文字列を名前部分と拡張子部分に分割した 2 要素のリスト `[root, ext]` を返します。

- **パラメータ**: なし。
- **動作仕様**:
  - 入力が `null` の場合は `null` を返します。
  - 最後のパス区切り文字（`/` または `\`）より後ろにドット（`.`）が存在する場合、そのドットの位置で分割します（拡張子にはドットが含まれます）。拡張子がない場合は `[path, ""]` を返します。
- **使用例**:
  - **パスと拡張子の分割**:
    ```jinja2
    {{ "/var/log/app.log" | splitext }}
    {# 返り値: ["/var/log/app", ".log"] #}

    {{ "/etc/nginx/nginx" | splitext }}
    {# 返り値: ["/etc/nginx/nginx", ""] #}
    ```

#### 14. realpath
指定された相対パスまたは絶対パスを、正則化・絶対パス化された文字列に変換して返します。

- **パラメータ**: なし。
- **動作仕様**:
  - 入力が `null` の場合は `null` を返します。
  - `File.getCanonicalPath()`（失敗時は `getAbsolutePath()`）を用いて絶対パスを解決し、OS 間のテンプレート互換性のためバックスラッシュ（`\`）をすべてスラッシュ（`/`）に正規化します。
- **使用例**:
  - **絶対パスへの変換**:
    ```jinja2
    {{ "./relative/path/file.txt" | realpath }}
    {# 返り値: "/absolute/path/relative/path/file.txt" #}
    ```

#### 15. quote
シェルコマンドに安全に埋め込めるよう、文字列をシングルクォートでエスケープ・クォート処理します。

- **パラメータ**: なし。
- **動作仕様**:
  - 入力が `null` または空文字の場合は `''` を返します。
  - 全体をシングルクォート（`'...'`）で囲み、内部のシングルクォート（`'`）は POSIX シェル互換のエスケープ列（`'\''`）に置換します。
- **使用例**:
  - **シェルクォート処理**:
    ```jinja2
    {{ "hello 'world'" | quote }}
    {# 返り値: "'hello '\''world'\''" #}
    ```

#### 16. bool
入力値を Ansible 互換の真偽値（`true` または `false`）へ変換します。

- **パラメータ**: なし。
- **動作仕様**:
  - `null` は `false` を返します。
  - `Boolean` 型はそのまま返します。
  - `String` 型は、空文字・空白、またはケース無視で `"false"`, `"no"`, `"off"` の場合に `false` を返し、それ以外は `true` を返します。
  - `Number` 型は `0` の場合のみ `false` を返し、非ゼロは `true` を返します。
- **使用例**:
  - **真偽値への変換**:
    ```jinja2
    {{ "yes" | bool }}
    {# 返り値: true #}

    {{ "off" | bool }}
    {# 返り値: false #}
    ```

#### 17. mandatory
変数が未定義（`null`）または空文字列（`""`）の場合に例外を発生させ、実行を停止させます。

- **パラメータ**:
  - `msg` (`String`, 位置引数 1, オプション): 未定義・空文字時にスローするカスタムエラーメッセージ。
- **動作仕様**:
  - 入力が `null` または空文字列の場合、デフォルトメッセージ（`"Mandatory variable is undefined or empty"`）または指定された `msg` を持つ `RuntimeException` をスローします。定義済みの場合は値をそのまま返します。
- **使用例**:
  - **必須変数の検証**:
    ```jinja2
    {{ my_var | mandatory }}
    {{ api_key | mandatory("API key must be defined!") }}
    ```

#### 18. unique
リストから重複する要素を排除し、ユニークな要素のリストを返します。

- **パラメータ**:
  - `attribute` (`String`, オプション, 位置引数 1 または `attribute='...'`): 辞書のリストに対して特定属性キーの値で重複排除を行う場合の属性名。
- **動作仕様**:
  - 入力が Collection でない場合は無変換で返します。
  - `attribute` 未指定時は、元の順序を維持（`LinkedHashSet`）しながら重複要素を排除します。
  - `attribute` 指定時は、要素（Map）内の指定キーの値に基づいて重複排除を行います。
- **使用例**:
  - **リストの重複排除**:
    ```jinja2
    {{ [1, 2, 2, 3, 1] | unique }}
    {# 返り値: [1, 2, 3] #}
    ```
  - **属性名による辞書リストの重複排除**:
    ```jinja2
    {{ [{'id': 1, 'name': 'a'}, {'id': 1, 'name': 'b'}, {'id': 2, 'name': 'c'}] | unique('id') }}
    {# 返り値: [{'id': 1, 'name': 'a'}, {'id': 2, 'name': 'c'}] #}
    ```

#### 19. urlencode
文字列または辞書（Map）オブジェクトを URL エンコード処理します。

- **パラメータ**: なし。
- **動作仕様**:
  - 入力が `null` の場合は空文字 `""` を返します。
  - 入力が Map の場合は各エントリを `key=value` 形式で URL エンコードし、`&` で結合したクエリ文字列を生成します。
  - 文字列入力の場合は UTF-8 で URL エンコードします（スペースは `%20` に置換）。
- **使用例**:
  - **文字列および Map の URL エンコード**:
    ```jinja2
    {{ "hello world&foo=bar" | urlencode }}
    {# 返り値: "hello%20world%26foo%3Dbar" #}

    {{ {'name': 'john doe', 'city': 'new york'} | urlencode }}
    {# 返り値: "name=john%20doe&city=new%20york" #}
    ```

#### 20. to_json
オブジェクト（Map、List、基本型等）をコンパクトな JSON 文字列にシリアライズ変換します。

- **パラメータ**: なし。
- **動作仕様**:
  - Jackson `ObjectMapper` を用いてオブジェクトを JSON 文字列に変換します。変換例外が発生した場合は `var.toString()` へフォールバックします。
- **使用例**:
  - **JSON へのシリアライズ**:
    ```jinja2
    {{ {'a': 1, 'b': [2, 3]} | to_json }}
    {# 返り値: '{"a":1,"b":[2,3]}' #}
    ```

#### 21. to_yaml
オブジェクト（Map、List、基本型等）を標準的なブロック形式の YAML 文字列にシリアライズ変換します。

- **パラメータ**: なし。
- **動作仕様**:
  - SnakeYAML の `Yaml.dump()` を用いてオブジェクトを YAML 文字列へ変換します。
- **使用例**:
  - **YAML へのシリアライズ**:
    ```jinja2
    {{ {'name': 'server', 'ports': [80, 443]} | to_yaml }}
    ```

### 4.4 独自フィルターの追加手順

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

Ansible において自動的に定義される特殊な変数（マジック変数）について、互換性向上のため `graal-ansible` では以下の変数をカテゴリ別にサポートしています。

これらの変数は、`VariableManager` によって自動的に各ホストの変数セットに注入され、テンプレート内で参照可能です。

### 5.1 ホスト・インベントリ関連
- **`inventory_hostname`**: 現在実行中のターゲットホストの名前（インベントリ内の定義名）。
- **`hostvars`** (◎): 他のホストの変数を参照するためのマップ。
    - **実装詳細**: `VariableManager` を介して必要な時だけ特定のホストの変数を解決する「遅延読み込みプロキシ」として実装されています。無限再帰を避けるため、`hostvars` 経由の解決時には `hostvars` 自体は除外されます。
- **`groups`**: 全てのグループとそれらに属するホストのリストを含むマップ。
- **`group_names`**: 現在のホストが属しているグループ名のリスト。
- **`inventory_dir`**: 使用しているインベントリファイルが配置されているディレクトリの絶対パス。
- **`inventory_file`**: 使用しているインベントリファイルのパスまたはファイル名。

### 5.2 実行コンテキスト・プレイ関連
- **`playbook_dir`**: 実行中のプレイブックが配置されているディレクトリの絶対パス。
- **`ansible_play_hosts`** (◎): 現在のプレイの対象ホストのうち、まだ失敗（failed）または到達不能（unreachable）になっていないホストのリスト。
- **`ansible_play_batch`** (◎): 現在のシリアルバッチ (`serial`) に含まれるホストのうち、まだアクティブな（失敗していない）ホストのリスト。
- **`ansible_play_hosts_all`** (◎): 失敗の有無に関わらず、現在のプレイの対象範囲に含まれるすべてのホストのリスト。
- **`ansible_run_tags`**: 実行時に `--tags` で指定されたタグのリスト。
- **`ansible_skip_tags`**: 実行時に `--skip-tags` で指定されたタグのリスト。

### 5.3 エンジン・実行状態関連
- **`ansible_check_mode`**: チェックモード（ドライラン）が有効な場合に `true` となる真偽値。
- **`ansible_diff_mode`**: 差分表示モード（`--diff` / `-D`）が有効かどうかを示す真偽値。
- **`ansible_verbosity`**: 実行時の詳細度（`-v` オプションの数）。
- **`ansible_version`** (◎): エンジンのバージョン情報（`full`, `major`, `minor`, `revision`, `string` を含む辞書形式）。

### 5.4 特殊なセンチネル変数 (`omit`)
- **概要**: モジュールの引数を条件に応じて省略するために使用される特殊な変数です。
- **動作仕様**:
    - **内部実装**: `omit` は内部的に `VariableManager.OMIT` という特殊なセンチネルオブジェクトとして定義されています。
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
- **実装済みの主要プラグイン (7種類)** (file, env, template, pipe, dict, vars, first_found):

  #### 1. `file`
  指定されたファイル（`playbook_dir` 相対または絶対パス）の内容をテキスト文字列として読み込みます。
  - **パラメータ / 引数**:
    - `terms`: 読み込み対象のファイルパス（単一文字列、複数位置引数、またはパスのリスト）。相対パスが指定された場合、`playbook_dir` を基準として絶対パスに自動解決します。
    - `errors` (`String`, オプション, デフォルト: `strict`): ファイル未検出・読み込み失敗時の動作（`strict`: 例外スロー、`warn`/`ignore`: エラーの無視または警告）。
  - **パス解決・挙動**:
    - 絶対パス（例: `/etc/hosts`）はそのまま読み込みます。
    - 相対パス（例: `files/id_rsa.pub`）は、現在の実行コンテキスト内の `playbook_dir` と結合して参照します。
  - **エラーハンドリング**: 指定されたファイルが存在しない場合や読み取り権限がない場合、`strict` モードでは `RuntimeException` をスローします。
  - **使用例**:
    - **単一ファイルの読み込み**:
      ```jinja2
      {{ lookup('file', 'files/id_rsa.pub') }}
      ```
    - **複数ファイルの読み込み (`query` / リスト指定)**:
      ```jinja2
      {{ query('file', 'file1.txt', 'file2.txt') }}
      ```

  #### 2. `env`
  管理ノード（制御ノード）のシステム環境変数の値を取得します。
  - **パラメータ / 引数**:
    - `terms`: 取得対象の環境変数名（単一文字列、複数位置引数、または変数名のリスト）。
    - `default` (`String`, オプション): 環境変数が未定義の場合に返却するデフォルト値。
  - **挙動**: `System.getenv(varName)` を参照し、環境変数が存在しない場合、`default` パラメータが未指定であれば空文字列（`""`）を返します。
  - **使用例**:
    - **単一環境変数の取得**:
      ```jinja2
      {{ lookup('env', 'HOME') }}
      ```
    - **複数環境変数の取得**:
      ```jinja2
      {{ query('env', 'USER', 'SHELL') }}
      ```

  #### 3. `template`
  指定された Jinja2 テンプレートファイル（`playbook_dir` 相対または絶対パス）を読み込み、現在の変数コンテキストでインライン評価・レンダリングした結果の文字列を返します。
  - **パラメータ / 引数**:
    - `terms`: テンプレートファイルのパス（単一文字列、複数位置引数、またはパスのリスト）。
    - `convert_data` (`Boolean`, オプション, デフォルト: `true`): レンダリング結果が YAML または JSON のデータ構造である場合に、動的に対応する Java のオブジェクト構造（Map/List）へ変換するかどうか。
  - **挙動**:
    - ファイルをロードし、現在の `JinjavaInterpreter` コンテキスト（変数マップ）を適用して Jinja2 評価（変数展開、フィルター、制御文等）を行います。
  - **エラーハンドリング**: ファイルが存在しない場合、またはテンプレート解析・レンダリングエラーが発生した場合は `RuntimeException` をスローします。
  - **使用例**:
    - **テンプレートのレンダリング**:
      ```jinja2
      {{ lookup('template', 'templates/app.conf.j2') }}
      ```

  #### 4. `pipe`
  管理ノード（制御ノード）上でシェルコマンドを実行し、その標準出力（`stdout`）を取得します。末尾の改行コード（`\n`, `\r\n`）は自動的にトリミングされます。
  - **パラメータ / 引数**:
    - `terms`: 実行するシェルコマンド文字列（単一文字列、複数位置引数、またはコマンドのリスト）。
  - **挙動**:
    - 管理ノードの OS に適した標準シェル（Linux/macOS: `/bin/sh -c`、Windows: `cmd.exe /c`）を用いてコマンドを実行し、終了まで待機します。
  - **エラーハンドリング**: コマンドの終了コードが非ゼロ（失敗）の場合、標準エラー出力の内容を含む `RuntimeException` をスローします。
  - **使用例**:
    - **シェルコマンド実行結果の取得**:
      ```jinja2
      {{ lookup('pipe', 'date +%Y-%m-%d') }}
      {{ lookup('pipe', 'git rev-parse --short HEAD') }}
      ```

  #### 5. `dict`
  渡された辞書（Map）オブジェクト、または `key=value` ペアの要素を、`key` と `value` を明示的なキーとして持つ Dict 構造体（`{"key": ..., "value": ...}`）のリスト形式に変換して返します。
  - **パラメータ / 引数**:
    - `terms`: 変換対象の辞書（Map）オブジェクト、または `key=value` ペア群。
  - **挙動**:
    - 入力が Map の場合、各エントリを `{"key": entryKey, "value": entryValue}` の Map 要素を持つリストへ構造変換します。`loop` や `with_dict` などの反復処理で活用されます。
  - **使用例**:
    - **辞書のリスト化構造変換**:
      ```jinja2
      {{ lookup('dict', {'web': 80, 'db': 5432}) }}
      {# 返り値: [{'key': 'web', 'value': 80}, {'key': 'db', 'value': 5432}] #}
      ```

  #### 6. `vars`
  指定された変数名（文字列）に対応する変数の値を、現在の Jinja2 変数コンテキストから動的に検索・取得します。動的に組み立てた変数名の参照などに利用されます。
  - **パラメータ / 引数**:
    - `terms`: 参照したい変数名（単一文字列、複数位置引数、または変数名のリスト）。
    - `default` (`Object`, オプション): 指定した変数がコンテキストに存在しない場合に返却するデフォルト値。
  - **挙動**: 現在の Jinja2 変数コンテキストから指定された変数名で値を解決します。
  - **エラーハンドリング**: 変数が未定義かつ `default` が未指定の場合、`RuntimeException`（未定義変数エラー）をスローします。
  - **使用例**:
    - **動的な変数名の参照**:
      ```jinja2
      {{ lookup('vars', 'ansible_' + env_type + '_host') }}
      ```
    - **デフォルト値を指定した変数名の参照**:
      ```jinja2
      {{ lookup('vars', 'maybe_missing_var', default='fallback_value') }}
      ```

  #### 7. `first_found`
  指定された複数の候補ファイルパス（または `files` と `paths` の組み合わせ）の中から、最初に実在するファイルの絶対パスを返します。
  - **パラメータ / 引数**:
    - `files`: 候補ファイル名のリストまたはカンマ区切り文字列。
    - `paths`: 検索対象ディレクトリのリストまたはカンマ区切り文字列（`playbook_dir` 相対または絶対パス）。
    - `skip` (`Boolean`, デフォルト: `false`): `true` の場合、候補ファイルが見つからなくても例外を発生させず空の結果を返します。
  - **使用例**:
    - **位置引数によるリスト指定**:
      ```jinja2
      {{ lookup('first_found', ['custom.conf', 'default.conf']) }}
      ```
    - **インラインマップ（辞書）による指定**:
      ```jinja2
      {{ lookup('first_found', {'files': ['app.conf', 'fallback.conf'], 'paths': ['/etc/app', 'config/'], 'skip': true}) }}
      ```

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
