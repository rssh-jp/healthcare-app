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
