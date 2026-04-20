# Healthcare App

ヘルスケア用Androidアプリ - ウォーキングトラッカー

## 機能

### 🚶 ウォーキング追跡
- GPSによるリアルタイム位置追跡（フォアグラウンドサービス）
- 歩いた経路を地図上にポリラインで表示
- 距離・速度・経過時間のリアルタイム表示

### 🔥 カロリー計算
- MET（代謝当量）ベースの消費カロリー自動計算
- 歩行速度に応じた6段階のMET値（ゆっくり歩き〜ランニング）
- セグメントごとの精密計算

### 📊 統計・集計
- **日別**: 1日ごとの距離・カロリー・回数
- **週別**: 1週間単位での集計
- **月別**: 1ヶ月単位での集計
- **カスタム**: 任意の期間を日付ピッカーで指定

### 🗺️ 履歴・地図
- 過去のウォーキング履歴一覧
- 各セッションの経路をGoogle Maps上で表示
- スタート/ゴールのマーカー付き

## 技術スタック

| 項目 | 技術 |
|------|------|
| 言語 | Kotlin |
| UI | Jetpack Compose + Material 3 |
| 地図 | Google Maps Compose SDK |
| DB | Room |
| DI | Hilt |
| 位置情報 | FusedLocationProviderClient |
| アーキテクチャ | MVVM |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 |

## セットアップ

### 1. Android Studioで開く
プロジェクトをAndroid Studioで開いてください。Gradleの同期が自動的に行われます。

### 2. Google Maps APIキーの設定
`local.properties` に Google Maps APIキーを設定してください:
```properties
MAPS_API_KEY=your_actual_api_key_here
```

APIキーは [Google Cloud Console](https://console.cloud.google.com/apis/credentials) で取得できます。
「Maps SDK for Android」を有効にしてください。

### 3. ビルド & 実行

#### コマンドライン（Windows）
`gradlew.bat` を使用します。`JAVA_HOME` が未設定でも Android Studio の JBR に自動フォールバックします。

```bat
:: Debug APK をビルド
.\gradlew.bat assembleDebug

:: Release APK をビルド
.\gradlew.bat assembleRelease

:: ビルド成果物を削除
.\gradlew.bat clean

:: クリーンビルド
.\gradlew.bat clean assembleDebug

:: ユニットテスト実行
.\gradlew.bat test

:: Android Lint 実行
.\gradlew.bat lint

:: 署名証明書の SHA-1 / SHA-256 確認
.\gradlew.bat signingReport

:: 接続済み端末へ Debug APK をインストール
.\gradlew.bat installDebug
```

> ビルド済み APK の出力先: `app/build/outputs/apk/debug/app-debug.apk`

#### Android Studio
**Run > Run 'app'** で実行できます。

### 地図が表示されないとき（トラブルシュート）
- `local.properties` の `MAPS_API_KEY` が空、または `YOUR_API_KEY_HERE` などのプレースホルダーになっていないか確認
- Google Cloud で **Billing 有効化** と **Maps SDK for Android 有効化** を確認
- APIキー制限を使う場合は、Android アプリ制限に **パッケージ名 `jp.co.rssh_jp.healthcareap`** と **署名証明書 SHA-1** を正しく登録
- 変更後は Gradle Sync / 再ビルドを実行

### 3. Firebase セットアップ（クラウド同期・Google サインインを使う場合）

> ⚠️ Firebase なしでもアプリはビルド・動作します。認証機能を有効にする場合のみ必要です。

#### 3-1. Firebase プロジェクト作成
1. [Firebase コンソール](https://console.firebase.google.com/) にアクセス
2. 「プロジェクトを作成」でプロジェクトを作成
3. 「アプリを追加」> Android を選択
4. パッケージ名: `jp.co.rssh_jp.healthcareap`
5. アプリのニックネームを入力（任意）
6. 署名証明書 SHA-1 を入力（`.\gradlew.bat signingReport` で確認）

#### 3-2. google-services.json の配置
1. Firebase コンソールから `google-services.json` をダウンロード
2. `app/google-services.json.example` を参考に内容を確認
3. ダウンロードしたファイルを `app/google-services.json` として配置
   - このファイルは `.gitignore` に追加することを推奨（認証情報を含む）

#### 3-3. Firebase Authentication の有効化
1. Firebase コンソール > Authentication > Sign-in method
2. 「Google」を有効化

#### 3-4. Firestore Database の作成
1. Firebase コンソール > Firestore Database > データベースを作成
2. 本番モードまたはテストモードで作成
3. `firestore.rules` の内容をセキュリティルールに適用:
   ```
   firebase deploy --only firestore:rules
   ```
   （Firebase CLI が必要: `npm install -g firebase-tools`）

### 4. 実機へのインストール
1. スマホの「開発者オプション」で「USBデバッグ」を有効にする
2. USBケーブルで接続
3. Android Studioで端末を選択して実行

## パーミッション
アプリは以下の権限を使用します:
- **位置情報（正確）**: GPS追跡に必要
- **フォアグラウンドサービス**: バックグラウンドでの追跡継続
- **通知**: 追跡中の通知表示
- **インターネット**: Google Maps表示

## プロジェクト構造
```
com.healthcare.app/
├── HealthcareApp.kt          # Hilt Application
├── MainActivity.kt           # エントリーポイント
├── data/
│   ├── entity/               # Room エンティティ
│   ├── dao/                  # Room DAO
│   ├── db/                   # AppDatabase
│   └── repository/           # リポジトリ
├── di/                       # Hilt DI モジュール
├── service/                  # 位置情報追跡サービス
├── util/                     # カロリー計算、日付ユーティリティ
└── ui/
    ├── navigation/           # ボトムナビゲーション
    ├── theme/                # Material 3 テーマ
    └── screen/
        ├── home/             # ダッシュボード
        ├── tracking/         # ウォーキング追跡
        ├── history/          # 履歴・地図表示
        └── stats/            # 統計・集計
```
