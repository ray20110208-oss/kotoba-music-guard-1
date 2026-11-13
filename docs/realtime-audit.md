# 2026-11-13 查核與首版範圍

## 遠端及原始碼證據

- main：`c423c6a32b376e91ec2865fd7c68c86ebd3e6155`，alpha5 / code 12。
- fix/battery-monitor：`b2a8d5595acc08060790ea3e419e259f6dd3953c`，battery1 / code 13；不在 main 祖先鏈。
- 根目錄沒有 AGENTS.md，但 alpha5 ZIP 內有；本分支將同份指引放到根目錄，方便後續查閱。
- `.github/workflows/main.yml` 才是遠端建置 workflow。ZIP 裡另有 workflow，不應誤當實際執行入口。
- 分卷合併 SHA256：`df40c2a3b1f06c902168f996a10c900efb017c0bcc4bc8f35f4bdce69c1490d1`。
- alpha5 原 Manifest 沒有 INTERNET。原後端有 `/v1/process`、`/v1/retranslate` 的 ASR→文字翻譯雛形，Android 沒接上。
- YAMNet 不再限制 Top5 分類回傳（畫面仍顯示 Top5）；Fusion 保留加權證據，AMBIGUOUS 放行。
- VadSegmenter `ByteArrayOutputStream` 無上限；持續 SPEAKING 時約每分鐘增加 1.92 MB 原始資料，另有配置容量／複製開銷。
- 翻譯模式完全不建立 VadSegmenter/YAMNet/診斷 WAV 緩衝。離線診斷以 5 分鐘樣本量安全停止，未完成片段捨棄；沒有改核心 VAD、MusicGuard 或 Fusion，也沒有增加翻譯句尾硬切。
- 本分支保留 battery1 的畫面可見時廣播接收；仍未真機驗證。沒有把歷史 Actions 成功當作本次測試證據。

## 官方 API 查核

查核時官方列出的可用型號，並不等於這個帳號已獲准呼叫。環境沒有 API 憑證，尚未實際建立 provider session。

| 選項 | 連續輸入／輸出 | 價格（美元） | 首版判斷 |
|---|---|---|---|
| `gpt-realtime-2.1-mini` | 音訊輸入、純文字輸出；可手動 commit + response.create | 每百萬 tokens：音訊輸入 10、快取 0.30；文字輸入 0.60、快取 0.06、輸出 2.40 | 首版預設，可用提示明確要求台灣繁中 |
| `gpt-realtime-2.1` | 同一 Realtime 對話協定，音訊／文字 | 每百萬 tokens：音訊輸入 32、快取 0.40；文字輸入 4、快取 0.40、輸出 24 | 需要品質比較時再評估 |
| `gpt-realtime-translate` | 專用翻譯串流，邊輸入邊回譯音訊與 transcript delta；不呼叫 response.create | 每分鐘 0.034，算術換算每小時 2.04 | 文件未確認可關閉音訊輸出／繁體字形控制；暫不採用，不能稱不支援中文 |

來源：
- https://developers.openai.com/api/docs/models/gpt-realtime-2.1-mini
- https://developers.openai.com/api/docs/models/gpt-realtime-2.1
- https://developers.openai.com/api/docs/models/gpt-realtime-translate
- https://developers.openai.com/api/docs/guides/realtime-conversations
- https://developers.openai.com/api/docs/guides/realtime-translation
- https://developers.openai.com/api/reference/resources/realtime/translation-client-events
- https://developers.openai.com/api/docs/guides/voice-latency-cost

一般 Realtime API 可設定 `turn_detection:null`，由應用觸發生成，不必等句尾；但它是回應式介面，不代表模型每收到一包就同步翻出新字。
專用翻譯 API 不依賴對話 turn，接收 24 kHz PCM16，建議 200 ms 分包；此數字是傳輸／引擎幀，不是語意句界。

## 此次實作

1. 保留 48 kHz mono 擷取，新增獨立 FIR 48→24 kHz adapter；原 48→16 kHz 診斷 resampler 不動。
2. 每 200 ms 傳送未經 VAD 過濾的 PCM，包含 BGM／靜音。Game usage 加入擷取匹配。
3. 後端等 session.updated 才允許送音訊。每累積至少 3 秒新音訊，且沒有進行中回應時，commit 並要求純文字輸出。
4. 3 秒是字幕更新預算，不是「一句結束」；保留近期音訊、譯文上下文，提示模型延後無法確定的未完子句。這不能保證長句品質，需真實測試。
5. 最多保留 20 個歷史 audio/text items（通常約 30 秒，並非整場直播），256 output tokens。截斷舊上下文可能降低人名／長句連貫性並減少快取命中。
6. 同時只允許一個 response。3 秒回應期限、6 秒未提交音訊上限、10 秒連線／無音訊期限；前後端都有 WebSocket 排隊位元組上限。超限斷線，不排隊補翻。
7. 一個有效存取碼預設最多一個 session。輸入僅允許 24 kHz PCM16、單包最多 200 ms，限制不超過即時速率加 1 秒抖動餘裕。
8. 一般 Realtime session 官方上限 60 分鐘；首版 50 分鐘主動換線。斷線／輪替會清空上下文與待送音訊，存在明示缺口，尚不是無縫接續。失敗重試最多 5 次，退避 1/2/4/8/16 秒。
9. 當前字幕隨 delta 增長，上一則固定；不回改歷史。6 秒後清除舊字幕；檢查 connection generation 和音訊時間，忽略過期回報。字幕存於記憶體。
10. 停止立即取消；未滿 3 秒尾段和未完成輸出會捨棄。沒有宣稱完整末句 flush。

## 成本及延遲解讀

官方使用者音訊約 100 ms/token，因此 mini **只算新音訊**約 $0.006/分鐘、$0.36/小時；這不是總費用。
每次 response 會讀取保留的上下文，手動提交的靜音／BGM 也可能計費。以穩定 3 秒窗口、30 秒歷史、完全無快取的情境估算，音訊輸入穩態約 $3.96/小時，另加提示、歷史及輸出的文字費用；窗口延長會改變此估算。這不是報價或費用上限。
不要把 $0.36/小時當作整體成本。實際應加總 `response.done.usage` 的模態、快取及輸出 tokens，並與帳單核對；首版 relay 轉送 usage，重播工具可記錄。

延遲目標 4–6 秒尚未驗證。3 秒累積與 3 秒 response timeout 只是控制參數，網路、未完子句、重連與 provider 都可能造成缺字／超時；未取得語意完整的字幕不算成功。必要時依實測調整回應節奏與模型，而非先改本地 VAD。

## 驗收與已知限制

- 自動測試使用 mock WebSocket provider，只驗證協定與生命週期；不證明模型接受所有設定或翻譯品質。
- 音訊 adapter 測試檢查樣本數、奇數分包連續性、語音頻帶、抗混疊與靜音保留。
- 真機待測：MediaProjection 的開始／停止／被系統收回、背景與鎖屏、電池回報、旋轉螢幕、網路切換。
- 使用原始完整素材重播：純 BGM、日文雜談、BGM+人聲、遊戲、跨 3 秒邊界長句、人名、重疊說話。
- 人工標記日文內容的時間與參考繁中，分開報告首字／完整子句延遲、漏譯、幻覺、重複、繁體字、語意完整度及每小時費用；不能把 CSV 放行率當準確率。
- App 只有內部字幕區，無跨 App overlay；可先用分割畫面測試。

## 本次已執行驗證

- JDK 17 + Android SDK 35：`assembleDebug lintDebug` 成功。產物 metadata 為 `0.3.0-alpha1-realtime` / code 14。
- lint 最終 0 errors、21 warnings（多為既有 locale、UI 字串與 manifest 提示）；日誌另有既有 Kotlin stdlib 2.2.0 與 lint 內嵌分析器 metadata 版本不相容訊息，故不宣稱靜態分析完全無警告。
- 後端 9 項 mock WebSocket 測試通過；包含預設 3 秒期限、長連線輪替及未提交音訊上限。
- JVM 音訊 adapter 與字幕狀態測試通過（當前字幕更新、完成後不可改寫、靜音後舊字幕過期、過期 ID／音訊與靜音）；JavaScript 語法檢查、Python 準備腳本語法檢查、diff 空白檢查通過。
- 沒有 API key、沒有部署後端、沒有真機／真實音訊翻譯測試；未驗證模型帳號權限、日文長句品質、費用及 4–6 秒目標。
