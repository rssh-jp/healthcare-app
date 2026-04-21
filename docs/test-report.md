# テスト報告

## 対象
- 履歴画面の選択削除機能
- 受け入れ条件: docs/acceptance-criteria.md

## 実施コマンド
- make build
  - 結果: 成功
  - 補足: BUILD SUCCESSFUL, app-debug.apk 生成

## 受け入れ条件ベース結果
- AC-1 選択モード開始: Pass (UI実装を確認)
- AC-2 項目選択と件数表示: Pass (選択トグルと件数表示実装を確認)
- AC-3 複数選択: Pass (Set<Long> による複数選択管理を確認)
- AC-4 選択解除: Pass (再タップでトグル解除)
- AC-5 削除確認: Pass (AlertDialog 表示)
- AC-6 削除確定: Pass (confirmDeleteSelected -> repository.deleteSessionsByIds)
- AC-7 削除キャンセル: Pass (dismissDeleteDialog で状態維持)
- AC-8 選択モードキャンセル: Pass (cancelSelectionMode で選択状態クリア)
- AC-9 非選択モード従来挙動: Pass (非選択時 onClick は selectSession)

## 重要シナリオ
- 複数選択して削除: Pass
- 削除ダイアログをキャンセル: Pass
- 削除後の選択状態解放: Pass

## 未実施/制約
- 実機またはエミュレータでの手動 UI 操作検証は未実施。
- 自動 UI テストは未整備。

## 不具合
- なし

---

# テストレポート: Firebase 認証とクラウド同期機能

- **テスト実施日**: 2026-04-20
- **テスト担当**: Tester Agent
- **対象**: Firebase Authentication + Firestore 同期実装
- **参照ドキュメント**: `docs/acceptance-criteria.md`, `docs/review-report.md`, `docs/specification.md`

---

## テスト結果サマリー

| カテゴリ | PASS | FAIL | 備考 |
|---|---|---|---|
| AC-AUTH（認証） | 6 | 0 | 静的検証。実機検証は `google-services.json` 差し替え必要 |
| AC-SYNC（同期） | 4 | 0 | 静的検証 |
| AC-MULTI（マルチデバイス） | 3 | 0 | 静的検証 |
| AC-OFFLINE（オフライン） | 3 | 0 | 静的検証 |
| Room マイグレーション | 2 | 0 | 静的検証 |
| Firestore Security Rules | 1 | 0 | 静的検証（SHOULD-4 は別途記載） |
| **ビルド検証** | 0 | **1** | コンパイルエラー（重複定義） |

**総合判定: FAIL**（ビルドが通らないため実機検証不可）

---

## 1. ビルド検証

### 実行コマンド
```
.\gradlew.bat assembleDebug
```

### 結果: **FAIL**

**エラー箇所**: `app/src/main/java/com/healthcare/app/ui/screen/home/HomeScreen.kt`

**エラー内容**:
```
e: HomeScreen.kt:45:1  Conflicting overloads: fun HomeScreen(...)
e: HomeScreen.kt:335:1 Conflicting overloads: fun HomeScreen(...)
e: HomeScreen.kt:262:1 Conflicting overloads: fun StatCard(...)
e: HomeScreen.kt:429:1 Conflicting overloads: fun StatCard(...)
e: HomeScreen.kt:296:1 Conflicting overloads: fun SessionCard(...)
e: HomeScreen.kt:463:1 Conflicting overloads: fun SessionCard(...)
e: AppNavigation.kt:79:34 Overload resolution ambiguity between candidates: fun HomeScreen(...)
```

**原因**: Firebase 認証 UI 追加時に旧実装を削除せずコードが追記され、以下の Composable 関数が同一ファイル内に重複定義された。

| 関数 | 旧実装（削除すべき行） | 新実装（Auth 対応版・正しい） |
|---|---|---|
| `HomeScreen` | 335〜428 | 45〜173 |
| `StatCard` | 429〜462 | 262〜295 |
| `SessionCard` | 463〜499 | 296〜334 |

**修正方針**: lines 335〜499（旧実装）を削除する。

**優先度**: **CRITICAL** — ビルドブロッカー。本バグが解消されるまで全テストは実機検証不可。

---

## 2. 静的検証結果（コードレビュー）

### AC-AUTH: Firebase Authentication

#### AC-AUTH-1 Google サインイン — 正常系: **PASS**
- `AuthRepository.signInWithGoogleIdToken()` 実装済み（`GoogleAuthProvider.getCredential` + `signInWithCredential`）
- `HomeScreen.kt` に `AuthSection` コンポーザブル存在確認（line 63, 175）
- サインイン成功後 `authState` Flow 経由で `HomeUiState.currentUser` に反映

#### AC-AUTH-2 Google サインイン — キャンセル: **PASS**
- `HomeViewModel.onSignInResult` にて `ApiException` を catch し `authError` に格納
- アプリはクラッシュせず未サインイン状態のまま継続可能

#### AC-AUTH-3 Google サインイン — ネットワークエラー: **PASS**
- `signInWithGoogleIdToken` 内で全例外を catch し `Result.failure(e)` で返却
- `onFailure` で `authError` を設定

#### AC-AUTH-4 セッション自動復元: **PASS**
- `authState` が `callbackFlow` + `FirebaseAuth.AuthStateListener` で実装
- `HomeViewModel.init` で `authState.collect` し `currentUser` に自動反映

#### AC-AUTH-5 サインアウト: **PASS**
- `AuthRepository.signOut()` が `firebaseAuth.signOut()` + `googleSignInClient.signOut()` を実行
- ローカル Room データの削除なし（仕様通り）

#### AC-AUTH-6 未サインイン時の基本機能利用: **PASS**
- `trySyncSession` は `authRepository.currentUser ?: return` で早期リターン
- 未サインイン時はローカル保存のみ実施、クラッシュなし

---

### AC-SYNC: Firestore クラウド同期

#### AC-SYNC-1 セッション完了時のクラウド同期: **PASS**
- `LocationTrackingService.trySyncSession` がセッション終了時に呼ばれ `uploadSession` を実行
- 成功時に `syncStatus = SYNCED` へ更新

#### AC-SYNC-2 同期失敗時のローカル保存の独立性: **PASS**
- セッションの Room 保存と Firestore 同期は独立した処理
- 同期失敗時は `syncStatus = FAILED`、ローカル保存は成功維持

#### AC-SYNC-3 未同期データの再同期: **PASS**
- `SyncWorker.doWork` が `getPendingOrFailedSessions` を呼び出し PENDING/FAILED セッションを再同期
- `PeriodicWorkRequest`（15分周期）と `OneTimeWorkRequest`（失敗時即時）の両方で対応

#### AC-SYNC-4 データ構造の正確性: **PASS**
- `sessionId`, `startTime`, `endTime`, `distanceMeters`, `caloriesBurned`, `geoPoints`（配列）, `syncedAt` 全フィールドが設定される

---

### AC-MULTI: 複数端末間の履歴共有

#### AC-MULTI-1 別端末からの履歴取得: **PASS**
- `FirestoreSyncRepository.fetchAndMerge` が `users/{uid}/walking_sessions` コレクションを取得
- サインイン成功後 `HomeViewModel.onSignInResult` から自動呼び出し

#### AC-MULTI-2 重複マージ防止: **PASS**
- `WalkingRepository.upsertSessionFromRemote` が `getByUuid(session.sessionUuid)` で重複チェック
- 既存行があれば挿入せず

#### AC-MULTI-3 他ユーザーデータへのアクセス不可: **PASS**
- `firestore.rules` に `request.auth.uid == userId` を必須条件として設定
- デフォルト拒否ルール（`match /{document=**} { allow read, write: if false }`）確認済み

---

### AC-OFFLINE: オフラインファースト

#### AC-OFFLINE-1 オフライン時のローカル保存: **PASS**
- `WalkingSession` のデフォルト `syncStatus = SyncStatus.PENDING`
- `LocationTrackingService.trySyncSession` はオフライン時に早期リターン
- セッションは Room に保存され履歴一覧に表示

#### AC-OFFLINE-2 オンライン復帰時の自動同期: **PASS**
- `SyncWorker` に `NetworkType.CONNECTED` 制約を設定
- `HealthcareApp.onCreate` で `enqueueUniquePeriodicWork` 登録済み

#### AC-OFFLINE-3 オフライン中の履歴閲覧: **PASS**
- `observeCompletedSessions` / `getSessionsByDateRange` は Room の Flow から取得
- Firestore 接続状態に依存せず表示可能

---

### Room マイグレーション

#### Migration1to2: **PASS**
- `sessionUuid TEXT NOT NULL DEFAULT ''` 追加 ✓
- `syncStatus TEXT NOT NULL DEFAULT 'PENDING'` 追加 ✓
- `firestoreDocId TEXT`（nullable）追加 ✓
- 既存行への UUID v4 近似値付与（SQLite `randomblob` 使用）✓

#### AppDatabase バージョン確認: **PASS**
- `@Database(version = 2)` ✓
- `DatabaseModule.kt` で `addMigrations(MIGRATION_1_2)` ✓

---

### Firestore Security Rules

#### Rules 検証: **PASS（条件付き）**
- `request.auth != null && request.auth.uid == userId` による認証・スコープ制限 ✓
- デフォルト拒否ルール確認済み ✓
- **注意**: `write` に `delete` が含まれる（SHOULD-4 未修正）

---

## 3. 発見したバグ・問題点

### BUG-001（CRITICAL）: `HomeScreen.kt` 関数の重複定義
- **ファイル**: `app/src/main/java/com/healthcare/app/ui/screen/home/HomeScreen.kt`
- **内容**: `HomeScreen`（lines 45, 335）、`StatCard`（lines 262, 429）、`SessionCard`（lines 296, 463）が二重定義
- **影響**: ビルド不可（コンパイルエラー）
- **原因**: Firebase 認証 UI 実装追加時に旧実装を削除せずファイルを編集
- **修正方法**: lines 335〜499（旧 Auth 非対応版 `HomeScreen` および重複 `StatCard`・`SessionCard`）を削除
- **優先度**: P0（リリースブロッカー）

### SHOULD-1〜7（未修正）
レビューレポート（`docs/review-report.md`）の SHOULD 指摘事項のうち以下が未修正：
- SHOULD-1: `SyncStatusConverter.toSyncStatus` のクラッシュリスク
- SHOULD-2: `fetchAndMerge` の uid 引数セキュリティリスク
- SHOULD-4: Firestore Security Rules が `delete` 操作を許可
- SHOULD-7: `NetworkMonitor` が `NET_CAPABILITY_VALIDATED` を未使用

### NOTE-001: `google-services.json` がプレースホルダー値
- 実機での Google サインイン・Firestore 接続は不可
- 実際の Firebase プロジェクトの `google-services.json` への差し替えが必要
- `app/google-services.json.example` が参照用として存在することを確認済み

---

## 4. 優先度別課題一覧

| ID | 種別 | 内容 | 再現条件 | 優先度 | 状態 |
|---|---|---|---|---|---|
| BUG-001 | バグ | `HomeScreen.kt` 重複定義によるコンパイルエラー | `.\gradlew.bat assembleDebug` 実行 | P0 | ✅ 修正済み |
| BUG-RACE-001 | バグ | `endSession` / `updateSessionStats` 競合状態（履歴が保存されない根本原因） | `stopTracking()` 後に位置更新コールバックが遅延到着 | P0 | ✅ 修正済み |
| NOTE-001 | 環境 | `google-services.json` がプレースホルダー | 実機起動・Google サインイン | P0（環境依存） | 未対応（実機作業） |
| SHOULD-1 | 品質 | `SyncStatusConverter` クラッシュリスク | DB に不正な syncStatus 値が入った場合 | P2 | ✅ 修正済み |
| SHOULD-2 | セキュリティ | `fetchAndMerge` の uid 引数 | 悪意ある呼び出し元が別 uid を渡した場合 | P2 | 未対応 |
| SHOULD-4 | セキュリティ | Security Rules が delete 許可 | クライアントから Firestore ドキュメント削除試行 | P2 | ✅ 修正済み（前回セッション） |
| SHOULD-6 | 品質 | `SyncWorker` リトライ上限なし | 長期ネットワーク障害 | P2 | ✅ 修正済み（runAttemptCount < 5） |
| SHOULD-7 | 品質 | NetworkMonitor が VALIDATED 未チェック | キャプティブポータル環境での同期 | P3 | ✅ 修正済み |

---

---

# テストレポート: 履歴保存バグ修正 (トラブルシュート)

- **テスト実施日**: 2026-04-21
- **テスト担当**: Copilot Agent
- **対象**: ウォーキング履歴が保存されない問題の調査・修正
- **参照ドキュメント**: `docs/quality-gate.md`, `docs/review-report.md`

---

## テスト結果サマリー

| カテゴリ | PASS | FAIL | 備考 |
|---|---|---|---|
| ビルド検証 | 1 | 0 | BUILD SUCCESSFUL 確認済み |
| BUG-RACE-001 競合状態修正 | 1 | 0 | 静的解析で検証 |
| SHOULD-1 SyncStatusConverter | 1 | 0 | 静的解析で検証 |
| SHOULD-6 SyncWorker リトライ | 1 | 0 | 静的解析で検証 |
| SHOULD-7 NetworkMonitor | 1 | 0 | 静的解析で検証 |

**総合判定: PASS（静的検証）**

---

## 1. ビルド検証

### 実行コマンド
```
./gradlew assembleDebug -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64
```

### 結果: **PASS**
- BUILD SUCCESSFUL in 3m 28s
- `app-debug.apk` 生成確認

---

## 2. BUG-RACE-001: endSession / updateSessionStats 競合状態

### 不具合内容
`LocationTrackingService.stopTracking()` が `serviceScope.launch(Dispatchers.IO)` で `endSession` を呼ぶと同時に、`setupLocationCallback` の最終コールバック（`Looper.getMainLooper()`）が遅延して到着し `updateSessionStats` コルーチンが IO スレッドで並走する。

両者が `getById → copy → update` の read-modify-write パターンを使うため、`updateSessionStats` が古い読み取り値で `endSession` の書き込みを上書きし `isActive=true / endTime=null` に戻る。結果として `observeCompletedSessions`（`WHERE isActive=0`）にセッションが現れず履歴に表示されない。

### 修正内容
`WalkingSessionDao` にターゲット UPDATE クエリを追加:
- `completeSession(id, endTime, distance, calories)` — `isActive=0`, `endTime` を1クエリで原子的に更新
- `updateStats(id, distance, calories)` — `isActive` / `endTime` を一切変更しない

`WalkingRepository.endSession` / `updateSessionStats` を read-modify-write から上記クエリ呼び出しに変更。各フィールドが独立して UPDATE されるため競合しない。

### 検証結果: **PASS（静的解析）**
- `WalkingSessionDao.completeSession` / `updateStats` に `@Query` アノテーションが存在することを確認
- `WalkingRepository.endSession` / `updateSessionStats` が read-modify-write パターンを使用していないことを確認
- ビルド成功（Room コンパイル時クエリ検証をパス）

---

## 3. SHOULD-1: SyncStatusConverter クラッシュリスク修正

### 不具合内容
`SyncStatus.valueOf(value)` は DB に不正値が存在する場合 `IllegalArgumentException` を throw し、Room の TypeConverter がクラッシュする。

### 修正内容
`runCatching { SyncStatus.valueOf(value) }.getOrDefault(SyncStatus.PENDING)` に変更。不正値は `PENDING` として扱い、クラッシュを防止。

### 検証結果: **PASS**

---

## 4. SHOULD-6: SyncWorker リトライ上限追加

### 不具合内容
`Result.retry()` に上限がなく、長期ネットワーク障害時にバックオフ付きでも無限リトライし続けバッテリーと通信量を消費する。

### 修正内容
`return if (hasFailure && runAttemptCount < 5) Result.retry() else Result.success()`  
5回リトライ後は `FAILED` 状態のまま次の `PeriodicWorkRequest` サイクルを待つ。

### 検証結果: **PASS**

---

## 5. SHOULD-7: NetworkMonitor キャプティブポータル対応

### 不具合内容
`NET_CAPABILITY_VALIDATED` を確認しないため、キャプティブポータル（公衆 Wi-Fi のログインページ）に接続している状態で `isConnected=true` となり同期を試みて失敗し続ける。

### 修正内容
`NetworkRequest.Builder` と `isConnectedNow()` の両方に `NET_CAPABILITY_VALIDATED` を追加。実際にインターネット到達性が確認済みのネットワークのみ接続済みと判定する。

### 検証結果: **PASS**

---

## 6. 未実施項目

- 実機での E2E 検証（ウォーキング開始→終了→履歴表示フロー）
- 競合状態の再現テスト（位置更新コールバックを人為的に遅延させたシナリオ）

---

## 5. 実機検証が必要な項目（BUG-001 解消後）

- AC-AUTH-1〜6（Google アカウント選択 UI の動作確認）
- AC-SYNC-1〜4（Firestore への実際の書き込み・読み込み確認）
- AC-MULTI-1〜3（複数端末での動作確認）
- AC-OFFLINE-1〜3（ネットワーク遮断・復帰での動作確認）

---

---

# テストレポート: Firebase 転送バグ修正 (トラブルシュート Phase 2)

- **テスト実施日**: 2026-04-21
- **テスト担当**: Copilot Agent
- **対象**: Firebase へセッションが転送されない問題の調査・修正
- **コミット**: `c560478`

---

## テスト結果サマリー

| カテゴリ | PASS | FAIL | 備考 |
|---|---|---|---|
| ビルド検証 | 1 | 0 | BUILD SUCCESSFUL (3m 9s) |
| BUG-PERIODIC-001 periodic sync 削除 | 1 | 0 | 静的解析で検証 |
| BUG-NONET-001 no-network リトライ欠落 | 1 | 0 | 静的解析で検証 |
| BUG-SILENT-001 無音エラー | 1 | 0 | 静的解析で検証 |

**総合判定: PASS（静的検証）**

---

## 1. ビルド検証

```
./gradlew assembleDebug -Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64
```

**結果: PASS** — BUILD SUCCESSFUL in 3m 9s

---

## 2. BUG-PERIODIC-001: periodic sync が削除されていた

### 不具合内容
`SyncWorker.kt` から `PERIODIC_WORK_NAME` と `buildPeriodicRequest()` が削除され、`HealthcareApp.kt` も `enqueueUniquePeriodicWork` を使わない実装に変更されていた。これにより one-time sync（起動時・onStop）が失敗した場合のフォールバックが完全に失われ、同期漏れが残存し続ける。

### 修正内容
- `SyncWorker.kt`: `PERIODIC_WORK_NAME = "periodic_sync"` と `buildPeriodicRequest()`（15分周期）を復元
- `HealthcareApp.kt`: `schedulePeriodicSync()` を追加し `enqueueUniquePeriodicWork(KEEP)` を呼ぶように修正

### 検証結果: **PASS（静的解析）**
- `SyncWorker.PERIODIC_WORK_NAME` と `buildPeriodicRequest()` の存在を確認
- `HealthcareApp.onCreate` から `schedulePeriodicSync()` が呼ばれることを確認
- ビルド成功

---

## 3. BUG-NONET-001: no-network 時に SyncWorker がスケジュールされない

### 不具合内容
`LocationTrackingService.trySyncSession` でネットワーク不在（`isConnectedNow()=false`）の場合、即座に `return` するが `scheduleSyncWorker()` を呼ばないため SyncWorker がエンキューされない。これにより、セッション終了時にオフラインだった場合、次の periodic sync まで（最大 15 分）転送が試みられない。また `NET_CAPABILITY_VALIDATED` の追加によりネットワーク判定が厳しくなり false negative が増加していた。

### 修正内容
`trySyncSession` の no-network 早期 return パスに `scheduleSyncWorker()` を追加。ネットワークが戻り次第 WorkManager が自動実行する。

```kotlin
if (!networkMonitor.isConnectedNow()) {
    Log.d(TAG, "trySyncSession: no network, scheduling SyncWorker for later")
    scheduleSyncWorker()
    return
}
```

### 検証結果: **PASS（静的解析）**
- no-network 早期 return 前に `scheduleSyncWorker()` が呼ばれることを確認

---

## 4. BUG-SILENT-001: エラーがサイレントで Logcat に出力されない

### 不具合内容
`FirestoreSyncRepository.uploadSession` と `syncOnLogin` の全 `catch` ブロックが `Result.failure(e)` を返すのみで `Log.w` が一切なかった。Firestore の権限エラー・ネットワークエラー・認証エラーなどの根本原因が Logcat で確認不可能だった。

### 修正内容
- `FirestoreSyncRepository`: `uploadSession` 成功時に `Log.d`、失敗時に `Log.w(TAG, ..., e)` を追加
- `FirestoreSyncRepository.syncOnLogin`: 個別アップロード失敗時に `Log.w` を追加
- `SyncWorker.doWork`: 各セッションの成功/失敗と retry 判定を `Log.d/w` で出力
- `LocationTrackingService.trySyncSession`: 認証なし / no-network / 成功 / 失敗を全パスで `Log.d/w` 出力

### 検証結果: **PASS（静的解析）**

---

## 5. 実機検証手順（次ステップ）

1. apk を実機インストール後、Logcat で以下のタグをフィルタ:
   - `SyncWorker`, `FirestoreSyncRepository`, `LocationTrackingService`
2. セッション完了後に `uploadSession: success uuid=...` が出ることを確認
3. エラーが出た場合は `Log.w` の例外クラス（例: `FirebaseFirestoreException`）を確認
4. ログが全く出ない場合は Firebase Console の **Authentication** でサインイン状態を確認

---

## 6. 優先度別課題一覧（更新）

| ID | 種別 | 内容 | 優先度 | 状態 |
|---|---|---|---|---|
| BUG-RACE-001 | バグ | endSession / updateSessionStats 競合状態 | P0 | ✅ 修正済み (コミット `0a2fafa`) |
| BUG-PERIODIC-001 | バグ | periodic sync が削除されていた | P0 | ✅ 修正済み (コミット `c560478`) |
| BUG-NONET-001 | バグ | no-network 時に SyncWorker がスケジュールされない | P1 | ✅ 修正済み (コミット `c560478`) |
| BUG-SILENT-001 | 品質 | Firestore エラーが無音 | P1 | ✅ 修正済み (コミット `c560478`) |
| NOTE-001 | 環境 | `google-services.json` がプレースホルダー | P0 | 未対応（実機作業） |
| SHOULD-2 | セキュリティ | `fetchAndMerge` の uid 引数 | P2 | 未対応 |
| SHOULD-5 | 品質 | Firestore 全件一括取得（ページネーションなし） | P2 | 未対応 |
