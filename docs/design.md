# 設計: 履歴画面で選択した項目の削除

## 対象
- UI: HistoryScreen
- 状態管理: HistoryViewModel, HistoryUiState
- データ層: WalkingRepository

## アーキテクチャ方針
既存の Compose + MVVM + Repository + Room 構成を維持し、削除操作は UI -> ViewModel -> Repository -> DAO の一方向データフローで実行する。

## UI 設計
### 一覧表示時のヘッダー
- 非選択モード
  - タイトル: ウォーキング履歴
  - 右アクション: 「選択」
- 選択モード
  - タイトル: 「n件選択中」
  - 右アクション: 「削除」(選択0件時は無効)
  - 左アクション: 「キャンセル」

### 項目カード
- 非選択モード: タップで詳細表示 (既存挙動)
- 選択モード: タップで選択トグル
- 選択モード中はチェックボックスを表示

### 削除確認ダイアログ
- トリガー: 選択件数 > 0 かつ削除アクション押下
- 文言: 選択件数を含む確認メッセージ
- ボタン: キャンセル / 削除

## 状態設計
HistoryUiState に以下を追加する:
- isSelectionMode: Boolean
- selectedSessionIds: Set<Long>
- showDeleteConfirmDialog: Boolean

派生値:
- selectedCount = selectedSessionIds.size

## ViewModel 設計
追加メソッド:
- enterSelectionMode()
- cancelSelectionMode()
- toggleSessionSelection(sessionId: Long)
- requestDeleteSelected()
- dismissDeleteDialog()
- confirmDeleteSelected()

削除処理:
- confirmDeleteSelected() で selectedSessionIds を取得
- repository.deleteSessionsByIds(ids) を呼ぶ
- 完了後に選択モードを終了しダイアログを閉じる

一覧同期時の整合:
- observeCompletedSessions の collect 時に、selectedSessionIds を現在存在する session id に限定して残す。

## Repository 設計
追加メソッド:
- suspend fun deleteSessionsByIds(ids: Collection<Long>)

実装方針:
- ids を走査して該当 session を取得し delete を実行
- WalkingPoint は外部キー CASCADE で連動削除

## 受け入れ条件との対応
- AC-1〜AC-4: 選択モード + トグル実装
- AC-5〜AC-7: 削除確認ダイアログ実装
- AC-8: キャンセル導線実装
- AC-9: 非選択モード既存タップ挙動を維持

## トレードオフ
- DAO に IN 句による一括削除を追加せず、既存 API を利用した逐次削除を採用する。
- 理由: 変更範囲を最小化し、既存 Room API と整合を保つため。
- 影響: 大量件数削除時の効率は最適ではないが、今回の機能要求は満たす。

---

# 設計: Firebase 認証とウォーキング履歴のクラウド同期

## アーキテクチャ概要（テキスト図）

```
┌──────────────────────────────────────────────────────────────┐
│                        UI Layer (Compose)                    │
│  HomeScreen              HistoryScreen    TrackingScreen     │
│  ├─ AuthUiSection        (既存 + 削除機能)  (既存)            │
│  └─ HomeViewModel        HistoryViewModel  TrackingViewModel │
│       ├─ AuthRepository                                      │
│       └─ WalkingRepository                                   │
└──────────────────────┬───────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────┐
│                     Repository Layer                         │
│  AuthRepository          WalkingRepository                   │
│  └─ FirebaseAuth         └─ WalkingSessionDao                │
│                          └─ WalkingPointDao                  │
│                                                              │
│  FirestoreSyncRepository                                     │
│  └─ FirebaseFirestore                                        │
└──────────────────────┬───────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────┐
│                      Data Layer                              │
│  Room DB (v2)                  Firestore                     │
│  ├─ walking_sessions           └─ users/{uid}/               │
│  │   (+ sessionUuid,                walking_sessions/        │
│  │    syncStatus,                   {sessionUuid}            │
│  │    firestoreDocId)                                        │
│  └─ walking_points                                           │
└──────────────────────────────────────────────────────────────┘
                       │
┌──────────────────────▼───────────────────────────────────────┐
│                  Background Sync Layer                       │
│  SyncWorker (WorkManager)     NetworkMonitor                 │
│  └─ NETWORK_CONNECTED 制約    └─ ConnectivityManager         │
│  └─ PENDING セッションを再送   callbackFlow                  │
└──────────────────────────────────────────────────────────────┘
```

## 各コンポーネントの責務

### AuthRepository
- `FirebaseAuth.currentUser` を `StateFlow<FirebaseUser?>` として保持
- `authStateChanges()` を `callbackFlow` で購読し State を更新
- Google Sign-In Intent の生成と `signInWithCredential` の実行
- サインアウト（`FirebaseAuth.signOut()` + `GoogleSignInClient.signOut()`）

### FirestoreSyncRepository
- Firestore への WalkingSession 書き込み（`users/{uid}/walking_sessions/{sessionUuid}`）
- Firestore からの WalkingSession 一覧取得（同一 UID）
- 取得したドキュメントを Room へマージ（`sessionUuid` による重複排除）
- 書き込み成否を `Result<Unit>` で返し、呼び出し元が syncStatus を更新

### WalkingRepository（変更分のみ）
- `updateSyncStatus(id: Long, status: SyncStatus, firestoreDocId: String?)` を追加
- `getPendingSessions(): List<WalkingSession>` を追加
- `upsertSession(session: WalkingSession)` をマージ用に追加（既存 insert + 衝突時 IGNORE を利用）

### SyncWorker
- WorkManager の `CoroutineWorker` として実装
- `PENDING` または `FAILED` のセッションを `WalkingRepository` から取得
- 各セッションを `FirestoreSyncRepository.uploadSession()` で送信
- 成功: `syncStatus = SYNCED`、失敗: `syncStatus = FAILED`
- WorkManager の `Constraints(requiredNetworkType = NetworkType.CONNECTED)` を使用

### NetworkMonitor
- `ConnectivityManager.NetworkCallback` を `callbackFlow` でラップ
- `isConnected: Flow<Boolean>` を提供
- `LocationTrackingService` または `SyncWorker` スケジューリングに利用

### HomeViewModel（変更分）
- `AuthRepository.authState` を購読し `HomeUiState` に `currentUser: FirebaseUser?` を追加
- `signInWithGoogle(activity: Activity)` イベントハンドラを追加
- `signOut()` イベントハンドラを追加
- サインイン成功後に `FirestoreSyncRepository.fetchAndMerge(uid)` を呼んで初回マージ

### LocationTrackingService（変更分）
- `FirestoreSyncRepository` を Hilt でインジェクト
- `stopTracking()` 完了後、サインイン済みかつオンラインなら即時 `uploadSession()` を呼ぶ
- 失敗またはオフラインなら `syncStatus = PENDING` のまま `SyncWorker` に委譲

---

## データモデル変更（Room Migration 1 → 2）

### WalkingSession 変更後

```kotlin
// data/entity/SyncStatus.kt (新規)
enum class SyncStatus { PENDING, SYNCED, FAILED }

// data/entity/WalkingSession.kt (変更)
@Entity(tableName = "walking_sessions")
data class WalkingSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionUuid: String = "",          // 追加: Firestore ドキュメント ID 兼 UUID
    val startTime: Long,
    val endTime: Long? = null,
    val totalDistanceMeters: Double = 0.0,
    val totalCalories: Double = 0.0,
    val isActive: Boolean = true,
    val syncStatus: SyncStatus = SyncStatus.PENDING,  // 追加
    val firestoreDocId: String? = null                // 追加: 同期済み時に sessionUuid と一致
)
```

`WalkingPoint.sessionId: Long` は変更しない（Room 内部 FK として `WalkingSession.id` を引き続き参照）。

### 方針: id は Long のまま維持
- `id: Long (autoGenerate)` を Room 内部 PK として維持する。外部キー関係（`WalkingPoint.sessionId`）の変更を避けるため。
- `sessionUuid: String` を別フィールドとして追加し、Firestore ドキュメント ID・端末間重複排除キーとして使用する。
- 新規セッション作成時は `UUID.randomUUID().toString()` を `WalkingRepository.startNewSession()` 内で生成して設定する。

### Migration SQL（AppDatabase v1 → v2）

```sql
-- MIGRATION_1_2
ALTER TABLE walking_sessions ADD COLUMN sessionUuid TEXT NOT NULL DEFAULT '';
ALTER TABLE walking_sessions ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'PENDING';
ALTER TABLE walking_sessions ADD COLUMN firestoreDocId TEXT;

-- 既存行に UUID を付与（SQLite randomblob を利用した UUID v4 近似形式）
UPDATE walking_sessions SET sessionUuid =
    lower(hex(randomblob(4))) || '-' ||
    lower(hex(randomblob(2))) || '-4' ||
    substr(lower(hex(randomblob(2))), 2) || '-' ||
    substr('89ab', abs(random() % 4) + 1, 1) ||
    substr(lower(hex(randomblob(2))), 2) || '-' ||
    lower(hex(randomblob(6)));
```

`AppDatabase` の `version = 2`、`addMigrations(MIGRATION_1_2)` を追加。

---

## Firestore データ構造

```
users/
  {uid}/
    walking_sessions/
      {sessionUuid}/
        sessionId      : String   (= sessionUuid、クライアント生成 UUID)
        startTime      : Long     (epoch ミリ秒)
        endTime        : Long     (epoch ミリ秒、未終了時は null)
        distanceMeters : Double
        caloriesBurned : Double
        geoPoints      : Array<GeoPoint>  (lat/lng ペア配列)
        syncedAt       : Timestamp (サーバー書き込み時刻)
```

### GeoPoint 配列のサイズ制限対策
- Firestore ドキュメント上限は 1 MiB。
- `WalkingPoint` 1 件を GeoPoint 約 50 byte とすると、上限は約 20,000 件。
- 実装では書き込み前に点数をチェックし、20,000 点超の場合は均等間引き（stride サンプリング）を行う。

---

## データフロー

### セッション完了時（オンライン・サインイン済み）
```
TrackingScreen
  → LocationTrackingService.stopTracking()
      → WalkingRepository.endSession(id, dist, cal)   // syncStatus = PENDING
      → FirestoreSyncRepository.uploadSession(session) // Firestore 書き込み
          → 成功: WalkingRepository.updateSyncStatus(id, SYNCED, docId)
          → 失敗: WalkingRepository.updateSyncStatus(id, FAILED, null)
                  SyncWorker をスケジュール
```

### セッション完了時（オフラインまたは未サインイン）
```
LocationTrackingService.stopTracking()
  → WalkingRepository.endSession(id, dist, cal)   // syncStatus = PENDING
  // Firestore 書き込みなし
  // NetworkMonitor がオンライン復帰を検知 → SyncWorker 起動
```

### ネットワーク復帰時の自動再同期
```
NetworkMonitor.isConnected (Flow<Boolean>)
  → true に変化
      → WorkManager.enqueueUniqueWork("sync", SyncWorker)
          → WalkingRepository.getPendingSessions()
          → 各セッションを FirestoreSyncRepository.uploadSession()
```

### 別端末でのサインイン後マージ
```
HomeViewModel.signInWithGoogle()
  → AuthRepository.signIn()
      → 成功: FirestoreSyncRepository.fetchAndMerge(uid)
          → Firestore から全セッション取得
          → sessionUuid で重複チェック
          → 未存在のセッションのみ WalkingRepository.upsertSession() で挿入
          → syncStatus = SYNCED で保存
```

---

## 認証状態管理

```
AuthRepository
  └─ authState: Flow<FirebaseUser?> (FirebaseAuth.addAuthStateListener を callbackFlow 化)
  └─ currentUser: FirebaseUser? (同期アクセス用)
  └─ suspend signInWithGoogle(idToken: String): Result<FirebaseUser>
  └─ suspend signOut()

HomeViewModel
  └─ HomeUiState.currentUser: FirebaseUser?   // 追加
  └─ HomeUiState.isSigningIn: Boolean          // 追加（ローディング表示用）
  └─ HomeUiState.authError: String?            // 追加（エラーメッセージ用）
  └─ fun onSignInResult(result: ActivityResult) // Google Sign-In 結果を処理
  └─ fun signOut()
```

`AuthRepository` は `SingletonComponent` に `@Singleton` でバインドする（`FirebaseModule`）。

---

## UI 設計（HomeScreen 変更分）

### 追加: AuthUiSection（HomeScreen 上部に配置）

```
┌──────────────────────────────────────────┐
│ [未サインイン時]                           │
│  Google でサインイン  [Sign-In Button]     │
├──────────────────────────────────────────┤
│ [サインイン済み]                           │
│  ○ avatar  user@gmail.com  [サインアウト]  │
└──────────────────────────────────────────┘
```

- サインインボタン押下 → `rememberLauncherForActivityResult` で Google Sign-In Intent を起動
- サインアウトは確認ダイアログなしで即時実行（ローカルデータは削除しない）
- サインイン中はプログレスインジケーター表示

---

## ネットワーク復帰検知の設計方針

**採用: WorkManager（制約付き）+ NetworkMonitor（補助）**

- `SyncWorker`: `CoroutineWorker`、`Constraints(NetworkType.CONNECTED)` で自動保留・実行再開
- `NetworkMonitor`: `callbackFlow` + `ConnectivityManager.registerNetworkCallback` — アプリ前面時の即時検知用（`LocationTrackingService` でのセッション終了後同期）
- WorkManager は `KEEP` 重複排除ポリシーで `enqueueUniqueWork`（多重起動防止）

**不採用: ConnectivityManager 単体**
- 理由: アプリ終了・プロセスキル後の再同期を保証できない。WorkManager はシステム再起動後も実行を保証するため採用。

---

## Firestore Security Rules（草案）

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    // ユーザー自身のデータのみ読み書き可能
    match /users/{userId}/walking_sessions/{sessionId} {
      allow read, write: if request.auth != null
                         && request.auth.uid == userId;
    }

    // 上記以外はすべて拒否
    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```

---

## 依存ライブラリ追加計画

| ライブラリ | バージョン | 用途 |
|---|---|---|
| `firebase-bom` | 33.x | Firebase BOM |
| `firebase-auth-ktx` | BOM 管理 | Firebase Authentication |
| `firebase-firestore-ktx` | BOM 管理 | Firestore |
| `play-services-auth` | 21.x | Google Sign-In |
| `work-runtime-ktx` | 2.9.x | WorkManager |
| Google Services Plugin | 4.4.x | google-services.json 処理 |
| Firebase Android Plugin | 4.3.x | `com.google.firebase:perf` 等（任意） |

`gradle/libs.versions.toml` と `app/build.gradle.kts` の両方を更新する。

---

## トレードオフ

| 決定 | 採用案 | 不採用案 | 理由 |
|---|---|---|---|
| sessionId の扱い | `id: Long` 維持 + `sessionUuid: String` 追加 | `id` を UUID 文字列に変更 | WalkingPoint の FK 変更を回避し移行リスクを最小化 |
| Sync 責務 | `FirestoreSyncRepository` を分離 | `WalkingRepository` に統合 | 単一責任原則、テスト容易性 |
| ネットワーク監視 | WorkManager + NetworkMonitor | ConnectivityManager 単体 | バックグラウンド再同期の信頼性 |
| Google Sign-In 実装 | `GoogleSignIn` API（legacy） | Credential Manager API | Min SDK 26 との互換性（Credential Manager は API 34 以降推奨） |
| Firestore リスナー | ポーリング/明示的同期のみ | リアルタイムリスナー | スコープ外（spec 定義通り）、バッテリー消費抑制 |
