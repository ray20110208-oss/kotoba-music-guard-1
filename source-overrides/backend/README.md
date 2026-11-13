# 即時音訊翻譯後端

Node 22+。主線只有 `/v1/live` WebSocket；舊 ASR `/v1/process`、文字 `/v1/retranslate` 已移除。

## 安全設定

將 `OPENAI_API_KEY` 放入部署平台的 secret/environment 設定。不要貼到聊天、寫入 APK、Gradle、Git 或 URL。
另產生至少 32 字元的隨機 `KOTOBA_APP_TOKEN`，用於這個單人原型的後端驗證；App 只輸入這個存取碼。
可以在受保護的本地終端產生，例如 `openssl rand -hex 32`，再存入部署平台 secrets。不要把實際值放進命令歷史。

```sh
npm ci
npm test
# 由部署平台注入 OPENAI_API_KEY 和 KOTOBA_APP_TOKEN 後：
npm start
```

可選 `KOTOBA_REALTIME_MODEL`（預設 `gpt-realtime-2.1-mini`）、`PORT`（預設 8787）。
server 綁定 127.0.0.1。以具有效 TLS 憑證且支援 WebSocket 的反向代理公開，App 必須使用 `wss://host/v1/live`。
若環境設定 `HTTPS_PROXY`（或 `https_proxy`），provider WebSocket 與重播工具會使用該代理，沿用 Node 的 CA 信任設定。不要關閉 TLS 驗證。缺 key／存取碼時 upgrade 回 503；錯誤存取碼回 401；同時多連線回 429。
健康檢查 `GET /health` 只表示程序與設定存在，不表示 API 可用。
這個環境目前沒有憑證，也沒有部署後端。

例如 Caddy 在同一主機：

```caddy
translate.example.com {
    reverse_proxy 127.0.0.1:8787
}
```

多租戶登入、使用者帳務與跨機擴展不在首版範圍。正式公開服務前需改成逐使用者短期授權；目前不要把存取碼分享給不受信任的人。

## 協定

- 客戶端帶 `Authorization: Bearer <KOTOBA_APP_TOKEN>`，等待 JSON `ready`。
- 二進位資料：PCM16 little-endian、mono、24000 Hz；200 ms = 9600 bytes。不要送 WAV header，不做 VAD 篩選。
- 收到 `caption`：`id`、`text`（目前完整字串）、`final`、`audioEndMs`。id 僅在本連線唯一；重連清空顯示。
- `usage` 轉送每次完成 response 的使用量；`status` 提供終止原因。
- `{"type":"stop"}` 或關閉連線：立即取消上游，未完成尾段不追補。
- 不保存原始音訊／字幕，不記錄金鑰或完整 provider 錯誤。

## 使用未經 VAD 篩選的素材重播

請從完整原音訊／影片轉換，只做格式轉換，不裁出 VAD 片段：

```sh
ffmpeg -i original.mp4 -vn -ac 1 -ar 24000 -f s16le original.pcm
# 透過環境安全設定 KOTOBA_RELAY_URL 和 KOTOBA_APP_TOKEN
node tools/replay.mjs original.pcm > replay.ndjson
```

重播會實際上傳並產生 API 費用；目前尚未執行真實重播。
NDJSON 含字幕與 usage，請存於本地 `work/`，不要提交私人素材或結果。
`windowEndAgeMs` 僅是音訊提交窗口結束至接收事件時間差，不是人工對齊的語意字幕延遲。
腳本在素材末尾加 3 秒靜音、等待 3 秒，協助觀察尾段；需人工核對是否完整。

連線輪替、逾時會終止這次重播並回失敗狀態，不能把中斷後的檔案當完整品質測試。
