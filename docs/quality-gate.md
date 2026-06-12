# 品質ゲート追補: 履歴表示画面のタイムライン表示

- **判定日**: 2026-06-12
- **判定者**: delivery-orchestrator
- **対象差分**: `HistoryScreen.kt`, `HistoryViewModel.kt` のタイムライン対応
- **入力成果物**: `docs/review-report.md`, `docs/test-report.md`, 実装差分

## 最終判定: Go

レビューで must 指摘 0 件、コンパイル検証成功、受け入れ条件 9 件すべて適合を確認したため、今回差分は Go と判定する。

## 判定根拠

### 1. レビュー結果

| 項目 | 状態 |
|---|---|
| must 指摘 | 0 件 |
| should 指摘 | 0 件 |
| nice-to-have | 1 件（カメラ追従改善余地） |

### 2. テスト結果

| カテゴリ | PASS | FAIL |
|---|---|---|
| ビルド検証 | 1 | 0 |
| AC-TL | 3 | 0 |
| AC-PT | 3 | 0 |
| AC-MAP | 3 | 0 |
| 合計 | 10 | 0 |

### 3. 品質観点別評価

| 観点 | 評価 | 根拠 |
|---|---|---|
| 機能 | 適合 | スライダー、選択時刻、座標、マーカー連動を実装 |
| 回帰 | 低リスク | 変更は履歴詳細画面と ViewModel に限定 |
| セキュリティ | 適合 | 新規のネットワーク送信や保存仕様変更なし |
| パフォーマンス | 許容 | 既存取得済みリストから単純計算のみ実施 |
| 運用性 | 適合 | 地図未設定時も既存フォールバックで継続利用可能 |

## 残存リスク

| ID | 内容 | 優先度 | 回避策 |
|---|---|---|---|
| TL-001 | 実機での地図マーカー視認性は未確認 | 中 | API キー設定後に手動 UI 検証を実施 |
| TL-002 | 長距離ルートでカメラ追従しない | 低 | 必要に応じて次スプリントで改善 |

## Handoff Contract

### 実施サマリ
レビュー・テスト成果物をもとに、タイムライン機能差分の品質ゲート判定を実施し Go と判定した。

### 成果物一覧
- `docs/quality-gate.md`（本追補）

### 未解決事項
- TL-001: 動的な地図マーカー確認未実施
- TL-002: カメラ追従改善余地

### 次スプリントへの依頼事項
1. API キー設定済み環境で履歴詳細画面の手動 UI テストを行うこと。
2. 必要であればスライダー操作時の地図カメラ追従を別タスクとして検討すること。

## 追補判定 (2026-06-12): タイムライン文言変更

- 変更対象: タイムライン情報行の `経過` 表示を `時間` 表示へ置換
- 検証結果:
	- Kotlin コンパイル成功
	- Debug APK 再インストール成功
	- 履歴詳細画面の再確認を実施

判定: **Go**（既存の品質判定を維持）

# 品質ゲート判定: Firestore 座標データ Blob 圧縮保存 (geoFlatBlob)

- **判定日**: 2026-05-19
- **判定者**: Quality Controller Agent
- **対象差分**: `FirestoreSyncRepository.kt` — geoFlatBlob 対応（`encodeGeoBlob` / `decodeGeoBlob` 追加、`uploadSession` / `syncOnLogin` 修正）
- **入力成果物**: `docs/review-report.md`, `docs/test-report.md`, 変更差分サマリー

---

## 最終判定: **Go**

> リリースブロッカーは 0 件。ビルド成功・全 AC 17 件 Pass を確認。NIT 2 件および既存技術的負債は次スプリントで管理する。

---

## 判定根拠

### 1. ビルド検証

| 検証項目 | 状態 |
|---|---|
| `./gradlew assembleDebug` | ✅ BUILD SUCCESSFUL (43 tasks up-to-date) |
| コンパイルエラー | なし |
| `app-debug.apk` 生成 | ✅ 確認済み |

---

### 2. レビュー指摘 — リリースブロッカー解消状況

| 指摘ID | 内容 | 状態 |
|---|---|---|
| MUST-1 | `decodeGeoBlob` — `GZIPInputStream` リソースリーク（`.use{}` 未使用） | ✅ **解消済み** |
| MUST-2 | `uploadSession` — stride が floor 除算でバグ（20,001〜39,999 点で間引き不能） | ✅ **解消済み**（ceil 除算に変更） |
| SHOULD-1 | `syncOnLogin` — `IOException` catch スコープが過大（座標復元以外の IO 失敗を誤補足） | ✅ **解消済み**（catch を座標復元ブロックのみに絞小） |

**must 0 件、should 0 件** を確認。リリースブロッカーなし。

---

### 3. 受け入れ条件の充足状況（静的解析）

| カテゴリ | PASS | FAIL | 検証手法 |
|---|---|---|---|
| AC-ENC（エンコード 3件） | 3 | 0 | 静的解析 |
| AC-DEC（デコード 3件） | 3 | 0 | 静的解析 |
| AC-UPLOAD（アップロード 6件） | 6 | 0 | 静的解析 |
| AC-SYNC（サインイン時同期 5件） | 5 | 0 | 静的解析 |
| **合計** | **17** | **0** | |

> **制約**: すべて静的（コードレビューベース）検証。実機・エミュレータによる動的検証は未実施。

---

### 4. 品質観点別評価

#### 機能

- `encodeGeoBlob`: `ByteBuffer`（lat 8B + lng 8B × n 点）→ `GZIPOutputStream(.use{})` → `Blob` の実装が正確。
- `decodeGeoBlob`: `GZIPInputStream(.use{})` → `ByteBuffer.wrap` → `while (remaining >= 16)` で末尾不完全バイトを安全に無視。
- `uploadSession`: ceil 除算により 20,001〜39,999 点のケースを含むすべての点数で間引き後 20,000 点以下を保証。
- `syncOnLogin`: `geoFlatBlob` 優先・`geoFlat` フォールバック・両方不在時は空リストと 3 ケースを網羅。

**評価: 適合**

#### セキュリティ（OWASP Top 10 Mobile）

| 観点 | 評価 | 根拠 |
|---|---|---|
| A01 アクセス制御 | ○ | Firestore Security Rules でユーザー UID にスコープ済み |
| A02 暗号化の失敗 | ○ | GZIP は圧縮であり暗号化ではないが、PHI（GPS 座標）は Firebase SDK の TLS 転送 + Firestore 認証・認可で保護。ローカル平文保存なし |
| A03 インジェクション | ○ | Firestore 型付き API (`Blob.fromBytes`) を使用。任意文字列インジェクションリスクなし |
| A04 安全でない設計 | ○ | 座標データはローカルに平文保存されない |
| A09 セキュリティログ | ○ | 破損 Blob 時に `Log.w(TAG, ..., e)` でトレーサブルなログを出力 |

**評価: 適合**

#### パフォーマンス

- **CPU 増加**: `encodeGeoBlob` / `decodeGeoBlob` が同期処理（`suspend` 関数内）で実行されるが、座標データは最大 20,000 点 × 16 B = 320 KB であり Coroutine の IO スレッドプールで許容可能。
- **ストレージ節約**: GPS 座標の連続値は GZIP 圧縮率が高く（通常 60〜80 % 削減）、Firestore の読み書きコスト・帯域を削減。
- **トレードオフ**: CPU オーバーヘッドよりストレージ・転送コスト削減が上回ると判断。

**評価: 許容範囲内**

#### 後方互換性

- `syncOnLogin` は `geoFlatBlob` フィールドが存在しない旧ドキュメントに対して `geoFlat` フォールバックを実装。旧クライアントが書き込んだデータを破損なく読み取れる。

**評価: 適合**

#### 運用継続性

- `decodeGeoBlob` は例外を呼び出し元に伝播し、`syncOnLogin` の `catch (e: IOException)` で捕捉して `Log.w` + `emptyList()` を返す。Blob 破損時もアプリはクラッシュせずセッションメタデータのみ同期継続。

**評価: 適合**

---

## 残存リスク一覧

### 優先度: 高（次スプリント推奨）

| ID | 内容 | リスク | 推奨対応 |
|---|---|---|---|
| DOC-001 | `docs/acceptance-criteria.md` AC-UPLOAD-3 の Given 節に `stride = floor(...)` と誤記 | レビュアー・テスターが実装の正しさを誤判定する可能性 | Given 節を `stride = ceil(n / MAX_GEO_POINTS)` に修正 |

### 優先度: 低（技術的負債として管理）

| ID | 内容 | 備考 |
|---|---|---|
| NIT-1 | `@Suppress("UNCHECKED_CAST")` アノテーションが不要（`as? List<*>` は safe cast） | 任意対応 |
| NIT-2 | `encodeGeoBlob` / `decodeGeoBlob` に `@VisibleForTesting` アノテーション未付与 | 任意対応 |
| SHOULD-2 | `fetchAndMerge(uid: String, ...)` が外部から任意 uid を受け取れる（前回スプリントからの継続） | 内部で `auth.currentUser?.uid` を取得するよう変更を推奨 |
| SHOULD-5 | Firestore から全セッションを一括取得（ページネーションなし）（前回スプリントからの継続） | セッション数増大時にメモリ・コスト増加 |

---

## 未実施の検証（制約事項）

| 項目 | 理由 | リスク |
|---|---|---|
| 実機・エミュレータ動的検証 | 本番 `google-services.json` 未設定 | Firestore 実書き込み・読み取りの動作が未確認 |
| ユニットテスト（JUnit/Mockito） | 既存テストコードが未整備 | `encodeGeoBlob` / `decodeGeoBlob` のラウンドトリップが自動回帰テストで保護されていない |
| リトライ動作の結合テスト | ネットワーク障害再現環境が未整備 | AC-UPLOAD-5/6 の動的確認が未実施 |

---

## Handoff Contract

### 実施サマリー
`docs/review-report.md`（must 0 件・should 0 件・NIT 2 件）および `docs/test-report.md`（BUILD SUCCESSFUL・全 AC 17 件 Pass）を入力とし、geoFlatBlob 対応差分の品質ゲート判定を実施した。機能・セキュリティ・パフォーマンス・後方互換性・運用継続性の全観点でリリースブロッカーなしと確認し、**Go** を宣言する。

### 成果物一覧
- `docs/quality-gate.md`（本ファイル）

### 未解決事項
- `docs/acceptance-criteria.md` AC-UPLOAD-3 の stride 計算式誤記（DOC-001）
- 実機テスト未実施（`google-services.json` 差し替え後に実施推奨）
- NIT-1 / NIT-2 の任意対応

### 次スプリントへの依頼事項
- `spec-writer`: AC-UPLOAD-3 の Given 節 stride 計算式を `ceil(n / MAX_GEO_POINTS)` に修正（DOC-001）。
- `implementer`（任意）: NIT-1（`@Suppress` 削除）・NIT-2（`@VisibleForTesting` 追加）・SHOULD-2（`fetchAndMerge` の uid 取得方法変更）の対応。
- QA 担当: 本番 `google-services.json` 配置後に実機 E2E 検証（サインイン・セッション完了・Firestore 書き込み・別端末マージ）を実施。
