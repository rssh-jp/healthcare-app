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
- Android Studio: **Run > Run 'app'**
- コマンドライン: `./gradlew assembleDebug`

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
