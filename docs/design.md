# 設計: Firestore 座標データの Blob 圧縮保存 (geoFlatBlob)

## 対象

- データ層: `FirestoreSyncRepository`（`encodeGeoBlob` / `decodeGeoBlob` / `uploadSession` / `syncOnLogin`）

---

## アーキテクチャ方針

既存の Compose + MVVM + Repository + Room + Hilt 構成を維持する。  
本フィーチャーの変更は **`FirestoreSyncRepository` 単一クラスに閉じる**。  
UI・ViewModel・Room スキーマへの変更はない。

---

## データフロー設計

### アップロード（`uploadSession`）

```
UI / ViewModel
    │
    ▼
FirestoreSyncRepository.uploadSession(session, points)
    │
    ├─ points.size > 20_000 → 均等間引き (stride = floor(size / 20_000))
    │
    ├─ encodeGeoBlob(sampledPoints)
    │       ByteBuffer.allocate(n * 16)
    │       → putDouble(lat), putDouble(lng) per point (big-endian)
    │       → GZIPOutputStream → ByteArray
    │       → Blob.fromBytes(...)
    │
    ├─ Firestore doc: { sessionId, startTime, endTime, distanceMeters,
    │                   caloriesBurned, geoFlatBlob (Blob), syncedAt }
    │   ※ geoFlat フィールドは含めない
    │
    └─ withRetry(maxAttempts=3, initialDelay=1s, maxDelay=16s)
           PERMISSION_DENIED → 即時 Result.failure (リトライなし)
```

### 同期（`syncOnLogin`）

```
FirestoreSyncRepository.syncOnLogin(uid, walkingRepository)
    │
    ├─ [Step 1] ローカル PENDING/FAILED セッションを uploadSession() でアップロード
    │
    └─ [Step 2] Firestore → ローカルへのダウンロード・マージ
            for each doc in snapshot:
                │
                ├─ sessionUuid が Room に存在 → スキップ (ローカルを正とする)
                │
                ├─ session メタデータを Room に upsert
                │
                └─ 座標復元（優先順位付き）
                        ┌─ [A] geoFlatBlob (Blob) が存在する場合
                        │       try {
                        │           decodeGeoBlob(blob)
                        │               GZIPInputStream → ByteBuffer (big-endian)
                        │               → List<Pair<Double, Double>>
                        │       } catch (IOException | ZipException) {
                        │           Log.w(TAG, "geoFlatBlob decode failed, skipping points")
                        │           → 座標なしでマージ継続 (セッションメタデータは保持)
                        │       }
                        │
                        ├─ [B] geoFlatBlob なし → geoFlat (List<*>) フォールバック
                        │       偶数インデックス: 緯度, 奇数インデックス: 経度
                        │       Number#toDouble() でキャスト (null なら該当ペアをスキップ)
                        │
                        └─ [C] 両フィールドなし → 座標なし (例外なし)
```

---

## 設計決定

### D-1: `encodeGeoBlob` / `decodeGeoBlob` の可視性を `internal` に変更

**理由**: `private` のままではユニットテストで Reflection が必要になる。  
`internal` にすることで同モジュール内のテストコードから直接呼び出せる。  
`public` にしない理由は、本関数はモジュール内部の変換処理であり外部 API として公開する意図がないため。

**影響範囲**: `FirestoreSyncRepository.kt` の関数修飾子のみ。呼び出し箇所は同クラス内のみのため外部への影響はない。

### D-2: 破損 Blob 受信時の `syncOnLogin` 挙動

**決定: セッションスキップ（座標なしマージ）+ WarningLog**

| 選択肢 | 内容 | 採否 |
|--------|------|------|
| A. 例外を外に投げる | syncOnLogin 全体が失敗 | 却下: 1 件の破損で全セッション同期が失敗するリスク過大 |
| B. ドキュメントごとに座標なしマージ | 破損 doc のみ座標なし、セッションは保持 | **採用** |
| C. 破損 doc 全体をスキップ | セッション自体をマージしない | 却下: データロスリスクが高い |

実装上の制約:

- `decodeGeoBlob` 自体は例外を**そのまま投げる**（AC-DEC-3 の「呼び出し元が捕捉する」を維持）。
- `syncOnLogin` 内でドキュメントループの座標復元ブロックに try-catch を設け、`java.io.IOException`（`ZipException` は IOException のサブクラス）を捕捉してログ出力後に座標なしマージを継続する。
- 関数レベルの外側 catch は `Exception` を捕捉しているが、破損 Blob はドキュメント単位の内側 catch で先に処理されるため、外側 catch に届かない設計とする。

### D-3: バイトオーダー

`ByteBuffer` のデフォルト（ビッグエンディアン）を使用。エンコード・デコード双方で変更しない。  
将来他プラットフォームと相互運用する場合は `ByteOrder.LITTLE_ENDIAN` への変更を検討するが、現時点では範囲外。

### D-4: 空リストのエンコード

`ByteBuffer.allocate(0)` → `GZIPOutputStream.write(ByteArray(0))` → 空の GZIP ストリームが生成される。  
`decodeGeoBlob` で `remaining() >= 16` が 0 回満たされるため空リストが返る。例外なし。

---

## 責務分担

| 関数 | 責務 |
|------|------|
| `encodeGeoBlob(points)` | `WalkingPoint` リスト → GZIP 圧縮 `Blob` 変換。副作用なし。破損入力に対しても例外なし（空リスト含む）。 |
| `decodeGeoBlob(blob)` | GZIP 圧縮 `Blob` → `List<Pair<Double,Double>>` 変換。副作用なし。破損 Blob は IOException を投げる（呼び出し元が捕捉）。 |
| `uploadSession(session, points)` | 間引き・エンコード・Firestore 書き込み・リトライ制御。 |
| `syncOnLogin(uid, repo)` | ローカル→Firestore アップロード + Firestore→ローカル マージ。破損 Blob は per-doc catch でセッションスキップ（座標なし）。 |

---

## 非機能要件との対応

| 要件 | 対応 |
|------|------|
| NFR-1: UI スレッドブロックなし | `uploadSession` / `syncOnLogin` は suspend fun。呼び出し元が IO Dispatcher を使用する設計は変更しない。 |
| NFR-2: 40〜60% 削減 | GZIP による圧縮（実データ依存）。間引き 20,000 点で圧縮前 320 KB。 |
| NFR-3: 1 MiB 上限 | 20,000 点間引きで圧縮前 320 KB。GZIP 後はさらに縮小。 |
| NFR-4: Room 変更なし | 本変更は Firestore レイヤのみ。Room エンティティ・DAO・マイグレーション変更なし。 |
| NFR-5: Security Rules 変更なし | `geoFlatBlob` フィールドは既存ルールのスコープ内（ドキュメント単位）に収まる。 |

---

## Handoff Contract

### 実施サマリ
仕様 (`docs/specification.md`) と受け入れ条件 (`docs/acceptance-criteria.md`) に基づき、`FirestoreSyncRepository` の設計を確定した。  
spec-writer からの依頼事項（`internal` 可視性変更・破損 Blob 挙動）を設計決定 D-1 / D-2 として明文化した。

### 成果物
- `docs/design.md`（本ファイル）
- `docs/task-breakdown.md`

### 未解決事項
なし（仕様の未確定事項はすべて本設計内で決定済み）

### 次フェーズ（`implementer`）への依頼事項
1. `FirestoreSyncRepository.kt` の `encodeGeoBlob` / `decodeGeoBlob` 修飾子を `private` → `internal` に変更する。
2. `syncOnLogin` のドキュメントループ内、座標復元ブロック（`geoFlatBlob` 取得後）に per-doc catch を追加し、`java.io.IOException` を捕捉してログ出力後に座標なしマージを継続する。
3. ユニットテスト (`FirestoreSyncRepositoryTest.kt`) に AC-ENC-1〜3 / AC-DEC-1〜3 のテストケースを追加する（`internal` 関数への直接呼び出し）。
