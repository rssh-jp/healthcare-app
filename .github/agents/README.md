# Copilot Agents Setup

このディレクトリは、開発を以下6フェーズで分業するためのエージェント定義を持つ。

1. 仕様作成: `spec-writer.agent.md`
2. 設計作成: `design-architect.agent.md`
3. 実装: `implementer.agent.md`
4. レビュー: `reviewer.agent.md`
5. テスト: `tester.agent.md`
6. 品管: `quality-controller.agent.md`

統括オーケストレーション:
- `delivery-orchestrator.agent.md`

## 運用ルール
- 各フェーズは `.github/instructions/*.instructions.md` を参照して実施する。
- 前フェーズのExit Gate未達時は次フェーズへ進めない。
- 各フェーズ完了時は Handoff Contract（サマリ、成果物、未解決事項、次工程依頼）を必ず出力する。

## 推奨進行
1. orchestrator が `spec-writer` を起動
2. ゲート通過後 `design-architect` へ引き継ぎ
3. 実装後に `reviewer` と `tester` を実行
4. 結果を `quality-controller` が統合
5. orchestrator が最終 Go/No-Go を確定

