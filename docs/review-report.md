# レビューレポート: Firebase 認証とクラウド同期機能

- **レビュー対象ブランチ/差分**: Firebase Auth + Firestore 同期実装差分
- **レビュー日時**: 2026-04-20
- **レビュアー**: Reviewer Agent
- **参照ドキュメント**: `docs/specification.md`, `docs/design.md`

---

## 総合評価

**条件付き合格**

must 指摘が 1 件あり（#MUST-1: `sessionUuid` のデフォルト値不備）、このままでは全新規セッションの Firestore 同期が永続的に失敗する。当該修正後は機能要件を満たす水準に達する。

---

## 指摘一覧

### MUST（リリースブロッカー）

---

#### [MUST-1] `WalkingSession.sessionUuid` のデフォルト値が空文字列

**ファイル**: `app/src/main/java/com/healthcare/app/data/entity/WalkingSession.kt`

**該当コード**:
```kotlin
val sessionUuid: String = "",
```

**問題**:
新規セッション作成時に `sessionUuid` を明示指定しない場合、すべてのセッションが `sessionUuid = ""` を持つ。その結果:
1. `FirestoreSyncRepository.uploadSession` で `document("")` を呼び出し → Firestore が `IllegalArgumentException` をスロー（catchされ`FAILED`扱い、以降も永続的に同期失敗）
2. `fetchAndMerge` の `getByUuid("")` が全空UUID行にマッチし、重複排除が機能しない
3. FR-SYNC-1（セッション完了時 Firestore 書き込み）が実質的に無効化される

**修正提案**:
```kotlin
import java.util.UUID

val sessionUuid: String = UUID.randomUUID().toString(),
```
Kotlin のデフォルト引数は呼び出しごとに評価されるため、コンストラクタ呼び出し毎に新規 UUID が生成される。

---

### SHOULD（品質・セキュリティ上の重要指摘）

---

#### [SHOULD-1] `SyncStatusConverter.toSyncStatus` が不正値でクラッシュする

**ファイル**: `app/src/main/java/com/healthcare/app/data/entity/SyncStatusConverter.kt`

**該当コード**:
```kotlin
fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)
```

**問題**:
`SyncStatus.valueOf(value)` は対応する enum 定数が存在しない場合 `IllegalArgumentException` をスローする。将来的な enum 追加や DB 破損でアプリがクラッシュするリスクがある。

**修正提案**:
```kotlin
fun toSyncStatus(value: String): SyncStatus =
    runCatching { SyncStatus.valueOf(value) }.getOrDefault(SyncStatus.PENDING)
```

---

#### [SHOULD-2] `fetchAndMerge` の `uid` をメソッド引数で受け取るセキュリティリスク

**ファイル**: `app/src/main/java/com/healthcare/app/data/repository/FirestoreSyncRepository.kt`

**該当コード**:
```kotlin
suspend fun fetchAndMerge(uid: String, walkingRepository: WalkingRepository): Result<Unit> {
    ...
    val snapshot = firestore.collection("users/$uid/walking_sessions").get().await()
```

**問題**:
呼び出し元が任意の `uid` を渡せる API 設計になっている。現在は `HomeViewModel` が `user.uid` を渡しており安全だが、Firestore Security Rules が最終防衛線となる構造は OWASP A01（アクセス制御の不備）観点で脆弱。

**修正提案**:
メソッド引数から `uid` を削除し、内部で `auth.currentUser?.uid` を取得する:
```kotlin
suspend fun fetchAndMerge(walkingRepository: WalkingRepository): Result<Unit> {
    val uid = auth.currentUser?.uid
        ?: return Result.failure(IllegalStateException("ユーザーが未認証です"))
    ...
}
```

---

#### [SHOULD-3] `FirestoreSyncRepository` が `WalkingRepository` をメソッド引数で受け取る設計

**ファイル**: `app/src/main/java/com/healthcare/app/data/repository/FirestoreSyncRepository.kt`

**該当コード**:
```kotlin
suspend fun fetchAndMerge(uid: String, walkingRepository: WalkingRepository): Result<Unit>
```

**問題**:
Repository が別の Repository に直接依存する設計はレイヤー境界を侵害し、テスト可能性と責務の明確さを損なう。設計ドキュメントではこのメソッドを `FirestoreSyncRepository` の責務としているが、依存注入の方向が逆転している。

**修正提案**:
取得のみ `FirestoreSyncRepository` が行い、マージ処理は ViewModel 側で行うよう分割する:
```kotlin
// FirestoreSyncRepository: Firestore からセッション一覧を返すのみ
suspend fun fetchSessions(): Result<List<WalkingSession>>

// HomeViewModel 側でマージを呼ぶ
firestoreSyncRepository.fetchSessions()
    .onSuccess { sessions -> repository.upsertSessionsFromRemote(sessions) }
```

---

#### [SHOULD-4] Firestore Security Rules が `delete` 操作を許可している

**ファイル**: `firestore.rules`

**該当コード**:
```
allow read, write: if request.auth != null && request.auth.uid == userId;
```

**問題**:
`write` は `create`・`update`・`delete` をすべて含む。仕様スコープには Firestore からのクライアント側削除は含まれていないが、現在のルールではクライアントから自身のセッションを Firestore 上で削除できてしまう。不正クライアントや将来的な誤実装でデータ消失のリスクがある。

**修正提案**:
```
allow read, create, update: if request.auth != null && request.auth.uid == userId;
```

---

#### [SHOULD-5] `fetchAndMerge` が全セッションを一括取得（ページネーションなし）

**ファイル**: `app/src/main/java/com/healthcare/app/data/repository/FirestoreSyncRepository.kt`

**該当コード**:
```kotlin
val snapshot = firestore.collection("users/$uid/walking_sessions").get().await()
```

**問題**:
セッション数が増加するとメモリ使用量・Firestore 読み取りコスト・ネットワーク転送量がリニアに増加する。1,000 件以上では顕著な遅延が発生しうる。

**修正提案**:
最終同期日時をローカルに保存し、差分のみを取得する:
```kotlin
.whereGreaterThan("syncedAt", Timestamp(lastSyncMillis / 1000, 0))
```
または `.limit(200)` + ページネーションを導入する。

---

#### [SHOULD-6] `SyncWorker` の再試行上限が未定義

**ファイル**: `app/src/main/java/com/healthcare/app/sync/SyncWorker.kt`

**該当コード**:
```kotlin
return if (hasFailure) Result.retry() else Result.success()
```

**問題**:
WorkManager は `Result.retry()` を返すと指数バックオフで無限に再試行する。ネットワーク障害やサーバー障害が長期化した場合、バッテリーと通信量を消費し続ける。

**修正提案**:
```kotlin
if (hasFailure) {
    if (runAttemptCount < 5) Result.retry() else Result.success()
} else Result.success()
```
再試行上限に達した場合は `FAILED` ステータスのまま残し、次回の `PeriodicWorkRequest` で再同期する。

---

#### [SHOULD-7] `NetworkMonitor` が `NET_CAPABILITY_VALIDATED` を確認しない

**ファイル**: `app/src/main/java/com/healthcare/app/sync/NetworkMonitor.kt`

**該当コード**:
```kotlin
val request = NetworkRequest.Builder()
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .build()
```

**問題**:
`NET_CAPABILITY_INTERNET` はネットワークがインターネット接続の能力を持つことを示すだけであり、実際の疎通（バリデーション）は確認しない。キャプティブポータル（ホテル Wi-Fi 等）では `isConnected = true` と判定されながら Firestore アクセスが失敗し続ける。

**修正提案**:
```kotlin
val request = NetworkRequest.Builder()
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    .build()
```
`isConnectedNow()` も同様に `hasCapability(NET_CAPABILITY_VALIDATED)` を追加する。

---

#### [SHOULD-8] `AppDatabase` の `exportSchema = false`

**ファイル**: `app/src/main/java/com/healthcare/app/data/db/AppDatabase.kt`

**該当コード**:
```kotlin
@Database(entities = [...], version = 2, exportSchema = false)
```

**問題**:
スキーマエクスポートが無効化されているため、DB バージョン履歴をソース管理できない。マイグレーションのレビューやデバッグが困難になる。

**修正提案**:
```kotlin
@Database(entities = [...], version = 2, exportSchema = true)
```
`app/schemas/` ディレクトリにエクスポートし `.gitignore` から除外する。`app/build.gradle.kts` に以下を追加:
```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

---

### NIT（nice-to-have）

---

#### [NIT-1] `WalkingSessionDao.updateSyncStatus` の `status` パラメータが `String` 型

**ファイル**: `app/src/main/java/com/healthcare/app/data/dao/WalkingSessionDao.kt`

```kotlin
suspend fun updateSyncStatus(id: Long, status: String, docId: String?)
```

`SyncStatus` 型で受け取り `.name` への変換を DAO 内に閉じ込めると、呼び出し元での変換忘れを防げる。

---

#### [NIT-2] `authState` Flow が複数 collector で複数の `AuthStateListener` を登録する

**ファイル**: `app/src/main/java/com/healthcare/app/data/repository/AuthRepository.kt`

`callbackFlow` はコールドフローのため、collect のたびに新しいリスナーが登録される。`stateIn` を使うとホットな `StateFlow` として共有できる（ただし適切なスコープが必要）。

---

#### [NIT-3] `sessionUuid` に UNIQUE インデックスがない

**ファイル**: `app/src/main/java/com/healthcare/app/data/entity/WalkingSession.kt`

DB レベルで一意性を保証するため、エンティティに以下を追加することを検討:
```kotlin
@Entity(
    tableName = "walking_sessions",
    indices = [Index(value = ["sessionUuid"], unique = true)]
)
```

---

#### [NIT-4] `fetchAndMerge` の失敗がサイレントに無視される

**ファイル**: `app/src/main/java/com/healthcare/app/ui/screen/home/HomeViewModel.kt`

```kotlin
.onSuccess { user ->
    firestoreSyncRepository.fetchAndMerge(user.uid, repository)
    // Result<Unit> が捨てられている
}
```

FR-SYNC-2 の「同期失敗がローカル保存に影響しない」要件は満たしているが、失敗時のログ出力が一切ない。デバッグ困難を招くため `.onFailure { Log.w(TAG, "fetchAndMerge failed", it) }` を追加することを推奨。

---

#### [NIT-5] `ApiException.statusCode` をユーザー向けエラーメッセージに含める

**ファイル**: `app/src/main/java/com/healthcare/app/ui/screen/home/HomeViewModel.kt`

```kotlin
_uiState.update { it.copy(authError = "サインインがキャンセルされました (${e.statusCode})") }
```

`12501` 等の内部コードはエンドユーザーに意味をなさない。ログには残しつつ、表示メッセージはコードなしにする。

---

## セキュリティサマリー（OWASP Top 10 対応状況）

| OWASP 項目 | 評価 | 備考 |
|---|---|---|
| A01 アクセス制御の不備 | △ | Firestore Security Rules は適切。ただし `fetchAndMerge` の uid 引数設計は改善余地あり（SHOULD-2） |
| A02 暗号化の失敗 | ○ | Firebase SDK が TLS を担保。`google-services.json` はコミット済みだが通常運用として許容範囲 |
| A03 インジェクション | ○ | Room の `@Query` パラメータは型安全。Firestore SDK もインジェクション対策済み |
| A04 安全でない設計 | △ | `write` ルールが `delete` を含む（SHOULD-4）。`uid` 引数設計（SHOULD-2） |
| A05 セキュリティ設定ミス | △ | `exportSchema = false`（SHOULD-8）。Security Rules の write 範囲（SHOULD-4） |
| A07 認証とセッション管理の失敗 | ○ | Firebase Auth + ID トークン検証の実装は正しい。`authState` の `callbackFlow` + `awaitClose` も適切 |
| A09 セキュリティログの失敗 | △ | 同期失敗のログが未実装（NIT-4） |

---

## データ整合性サマリー

| 観点 | 評価 | 備考 |
|---|---|---|
| `sessionUuid` 一意性（新規） | ✗ | **MUST-1**: デフォルト値 `""` により一意性が保証されない |
| `sessionUuid` 一意性（既存行） | ○ | マイグレーションの UUID v4 近似 SQL は正確 |
| マイグレーション SQL | ○ | `ALTER TABLE` + `UPDATE` の順序・構文は正しい |
| `syncStatus` 整合性 | △ | TypeConverter の堅牢性に課題（SHOULD-1） |
| カスケード削除との共存 | ○ | `walking_points` の CASCADE 設定に影響なし |

---

## 既存機能への影響評価

| 機能 | 影響 | 内容 |
|---|---|---|
| ウォーキング追跡 | なし | `TrackingViewModel`/`LocationTrackingService` に変更なし |
| 履歴一覧・詳細 | なし | `HistoryViewModel`/`HistoryScreen` に変更なし |
| 統計・集計 | なし | `DailyAggregation` クエリに変更なし |
| 削除機能（前フェーズ） | なし | `deleteByIds` は既存のまま維持されている |
| Room DB v1→v2 マイグレーション | 要注意 | MUST-1 修正前に新規レコードを作成すると `sessionUuid = ""` が混入する |

---

## 指摘サマリー

| 分類 | 件数 |
|---|---|
| must | 1 |
| should | 8 |
| nit | 5 |
| **合計** | **14** |

### nice-to-have
- 1件: 削除完了時に Snackbar 等で結果通知があると操作結果が分かりやすい。
  - 対応方針: 今回スコープ外として見送り。

## 要件適合チェック
- AC-1〜AC-9 に対応する UI/状態遷移/削除処理が実装されていることを差分で確認。

## 結論
- must 指摘は 0 件。
- 実装は仕様と整合し、次フェーズ進行可能。
