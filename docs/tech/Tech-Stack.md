# 技術スタック

本プロジェクトの開発に使用される技術、ライブラリ、およびツールの一覧です。

## 1. 使用技術一覧

| 分類               | 技術・ツール         | バージョン | 備考                                                                   |
| :----------------- | :------------------- | :--------- | :--------------------------------------------------------------------- |
| 言語               | Java                 | 21 (LTS)   | GraalVM JDK                                                            |
| スクリプト実行     | GraalPy              | 25.0.2     | [GraalPy 統合の詳細](GraalPy-Integration.md) を参照                    |
| フレームワーク     | GraalVM Native Image | 25.0.2     | ネイティブバイナリ化 (GraalVM SDK)                                     |
| CLI 解析           | Picocli              | 4.7.5      | `ansible-playbook` 互換 CLI 解析                                       |
| YAML 解析          | SnakeYAML            | 2.2        | Playbook 解析用                                                        |
| JSON シリアライズ  | Jackson              | 2.17.0     | データ構造シリアライズおよびモジュール出力解析                         |
| 接続 (SSH)         | Apache MINA SSHD     | 2.12.1     | Java ネイティブ SSH / 多段踏み台トンネリング実装                        |
| 接続 (Windows)     | WinRM4J              | 0.12.3     | Windows WinRM 接続実装                                                 |
| テンプレート       | Jinjava (HubSpot)    | 2.8.3      | Jinja2 互換テンプレートエンジン用                                      |
| テスト             | JUnit 5              | 5.10.2     | ユニットテスト・統合テストフレームワーク                               |
| テスト (モック)    | Mockito              | 5.11.0     | モックテストスイート (`SshConnectionTest`, `WinRMConnectionTest` 等)   |
| テスト (コンテナ)  | Testcontainers       | 2.0.2      | SSH 接続統合テスト、コンテナ実行用                                     |
| ビルド             | Maven                | 3.9.x      | プロジェクトビルドおよび依存関係管理                                   |
