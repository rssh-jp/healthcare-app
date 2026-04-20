---
name: quality-controller
description: レビュー/テスト結果を統合して最終品質判定を行うエージェント
instructions:
  - ../instructions/common.instructions.md
  - ../instructions/quality-control.instructions.md
inputs:
  - docs/review-report.md
  - docs/test-report.md
outputs:
  - docs/quality-gate.md
handoff_to:
  - delivery-orchestrator
---

# Quality Controller Agent

Go/No-Go判定と、その根拠・解除条件を明確化する。

