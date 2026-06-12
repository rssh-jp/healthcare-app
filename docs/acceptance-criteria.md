# 受け入れ条件追補: 履歴表示画面のタイムライン表示

## TIMELINE: スライダー表示

### AC-TL-1 タイムラインカード表示
- **Given**: 完了済みウォーキングセッションを履歴画面から選択する。
- **When**: 履歴詳細画面が開く。
- **Then**: タイムラインカードが表示され、スライダーが 1 つ表示される。

### AC-TL-2 開始時刻・終了時刻表示
- **Given**: 履歴詳細画面に 2 点以上の位置記録を持つセッションを表示している。
- **When**: タイムラインカードを確認する。
- **Then**: スライダー左端に最初の位置記録時刻、右端に最後の位置記録時刻が表示される。

### AC-TL-3 単一点セッションの扱い
- **Given**: 位置記録が 1 点のみのセッションを開く。
- **When**: 履歴詳細画面が表示される。
- **Then**: タイムラインカードは表示されるが、スライダーは操作不可であり、唯一の位置記録の情報が表示される。

## POINT: 選択時点情報

### AC-PT-1 最新地点を初期選択
- **Given**: 2 点以上の位置記録を持つセッションを開く。
- **When**: 履歴詳細画面が初回表示される。
- **Then**: スライダーは終端位置に初期化され、最後の位置記録の時刻と座標が表示される。

### AC-PT-2 スライダー操作で時刻が更新される
- **Given**: 2 点以上の位置記録を持つセッションを開いている。
- **When**: スライダーを別の位置へ移動する。
- **Then**: 表示中の日時・時刻・座標が、その進捗に対応する `WalkingPoint` に更新される。

### AC-PT-3 詳細終了時の状態リセット
- **Given**: タイムラインを途中位置まで動かした後に詳細画面を閉じる。
- **When**: 同じまたは別のセッションを再度開く。
- **Then**: 前回の途中位置は引き継がれず、各セッションの初期状態から表示される。

## MAP: 地図連動

### AC-MAP-1 選択位置マーカー表示
- **Given**: Google Maps API キーが有効で、履歴詳細画面を開いている。
- **When**: タイムラインで任意の位置を選択する。
- **Then**: 地図上に選択位置マーカーが表示される。

### AC-MAP-2 既存ルート表示の維持
- **Given**: 2 点以上の位置記録を持つセッションを開いている。
- **When**: タイムラインを操作する。
- **Then**: ルート線、スタートマーカー、ゴールマーカーは引き続き表示される。

### AC-MAP-3 地図未設定時のフォールバック
- **Given**: Google Maps API キーが未設定、またはプレースホルダー値である。
- **When**: 履歴詳細画面を開く。
- **Then**: 地図の代わりに既存のエラーカードが表示され、アプリはクラッシュしない。

## Handoff Contract

### 実施サマリ
タイムラインスライダー、選択時点情報、地図連動について検証可能な受け入れ条件を追加した。

### 成果物
- `docs/acceptance-criteria.md`（本追補）

### 未解決事項
- None

### 次フェーズへの依頼事項
1. `docs/design.md` で `timelineProgress` の状態管理と選択地点計算ロジックを明文化すること。
2. 実装後に AC-TL / AC-PT / AC-MAP を対象にビルドおよび画面動作を検証すること。

# 受け入れ条件: Firestore 座標データの Blob 圧縮保存 (geoFlatBlob)

## ENC: エンコード処理

### AC-ENC-1 通常座標のエンコード
- **Given**: 座標点が 3 点ある `WalkingPoint` リスト（lat/lng がそれぞれ有効な Double 値）。
- **When**: `encodeGeoBlob(points)` を呼び出す。
- **Then**: 戻り値の `Blob` が null でなく、バイト列が GZIP ヘッダー（`1f 8b`）で始まる。

### AC-ENC-2 空リストのエンコード
- **Given**: 空の `WalkingPoint` リスト。
- **When**: `encodeGeoBlob(emptyList())` を呼び出す。
- **Then**: 例外が発生せず、`Blob` が返る。`decodeGeoBlob` にかけると空リストが返る。

### AC-ENC-3 バイトサイズの正確性
- **Given**: n 点の座標リスト。
- **When**: `encodeGeoBlob` を呼び出し、Blob を `decodeGeoBlob` で展開したバイト数を確認する。
- **Then**: 展開後のバイト数が `n * 16` バイトと一致する。

---

## DEC: デコード処理

### AC-DEC-1 ラウンドトリップ保証
- **Given**: 任意の `WalkingPoint` リスト（n 点）。
- **When**: `encodeGeoBlob(points)` → `decodeGeoBlob(blob)` の順に呼び出す。
- **Then**: 返された `List<Pair<Double, Double>>` の件数が n と一致し、各点の lat/lng 値が元の `WalkingPoint` の値と完全に一致する。

### AC-DEC-2 末尾の不完全バイトを無視
- **Given**: GZIP 展開後のバイト列が `16 * n + k`（0 < k < 16）バイトの Blob。
- **When**: `decodeGeoBlob(blob)` を呼び出す。
- **Then**: 例外が発生せず、n 点のリストが返る。余剰の k バイトは無視される。

### AC-DEC-3 破損 Blob の例外伝播
- **Given**: GZIP として無効なバイト列を持つ `Blob`。
- **When**: `decodeGeoBlob(blob)` を呼び出す。
- **Then**: `java.util.zip.ZipException` または `IOException` が発生し、アプリはクラッシュしない（呼び出し元が例外を捕捉する）。

---

## UPLOAD: セッションアップロード

### AC-UPLOAD-1 geoFlatBlob フィールドの書き込み
- **Given**: 認証済みユーザーが存在し、`WalkingSession` と `WalkingPoint` リストが用意されている。
- **When**: `uploadSession(session, points)` を呼び出す。
- **Then**: Firestore ドキュメント `users/{uid}/walking_sessions/{sessionId}` に `geoFlatBlob`（Blob 型）フィールドが書き込まれる。`geoFlat` フィールドは存在しない。

### AC-UPLOAD-2 geoFlat フィールドの不在
- **Given**: 認証済みユーザーが存在し、セッション同期が完了している。
- **When**: Firestore コンソールまたはテストコードで該当ドキュメントを参照する。
- **Then**: ドキュメントに `geoFlat` キーが存在しない。

### AC-UPLOAD-3 20,000 点超の間引き
- **Given**: 座標点が 30,000 点ある `WalkingPoint` リスト（stride = ceil(30000 / 20000) = 2、すなわち 2 点に 1 点を採用）。
- **When**: `uploadSession(session, points)` を呼び出す。
- **Then**: Firestore に書き込まれる `geoFlatBlob` のデコード後点数が 20,000 点以下となる。アップロード自体は成功する。

### AC-UPLOAD-4 未認証時のエラー
- **Given**: `FirebaseAuth.currentUser` が null（未サインイン）。
- **When**: `uploadSession(session, points)` を呼び出す。
- **Then**: `Result.failure(IllegalStateException)` が返り、Firestore への書き込みは行われない。

### AC-UPLOAD-5 指数バックオフリトライ
- **Given**: Firestore への書き込みが一時的なネットワークエラーで失敗する（最初の 2 回）。
- **When**: `uploadSession(session, points)` を呼び出す。
- **Then**: 最大 3 回の試行後に成功し、`Result.success` が返る。

### AC-UPLOAD-6 PERMISSION_DENIED はリトライしない
- **Given**: Firestore への書き込みが `PERMISSION_DENIED` で失敗する。
- **When**: `uploadSession(session, points)` を呼び出す。
- **Then**: リトライは行われず、即座に `Result.failure` が返る。試行回数は 1 回のみ。

---

## SYNC: サインイン時同期 (geoFlatBlob 優先読み込み)

### AC-SYNC-1 geoFlatBlob 優先で座標を復元
- **Given**: Firestore ドキュメントに `geoFlatBlob`（有効な Blob）と `geoFlat`（Array）の両方が存在する。
- **When**: `syncOnLogin` がそのドキュメントを処理する。
- **Then**: `geoFlatBlob` のデコード結果が `WalkingPoint` として Room に保存される。`geoFlat` は使用されない。

### AC-SYNC-2 geoFlatBlob 不在時は geoFlat フォールバック
- **Given**: Firestore ドキュメントに `geoFlatBlob` フィールドが存在せず、`geoFlat`（偶数個の Double）が存在する。
- **When**: `syncOnLogin` がそのドキュメントを処理する。
- **Then**: `geoFlat` の値から座標ペアが復元され、`WalkingPoint` として Room に保存される。

### AC-SYNC-3 両フィールド不在時は座標なしでマージ
- **Given**: Firestore ドキュメントに `geoFlatBlob` も `geoFlat` も存在しない。
- **When**: `syncOnLogin` がそのドキュメントを処理する。
- **Then**: セッションメタデータ（startTime / endTime / distance / calories）は Room にマージされるが、`WalkingPoint` は追加されない。例外は発生しない。

### AC-SYNC-4 ローカル既存セッションは上書きしない
- **Given**: `sessionUuid` が Room に既存のセッションが Firestore にも存在する。
- **When**: `syncOnLogin` を実行する。
- **Then**: Room の既存セッション・座標データは変更されない。

### AC-SYNC-5 重複マージ防止
- **Given**: 同一 `sessionUuid` に対して `syncOnLogin` を 2 回実行する。
- **When**: 2 回目の `syncOnLogin` を実行する。
- **Then**: Room の当該セッションが重複登録されない。履歴一覧に同一セッションが 2 件表示されない。