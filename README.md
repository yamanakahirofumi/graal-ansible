# graal-ansible

ansible-coreをGraalVM/Javaで再実装し、高速な実行とネイティブバイナリ配布を可能にするプロジェクトです。

## プロジェクト目標

- **Ansible 13** で動作するコレクションが実行できる互換性を維持すること。
- GraalVM Native Image による高速な起動と低リソース消費。
- Java による主要モジュールの再実装（Python環境不要）。