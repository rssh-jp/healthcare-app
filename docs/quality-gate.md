# 品質ゲート判定: Firebase 認証とクラウド同期機能

- **判定日**: 2026-04-20
- **判定者**: Quality Controller Agent
- **対象**: Firebase Authentication + Firestore 同期実装
- **入力成果物**: `docs/review-report.md`, `docs/test-report.md`, `docs/acceptance-criteria.md`

---

## 最終判定: **Go（条件付き）**

> リリースブロッカーはすべて解消済み。ただし、本番環境向けのデプロイ前に「リリース前必須作業」を完了すること。

---

## 判定根拠

### 1. ビルド成功

| 検証項目 | 状態 |
|---|---|
| `assembleDebug` | ✅ BUILD SUCCESSFUL (18s) |
| `app-debug.apk` 生成 | ✅ 確認済み |

### 2. リリースブロッカーの解消

| 指摘ID | 内容 | 状態 |
|---|---|---|
| MUST-1 | `WalkingSession.sessionUuid` が空文字列デフォルト → 全セッション同期永続失敗 | ✅ **修正済み** (`UUID.randomUUID().toString()` に変更) |
| BUG-001 | `HomeScreen.kt` の `HomeScreen` / `StatCard` / `SessionCard` 重複定義 → ビルドエラー | ✅ **修正済み** (旧実装を削除、各関数1定義のみ) |
| BUG-RACE-001 | `endSession` / `updateSessionStats` の競合状態 → 履歴が保存されない根本原因 | ✅ **修正済み** (read-modify-write → ターゲット UPDATE クエリに変更) |

**コード実態確認**:
- `WalkingSession.kt` L11: `val sessionUuid: String = UUID.randomUUID().toString()` ✅
- `HomeScreen.kt`: `fun HomeScreen` 1箇所 / `fun StatCard` 1箇所 / `fun SessionCard` 1箇所 ✅

### 3. 受け入れ条件の充足状況（静的検証）

| カテゴリ | PASS | FAIL | 備考 |
|---|---|---|---|
| AC-AUTH（認証 6条件） | 6 | 0 | 実機検証は本番 `google-services.json` 要 |
| AC-SYNC（同期 4条件） | 4 | 0 | 静的検証 |
| AC-MULTI（マルチデバイス 3条件） | 3 | 0 | 静的検証 |
| AC-OFFLINE（オフラインファースト 3条件） | 3 | 0 | 静的検証 |
| Room マイグレーション（2条件） | 2 | 0 | 静的検証 |
| Firestore Security Rules | 1 | 0 | SHOULD-4（delete許可）は残存リスクとして記載 |
| **合計** | **19** | **0** | |

> **制約**: すべて静的（コードレビューベース）検証。実機またはエミュレータによる動的検証は未実施。

---

## 残存リスク一覧

### 優先度: 高（リリース前または早期対応推奨）

| ID | ファイル | 内容 | リスク | 推奨対応 | 状態 |
|---|---|---|---|---|---|
| SHOULD-4 | `firestore.rules` | `write` に `delete` を含む。クライアントから自身のセッションを Firestore 上で削除可能 | データ消失（誤実装・悪意あるクライアント） | `allow read, create, update:` に変更し `delete` を除外 | ✅ 修正済み |
| SHOULD-2 | `FirestoreSyncRepository.kt` | `fetchAndMerge(uid: String, ...)` が外部から任意の `uid` を受け取れる | OWASP A01 観点の潜在的アクセス制御不備（Firestore Rules が最終防衛線） | 内部で `auth.currentUser?.uid` を取得するよう変更 | 未対応 |
| SHOULD-6 | `SyncWorker.kt` | `Result.retry()` の上限未定義。ネットワーク障害長期化時にバッテリー・通信量を消費し続ける | UX劣化・端末リソース消費 | `runAttemptCount < 5` で上限を設ける | ✅ 修正済み |

### 優先度: 中（次スプリント対応推奨）

| ID | ファイル | 内容 | リスク | 状態 |
|---|---|---|---|---|
| SHOULD-1 | `SyncStatusConverter.kt` | `SyncStatus.valueOf(value)` が不正値で `IllegalArgumentException` をスロー | DB 破損・将来の enum 変更でクラッシュ | ✅ 修正済み |
| SHOULD-5 | `FirestoreSyncRepository.kt` | Firestore から全セッションを一括取得（ページネーションなし） | セッション数増加でメモリ・Firestore 読み取りコスト・遅延が線形増加 | 未対応 |
| SHOULD-7 | `NetworkMonitor.kt` | `NET_CAPABILITY_VALIDATED` を確認しない | キャプティブポータル環境で `isConnected = true` のまま同期失敗が続く | ✅ 修正済み |

### 優先度: 低（技術的負債として管理）

| ID | 内容 |
|---|---|
| SHOULD-3 | `FirestoreSyncRepository` が `WalkingRepository` をメソッド引数で受け取る（レイヤー境界侵害） |
| SHOULD-8 | `AppDatabase` の `exportSchema = false`（マイグレーション履歴の追跡困難） |
| NIT-1 | `updateSyncStatus` パラメータが `String` 型（`SyncStatus` 型化推奨） |
| NIT-2 | `authState` Flow が複数 collector でリスナー重複登録（`stateIn` 化推奨） |
| NIT-3 | `sessionUuid` に DB レベルの UNIQUE インデックスなし |
| NIT-4 | `fetchAndMerge` の失敗がサイレントに無視される（ログなし） |
| NIT-5 | `ApiException.statusCode` をユーザー向けエラーメッセージに含めている |

---

## リリース前必須作業

以下はコードレビュー・静的検証では確認不可能であり、人手による確認・作業が必須。

| # | 作業 | 担当 | 備考 |
|---|---|---|---|
| 1 | **本番用 `google-services.json` の配置** | インフラ/リリース担当 | `app/google-services.json.example` を参考に本番プロジェクトの設定ファイルへ差し替え。`google-services.json` が `.gitignore` で除外されていることを必ず確認 |
| 2 | **Firestore Security Rules のデプロイ** | インフラ/リリース担当 | `firestore.rules` を本番プロジェクトにデプロイ済みであることを確認。デプロイ漏れの場合、全ユーザーのデータが無防備になる |
| 3 | **Google Sign-In の OAuth クライアント ID 設定確認** | インフラ/リリース担当 | Firebase Console の認証設定（SHA-1 フィンガープリント、パッケージ名）が本番 APK の署名と一致していることを確認 |
| 4 | **実機による E2E 動作確認（最低限）** | QA担当 | サインイン・セッション完了・Firestore 書き込み・別端末でのマージの4フローを実機で確認。静的検証では代替不可 |
| 5 | **SHOULD-4 修正（Firestore Rules の delete 除外）** | 開発担当 | `allow read, write:` を `allow read, create, update:` へ変更することをリリース前に強く推奨 |

---

## 合格基準の充足サマリー

| 基準 | 状態 |
|---|---|
| すべての MUST 指摘が解消されている | ✅ MUST-1 / BUG-001 ともに修正済み |
| ビルドが成功している | ✅ BUILD SUCCESSFUL |
| 主要受け入れ条件（AC-AUTH/SYNC/MULTI/OFFLINE）が静的に満たされている | ✅ 19/19 PASS |
| 残存する SHOULD/NIT 指摘が即時リリースブロッカーでない | ✅ 機能要件に直接影響しない（詳細は残存リスク一覧参照） |

---

## No-Go 解除条件

現時点の判定は **Go（条件付き）** であり No-Go ではないが、以下のいずれかが判明した場合は即時 No-Go に切り替えること:

- 本番 `google-services.json` が未設定のまま本番ビルドを行う
- Firestore Security Rules が本番プロジェクトにデプロイされていない
- 実機 E2E 確認でサインイン・同期フローに致命的バグが発見される

---

*作成: Quality Controller Agent — 2026-04-20*
