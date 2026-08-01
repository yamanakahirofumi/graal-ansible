# 技術スタック

本プロジェクトの開発に使用される技術、ライブラリ、およびツールの一覧です。

## 1. 使用技術一覧

| 分類 | 技術・ツール | バージョン | 備考 |
| :--- | :--- | :--- | :--- |
| 言語 | Java | 21 (LTS) | GraalVM JDK |
| スクリプト実行 | GraalPy | - | [GraalPy 統合の詳細](GraalPy-Integration.md) を参照 |
| フレームワーク | GraalVM Native Image | - | ネイティブバイナリ化 |
| YAML解析 | SnakeYAML | 2.2 | Playbook解析用 |
| 接続 | Apache MINA SSHD | 2.12.1 | SSH接続実装 |
| 接続 (Windows) | WinRM4J | 0.12.3 | Windows WinRM接続実装 |
| テンプレート | Jinjava (HubSpot) | 2.8.3 | Jinja2互換テンプレート用 |
| テスト | JUnit 5 | 5.10.2 | |
| テスト (コンテナ) | Testcontainers | 2.0.2 | SSH接続統合テスト、コンテナ実行用 |
| ビルド | Maven | 3.9.x | |
