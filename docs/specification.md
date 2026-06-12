# 仕様追補: 履歴表示画面のタイムライン表示

## 背景

履歴表示画面ではウォーキングセッションのルート全体は確認できるが、利用者が「何時にどこにいたか」を時系列で確認する手段がなかった。

## 目的

履歴詳細画面に時間スライダー付きのタイムラインを追加し、セッション内の任意時点における位置と時刻を視覚的に確認できるようにする。

## 機能要件

### FR-TL: タイムライン表示

- FR-TL-1: 履歴詳細画面にタイムラインカードを表示すること。
- FR-TL-2: タイムラインカードにはスライダーを表示し、セッションの開始地点から終了地点までを 0.0〜1.0 の進捗として選択できること。
- FR-TL-3: スライダーの左右に、セッション内で保持している最初と最後の位置記録の時刻を表示すること。

### FR-PT: 選択時点情報表示

- FR-PT-1: スライダー位置に対応する `WalkingPoint` を 1 件選択し、その時刻を表示すること。
- FR-PT-2: 選択時点について、その地点の時刻を表示すること。
- FR-PT-3: 選択時点の緯度・経度を表示すること。

### FR-MAP: 地図連動

- FR-MAP-1: 地図表示が有効な場合、選択時点を示すマーカーを地図上に表示すること。
- FR-MAP-2: 既存のルート線、スタートマーカー、ゴールマーカーの表示は維持すること。
- FR-MAP-3: Google Maps API キー未設定時は既存のフォールバック表示を維持し、アプリがクラッシュしないこと。

### FR-STATE: UI 状態管理

- FR-STATE-1: タイムラインの進捗は `HistoryViewModel` の UI state で管理すること。
- FR-STATE-2: セッション選択時は最新地点を初期選択とすること。
- FR-STATE-3: 詳細画面を閉じたとき、タイムライン状態をリセットすること。

## 非機能要件

- NFR-TL-1: 既存の MVVM 構成を維持し、タイムライン UI 状態は `HistoryViewModel` に閉じること。
- NFR-TL-2: 既存の Room スキーマや Firestore 同期処理に変更を入れないこと。
- NFR-TL-3: 地図未設定環境でも最低限、日時・時刻・座標を確認できること。
- NFR-TL-4: 位置データの保存タイミング：ローカル DB (Room) には約 3 秒間隔で記録し、セッション停止時にクラウド (Firestore) に同期すること。

## スコープ

### In Scope

- `HistoryScreen` の詳細表示 UI 拡張
- `HistoryViewModel` のタイムライン状態追加
- 履歴詳細画面での選択位置マーカー表示

### Out of Scope

- 位置履歴データの保存形式変更
- セッション再生アニメーション
- タイムラインに連動した地図カメラ追従
- 履歴一覧画面の並び替え・フィルタ変更

## 前提

- `WalkingPoint` は `latitude`、`longitude`、`timestamp` を保持している。
- 履歴詳細画面では、対象セッションの `WalkingPoint` 一覧を既に取得できる。

## リスク

- 記録点の密度が低いセッションでは、スライダー移動時の位置変化が粗く見える可能性がある。
- API キー未設定環境では地図マーカー確認ができず、テキスト情報のみでの利用となる。

## Handoff Contract

### 実施サマリ
履歴詳細画面で「何時にどこにいたか」を確認できるようにするため、タイムラインスライダーと選択位置表示の仕様を定義した。

### 成果物
- `docs/specification.md`（本追補）

### 未解決事項
- None

### 次フェーズへの依頼事項
1. `docs/acceptance-criteria.md` にタイムライン表示・選択位置表示・地図連動の受け入れ条件を追加すること。
2. `docs/design.md` に `HistoryScreen` / `HistoryViewModel` の責務分割を追記すること。

# 仕様: Firestore 座標データの Blob 圧縮保存 (geoFlatBlob)

## 背景

従来の `FirestoreSyncRepository` は、ウォーキングセッションの GPS 座標を Firestore の `geoFlat` フィールド（`Array<Double>`）として保存していた。  
Double 配列はテキスト表現であるため、多数の座標点を持つセッションでドキュメントサイズが肥大化し、Firestore の 1 MiB 上限に近づくリスクがあった。

## 目的

GPS 座標を ByteBuffer でバイナリ化し GZIP 圧縮した Firestore `Blob` (`geoFlatBlob` フィールド) として保存・読み込みすることで、ドキュメントサイズを削減しつつ、旧形式 (`geoFlat`) との後方互換性を維持する。

## 機能要件

### FR-ENC: エンコード処理 (`encodeGeoBlob`)

- FR-ENC-1: `WalkingPoint` リストを受け取り、緯度・経度をそれぞれ 8 バイト (Double) の順で `ByteBuffer` に詰める。1 点あたりのバイト数は 16 バイト（lat 8B + lng 8B）とする。
- FR-ENC-2: `ByteBuffer` の内容を GZIP 圧縮し、Firestore の `Blob` 型として返す。
- FR-ENC-3: 空リストを渡した場合は、空の GZIP ストリームを持つ `Blob` を返す（例外を投げない）。

### FR-DEC: デコード処理 (`decodeGeoBlob`)

- FR-DEC-1: GZIP 圧縮済みの Firestore `Blob` を受け取り、GZIP 展開後に `ByteBuffer` として解釈する。
- FR-DEC-2: `ByteBuffer` を 16 バイト単位で読み取り、`List<Pair<Double, Double>>`（緯度, 経度）を返す。
- FR-DEC-3: バッファ末尾に 16 バイト未満の残余がある場合はその部分を無視する（部分書き込みへの堅牢性）。
- FR-DEC-4: エンコード時と同一の点数・値が復元されること（ラウンドトリップ保証）。

### FR-UPLOAD: セッションアップロード (`uploadSession`)

- FR-UPLOAD-1: Firestore ドキュメントには `geoFlatBlob` フィールド（Blob 型）を使用し、旧 `geoFlat` フィールド（Array<Double>）は書き込まない。
- FR-UPLOAD-2: 座標点数が 20,000 点を超える場合は、均等間引き（stride = floor(points.size / 20_000)）を行った上でエンコードする。
- FR-UPLOAD-3: ドキュメントに含まれるフィールドは `sessionId`、`startTime`、`endTime`、`distanceMeters`、`caloriesBurned`、`geoFlatBlob`、`syncedAt` とする。
- FR-UPLOAD-4: 指数バックオフ（初期遅延 1 秒、最大 16 秒、最大 3 回）でリトライする。`PERMISSION_DENIED` エラーは即座に失敗とし、リトライしない。

### FR-SYNC: サインイン時同期 (`syncOnLogin`)

- FR-SYNC-1: Firestore ドキュメントの読み込み時は `geoFlatBlob` フィールドを優先して座標を復元する。
- FR-SYNC-2: `geoFlatBlob` が存在しないドキュメント（旧形式）は `geoFlat`（`List<*>`、偶数インデックス: 緯度, 奇数インデックス: 経度）から座標を復元する（後方互換フォールバック）。
- FR-SYNC-3: `geoFlatBlob`・`geoFlat` ともに存在しないドキュメントは座標なしでセッションのみをマージする。
- FR-SYNC-4: ローカルに既存のセッション（`sessionUuid` 一致）は Firestore 側データで上書きしない（ローカルを正とする）。

## 非機能要件

- NFR-1: エンコード・デコード処理は UI スレッドをブロックしない（呼び出し元が IO Dispatcher を使用）。
- NFR-2: GZIP 圧縮により、Double 配列比で約 40〜60% のサイズ削減を達成すること（目安値。実データ依存）。
- NFR-3: Firestore ドキュメントサイズが 1 MiB 上限を超えないよう、20,000 点の間引き上限を設ける。
- NFR-4: 本変更は既存の Room テーブル構造・マイグレーションに影響を与えない。
- NFR-5: 既存の Firestore Security Rules を変更しない。

## スコープ

### In Scope

- `FirestoreSyncRepository` における `encodeGeoBlob` / `decodeGeoBlob` の実装
- `uploadSession` の `geoFlat` → `geoFlatBlob` への切り替え
- `syncOnLogin` における `geoFlatBlob` 優先・`geoFlat` フォールバック読み込み

### Out of Scope

- 既存 Firestore ドキュメントの `geoFlat` → `geoFlatBlob` への一括マイグレーション
- `geoFlat` フィールドの Firestore ドキュメントからの物理削除
- Room への座標保存形式の変更
- 20,000 点間引き以外のサンプリングアルゴリズム変更
- `FirestoreSyncRepository` 以外のクラスへの変更

## 制約

- バイトオーダーは `ByteBuffer` のデフォルト（ビッグエンディアン）を使用する。エンコード・デコード双方で同一オーダーを使用すること。
- Firestore `Blob` の最大サイズは 1 MiB 未満。間引き後 20,000 点 × 16 バイト = 320 KB（圧縮前）を上限とする。
- GZIP 実装は JVM 標準ライブラリ（`java.util.zip.GZIPOutputStream` / `GZIPInputStream`）を使用する。

## 前提

- `WalkingPoint` は `latitude: Double` と `longitude: Double` フィールドを持つ。
- `FirestoreSyncRepository` は既に Firebase Authentication / Firestore を DI 経由で取得している。
- Firestore への書き込み権限は認証済みユーザーの UID でスコープされており、Security Rules により保護されている。

## リスク

- 旧形式ドキュメントの `geoFlat` フォールバック処理は `List<*>` キャストに依存するため、Firestore SDK の型変換挙動が変わった場合にサイレントに空配列となるリスクがある。
- GZIP ストリームが破損した Blob を読み込んだ場合、`GZIPInputStream` が例外を投げるため、呼び出し元での例外ハンドリングが必要。