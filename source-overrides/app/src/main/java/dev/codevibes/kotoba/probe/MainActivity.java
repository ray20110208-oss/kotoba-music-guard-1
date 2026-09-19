package dev.codevibes.kotoba.probe;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_AUDIO = 10, REQUEST_CAPTURE = 11, REQUEST_EXPORT = 12;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView state, vad, metrics, music, yamnet, council, counts, presetLabel, markStatus, playbackStatus;
    private LinearLayout segmentHistory;
    private ProgressBar meter;
    private Button start;
    private MediaPlayer player;
    private int renderedSegmentCount = -1;
    private TextView batteryStatus;
    private double batteryCelsius = Double.NaN;
    private long batteryReportElapsed = -1;
    private boolean batteryReceiverRegistered;
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) return;
            int temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            batteryCelsius = temperature == Integer.MIN_VALUE
                    || !intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
                    ? Double.NaN : temperature / 10.0;
            // A sticky delivery may predate registration. Never label it as a fresh sample.
            batteryReportElapsed = isInitialStickyBroadcast() ? -1 : SystemClock.elapsedRealtime();
            renderBatteryStatus();
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            SharedPreferences p = getSharedPreferences("probe", MODE_PRIVATE);
            boolean running = p.getBoolean("running", false);
            double db = bits(p, "db", -120), noise = bits(p, "noise", -60), gate = bits(p, "gate", -54);
            state.setText(p.getString("state", "尚未開始"));
            vad.setText((p.getBoolean("speech", false) ? "● " : "○ ") + p.getString("vad_state", "等待音訊"));
            vad.setTextColor(p.getBoolean("speech", false) ? Color.rgb(25, 135, 84) : Color.rgb(90, 96, 112));
            metrics.setText(String.format("音量 %.1f dBFS\n背景 %.1f dBFS｜閘門 %.1f dBFS\n16 kHz｜20 ms｜WebRTC VAD", db, noise, gate));
            double backgroundScore = bits(p, "background_music_score", 0);
            double foregroundScore = bits(p, "foreground_voice_score", 0);
            double ratio = bits(p, "vad_ratio", 0);
            double harmonic = bits(p, "harmonic", 0);
            double rhythm = bits(p, "rhythm", 0);
            double fluxZ = bits(p, "flux_z", 0), residual = bits(p, "spectrum_residual", 0);
            double bandDelta = bits(p, "voice_band_delta", 0), energyDelta = bits(p, "energy_delta", 0);
            double avgUs = bits(p, "guard_avg_us", 0), maxUs = bits(p, "guard_max_us", 0);
            renderBatteryStatus();
            music.setText(String.format("Background Music %.2f｜%s\nForeground Voice %.2f｜%s｜%s\nFlux-z %.2f｜Residual %.3f｜Voice band %+.1f dB\nEnergy Δ %+.1f dB｜VAD %.0f%%｜Harmonic %.2f｜Rhythm %.2f\nGuard 平均 %.0f µs｜最大 %.0f µs",
                    backgroundScore, p.getBoolean("background_music", false) ? "音樂背景" : "未鎖定背景",
                    foregroundScore, p.getBoolean("voice_override", false) ? "語音覆寫" : "背景抑制",
                    p.getBoolean("voice_armed", true) ? "已武裝" : "等待重置",
                    fluxZ, residual, bandDelta, energyDelta, ratio * 100, harmonic, rhythm, avgUs, maxUs));
            double yTop = bits(p, "yamnet_top_score", 0), ySpeech = bits(p, "yamnet_speech", 0);
            double yMusic = bits(p, "yamnet_music", 0), yGame = bits(p, "yamnet_game", 0);
            double yLast = bits(p, "yamnet_last_ms", 0), yAvg = bits(p, "yamnet_avg_ms", 0);
            double yMax = bits(p, "yamnet_max_ms", 0);
            String yError = p.getString("yamnet_error", "");
            yamnet.setText(String.format("YAMNet Shadow｜%s %.0f%%\nSpeech %.0f%%｜Music %.0f%%｜Game/FX %.0f%%\n推論 %.1f ms｜平均 %.1f ms｜最大 %.1f ms｜丟窗 %d%s",
                    p.getString("yamnet_top_label", "初始化中"), yTop * 100,
                    ySpeech * 100, yMusic * 100, yGame * 100,
                    yLast, yAvg, yMax, p.getLong("yamnet_dropped", 0),
                    yError.isEmpty() ? "" : "\n錯誤：" + yError));
            double speechEvidence = bits(p, "speech_evidence", 0);
            double musicEvidence = bits(p, "music_evidence", 0);
            double speechRolling = bits(p, "yamnet_speech_rolling", 0);
            double musicRolling = bits(p, "yamnet_music_rolling", 0);
            String verdict = p.getString("fusion_verdict", "AMBIGUOUS");
            String reason = p.getString("fusion_reason", "yamnet_warmup");
            council.setText(String.format("Evidence Council｜%s\nSpeech %.0f%%｜Music %.0f%%｜%s\nYAMNet rolling：Speech %.0f%%｜Music %.0f%%\n理由：%s",
                    verdict, speechEvidence * 100, musicEvidence * 100,
                    p.getBoolean("fusion_ready", false) ? "三票就緒" : "暖機中",
                    speechRolling * 100, musicRolling * 100, reason));
            meter.setProgress((int) Math.max(0, Math.min(100, (db + 60) * 100 / 60)));
            counts.setText("保留 " + p.getInt("kept", 0) + "｜丟棄 " + p.getInt("discarded", 0));
            renderSegmentHistory(p);
            int preset = p.getInt("preset", 1);
            presetLabel.setText("Preset：" + presetName(preset));
            start.setText(running ? "停止 VAD 測試" : "開始 VAD 測試");
            handler.postDelayed(this, 250);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); setContentView(buildUi());
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { toggle(); }
        });
        presetLabel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { cyclePreset(); }
        });
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(44), dp(22), dp(28)); root.setBackgroundColor(Color.rgb(245, 246, 250));
        TextView title = tv("Kotoba Music Guard", 27, Color.rgb(20, 24, 35)); title.setTypeface(null, 1); root.addView(title);
        root.addView(tv("v0.2.5-alpha5-battery1｜自然句尾切片；投票式證據融合。沒有網路權限。", 15, Color.rgb(78, 85, 102)), margin(0, 10, 0, 22));
        state = tv("尚未開始", 21, Color.rgb(20, 24, 35)); root.addView(state, margin(0, 0, 0, 8));
        vad = tv("○ 等待音訊", 18, Color.rgb(90, 96, 112)); root.addView(vad, margin(0, 0, 0, 12));
        meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); meter.setMax(100); root.addView(meter, lp(-1, dp(16)));
        metrics = tv("音量 —", 15, Color.rgb(80, 86, 102)); root.addView(metrics, margin(0, 12, 0, 18));
        batteryStatus = tv("電池溫度 —｜等待系統回報", 14, Color.rgb(80, 86, 102));
        root.addView(batteryStatus, margin(0, 0, 0, 18));
        music = tv("Music Guard 尚未取得資料", 14, Color.rgb(80, 86, 102));
        music.setPadding(dp(12), dp(12), dp(12), dp(12)); music.setBackgroundColor(Color.WHITE);
        root.addView(music, margin(0, 0, 0, 18));
        yamnet = tv("YAMNet Shadow 尚未初始化", 14, Color.rgb(80, 86, 102));
        yamnet.setPadding(dp(12), dp(12), dp(12), dp(12)); yamnet.setBackgroundColor(Color.WHITE);
        root.addView(yamnet, margin(0, 0, 0, 18));
        council = tv("Evidence Council 尚未取得資料", 14, Color.rgb(80, 86, 102));
        council.setPadding(dp(12), dp(12), dp(12), dp(12)); council.setBackgroundColor(Color.WHITE);
        root.addView(council, margin(0, 0, 0, 18));
        presetLabel = tv("Preset：Chat / Mode 1", 17, Color.rgb(45, 75, 210)); presetLabel.setPadding(dp(12), dp(14), dp(12), dp(14)); presetLabel.setBackgroundColor(Color.WHITE); root.addView(presetLabel, margin(0, 0, 0, 18));
        TextView segmentTitle = tv("完成片段（點選回聽）", 16, Color.rgb(20, 24, 35)); segmentTitle.setTypeface(null, 1); root.addView(segmentTitle);
        counts = tv("保留 0｜丟棄 0", 14, Color.rgb(100, 106, 122)); root.addView(counts, margin(0, 0, 0, 14));
        segmentHistory = new LinearLayout(this); segmentHistory.setOrientation(LinearLayout.VERTICAL);
        root.addView(segmentHistory, lp(-1, -2));
        playbackStatus = tv("還沒有完成的片段", 13, Color.rgb(100, 106, 122));
        root.addView(playbackStatus, margin(0, 6, 0, 0));
        start = new Button(this); start.setText("開始 VAD 測試"); start.setAllCaps(false); root.addView(start, margin(0, 14, 0, 0));
        root.addView(tv("推薦測試：同一段素材依序測純 BGM、說話＋遊戲、BGM＋說話；注意 Decision 與實際時間軸是否一致。", 13, Color.rgb(105, 111, 128)), margin(0, 18, 0, 12));
        TextView labelTitle = tv("人工標記（寫入診斷 CSV）", 15, Color.rgb(20, 24, 35));
        labelTitle.setTypeface(null, 1); root.addView(labelTitle, margin(0, 22, 0, 6));
        root.addView(markButton("純音樂", "music"));
        root.addView(markButton("說話", "speech"));
        root.addView(markButton("BGM＋說話", "speech_with_bgm"));
        root.addView(markButton("遊戲音效／誤判", "game_or_false_positive"));
        root.addView(markButton("漏判／吃句首", "miss_or_clipped_start"));
        markStatus = tv("尚未標記", 13, Color.rgb(100, 106, 122)); root.addView(markStatus, margin(0, 8, 0, 10));
        Button export = new Button(this); export.setText("匯出診斷 CSV"); export.setAllCaps(false);
        export.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { exportCsv(); }
        });
        root.addView(export, lp(-1, dp(50)));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottom = insets.getSystemWindowInsetBottom();
            view.setPadding(dp(22), dp(44), dp(22), dp(28) + bottom);
            return insets;
        });
        scroll.addView(root); return scroll;
    }

    private void toggle() {
        boolean running = getSharedPreferences("probe", MODE_PRIVATE).getBoolean("running", false);
        if (running) { startService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_STOP)); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO); return;
        }
        requestProjection();
    }

    private void requestProjection() {
        startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    private void cyclePreset() {
        SharedPreferences p = getSharedPreferences("probe", MODE_PRIVATE);
        int next = p.getInt("preset", 1) % 4 + 1; p.edit().putInt("preset", next).apply();
    }

    private void playSegment(String path, String summary) {
        try {
            if (player != null) player.release();
            player = new MediaPlayer(); player.setDataSource(path); player.prepare(); player.start();
            playbackStatus.setText("正在播放：" + summary);
        } catch (Exception error) { playbackStatus.setText("播放失敗：" + error.getMessage()); }
    }

    private void renderSegmentHistory(SharedPreferences preferences) {
        int count = preferences.getInt("segment_count", 0);
        if (count == renderedSegmentCount) return;
        renderedSegmentCount = count;
        segmentHistory.removeAllViews();
        if (count == 0) {
            playbackStatus.setText("還沒有完成的片段");
            return;
        }
        for (int i = 0; i < count; i++) {
            final String path = preferences.getString("segment_path_" + i, "");
            final String summary = preferences.getString("segment_summary_" + i, "片段 #" + (i + 1));
            Button button = new Button(this);
            button.setText(summary);
            button.setAllCaps(false);
            button.setEnabled(new File(path).exists());
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { playSegment(path, summary); }
            });
            segmentHistory.addView(button, margin(0, 0, 0, 6));
        }
    }

    private Button markButton(String title, final String label) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { appendMark(label); }
        });
        return button;
    }

    private void appendMark(String label) {
        File file = new File(getSharedPreferences("probe", MODE_PRIVATE).getString("diagnostic_file", ""));
        try {
            DiagnosticLogger.appendLabel(file, label);
            markStatus.setText("已標記：" + label);
        } catch (Exception error) { markStatus.setText("標記失敗：" + error.getMessage()); }
    }

    private void exportCsv() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("text/csv").putExtra(Intent.EXTRA_TITLE, "kotoba-v025-evidence-fusion.csv")
                .addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    private void copyDiagnostics(Uri destination) {
        File source = new File(getSharedPreferences("probe", MODE_PRIVATE).getString("diagnostic_file", ""));
        try {
            FileInputStream input = new FileInputStream(source);
            OutputStream output = getContentResolver().openOutputStream(destination, "w");
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            input.close(); output.close(); markStatus.setText("CSV 已匯出");
        } catch (Exception error) { markStatus.setText("匯出失敗：" + error.getMessage()); }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == REQUEST_AUDIO && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) requestProjection();
    }

    @Override protected void onActivityResult(int code, int resultCode, Intent data) {
        super.onActivityResult(code, resultCode, data);
        if (code == REQUEST_CAPTURE && resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode).putExtra(CaptureService.EXTRA_RESULT_DATA, data);
            startForegroundService(service);
        }
        if (code == REQUEST_EXPORT && resultCode == RESULT_OK && data != null && data.getData() != null)
            copyDiagnostics(data.getData());
    }

    @Override protected void onStart() {
        super.onStart();
        batteryCelsius = Double.NaN;
        batteryReportElapsed = -1;
        renderBatteryStatus();
        // This filter receives only the protected system battery broadcast.
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        batteryReceiverRegistered = true;
    }
    @Override protected void onStop() {
        if (batteryReceiverRegistered) {
            unregisterReceiver(batteryReceiver);
            batteryReceiverRegistered = false;
        }
        super.onStop();
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { if (player != null) player.release(); super.onDestroy(); }

    private static double bits(SharedPreferences p, String key, double fallback) {
        return Double.longBitsToDouble(p.getLong(key, Double.doubleToRawLongBits(fallback)));
    }
    private static String presetName(int preset) {
        if (preset == 2) return "Game / Mode 2";
        if (preset == 3) return "Noisy / Mode 3";
        if (preset == 4) return "BGM / Mode 1＋Council";
        return "Chat / Mode 1";
    }
    private void renderBatteryStatus() {
        String temperature = Double.isNaN(batteryCelsius) ? "—（系統未提供）"
                : String.format(Locale.TAIWAN, "%.1f°C", batteryCelsius);
        String report = batteryReportElapsed < 0
                ? "系統保留值；等待後續電池回報"
                : "距上次收到電池回報 " + ((SystemClock.elapsedRealtime() - batteryReportElapsed) / 1000) + " 秒";
        batteryStatus.setText("電池溫度 " + temperature + "\n" + report
                + "\n回報時間不等於感測器量測時間；更新頻率由系統決定。");
    }
    private TextView tv(String text, int sp, int color) { TextView v = new TextView(this); v.setText(text); v.setTextSize(sp); v.setTextColor(color); v.setLineSpacing(0, 1.15f); return v; }
    private LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) { LinearLayout.LayoutParams p = lp(-1, -2); p.setMargins(dp(l), dp(t), dp(r), dp(b)); return p; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
