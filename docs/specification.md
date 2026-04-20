# 仕様: 履歴画面で選択した項目の削除

## 背景
履歴画面に保存済みウォーキングセッションが蓄積されるが、不要な記録をユーザー自身で整理できない。

## 目的
履歴一覧でユーザーが選択したセッションを削除できるようにし、記録管理性を向上させる。

## 機能要件
- 履歴一覧画面に選択モードを追加する。
- 選択モード中、各履歴項目を選択/解除できる。
- 選択した件数を画面上で確認できる。
- 選択中に削除アクションを実行できる。
- 削除実行時に確認ダイアログを表示し、ユーザーが確定した場合のみ削除する。
- 削除後は履歴一覧から対象項目が消える。
- 選択モードをキャンセルできる。

## 非機能要件
- 既存アーキテクチャ (Compose + MVVM + Room) に準拠する。
- UI 応答を阻害しないよう、削除処理は ViewModel 経由で非同期に実行する。
- 既存の履歴詳細表示機能と共存し、選択モード外では従来通りタップで詳細表示する。

## スコープ
### In Scope
- 履歴一覧画面の選択 UI と削除導線の追加
- ViewModel の選択状態管理と削除処理追加
- Repository への削除メソッド追加

### Out of Scope
- 削除した履歴の復元 (Undo)
- 一括全件削除
- 履歴以外のデータ構造変更

## 制約
- Room の既存テーブル構造を維持する。
- 既存の画面遷移構造を維持する。

## 前提
- `walking_points` は `walking_sessions` に外部キー制約 (onDelete = CASCADE) を持つため、セッション削除時に関連ポイントも削除される。

## リスク
- 選択モードと詳細表示のタップ挙動が競合すると誤操作につながる。
- 複数削除時の確認文言が不十分だとユーザー意図と異なる削除を招く。

---

# 仕様: Firebase 認証とウォーキング履歴のクラウド同期

## 背景
現状のウォーキング履歴は Room（ローカル DB）のみに保存されるため、端末紛失・機種変更時にデータが失われ、複数端末での共有もできない。

## 目的
Firebase Authentication による Google アカウントサインインと Firebase Firestore へのクラウド同期を導入し、複数端末間での履歴共有とデータ永続性を実現する。

## 機能要件

### FR-AUTH: Firebase Authentication
- FR-AUTH-1: ユーザーは Google アカウントでサインインできる。
- FR-AUTH-2: サインイン済みの場合、アプリ起動時に自動でセッションを復元する。
- FR-AUTH-3: ユーザーはサインアウトできる。
- FR-AUTH-4: 未サインイン状態でもアプリの基本機能（ウォーキング追跡・ローカル履歴閲覧）を利用できる。
- FR-AUTH-5: サインイン状態は UI（例: アカウントアイコン・名前）で確認できる。

### FR-SYNC: Firestore クラウド同期
- FR-SYNC-1: サインイン済みの場合、ウォーキングセッション完了時にセッションデータを Firestore へ書き込む。
- FR-SYNC-2: Firestore への同期は Room への保存と独立して行い、同期失敗がローカル保存に影響しない。
- FR-SYNC-3: Firestore のドキュメント構造は `users/{uid}/walking_sessions/{sessionId}` とする。
- FR-SYNC-4: 各セッションには `syncStatus`（`SYNCED` / `PENDING` / `FAILED`）フィールドを Room に持つ。
- FR-SYNC-5: `PENDING` または `FAILED` 状態のセッションは、ネットワーク復帰時に再同期を試みる。

### FR-MULTI: 複数端末間の履歴共有
- FR-MULTI-1: 同一 Google アカウントでサインインした別端末から、Firestore に保存済みのセッションを取得できる。
- FR-MULTI-2: Firestore から取得したセッションをローカル Room DB にマージする（重複なし）。
- FR-MULTI-3: マージは `sessionId`（UUID）をキーとして重複排除する。

### FR-OFFLINE: オフラインファースト
- FR-OFFLINE-1: オフライン時はセッション完了後、Room のみに保存し `syncStatus = PENDING` を設定する。
- FR-OFFLINE-2: ネットワーク復帰を検知したら `PENDING` セッションを自動的に Firestore へ同期する。
- FR-OFFLINE-3: 同期中および未同期のセッションは履歴一覧に通常表示し、同期状態をオプションで視覚表示してもよい。

## 非機能要件
- NFR-1: 同期処理は UI スレッドをブロックしない（バックグラウンド Coroutine / WorkManager を使用）。
- NFR-2: Firestore への書き込み・読み込みは認証済みユーザーの UID でスコープを絞り、他ユーザーのデータへはアクセスしない（Firestore Security Rules で強制）。
- NFR-3: Google サインイン失敗時はエラーメッセージを表示し、未サインイン状態のまま継続できる。
- NFR-4: Room の既存テーブル構造への変更は最小限とし、マイグレーションを提供する。
- NFR-5: Min SDK 26（Android 8.0）以上を対象とする。Firebase SDK の最低動作要件を満たすこと。
- NFR-6: Firestore 書き込みは 1 セッションあたり 1 ドキュメント単位とし、ウォーキングポイント列は `geoPoints` 配列フィールドとして埋め込む（サブコレクション不使用）。

## スコープ

### In Scope
- Firebase Authentication（Google プロバイダのみ）
- Firestore へのウォーキングセッション同期（書き込み・読み込み・マージ）
- オフライン時のローカル保存とネットワーク復帰時の自動同期
- `walking_sessions` テーブルへの `syncStatus` / `firestoreId` カラム追加とマイグレーション
- サインイン/サインアウト UI（既存 UI への最小限の追加）
- Firestore Security Rules の定義

### Out of Scope
- Apple / メール等の Google 以外の認証プロバイダ
- Firestore リアルタイムリスナーによる即時反映（ポーリング / 明示的同期のみ）
- ウォーキングポイント（GPS 座標）以外のデータ（統計集計値等）のクラウド同期
- サーバーサイドのビジネスロジック（Cloud Functions）
- プッシュ通知
- Firestore オフラインキャッシュ（Firebase SDK 組み込み機能）の明示的活用

## 制約
- Google Sign-In は `firebase-ui-auth` または `Credential Manager API`（Android 14+）を使用する。Min SDK 26 との互換性を考慮し、`Credential Manager` と `GoogleSignIn` の併用または `firebase-ui-auth` を採用する。
- Firestore ドキュメントサイズ上限は 1 MiB。ウォーキングポイント数が多い場合（目安: 10,000 点超）はポイント間引き処理を検討する（詳細設計で判断）。
- `google-services.json` を `app/` 直下に配置し、`local.properties` には Firebase 設定値を含めない。
- 既存の Room DB バージョンを更新し、マイグレーションスクリプトを提供する（破壊的マイグレーション不使用）。

## 前提
- Firebase プロジェクトが作成済みで、Android アプリが登録されていること（`google-services.json` 取得済み）。
- Firestore データベースが作成済みであること。
- `walking_sessions` の `id` は UUID 文字列であり、端末をまたいでも一意となること（現状確認が必要）。

## リスク
- ウォーキングポイント数が多いセッションで Firestore ドキュメントサイズ上限（1 MiB）に達する可能性がある。
- `sessionId` が端末ローカルの自動採番（Long）の場合、端末間で衝突するため UUID への移行が必要となる。
- ネットワーク状態の監視（`ConnectivityManager`）は Android バージョンによって API が異なるため、互換実装が必要。
- Firestore Security Rules の不備により、他ユーザーのデータへのアクセスが生じるリスクがある。
