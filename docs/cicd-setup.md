# CI/CD セットアップ: Google Play Store 自動デプロイ

## 概要

`v*.*.*` 形式の git タグをプッシュすると GitHub Actions が自動起動し、署名済み AAB をビルドして Play Store の internal トラックにアップロードする。

---

## 構成ファイル

| ファイル | 役割 |
|---|---|
| `.github/workflows/deploy-to-play-store.yml` | CI/CD ワークフロー本体 |
| `app/build.gradle.kts` | versionName / versionCode の自動解決ロジック |

---

## ワークフロー実行フロー

```
git tag v1.x.x → push → GitHub Actions 起動
  ↓
1. Checkout code
2. Set up JDK 17 (Temurin)
3. Set up Gradle cache
4. Decode Keystore        (KEYSTORE_BASE64 → release.keystore)
5. Decode google-services.json  (GOOGLE_SERVICES_JSON → app/google-services.json)
6. Create local.properties      (署名情報 / MAPS_API_KEY を注入)
7. Grant execute permission for gradlew
8. Get next versionCode from Play Console  ← Python + Android Publisher API v3
9. Build Release AAB       (VERSION_CODE 環境変数で注入)
10. Upload to Google Play (internal track)
```

---

## バージョニング戦略

### versionName
`git describe --tags --abbrev=0` で最新タグを取得し `v` プレフィックスを除いた文字列を使用する。  
タグが存在しない場合はフォールバック値 `"1.0.1"` を使用する。

```kotlin
// app/build.gradle.kts
fun gitTag(projectDir: java.io.File): String? = try {
    val proc = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
        .directory(projectDir).redirectErrorStream(true).start()
    val line = proc.inputStream.bufferedReader().readLine()?.trim()
    if (proc.waitFor() == 0 && !line.isNullOrEmpty()) line else null
} catch (_: Exception) { null }

val appVersionName: String = gitTag(rootProject.projectDir)?.removePrefix("v") ?: "1.0.1"
```

### versionCode
ワークフロー実行時に Play Console Android Publisher API v3 で全トラックの最大 versionCode を取得し `+1` した値を `VERSION_CODE` 環境変数として Gradle に渡す。  
ローカルビルド時はフォールバック値 `1` を使用する。

```kotlin
// app/build.gradle.kts
val appVersionCode: Int = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
```

**API 呼び出しの流れ (Python)**:
1. サービスアカウント認証 (`google-auth`) でアクセストークン取得
2. `POST /edits` で一時 Edit を作成 (コミットしない)
3. `GET /edits/{id}/tracks` で全トラックのリリース情報を取得
4. 全リリースの versionCodes 最大値 `max_vc` を算出 → `next_vc = max_vc + 1`
5. `$GITHUB_OUTPUT` に `version_code=N` を書き込み、後続ステップで参照

---

## 必要な GitHub Secrets

| Secret 名 | 内容 |
|---|---|
| `KEYSTORE_BASE64` | 署名キーストア (`release/sanpokei.jks`) を Base64 エンコードした文字列 |
| `KEYSTORE_PASSWORD` | キーストアのパスワード |
| `KEY_ALIAS` | キーのエイリアス (`key0`) |
| `KEY_PASSWORD` | キーのパスワード |
| `MAPS_API_KEY` | Google Maps API キー |
| `GOOGLE_SERVICES_JSON` | `app/google-services.json` を Base64 エンコードした文字列 |
| `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | Play Console サービスアカウントの JSON キー (平文) |

---

## サービスアカウント権限

Play Console で以下の権限を付与する必要がある。

1. Play Console →「ユーザーと権限」→「サービスアカウント」でサービスアカウントを招待
2. 対象アプリに対して「リリースの管理」権限を付与

---

## リリース手順

```bash
# タグを作成してプッシュするだけで自動デプロイが走る
git tag v1.0.2
git push origin v1.0.2
```

手動実行が必要な場合は GitHub Actions の「Run workflow」ボタンから実行する (`workflow_dispatch` 対応)。

---

## トラブルシューティング

| エラー | 原因 | 対処 |
|---|---|---|
| `Version code N has already been used` | versionCode が Play Console に既登録 | Play Console API 自動取得により以降は発生しない |
| `File google-services.json is missing` | `GOOGLE_SERVICES_JSON` secret 未設定 | secret を設定しワークフローを再実行 |
| `The caller does not have permission` | サービスアカウントの権限不足 | Play Console でリリース管理権限を付与 |
| `org.gradle.java.home is invalid` | `gradle.properties` にローカル JDK パスが残存 | `gradle.properties` から `org.gradle.java.home` を削除 |

---

## 動作確認済みバージョン

- 初回成功: run `26097703070` (versionCode 手動指定)
- Play Console API 連携成功: run `26098306133` (versionCode 自動取得)
