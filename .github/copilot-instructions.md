# GitHub Copilot Instructions

## プロジェクト概要
Healthcare App — Kotlin + Jetpack Compose + MVVM + Room + Hilt で構成された Android アプリケーション。

---

## 全エージェント共通規約

- 既存のアーキテクチャ（Kotlin、Compose、MVVM、Room、Hilt）と命名規約を厳守する。
- 変更は小さく分割し、目的と影響範囲を明確にする。
- 憶測実装は行わない。不足情報はフェーズ内で明示して解消する。
- 各フェーズ終了時に **Handoff Contract**（実施サマリ・成果物一覧・未解決事項・次フェーズへの依頼事項）を必ず出力する。
- OWASP Top 10 に準拠したセキュアなコードを実装する。
- 破壊的操作（ファイル削除、ブランチ削除、強制プッシュ等）は必ずユーザー確認を取る。

---

## `delivery-orchestrator` エージェント — 厳格な動作規定

> このセクションは `delivery-orchestrator` エージェントが呼び出された場合に**最優先で適用**される。

### フェーズ定義と Exit Gate

| # | フェーズ | 担当エージェント | 必須成果物 | Exit Gate |
|---|----------|-----------------|-----------|-----------|
| 1 | 仕様作成 | `spec-writer` | `docs/specification.md`<br>`docs/acceptance-criteria.md` | 受け入れ条件が全機能に紐づき、Out of Scope が明文化されていること |
| 2 | 設計作成 | `design-architect` | `docs/design.md`<br>`docs/task-breakdown.md` | 仕様の受け入れ条件を満たす設計根拠があり、実装単位まで分解されていること |
| 3 | 実装 | `implementer` | 実装コード差分<br>変更点サマリ | コンパイル可能かつ受け入れ条件に対応する実装が存在すること |
| 4 | レビュー | `reviewer` | `docs/review-report.md` | must 指摘が 0 件、または全 must 指摘に対処方針が合意済みであること |
| 5 | テスト | `tester` | `docs/test-report.md` | 重要シナリオのテスト結果が記録され、失敗項目に再現条件と優先度が付与されていること |
| 6 | 品管 | `quality-controller` | `docs/quality-gate.md` | Go / No-Go 判定が明確であり、No-Go の場合は解除条件が定義されていること |

### 制御ルール（違反禁止）

1. **前フェーズの Exit Gate が未達の場合、次フェーズへ絶対に進まない。** 差し戻し理由を明示して同フェーズを再実行する。
2. **成果物ファイルが存在しない場合**、そのフェーズは未完了とみなす。ファイルパスの確認を先に行う。
3. **各フェーズ開始前に入力成果物の存在確認を行う。** 存在しない場合は前フェーズへ差し戻す。
4. **Handoff Contract の確認はフェーズ完了の必須条件**。Contract が不完全な場合はフェーズ完了を宣言しない。
5. **ブロッカーはフェーズ内で解消する。** 解消できない場合は未解決事項として明示し再計画する。
6. **最終フェーズ完了後に Go / No-Go を宣言する。** No-Go の場合は対象フェーズへ差し戻す。

### オーケストレータの出力フォーマット

```
## Delivery Status

| フェーズ | 状態 | Exit Gate |
|---------|------|-----------|
| 仕様作成 | ✅ 完了 / 🔄 進行中 / ❌ 未達 / ⏳ 未着手 | ... |
| 設計作成 | ... | ... |
| 実装     | ... | ... |
| レビュー | ... | ... |
| テスト   | ... | ... |
| 品管     | ... | ... |

## ブロッカー一覧
- （なければ `None`）

## 最終判定
**Go** / **No-Go** — 理由: ...
```

---

## フェーズ別エージェント指示詳細

### Phase 1: 仕様作成（`spec-writer`）
- **入力**: プロダクト要望、既存コード/既存仕様
- **作業**: 機能要件・非機能要件・制約に分解。スコープ（In/Out）定義。受け入れ条件をテスト可能な形で列挙。不明点・前提・リスクを明示。
- **出力**: `docs/specification.md`、`docs/acceptance-criteria.md`

### Phase 2: 設計作成（`design-architect`）
- **入力**: `docs/specification.md`、`docs/acceptance-criteria.md`
- **作業**: アーキテクチャ・責務分割・データフロー設計。画面/UI・状態管理・永続化・外部API連携方針定義。実装タスクへ分解し依存関係を明示。
- **出力**: `docs/design.md`、`docs/task-breakdown.md`

### Phase 3: 実装（`implementer`）
- **入力**: `docs/design.md`、`docs/task-breakdown.md`
- **作業**: 設計に従い段階的に実装。既存規約（命名・DI・状態管理・UI構成）に準拠。変更理由とトレードオフを差分説明に残す。
- **出力**: 実装コード差分、変更点サマリ

### Phase 4: レビュー（`reviewer`）
- **入力**: 実装差分、`docs/specification.md`、`docs/design.md`
- **作業**: バグ・セキュリティ・回帰・要件逸脱を重点レビュー。指摘を `must / should / nice-to-have` で分類。
- **出力**: `docs/review-report.md`

### Phase 5: テスト（`tester`）
- **入力**: 実装差分、`docs/acceptance-criteria.md`
- **作業**: 受け入れ条件に対するテスト観点作成。既存テスト/ビルドコマンドで検証し結果を記録。不具合の再現手順と期待結果/実結果を残す。
- **出力**: `docs/test-report.md`

### Phase 6: 品管（`quality-controller`）
- **入力**: `docs/review-report.md`、`docs/test-report.md`、変更差分一式
- **作業**: リリース可否を品質ゲート観点で判定。機能・運用・セキュリティ・パフォーマンスリスク評価。未解決事項の回避策・残課題整理。
- **出力**: `docs/quality-gate.md`

---

## コーディング規約

### Android / Kotlin
- 言語: Kotlin（Java は使用しない）
- UI: Jetpack Compose（View システムは使用しない）
- アーキテクチャ: MVVM（ViewModel + StateFlow + UiState）
- DI: Hilt
- 永続化: Room
- 非同期: Kotlin Coroutines + Flow
- ナビゲーション: Navigation Compose

### 命名規約
- ViewModel: `XxxViewModel`
- UiState: `XxxUiState`
- Repository: `XxxRepository` / `XxxRepositoryImpl`
- UseCase: `XxxUseCase`
- Composable: PascalCase、プレビュー関数には `@Preview` を付与

### ファイル構成
```
app/src/main/java/.../
  feature/
    <feature>/
      ui/          # Composable, ViewModel
      domain/      # UseCase
      data/        # Repository, DAO, Entity
```

---

## セキュリティ要件
- ユーザー入力は必ずバリデーションを行う（システム境界での検証）。
- 個人健康情報（PHI）はローカルに平文保存しない。
- API キー・シークレットはソースコードにハードコードしない。`local.properties` または環境変数を使用する。
- OWASP Top 10 Mobile の項目を常に意識する。

---

## ドキュメント管理
- `docs/` ディレクトリ以下の成果物ファイルは各フェーズエージェントが作成・更新する。
- ユーザーから明示的に指示されない限り、マークダウンファイルをドキュメントとして新規作成しない。
- 成果物ファイルへの変更は差分説明と合わせて記録する。
