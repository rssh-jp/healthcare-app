# タスク分解追補: 履歴表示画面のタイムライン表示

## Task 1: `HistoryUiState` にタイムライン状態を追加

**優先度**: P0  
**ファイル**: `app/src/main/java/com/healthcare/app/ui/screen/history/HistoryViewModel.kt`

**変更内容**:
- `timelineProgress: Float` を `HistoryUiState` に追加
- セッション選択時に初期値を設定
- 詳細終了時に状態をリセット

**完了条件**:
- セッション選択で `timelineProgress` が初期化される
- `clearSelection()` でタイムライン状態がリセットされる

## Task 2: `HistoryScreen` にタイムライン UI を追加

**優先度**: P0  
**ファイル**: `app/src/main/java/com/healthcare/app/ui/screen/history/HistoryScreen.kt`

**変更内容**:
- タイムラインカード追加
- Slider 追加
- 選択日時、選択時刻、座標表示

**完了条件**:
- 履歴詳細画面でスライダーと時刻情報が表示される
- 単一点セッションではスライダーが disabled になる

## Task 3: 地図マーカー連動を追加

**優先度**: P1  
**ファイル**: `app/src/main/java/com/healthcare/app/ui/screen/history/HistoryScreen.kt`

**変更内容**:
- `timelineProgress` から選択地点を計算
- 選択位置マーカーを地図へ表示
- 既存ルート・スタート・ゴール表示を維持

**完了条件**:
- タイムライン操作で選択位置マーカーが更新される
- 既存地図表示が崩れない

## Task 4: ビルド・受け入れ条件確認

**優先度**: P1

**手順**:
1. `./gradlew :app:compileDebugKotlin`
2. AC-TL / AC-PT / AC-MAP の観点で確認

**完了条件**:
- コンパイル成功
- 主要受け入れ条件に対する結果が `docs/test-report.md` に記録される

## Handoff Contract

### 実施サマリ
タイムライン機能を ViewModel 状態追加、UI 追加、地図連動、検証の 4 タスクに分解した。

### 成果物
- `docs/task-breakdown.md`（本追補）

### 未解決事項
- None

### 次フェーズへの依頼事項
1. まず `HistoryViewModel` の状態追加を実装し、その後 UI と地図連動を追加すること。
2. 実装後はコンパイル結果と AC ごとの確認結果をレポート化すること。

# タスク分解: Firestore 座標データの Blob 圧縮保存 (geoFlatBlob)

## 優先順位凡例
- P0: ブロッカー（後続タスクが依存）
- P1: コア機能
- P2: 補助・仕上げ

---

## Task 1: `encodeGeoBlob` / `decodeGeoBlob` の可視性変更

**優先度**: P0  
**ファイル**: `app/src/main/java/com/healthcare/app/data/repository/FirestoreSyncRepository.kt`

**変更内容**:
- `private fun encodeGeoBlob(...)` → `internal fun encodeGeoBlob(...)`
- `private fun decodeGeoBlob(...)` → `internal fun decodeGeoBlob(...)`

**完了条件**:
- `internal` キーワードが付与されていること
- 同クラス内からの呼び出しコードは変更不要（`private` → `internal` は後退しない）
- コンパイルエラーがないこと

**依存**: なし

---

## Task 2: `syncOnLogin` に per-doc 例外ハンドリングを追加

**優先度**: P0  
**ファイル**: `app/src/main/java/com/healthcare/app/data/repository/FirestoreSyncRepository.kt`

**変更内容**:  
`syncOnLogin` の Step 2 ドキュメントループ内で、`geoFlatBlob` の `decodeGeoBlob` 呼び出し部分を try-catch で囲む。

```kotlin
// 変更前（現状）
val coordPairs: List<Pair<Double, Double>> = run {
    val blob = doc.getBlob("geoFlatBlob")
    if (blob != null) {
        decodeGeoBlob(blob)           // ← 例外が関数レベル catch まで届く
    } else {
        // geoFlat フォールバック ...
    }
}

// 変更後
val coordPairs: List<Pair<Double, Double>> = run {
    val blob = doc.getBlob("geoFlatBlob")
    if (blob != null) {
        try {
            decodeGeoBlob(blob)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "syncOnLogin: geoFlatBlob decode failed for $sessionUuid, skipping points", e)
            emptyList()               // ← 座標なしマージを継続
        }
    } else {
        // geoFlat フォールバック ...
    }
}
```

**完了条件**:
- AC-DEC-3: 破損 Blob を渡しても `syncOnLogin` は `Result.failure` を返さず、セッションメタデータが Room にマージされること
- AC-SYNC-1〜5 のいずれも既存挙動を維持すること
- コンパイルエラーがないこと

**依存**: Task 1 の完了（`internal` 変更後にテストで確認するため）

---

## Task 3: `encodeGeoBlob` / `decodeGeoBlob` のユニットテスト追加

**優先度**: P1  
**ファイル**: `app/src/test/java/com/healthcare/app/data/repository/FirestoreSyncRepositoryTest.kt`（新規または追記）

**テストケース**:

| テスト名 | 対応 AC |
|----------|---------|
| `encodeGeoBlob_returnsGzipBlob` | AC-ENC-1 |
| `encodeGeoBlob_emptyList_returnsEmptyBlob` | AC-ENC-2 |
| `encodeGeoBlob_byteSize_matchesExpected` | AC-ENC-3 |
| `decodeGeoBlob_roundTrip` | AC-DEC-1 |
| `decodeGeoBlob_ignoresTrailingBytes` | AC-DEC-2 |
| `decodeGeoBlob_corruptedBlob_throwsIOException` | AC-DEC-3 |

**実装方針**:
- Robolectric または純粋な JVM テストで実行（Firestore への接続不要）
- `FirestoreSyncRepository` のコンストラクタは `FirebaseFirestore` / `FirebaseAuth` を必要とするため、モックして初期化する
- `Blob.fromBytes(...)` は Firebase SDK の静的メソッド。モック不要（実 SDK をテストクラスパスに含める）

**完了条件**:
- 6 件のテストがすべて PASS すること
- `encodeGeoBlob` / `decodeGeoBlob` が `internal` であることを前提とした直接呼び出しであること

**依存**: Task 1

---

## Task 4: ビルド検証

**優先度**: P2  
**手順**:
```
make build
```

**完了条件**:
- `BUILD SUCCESSFUL` であること
- Task 1〜3 の変更が含まれた状態でコンパイルエラーがないこと

**依存**: Task 1, 2, 3

---

## タスク依存グラフ

```
Task 1 (internal 化)
    └─ Task 2 (per-doc catch)
    └─ Task 3 (ユニットテスト)
           └─ Task 4 (ビルド検証)
```

---

## 受け入れ条件との対応マトリクス

| 受け入れ条件 | 対応タスク |
|-------------|-----------|
| AC-ENC-1 通常座標のエンコード | Task 3 |
| AC-ENC-2 空リストのエンコード | Task 3 |
| AC-ENC-3 バイトサイズの正確性 | Task 3 |
| AC-DEC-1 ラウンドトリップ保証 | Task 3 |
| AC-DEC-2 末尾の不完全バイトを無視 | Task 3 |
| AC-DEC-3 破損 Blob の例外伝播 | Task 2 + Task 3 |
| AC-UPLOAD-1 geoFlatBlob フィールドの書き込み | 既実装（変更なし） |
| AC-UPLOAD-2 geoFlat フィールドの不在 | 既実装（変更なし） |
| AC-UPLOAD-3 20,000 点超の間引き | 既実装（変更なし） |
| AC-UPLOAD-4 未認証時のエラー | 既実装（変更なし） |
| AC-UPLOAD-5 指数バックオフリトライ | 既実装（変更なし） |
| AC-UPLOAD-6 PERMISSION_DENIED はリトライしない | 既実装（変更なし） |
| AC-SYNC-1 geoFlatBlob 優先で座標を復元 | 既実装（変更なし） |
| AC-SYNC-2 geoFlatBlob 不在時は geoFlat フォールバック | 既実装（変更なし） |
| AC-SYNC-3 両フィールド不在時は座標なしでマージ | 既実装（変更なし） |
| AC-SYNC-4 ローカル既存セッションは上書きしない | 既実装（変更なし） |
| AC-SYNC-5 重複マージ防止 | 既実装（変更なし） |
