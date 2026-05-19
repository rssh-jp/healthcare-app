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