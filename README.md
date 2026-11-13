# Kotoba Music Guard — Realtime subtitle prototype

此分支：`0.3.0-alpha1-realtime` / versionCode **14**。未完成真實 API／真機驗收。

主線為 Android 未篩選的播放音訊 → 後端 WebSocket → 支援音訊的 Realtime 模型 → App 內繁體中文字幕。
不串接本地 VAD、獨立 ASR 或文字翻譯 API。離線診斷另保留，單次最多 5 分鐘。

- [核實現況、API 選擇、價格及限制](docs/realtime-audit.md)
- [安全設定後端與重播測試](source-overrides/backend/README.md)
- [電池監控修正背景](source-overrides/README-battery-monitor.md)

## 真正的建置來源

`Kotoba-Music-Guard-source-v0.2.5-alpha5.zip.part-*` 按順序合併、核對固定 SHA256，再套用 `source-overrides/`。
請修改 overrides，不要改只存在於解壓目錄的副本。舊 ZIP 內的產品／後端文件不是此分支現況。
保留封存架構，沒有將整個專案搬進 Git。

```sh
python3 scripts/prepare_source.py work/project
cd work/project
# 需要 JDK 17、Android SDK 35
bash gradlew assembleDebug
cd backend
npm ci
npm test
```

`prepare_source.py` 不會覆寫既有目的目錄。GitHub Actions 使用相同準備程序、後端測試及音訊轉接器測試。
分支基於 `fix/battery-monitor`；保留其可見畫面接收電池廣播的改動，不代表原 main 已合併該分支。

## App 使用

填入 `wss://你的後端/v1/live` 與後端存取碼，啟用即時翻譯，再授權音訊擷取。
OpenAI API 金鑰只放後端。App 存取碼只留本次記憶體、不打包、不寫偏好設定；重開 App 需重填。
字幕目前顯示在 App 內，可用分割畫面；尚無跨 App 浮動字幕。
關閉即時翻譯開關則執行既有離線診斷，不上傳音訊。

擷取取決於來源 App 是否允許 AudioPlaybackCapture，受保護內容無法保證擷取。
停止會立即取消連線與未完成尾段；重連期間的音訊不補送，避免舊字幕追趕。
