# graal-ansible

<!-- badges: start -->
[![Docs MD Lines](https://img.shields.io/badge/docs%20md%20lines-1742-blue)](./docs) [![Java LOC](https://img.shields.io/badge/Java%20LOC-3984-green)](.)
<!-- badges: end -->

ansible-coreをGraalVM/Javaで再実装し、高速な実行とネイティブバイナリ配布を可能にするプロジェクトです。

## プロジェクト目標

- **Ansible 13** で動作するコレクションが実行できる互換性を維持すること。
- GraalVM Native Image による高速な起動と低リソース消費。
