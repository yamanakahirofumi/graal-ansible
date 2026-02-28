# 動作環境 (System Requirements)

`graal-ansible` を実行および開発するための環境要件です。

## 1. 実行環境 (Runtime)

- **Java Runtime**: GraalVM JDK 21 以上
    - 本家 Ansible モジュールの Python 互換レイヤーとして **GraalPy** を利用するため、GraalVM が必須となります。
- **Native Binary**: GraalVM Native Image でビルドされたバイナリ
    - ネイティブバイナリとして配布する場合、ターゲット OS ごとにビルドが必要です。

## 2. 対応 OS

| OS | バージョン | サポート状況 |
| :--- | :--- | :--- |
| **Linux** | Ubuntu 22.04 LTS+, RHEL 8/9+ | 第一優先 (Main) |
| **macOS** | macOS 13 (Ventura) 以上 (x64/ARM) | 準優先 (Secondary) |
| **Windows** | Windows 10/11 (PowerShell/WSL2) | 検討中 (Experimental) |

## 3. 依存ライブラリ・ツール

- **Python インタプリタ**:
    - `graal-ansible` 本体には GraalPy が内蔵されるため、システム上の `python3` は不要ですが、特定の Python モジュールがネイティブ依存関係（C 拡張等）を持つ場合は、対応する OS パッケージのインストールが必要になることがあります。
- **SSH クライアント**:
    - `ssh` 接続プラグインを使用する場合、ターゲットホストへのネットワーク疎通が必要です。
- **YAML 解析**:
    - SnakeYAML 2.x (Java ライブラリとしてバンドル)

## 4. 最小リソース要件 (目安)

- **メモリ**: 512MB 以上 (Native Image 時) / 1GB 以上 (JVM 時)
- **ディスク**: 100MB 以上 (バイナリ単体)

## 5. 互換性目標 (Compatibility Goal)

- **ターゲットバージョン**: **Ansible 13** で動作するコレクションの実行を目標としています。
- **互換性の範囲**: 
    - Ansible 13 相当の `ansible-core` 仕様への準拠。
    - Ansible 13 エコシステムで提供される一般的な Collection の動作。
