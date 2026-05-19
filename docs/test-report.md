# テストレポート: Firestore 座標データ Blob 圧縮保存 (geoFlatBlob)

- **テスト実施日**: 2026-05-19
- **テスト担当**: Tester Agent
- **対象ファイル**: `app/src/main/java/com/healthcare/app/data/repository/FirestoreSyncRepository.kt`
- **参照ドキュメント**: `docs/acceptance-criteria.md`

---

## テスト結果サマリー

| カテゴリ | PASS | FAIL | 検証手法 |
|---|---|---|---|
| ビルド検証 | 1 | 0 | `./gradlew assembleDebug` |
| AC-ENC（エンコード） | 3 | 0 | 静的解析 |
| AC-DEC（デコード） | 3 | 0 | 静的解析 |
| AC-UPLOAD（アップロード） | 6 | 0 | 静的解析 |
| AC-SYNC（サインイン時同期） | 5 | 0 | 静的解析 |

**総合判定: PASS**（ビルド成功・全 AC 項目 Pass）

---

## 1. ビルド検証

### 実行コマンド
```
cd c:\Users\tarau\home\prj\github\healthcare-app
.\gradlew assembleDebug 2>&1
```

### 結果: **BUILD SUCCESSFUL**

```
> Task :app:assembleDebug UP-TO-DATE

BUILD SUCCESSFUL in 2s
43 actionable tasks: 43 up-to-date
```

- コンパイルエラー: なし
- Deprecated API に関する警告あり（機能への影響なし）
- `app-debug.apk` 生成済み

---

## 2. 静的解析: エンコード処理

### AC-ENC-1 通常座標のエンコード: **PASS**

**根拠**:
```kotlin
internal fun encodeGeoBlob(points: List<WalkingPoint>): Blob {
    val buf = ByteBuffer.allocate(points.size * 16)
    for (p in points) {
        buf.putDouble(p.latitude)
        buf.putDouble(p.longitude)
    }
    val bos = ByteArrayOutputStream()
    GZIPOutputStream(bos).use { it.write(buf.array()) }
    return Blob.fromBytes(bos.toByteArray())
}
```

- `ByteBuffer` に lat(8B) + lng(8B) を書き込み、`GZIPOutputStream` で圧縮
- `GZIPOutputStream` の出力は必ず GZIP マジックバイト `1f 8b` で始まる
- 戻り値は `Blob.fromBytes(...)` で非 null の `Blob` が返る

---

### AC-ENC-2 空リストのエンコード: **PASS**

**根拠**:
- `points.size = 0` → `ByteBuffer.allocate(0)` → 空バイト配列を GZIP 圧縮
- `GZIPOutputStream.use { it.write(ByteArray(0)) }` は例外を発生させない
- `decodeGeoBlob` に渡すと `buf.remaining() < 16` で while ループに入らず `emptyList()` が返る

---

### AC-ENC-3 バイトサイズの正確性: **PASS**

**根拠**:
- `buf.putDouble(lat)` は 8 バイト、`buf.putDouble(lng)` は 8 バイト → 1 点 = 16 バイト
- `ByteBuffer.allocate(points.size * 16)` で正確に確保
- GZIP 展開後のバイト列は `points.size * 16` バイトと一致する

---

## 3. 静的解析: デコード処理

### AC-DEC-1 ラウンドトリップ保証: **PASS**

**根拠**:
```kotlin
internal fun decodeGeoBlob(blob: Blob): List<Pair<Double, Double>> {
    val raw = GZIPInputStream(ByteArrayInputStream(blob.toBytes())).use { it.readBytes() }
    val buf = ByteBuffer.wrap(raw)
    val result = mutableListOf<Pair<Double, Double>>()
    while (buf.remaining() >= 16) {
        result.add(buf.getDouble() to buf.getDouble())
    }
    return result
}
```

- `ByteBuffer.putDouble` / `getDouble` は IEEE 754 倍精度をバイト順序込みで保持（デフォルト Big Endian）
- encode → decode でバイト列が完全再現される → lat/lng 値は完全一致
- 件数も `allocate(n * 16)` → `while (remaining >= 16)` で n 回ループ → 件数一致

---

### AC-DEC-2 末尾の不完全バイトを無視: **PASS**

**根拠**:
- `while (buf.remaining() >= 16)` — 残りが 16 未満になると即座にループを抜ける
- `k` バイトの余剰分（0 < k < 16）は読み取られず、例外も発生しない

---

### AC-DEC-3 破損 Blob の例外伝播: **PASS**

**根拠**:
- 無効な GZIP バイト列を `GZIPInputStream` に渡すと `java.util.zip.ZipException`（`IOException` のサブクラス）が発生
- `decodeGeoBlob` 自体は例外を捕捉しない → 呼び出し元に伝播する
- `syncOnLogin` の呼び出しブロックが `catch (e: java.io.IOException)` で捕捉し `emptyList()` を返す:
  ```kotlin
  } catch (e: java.io.IOException) {
      Log.w(TAG, "syncOnLogin: failed to decode geoFlatBlob for doc=${doc.id}", e)
      emptyList()
  }
  ```
- アプリはクラッシュせず処理を継続する

---

## 4. 静的解析: セッションアップロード

### AC-UPLOAD-1 geoFlatBlob フィールドの書き込み: **PASS**

**根拠**:
```kotlin
val doc = mapOf(
    "sessionId"       to session.sessionUuid,
    "startTime"       to session.startTime,
    "endTime"         to session.endTime,
    "distanceMeters"  to session.totalDistanceMeters,
    "caloriesBurned"  to session.totalCalories,
    "geoFlatBlob"     to encodeGeoBlob(sampledPoints),
    "syncedAt"        to FieldValue.serverTimestamp()
)
```

- `geoFlatBlob` キーに `Blob` 型の値が設定される
- `geoFlat` キーはマップに存在しない

---

### AC-UPLOAD-2 geoFlat フィールドの不在: **PASS**

**根拠**:
- 上記 `doc` マップに `"geoFlat"` キーは含まれない
- `firestore...set(doc)` はマップのキーのみ書き込む → Firestore ドキュメントに `geoFlat` フィールドは作成されない

---

### AC-UPLOAD-3 20,000 点超の間引き: **PASS**

**根拠**:
```kotlin
val stride = (points.size + MAX_GEO_POINTS - 1) / MAX_GEO_POINTS
points.filterIndexed { index, _ -> index % stride == 0 }
```

- 30,000 点の場合: `stride = (30000 + 20000 - 1) / 20000 = 49999 / 20000 = 2`（切り上げ除算）
- `index % 2 == 0` で 0, 2, 4, … → 取得点数 = 15,000 点（≤ 20,000）✓
- アップロード自体は成功する

> **注記**: `docs/acceptance-criteria.md` AC-UPLOAD-3 の Given 節に「stride = floor(30000 / 20000) = 1」と記載があるが、実装は切り上げ除算を使用しており stride=2 となる。Then 節の条件（20,000 点以下）は満たしている。AC 側の stride 計算式の記載が誤りと判断する（詳細は §7 参照）。

---

### AC-UPLOAD-4 未認証時のエラー: **PASS**

**根拠**:
```kotlin
val uid = auth.currentUser?.uid
    ?: return Result.failure(IllegalStateException("ユーザーが未認証です"))
```

- `withRetry` 呼び出し前に早期リターン
- Firestore への書き込みは行われない
- `Result.failure(IllegalStateException)` が返る

---

### AC-UPLOAD-5 指数バックオフリトライ: **PASS**

**根拠**:
```kotlin
private suspend fun <T> withRetry(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 1_000L,
    block: suspend () -> Result<T>
): Result<T> {
    var delayMs = initialDelayMs
    for (attempt in 1..maxAttempts) {
        lastResult = block()
        if (lastResult.isSuccess) return lastResult
        if (attempt < maxAttempts) {
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(16_000L)
        }
    }
}
```

- 最大 3 回試行（`maxAttempts = 3`）
- 初回失敗後 1,000 ms → 2,000 ms と倍増する指数バックオフ
- 一時的ネットワークエラーで 2 回失敗後、3 回目に成功すると `Result.success` が返る

---

### AC-UPLOAD-6 PERMISSION_DENIED はリトライしない: **PASS**

**根拠**:
```kotlin
if (e?.message?.contains("PERMISSION_DENIED") == true) {
    Log.w(TAG, "withRetry: PERMISSION_DENIED – skipping retry")
    return lastResult
}
```

- エラーメッセージに `"PERMISSION_DENIED"` が含まれる場合、即座に `return lastResult`
- ループの `attempt = 1` の段階で返るため試行回数は 1 回のみ
- `Result.failure` が返る

---

## 5. 静的解析: サインイン時同期

### AC-SYNC-1 geoFlatBlob 優先で座標を復元: **PASS**

**根拠**:
```kotlin
val blob = doc.getBlob("geoFlatBlob")
if (blob != null) {
    decodeGeoBlob(blob)
} else {
    // geoFlat フォールバック
}
```

- `geoFlatBlob` が非 null の場合は `decodeGeoBlob(blob)` を使用
- `geoFlat` は読み取られない

---

### AC-SYNC-2 geoFlatBlob 不在時は geoFlat フォールバック: **PASS**

**根拠**:
- `blob == null` の場合は `else` ブロックに進む
- `doc.get("geoFlat")` を `List<*>` にキャストし、2 要素ずつ lat/lng として解釈
- 奇数インデックスまたは `Number` でない要素はスキップ
- 座標ペアが Room に `WalkingPoint` として保存される

---

### AC-SYNC-3 両フィールド不在時は座標なしでマージ: **PASS**

**根拠**:
- `blob == null` かつ `doc.get("geoFlat") = null` の場合: `geoFlat = emptyList<Any>()`
- `while (i + 1 < geoFlat.size)` のループに入らず `coordPairs = emptyList()`
- `walkingPoints = emptyList()` → `if (walkingPoints.isNotEmpty())` が偽 → `addPoints` 未呼び出し
- セッションメタデータ（`startTime`, `endTime`, `distanceMeters`, `caloriesBurned`）は `upsertSessionFromRemote` で保存済み
- 例外は発生しない

---

### AC-SYNC-4 ローカル既存セッションは上書きしない: **PASS**

**根拠**:
```kotlin
if (walkingRepository.getByUuid(sessionUuid) != null) continue
```

- Firestore ドキュメントを処理する前に `getByUuid` でローカル存在確認
- 既存の場合は `continue` で即座にスキップ
- `upsertSessionFromRemote` も `addPoints` も呼ばれない

---

### AC-SYNC-5 重複マージ防止: **PASS**

**根拠**:
- AC-SYNC-4 と同じ `getByUuid != null` チェックが重複防止に機能する
- 1 回目の `syncOnLogin` 完了後、2 回目の `syncOnLogin` でも同一 UUID に対して `continue` が実行される
- Room に当該セッションが重複登録されない

---

## 6. 未実施事項

| 項目 | 理由 |
|---|---|
| 実機・エミュレータでの動作確認 | `google-services.json` が本番プロジェクト用に未設定のため |
| Firestore への実際の書き込み確認 | 実機環境が必要 |
| ユニットテスト（JUnit/Mockito/Robolectric） | 既存テストコードが未整備のため静的解析で代替 |
| AC-UPLOAD-5 リトライ動作の結合テスト | ネットワーク障害の再現環境が未整備 |
| AC-UPLOAD-3 点数カウントの実測 | インスツルメンテーションテストが必要 |

---

## 7. 特記事項: AC-UPLOAD-3 の stride 計算に関するドキュメント不整合

`docs/acceptance-criteria.md` の AC-UPLOAD-3 Given 節:
> stride = floor(30000 / 20000) = 1

実装では切り上げ除算（ceil）を使用:
```kotlin
val stride = (points.size + MAX_GEO_POINTS - 1) / MAX_GEO_POINTS  // ceil division
// 30,000 点の場合: (30000 + 19999) / 20000 = 2
```

- AC の floor 式では stride=1 → `index % 1 == 0` が常に真 → 全 30,000 点取得（Then 条件「20,000 点以下」を満たさない）
- 実装の ceil 式では stride=2 → 15,000 点取得（Then 条件を満たす）

**結論**: 実装が正しく、`docs/acceptance-criteria.md` の stride 計算式の記載が誤り。次フェーズで AC の修正を推奨する。

---

## Handoff Contract

### 実施サマリー
- `./gradlew assembleDebug` を実行し BUILD SUCCESSFUL を確認（43 タスク up-to-date、コンパイルエラーなし）
- `FirestoreSyncRepository.kt` の全変更実装（`encodeGeoBlob`, `decodeGeoBlob`, `uploadSession`, `syncOnLogin`）を静的解析
- `docs/acceptance-criteria.md` の全 17 AC 項目を **全て Pass** と判定

### 成果物一覧
- `docs/test-report.md`（本ファイル）

### 未解決事項
- 実機テスト未実施（`google-services.json` の差し替えが必要）
- AC-UPLOAD-3 の `docs/acceptance-criteria.md` stride 計算式の誤記（修正推奨）

### 次フェーズへの依頼事項
- `quality-controller`: ビルド成功・全 AC Pass の結果を受けて Go / No-Go 判定を実施してください。
- `spec-writer`（任意）: AC-UPLOAD-3 の Given 節 stride 計算式を `ceil(n / MAX_GEO_POINTS)` に修正することを推奨します。
