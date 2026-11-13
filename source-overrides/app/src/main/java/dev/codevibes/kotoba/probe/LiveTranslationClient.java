package dev.codevibes.kotoba.probe;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;

/** Owns one bounded live connection; all reconnects discard old audio and captions. */
final class LiveTranslationClient {
    static volatile String status = "尚未連線", caption = "";
    static volatile long captionAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS).build();
    private final String endpoint, token;
    private WebSocket socket;
    private boolean stopped, ready;
    private int generation, retries;
    private long audioBytes;
    private SubtitleBuffer subtitles = new SubtitleBuffer();
    private LiveAudioAdapter adapter;
    LiveTranslationClient(String endpoint, String token) { this.endpoint = endpoint; this.token = token; }
    synchronized void start() { connect(); }
    private synchronized void connect() {
        if (stopped) return;
        final int epoch = ++generation;
        ready = false; audioBytes = 0; subtitles = new SubtitleBuffer(); caption = "";
        status = "連線中（尚未上傳音訊）";
        Request request = new Request.Builder().url(endpoint).header("Authorization", "Bearer " + token).build();
        socket = http.newWebSocket(request, new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) {
                synchronized (LiveTranslationClient.this) {
                    if (stopped || epoch != generation) return;
                    try {
                        JSONObject e = new JSONObject(text);
                        String type = e.optString("type");
                        if (type.equals("ready")) {
                            adapter = new LiveAudioAdapter(); ready = true;
                            status = "翻譯中：未篩選音訊正在上傳";
                        } else if (type.equals("caption")) {
                            long id = e.getLong("id"), end = e.getLong("audioEndMs");
                            String current = e.optString("text");
                            if (current.length() > 2048) { retry(epoch, "字幕超出上限", false); return; }
                            long now = SystemClock.elapsedRealtime();
                            String display = subtitles.accept(id, end, audioBytes / 48,
                                    current, e.optBoolean("final"), now);
                            if (display != null) { caption = display; captionAt = now; }
                        } else if (type.equals("status")) status = "連線狀態：" + e.optString("state");
                    } catch (Exception ignored) { retry(epoch, "字幕通訊格式錯誤", false); }
                }
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                synchronized (LiveTranslationClient.this) {
                    if (epoch == generation && "rotate_session".equals(reason)) retries = 0;
                    retry(epoch, "連線中斷，音訊缺口不補送", true);
                }
            }
            @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, reason); }
            @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                int code = response == null ? 0 : response.code();
                retry(epoch, code == 401 ? "後端驗證失敗" : code == 503 ? "後端尚未設定憑證" : "連線失敗",
                        code != 401 && code != 503);
            }
        });
    }
    synchronized void accept(short[] pcm48, int count) {
        if (stopped || !ready) return; // No offline/pre-connect replay queue.
        final int epoch = generation;
        adapter.accept(pcm48, count, pcm -> {
            if (!ready || epoch != generation) return;
            if (socket.queueSize() > 48000 || !socket.send(ByteString.of(pcm))) {
                retry(epoch, "網路過慢，已捨棄過期音訊", true); return;
            }
            audioBytes += pcm.length;
        });
    }
    private synchronized void retry(int epoch, String message, boolean recoverable) {
        if (stopped || epoch != generation) return;
        ++generation; ready = false; caption = ""; subtitles = new SubtitleBuffer();
        if (socket != null) socket.cancel();
        handler.removeCallbacksAndMessages(null);
        if (!recoverable || retries >= 5) {
            status = message + "；請停止後重新開始"; return;
        }
        int delay = Math.min(16000, 1000 << retries++);
        status = message + "；" + delay / 1000 + " 秒後重連";
        handler.postDelayed(this::connect, delay);
    }
    synchronized void stop() {
        if (stopped) return;
        stopped = true; ++generation; ready = false;
        handler.removeCallbacksAndMessages(null);
        if (socket != null) socket.cancel();
        caption = ""; status = "已停止翻譯（未完成尾段已取消）";
        http.dispatcher().executorService().shutdown(); http.connectionPool().evictAll();
    }
}
