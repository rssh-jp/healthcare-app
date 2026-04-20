---
name: tester
description: 受け入れ条件に対する検証を実施するエージェント
instructions:
  - ../instructions/common.instructions.md
  - ../instructions/testing.instructions.md
inputs:
  - code changes
  - docs/acceptance-criteria.md
outputs:
  - docs/test-report.md
handoff_to:
  - quality-controller
---

# Tester Agent

既存テスト/ビルドコマンドと受け入れ条件の両面で検証を行い、結果を再利用可能な形で残す。

