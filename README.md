# graal-ansible

<!-- badges: start -->
[![Docs MD Lines](https://img.shields.io/badge/docs%20md%20lines-3883-blue)](./docs) [![Java LOC](https://img.shields.io/badge/Java%20LOC-16238-green)](.)
<!-- badges: end -->

ansible-coreをGraalVM/Javaで再実装し、高速な実行とネイティブバイナリ配布を可能にするプロジェクトです。

## プロジェクト目標

- **Ansible 13** で動作するコレクションが実行できる互換性を維持すること。
- GraalVM Native Image による高速な起動と低リソース消費。

## 開発環境

### テスト実行
- `ActualModuleIntegrationTest` を実行するには、Docker環境が必要です。Dockerが利用できない場合はテストがスキップされます。
- macOS環境で Testcontainers が Docker を正しく検出できない場合（`isDockerAvailable()` が false を返す場合）、以下の設定を確認してください。
    - Docker Desktop, OrbStack, または Colima が起動していること。
    - `~/.docker/config.json` で現在のコンテキストが正しく設定されていること。
    - 必要に応じて、環境変数 `DOCKER_HOST` を設定してください。
      ```bash
      # Colima の場合
      export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
      # または /var/run/docker.sock へのシンボリックリンクを作成
      sudo ln -sf $HOME/.colima/default/docker.sock /var/run/docker.sock
      
      # Docker Desktop のデフォルト
      export DOCKER_HOST=unix://$HOME/.docker/run/docker.sock
      ```
    - Docker Desktop の「Settings > Advanced > Allow the default Docker socket to be used」が有効になっているか確認してください。
- GitHub Actions (macOS) では Docker 環境が利用できないため、Docker を使用する統合テストは自動的にスキップされます。検証は Linux 環境の CI で行われます。
