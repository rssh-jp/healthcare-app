# Orchestration Instructions

## Purpose
6フェーズ（仕様→設計→実装→レビュー→テスト→品管）を順序制御し、成果物とゲート判定を統括する。

## Workflow
1. 仕様作成
2. 設計作成
3. 実装
4. レビュー
5. テスト
6. 品管

## Control Rules
- 前フェーズの Exit Gate 未達なら次フェーズへ進まない。
- 各フェーズの Handoff Contract が揃ってから次フェーズへ引き継ぐ。
- ブロッカーはフェーズ内で解消するか、未解決事項として明示して再計画する。

## Orchestrator Output
- フェーズごとの進行状況
- ブロッカー一覧
- Go / No-Go の最終判定

