# タスク分解: 履歴画面で選択した項目の削除

## Task 1 ViewModel 状態拡張
- HistoryUiState に選択関連状態を追加
- 一覧更新時に選択IDの整合を維持
- 完了条件: 選択モード/選択件数/ダイアログ表示を状態で保持できる

## Task 2 ViewModel 操作追加
- 選択モード開始/終了
- 項目選択トグル
- 削除確認要求/確定/キャンセル
- 完了条件: UI から呼べるイベントが一通り揃う

## Task 3 Repository 削除 API 追加
- deleteSessionsByIds(ids) を追加
- 完了条件: 複数 ID 指定でセッション削除できる

## Task 4 HistoryScreen UI 変更
- 一覧ヘッダーに選択/削除/キャンセル導線を追加
- 選択モード時のカード選択 UI (チェックボックス) を追加
- 削除確認ダイアログを追加
- 完了条件: AC-1〜AC-9 を画面操作で満たす

## Task 5 検証
- make build 実行
- 受け入れ条件に沿った手動確認結果を test-report に記録
- 完了条件: ビルド成功、主要シナリオ結果記録

---

# タスク分解: Firebase 認証とウォーキング履歴のクラウド同期

## 優先順位凡例
- P0: ブロッカー（後続タスクが依存）
- P1: コア機能
- P2: 補助機能・仕上げ

---

## Phase A: 基盤整備（P0）

### A-1 依存ライブラリ追加
**優先度**: P0  
**ファイル**:
- `gradle/libs.versions.toml`
- `app/build.gradle.kts`

**変更内容**:
```toml
# libs.versions.toml [versions] に追加
firebase-bom = "33.1.0"
play-services-auth = "21.2.0"
work-runtime = "2.9.1"

# [libraries] に追加
firebase-bom = { group = "com.google.firebase", name = "firebase-bom", version.ref = "firebase-bom" }
firebase-auth-ktx = { group = "com.google.firebase", name = "firebase-auth-ktx" }
firebase-firestore-ktx = { group = "com.google.firebase", name = "firebase-firestore-ktx" }
play-services-auth = { group = "com.google.android.gms", name = "play-services-auth", version.ref = "play-services-auth" }
work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work-runtime" }

# [plugins] に追加
google-services = { id = "com.google.gms.google-services", version = "4.4.2" }
```

`app/build.gradle.kts` に `alias(libs.plugins.google.services)` プラグイン追加 + dependencies 追加。  
`settings.gradle.kts` / `build.gradle.kts` にクラスパス追加。  
`app/` 直下に `google-services.json` が存在することを前提とする（コミット対象外）。

**完了条件**: Gradle sync 成功、Firebase SDK が classpath に解決されること。

---

### A-2 SyncStatus enum 追加
**優先度**: P0  
**ファイル**: `app/src/main/java/com/healthcare/app/data/entity/SyncStatus.kt`（新規）

**変更内容**:
```kotlin
package com.healthcare.app.data.entity

enum class SyncStatus { PENDING, SYNCED, FAILED }
```

**完了条件**: コンパイル通過。

---

### A-3 WalkingSession エンティティ変更 + Room Migration 1→2
**優先度**: P0  
**ファイル**:
- `app/src/main/java/com/healthcare/app/data/entity/WalkingSession.kt`（変更）
- `app/src/main/java/com/healthcare/app/data/db/AppDatabase.kt`（変更）
- `app/src/main/java/com/healthcare/app/data/db/Migration1to2.kt`（新規）

**WalkingSession.kt 変更内容**:
```kotlin
import com.healthcare.app.data.entity.SyncStatus

@Entity(tableName = "walking_sessions")
data class WalkingSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionUuid: String = "",          // 追加
    val startTime: Long,
    val endTime: Long? = null,
    val totalDistanceMeters: Double = 0.0,
    val totalCalories: Double = 0.0,
    val isActive: Boolean = true,
    val syncStatus: SyncStatus = SyncStatus.PENDING,  // 追加
    val firestoreDocId: String? = null                // 追加
)
```

**Migration1to2.kt**:
```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE walking_sessions ADD COLUMN sessionUuid TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE walking_sessions ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'PENDING'")
        database.execSQL("ALTER TABLE walking_sessions ADD COLUMN firestoreDocId TEXT")
        database.execSQL("""
            UPDATE walking_sessions SET sessionUuid =
                lower(hex(randomblob(4))) || '-' ||
                lower(hex(randomblob(2))) || '-4' ||
                substr(lower(hex(randomblob(2))), 2) || '-' ||
                substr('89ab', abs(random() % 4) + 1, 1) ||
                substr(lower(hex(randomblob(2))), 2) || '-' ||
                lower(hex(randomblob(6)))
        """)
    }
}
```

**AppDatabase.kt 変更**: `version = 2`、`exportSchema = true`（スキーマ検証用）、`addMigrations(MIGRATION_1_2)`。

**完了条件**: Room スキーマ v2 ビルド成功、既存端末で起動してクラッシュなし。

---

### A-4 WalkingSessionDao 拡張
**優先度**: P0  
**ファイル**: `app/src/main/java/com/healthcare/app/data/dao/WalkingSessionDao.kt`（変更）

**追加クエリ**:
```kotlin
@Query("SELECT * FROM walking_sessions WHERE syncStatus IN ('PENDING', 'FAILED') AND isActive = 0")
suspend fun getPendingOrFailedSessions(): List<WalkingSession>

@Query("UPDATE walking_sessions SET syncStatus = :status, firestoreDocId = :docId WHERE id = :id")
suspend fun updateSyncStatus(id: Long, status: String, docId: String?)

@Query("SELECT * FROM walking_sessions WHERE sessionUuid = :uuid LIMIT 1")
suspend fun getByUuid(uuid: String): WalkingSession?

@Insert(onConflict = OnConflictStrategy.IGNORE)
suspend fun insertIfNotExists(session: WalkingSession): Long
```

**完了条件**: コンパイル通過。

---

### A-5 WalkingRepository 拡張
**優先度**: P0  
**ファイル**: `app/src/main/java/com/healthcare/app/data/repository/WalkingRepository.kt`（変更）

**変更内容**:
- `startNewSession()` を変更: `UUID.randomUUID().toString()` を生成し `sessionUuid` に設定してから `insert`
- `suspend fun updateSyncStatus(id: Long, status: SyncStatus, firestoreDocId: String?)` を追加
- `suspend fun getPendingSessions(): List<WalkingSession>` を追加（DAO 委譲）
- `suspend fun upsertSessionFromRemote(session: WalkingSession)` を追加（Firestore マージ用 `insertIfNotExists`）

**完了条件**: コンパイル通過、新規セッションに UUID が付与されること。

---

## Phase B: Firebase 認証（P1）

### B-1 FirebaseModule 追加（Hilt DI）
**優先度**: P1  
**ファイル**: `app/src/main/java/com/healthcare/app/di/FirebaseModule.kt`（新規）

**変更内容**:
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {
    @Provides @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides @Singleton
    fun provideFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()

    @Provides @Singleton
    fun provideGoogleSignInClient(@ApplicationContext ctx: Context): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(ctx.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(ctx, options)
    }
}
```

`R.string.default_web_client_id` は `google-services.json` から `google-services` プラグインが自動生成する。

**完了条件**: Hilt DI グラフがビルド成功すること。

---

### B-2 AuthRepository 追加
**優先度**: P1  
**ファイル**: `app/src/main/java/com/healthcare/app/data/repository/AuthRepository.kt`（新規）

**責務**:
- `authState: Flow<FirebaseUser?>` — `FirebaseAuth.addAuthStateListener` を `callbackFlow` で購読
- `val currentUser: FirebaseUser?` — 同期アクセス用
- `suspend fun signInWithGoogleIdToken(idToken: String): Result<FirebaseUser>` — `signInWithCredential` 呼び出し
- `suspend fun signOut()` — `FirebaseAuth.signOut()` + `GoogleSignInClient.signOut()`

**実装上の注意**:
- `callbackFlow` 内で `awaitClose { auth.removeAuthStateListener(listener) }` を必ず呼ぶ（リーク防止）。

**完了条件**: コンパイル通過、`authState` が `FirebaseAuth.currentUser` 変化を反映すること（ユニットテスト or 手動確認）。

---

### B-3 HomeViewModel に認証ロジック追加
**優先度**: P1  
**ファイル**: `app/src/main/java/com/healthcare/app/ui/screen/home/HomeViewModel.kt`（変更）

**変更内容**:
- `AuthRepository` を constructor inject
- `HomeUiState` に `currentUser: FirebaseUser?`, `isSigningIn: Boolean`, `authError: String?` を追加
- `authState` を `combine` に加える（または別途 `stateIn` で保持）
- `fun onSignInResult(result: ActivityResult)` — Google Sign-In Activity 結果を受け取り `idToken` を抽出して `AuthRepository.signInWithGoogleIdToken()` を呼ぶ
- `fun signOut()` — `AuthRepository.signOut()`、完了後 `FirestoreSyncRepository.fetchAndMerge` は呼ばない

**完了条件**: サインイン/サインアウト後に `HomeUiState.currentUser` が更新されること。

---

### B-4 HomeScreen に認証 UI 追加
**優先度**: P1  
**ファイル**: `app/src/main/java/com/healthcare/app/ui/screen/home/HomeScreen.kt`（変更）

**変更内容**:
- `LazyColumn` の先頭 `item { }` に `AuthUiSection(state, onSignIn, onSignOut)` を追加
- `rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult())` で Google Sign-In を起動
- サインイン状態: `CircleAvatar`（イニシャル）+ メールアドレス + 「サインアウト」テキストボタン
- 未サインイン状態: 「Google でサインイン」ボタン（`OutlinedButton`）
- `isSigningIn = true` 時は `CircularProgressIndicator` を表示
- `authError != null` 時は `Snackbar` でエラー表示

**完了条件**: AC-AUTH-1〜AC-AUTH-6 を手動確認できること。

---

## Phase C: Firestore 同期（P1）

### C-1 FirestoreSyncRepository 追加
**優先度**: P1  
**ファイル**: `app/src/main/java/com/healthcare/app/data/repository/FirestoreSyncRepository.kt`（新規）

**責務**:
```kotlin
@Singleton
class FirestoreSyncRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth
) {
    suspend fun uploadSession(session: WalkingSession, points: List<WalkingPoint>): Result<Unit>
    suspend fun fetchAndMerge(uid: String, walkingRepository: WalkingRepository): Result<Unit>
}
```

**uploadSession の詳細**:
1. `auth.currentUser` が null なら `Result.failure` を返す
2. `geoPoints` 配列を `points` から構築（20,000 点超は均等間引き）
3. `firestore.collection("users/$uid/walking_sessions").document(session.sessionUuid).set(doc).await()` を呼ぶ
4. 成功: `Result.success(Unit)` を返す

**fetchAndMerge の詳細**:
1. `firestore.collection("users/$uid/walking_sessions").get().await()` で全ドキュメント取得
2. 各ドキュメントを `WalkingSession` に変換
3. `walkingRepository.upsertSessionFromRemote(session)` で挿入（`sessionUuid` 重複時は `IGNORE`）

**完了条件**: AC-SYNC-1, AC-SYNC-4 を満たすこと。

---

### C-2 LocationTrackingService に同期トリガー追加
**優先度**: P1  
**ファイル**: `app/src/main/java/com/healthcare/app/service/LocationTrackingService.kt`（変更）

**変更内容**:
- `FirestoreSyncRepository` と `AuthRepository` を `@Inject` で追加
- `stopTracking()` の `repository.endSession(...)` 呼び出し後に以下を追加:
  ```kotlin
  val user = authRepository.currentUser
  if (user != null && networkMonitor.isConnectedNow()) {
      val session = repository.getById(currentSessionId) ?: return
      val points = repository.getPointsBySessionOnce(currentSessionId)
      val result = firestoreSyncRepository.uploadSession(session, points)
      repository.updateSyncStatus(
          currentSessionId,
          if (result.isSuccess) SyncStatus.SYNCED else SyncStatus.FAILED,
          session.sessionUuid.takeIf { result.isSuccess }
      )
      if (result.isFailure) scheduleSyncWorker()
  }
  ```
- `scheduleSyncWorker()`: `WorkManager.enqueueUniqueWork("pending_sync", KEEP, SyncWorker.buildRequest())`

**完了条件**: AC-SYNC-1, AC-SYNC-2 を満たすこと。

---

## Phase D: オフライン・再同期（P1）

### D-1 NetworkMonitor 追加
**優先度**: P1  
**ファイル**: `app/src/main/java/com/healthcare/app/sync/NetworkMonitor.kt`（新規）

**変更内容**:
```kotlin
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val isConnected: Flow<Boolean> = callbackFlow { /* NetworkCallback */ }
    fun isConnectedNow(): Boolean  // ConnectivityManager.activeNetworkInfo 同期確認
}
```

`ConnectivityManager.registerNetworkCallback` を `callbackFlow` で包む。`awaitClose` でコールバック解除。

**完了条件**: コンパイル通過。

---

### D-2 SyncWorker 追加
**優先度**: P1  
**ファイル**: `app/src/main/java/com/healthcare/app/sync/SyncWorker.kt`（新規）

**変更内容**:
```kotlin
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val walkingRepository: WalkingRepository,
    private val firestoreSyncRepository: FirestoreSyncRepository,
    private val authRepository: AuthRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val uid = authRepository.currentUser?.uid ?: return Result.success()
        val pending = walkingRepository.getPendingSessions()
        var hasFailure = false
        pending.forEach { session ->
            val points = walkingRepository.getPointsBySessionOnce(session.id)
            val result = firestoreSyncRepository.uploadSession(session, points)
            walkingRepository.updateSyncStatus(
                session.id,
                if (result.isSuccess) SyncStatus.SYNCED else SyncStatus.FAILED,
                session.sessionUuid.takeIf { result.isSuccess }
            )
            if (result.isFailure) hasFailure = true
        }
        return if (hasFailure) Result.retry() else Result.success()
    }

    companion object {
        fun buildRequest() = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
    }
}
```

`HiltWorkerFactory` を `HealthcareApp` に設定（`HiltAndroidApp` 継承済みの場合は `Configuration.Provider` 実装が必要）。

**完了条件**: AC-SYNC-3, AC-OFFLINE-2 を満たすこと。

---

### D-3 HiltWorkerFactory 設定
**優先度**: P1  
**ファイル**: `app/src/main/java/com/healthcare/app/HealthcareApp.kt`（変更）

**変更内容**:
- `Configuration.Provider` を実装し `HiltWorkerFactory` を注入
- `getWorkManagerConfiguration()` で `WorkManager.initialize()` に `HiltWorkerFactory` を渡す

**完了条件**: SyncWorker が WorkManager から起動できること（ログ確認）。

---

## Phase E: 複数端末マージ（P2）

### E-1 サインイン後の Firestore マージトリガー
**優先度**: P2  
**ファイル**: `app/src/main/java/com/healthcare/app/ui/screen/home/HomeViewModel.kt`（変更）

**変更内容**:
- `onSignInResult` 成功後に `firestoreSyncRepository.fetchAndMerge(uid, walkingRepository)` を呼ぶ
- `FirestoreSyncRepository` を `HomeViewModel` に constructor inject

**完了条件**: AC-MULTI-1, AC-MULTI-2 を満たすこと。

---

## Phase F: セキュリティ・仕上げ（P2）

### F-1 Firestore Security Rules 適用
**優先度**: P2  
**ファイル**: `docs/firestore.rules`（新規、Firebase コンソールへの適用手順をコメントに記載）

**変更内容**: design.md に記載の Rules をファイルに書き出す。  
Firebase CLI `firebase deploy --only firestore:rules` で適用。

**完了条件**: AC-MULTI-3 を満たすこと（別 UID のパスへのアクセスが拒否されること）。

---

### F-2 proguard-rules.pro 更新
**優先度**: P2  
**ファイル**: `app/proguard-rules.pro`（変更）

**変更内容**: Firebase / Google Play Services に関する ProGuard ルールを追加（release ビルドで難読化されないよう）。

**完了条件**: release ビルドが成功し、Firebase 機能が動作すること。

---

## 実装タスク依存関係

```
A-1 → A-2 → A-3 → A-4 → A-5
                         ↓
A-1 → B-1 → B-2 → B-3 → B-4
                         ↓
             B-2 → C-1 → C-2 → D-2
                         ↓
             D-1 → C-2
             D-1 → D-2
             D-2 → D-3
             C-1 → E-1
             B-3 → E-1
```

## 各タスクの変更ファイル早見表

| タスク | 変更ファイル |
|---|---|
| A-1 | `gradle/libs.versions.toml`, `app/build.gradle.kts`, `settings.gradle.kts` |
| A-2 | `data/entity/SyncStatus.kt`（新規） |
| A-3 | `data/entity/WalkingSession.kt`, `data/db/AppDatabase.kt`, `data/db/Migration1to2.kt`（新規） |
| A-4 | `data/dao/WalkingSessionDao.kt` |
| A-5 | `data/repository/WalkingRepository.kt` |
| B-1 | `di/FirebaseModule.kt`（新規） |
| B-2 | `data/repository/AuthRepository.kt`（新規） |
| B-3 | `ui/screen/home/HomeViewModel.kt` |
| B-4 | `ui/screen/home/HomeScreen.kt` |
| C-1 | `data/repository/FirestoreSyncRepository.kt`（新規） |
| C-2 | `service/LocationTrackingService.kt` |
| D-1 | `sync/NetworkMonitor.kt`（新規） |
| D-2 | `sync/SyncWorker.kt`（新規） |
| D-3 | `HealthcareApp.kt` |
| E-1 | `ui/screen/home/HomeViewModel.kt` |
| F-1 | `docs/firestore.rules`（新規） |
| F-2 | `proguard-rules.pro` |
