# レビューレポート追補: 履歴表示画面のタイムライン表示

- **レビュー対象**: `app/src/main/java/com/healthcare/app/ui/screen/history/HistoryScreen.kt`, `app/src/main/java/com/healthcare/app/ui/screen/history/HistoryViewModel.kt`
- **レビュー日時**: 2026-06-12
- **レビュアー**: delivery-orchestrator
- **参照ドキュメント**: `docs/specification.md`, `docs/design.md`, `docs/acceptance-criteria.md`

## 総合評価

**合格（must 指摘 0 件）**

タイムライン状態は `HistoryViewModel` に閉じており、UI では導出状態として選択地点を計算している。既存の履歴一覧・削除・ルート表示への影響も局所的で、要件逸脱は確認されなかった。

## 指摘一覧

### MUST

なし

### SHOULD

なし

### NICE-TO-HAVE

#### NIT-TL-1: スライダー値に応じた地図カメラ追従は未実装

今回の仕様範囲外だが、長距離ルートで選択マーカーの視認性をさらに高めるには、スライダー変更時にカメラを選択地点へ寄せる改善余地がある。

## 要件適合チェック

| 受け入れ条件 | 適合 | 備考 |
|---|---|---|
| AC-TL-1 | ✓ | 詳細画面にタイムラインカードと Slider を追加 |
| AC-TL-2 | ✓ | 左右に最初/最後の位置記録時刻を表示 |
| AC-TL-3 | ✓ | `enabled = points.size > 1` で単一点時に操作不可 |
| AC-PT-1 | ✓ | セッション選択時に `timelineProgress = 1f` |
| AC-PT-2 | ✓ | `selectedPoint` から日時・時刻・座標を再描画 |
| AC-PT-3 | ✓ | `clearSelection()` で状態リセット |
| AC-MAP-1 | ✓ | `selectedPoint` 用 Marker を追加 |
| AC-MAP-2 | ✓ | 既存 Polyline / スタート / ゴール表示を維持 |
| AC-MAP-3 | ✓ | 既存の地図未設定フォールバックを維持 |

## リスクサマリー

| 観点 | 評価 | 備考 |
|---|---|---|
| 機能回帰 | 低 | 変更範囲は History 画面と ViewModel に限定 |
| セキュリティ | 低 | 位置データの新規保存や送信処理は追加していない |
| 保守性 | 良好 | 進捗状態と導出状態が分離されている |

## 結論

must 指摘 0 件、should 指摘 0 件。今回の差分は仕様追補・設計追補・受け入れ条件追補に整合しているため、テストフェーズへ進行可能。

## Handoff Contract

### 実施サマリ
タイムライン機能差分をレビューし、要件逸脱・リリースブロッカーがないことを確認した。

### 成果物
- `docs/review-report.md`（本追補）

### 未解決事項
- NIT-TL-1: カメラ追従改善余地

### 次フェーズへの依頼事項
1. コンパイルと画面動作を通じて AC-TL / AC-PT / AC-MAP を確認すること。
2. 地図未設定時のフォールバック表示が維持されることを明示的に記録すること。

# レビューレポート: Firestore 座標データの Blob 圧縮保存 (geoFlatBlob)

- **レビュー対象**: `app/src/main/java/com/healthcare/app/data/repository/FirestoreSyncRepository.kt`（geoFlatBlob 対応差分 — 修正済み最終版）
- **レビュー日時**: 2026-05-19
- **レビュアー**: Reviewer Agent
- **参照ドキュメント**: `docs/specification.md`, `docs/design.md`, `docs/acceptance-criteria.md`

---

## 総合評価

**合格（must 指摘 0 件）**

前回指摘の MUST-1（GZIPInputStream リソースリーク）・MUST-2（stride 計算バグ）・SHOULD-1（catch スコープ過大）がすべて適切に修正された。  
仕様・受け入れ条件・設計方針との適合を確認し、次フェーズ（テスト）への進行を承認する。

---

## 前回指摘の対応確認

### MUST-1（解消済み）: `decodeGeoBlob` — `GZIPInputStream` リソースリーク

**対応内容**:
```kotlin
// 修正前
val raw = GZIPInputStream(ByteArrayInputStream(blob.toBytes())).readBytes()

// 修正後
val raw = GZIPInputStream(ByteArrayInputStream(blob.toBytes())).use { it.readBytes() }
```

`.use {}` でストリームを確実にクローズするよう修正済み。JVM リソース管理規約に適合。**解消を確認。**

---

### MUST-2（解消済み）: `uploadSession` — stride 計算バグ（AC-UPLOAD-3 違反）

**対応内容**:
```kotlin
// 修正前（floor 除算 — [20001, 39999] 点でフィルタが機能しない）
val stride = points.size / MAX_GEO_POINTS

// 修正後（ceil 除算）
val stride = (points.size + MAX_GEO_POINTS - 1) / MAX_GEO_POINTS
```

ceil 除算により、いかなる点数においても間引き後の点数が `MAX_GEO_POINTS (20,000)` 以下となることを確認。

| points.size | stride (旧) | stride (新) | 間引き後点数 (新) | AC-UPLOAD-3 |
|---|---|---|---|---|
| 20,000 | 1 | 1 | 20,000 | ✓ |
| 20,001 | 1 (バグ) | 2 | ≤ 10,001 | ✓ |
| 25,000 | 1 (バグ) | 2 | 12,500 | ✓ |
| 39,999 | 1 (バグ) | 2 | ≤ 20,000 | ✓ |
| 40,000 | 2 | 2 | 20,000 | ✓ |

**解消を確認。**

---

### SHOULD-1（解消済み）: `syncOnLogin` — `IOException` catch スコープ過大

**対応内容**:

外側の広い try-catch から、座標復元ブロックのみを内側の try-catch に移動する形に修正済み。

```kotlin
// 修正後イメージ
walkingRepository.upsertSessionFromRemote(session)       // catch 対象外
val roomSession = walkingRepository.getByUuid(sessionUuid) ?: continue  // catch 対象外

val coordPairs: List<Pair<Double, Double>> = try {
    val blob = doc.getBlob("geoFlatBlob")
    if (blob != null) {
        decodeGeoBlob(blob)   // ← catch 対象はここのみ
    } else {
        // geoFlat フォールバック処理
        ...
    }
} catch (e: IOException) {
    Log.w(TAG, "syncOnLogin: geoFlatBlob decode failed for doc=${doc.id}, skipping points", e)
    emptyList()
}

walkingRepository.addPoints(walkingPoints)               // catch 対象外
```

`upsertSessionFromRemote` / `addPoints` の IOException は外側 catch に委ねられ、誤ログ診断・サイレントデータロスのリスクが解消された。設計 D-2 の意図（座標復元ブロックのみを per-doc catch）に適合。**解消を確認。**

---

## 指摘一覧（最終版）

### MUST（リリースブロッカー）

なし — 前回指摘の MUST-1・MUST-2 はいずれも解消済み。

---

### SHOULD（品質上の重要指摘）

なし — 前回指摘の SHOULD-1 は解消済み。

---

### NICE-TO-HAVE（軽微な改善提案）

前回からの持ち越し提案。対応は任意。

---

#### [NIT-1] `@Suppress("UNCHECKED_CAST")` アノテーションが不要

**該当コード**:
```kotlin
@Suppress("UNCHECKED_CAST")
val geoFlat = doc.get("geoFlat") as? List<*> ?: emptyList<Any>()
```

`as? List<*>` は safe cast かつスタープロジェクションのため、Kotlin コンパイラは UNCHECKED_CAST 警告を出さない。アノテーションを削除しても警告は発生しない。

---

#### [NIT-2] `encodeGeoBlob` / `decodeGeoBlob` に `@VisibleForTesting` アノテーション未付与

設計 D-1 の意図（テストのために `internal` に変更）を明示するため、以下のアノテーションを追加することを推奨:
```kotlin
@VisibleForTesting
internal fun encodeGeoBlob(points: List<WalkingPoint>): Blob { ... }

@VisibleForTesting
internal fun decodeGeoBlob(blob: Blob): List<Pair<Double, Double>> { ... }
```

---

## 要件適合チェック

| 受け入れ条件 | 適合 | 備考 |
|---|---|---|
| AC-ENC-1: エンコード → GZIP ヘッダー開始 | ✓ | `GZIPOutputStream` が正しく使用されている |
| AC-ENC-2: 空リストで例外なし | ✓ | `ByteBuffer.allocate(0)` → 空 GZIP ストリーム生成 |
| AC-ENC-3: バイトサイズの正確性 (n×16) | ✓ | `buf.putDouble` × 2 で 16B/点 が保証される |
| AC-DEC-1: ラウンドトリップ保証 | ✓ | ByteBuffer big-endian がエンコード・デコードで一致 |
| AC-DEC-2: 末尾の不完全バイトを無視 | ✓ | `while (buf.remaining() >= 16)` で正しく処理 |
| AC-DEC-3: 破損 Blob が IOException を伝播 | ✓ | `decodeGeoBlob` は例外をそのまま投げる |
| AC-UPLOAD-1: `geoFlatBlob` フィールドが書き込まれる | ✓ | doc マップに `geoFlatBlob` が含まれる |
| AC-UPLOAD-2: `geoFlat` フィールドが不在 | ✓ | doc マップに `geoFlat` キーが存在しない |
| AC-UPLOAD-3: 20,000 点超を間引き | ✓ | **MUST-2 解消**: ceil 除算に修正済み。全点数パターンで 20,000 点以下を保証 |
| AC-UPLOAD-4: 未認証時は `Result.failure` | ✓ | `auth.currentUser?.uid ?: return Result.failure(...)` で処理 |
| AC-UPLOAD-5: 指数バックオフリトライ | ✓ | `withRetry` の初期遅延・最大遅延・試行回数が仕様通り |
| AC-UPLOAD-6: PERMISSION_DENIED はリトライなし | ✓ | エラーメッセージチェックで即時返却 |
| AC-SYNC-1: `geoFlatBlob` 優先で座標復元 | ✓ | `blob != null` チェックが先行する |
| AC-SYNC-2: `geoFlatBlob` 不在時 `geoFlat` フォールバック | ✓ | else ブランチで `List<*>` キャスト処理 |
| AC-SYNC-3: 両フィールド不在で座標なしマージ | ✓ | `geoFlat` が null のとき `emptyList()` が返る |
| AC-SYNC-4: ローカル既存セッションを上書きしない | ✓ | `getByUuid(sessionUuid) != null → continue` で保護 |
| AC-SYNC-5: 重複マージ防止 | ✓ | 同上 |

全 17 件の受け入れ条件が適合。

---

## セキュリティサマリー（OWASP Top 10 観点）

| 項目 | 評価 | 備考 |
|---|---|---|
| A01 アクセス制御の不備 | ○ | Firestore Security Rules はユーザー UID でスコープ済み |
| A02 暗号化の失敗 | ○ | Firebase SDK が TLS を担保。GPS 座標は Firestore に保存されるが認証・認可で保護 |
| A03 インジェクション | ○ | Firestore 型付き API を使用。任意文字列インジェクションリスクなし |
| A04 安全でない設計 | ○ | PHI（GPS 座標）はローカル平文保存なし |
| A09 セキュリティログの失敗 | ○ | 破損 Blob 時に `Log.w` でトレーサブルなログを出力 |

---

## 指摘サマリー

| 分類 | 件数 |
|---|---|
| must | **0** |
| should | **0** |
| nice-to-have | 2 |
| **合計** | **2** |

---

## 結論

must 指摘 0 件。前回の全 must / should 指摘が解消され、仕様・設計・受け入れ条件との適合を確認した。  
nice-to-have 2 件（NIT-1: `@Suppress` 不要、NIT-2: `@VisibleForTesting` 未付与）は任意対応。  
**次フェーズ（テスト）への進行を承認する。**

---

## Handoff Contract

### 実施サマリ
修正済み実装（MUST-1・MUST-2・SHOULD-1 対応後）に対して最終レビューを実施した。  
must 指摘 0 件、should 指摘 0 件を確認し、全受け入れ条件（17 件）の適合を検証した。

### 成果物
- `docs/review-report.md`（本ファイル — 最終版）

### 未解決事項
なし

### 次フェーズ（`tester`）への依頼事項
1. `docs/acceptance-criteria.md` の全条件（AC-ENC-1〜3、AC-DEC-1〜3、AC-UPLOAD-1〜6、AC-SYNC-1〜5）に対するテストケースを作成・実行し、結果を `docs/test-report.md` に記録すること。
2. `FirestoreSyncRepositoryTest.kt` において、修正済み stride 計算（points.size = 20,001 / 25,000 / 39,999）の境界値テストを重点的に検証すること。
3. `decodeGeoBlob` の破損 Blob 受信シナリオ（IOException 伝播）を確認すること。

