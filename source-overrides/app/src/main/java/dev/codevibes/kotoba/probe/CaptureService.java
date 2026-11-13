package dev.codevibes.kotoba.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
public final class CaptureService extends Service {
    static final String ACTION_START = "dev.codevibes.kotoba.probe.START";
    static final String ACTION_STOP = "dev.codevibes.kotoba.probe.STOP";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";
    private static final int SAMPLE_RATE = 48_000;
    private static final String CHANNEL = "kotoba_vad";
    private static final int NOTIFICATION_ID = 20;

    private volatile boolean capturing;
    private boolean liveMode;
    private volatile int captureGeneration;
    private LiveTranslationClient live;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private AudioRecord record;
    private MediaProjection projection;
    private VadSegmenter segmenter;
    private DiagnosticLogger diagnosticLogger;
    private YamnetShadowClassifier yamnet;
    private Thread worker;
    private int keptSegments;
    private int discardedSegments;
    private int segmentId;
    private int capturePreset;
    private int captureVadMode;
    private long analyzedFrames;
    private double averageGuardUs;
    private double maxGuardUs;

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Kotoba VAD", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) { stopCapture("測試已停止"); return START_NOT_STICKY; }
        if (!ACTION_START.equals(intent.getAction()) || capturing) return START_NOT_STICKY;
        final int captureEpoch = ++captureGeneration;
        liveMode = intent.getBooleanExtra("live_mode", true);
        startForegroundCompat();
        try {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = getIntentExtra(intent, EXTRA_RESULT_DATA);
            if (resultData == null) throw new IllegalStateException("缺少 MediaProjection 授權");
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(resultCode, resultData);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { if (captureEpoch == captureGeneration) stopCapture("系統已結束擷取授權"); }
            }, null);

            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME).build();
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("未授予音訊權限");
            record = new AudioRecord.Builder().setAudioFormat(format)
                    .setBufferSizeInBytes(Math.max(min * 2, 24_000))
                    .setAudioPlaybackCaptureConfig(config).build();

            int preset = getSharedPreferences("probe", MODE_PRIVATE).getInt("preset", 1);
            capturePreset = preset;
            captureVadMode = preset == 3 ? 3 : preset == 2 ? 2 : 1;
            keptSegments = 0; discardedSegments = 0; segmentId = 0; analyzedFrames = 0;
            averageGuardUs = 0; maxGuardUs = 0;
            clearPreviousSegments();
            getSharedPreferences("probe", MODE_PRIVATE).edit()
                    .putInt("kept", 0).putInt("discarded", 0)
                    .putInt("segment_count", 0).apply();
            if (liveMode) {
                String endpoint = intent.getStringExtra("relay_url");
                String token = intent.getStringExtra("relay_token");
                if (endpoint == null || !endpoint.startsWith("wss://") || token == null || token.length() < 32)
                    throw new IllegalArgumentException("缺少安全後端設定");
                live = new LiveTranslationClient(endpoint, token); live.start();
            } else {
            segmenter = new VadSegmenter(preset, new SegmentListener());
            yamnet = new YamnetShadowClassifier(this);
            diagnosticLogger = new DiagnosticLogger(getFilesDir());
            getSharedPreferences("probe", MODE_PRIVATE).edit()
                    .putString("diagnostic_file", diagnosticLogger.getFile().getAbsolutePath()).apply();
            }
            record.startRecording(); capturing = true;
            writeBase(liveMode ? "即時翻譯擷取中" : "離線音訊診斷中", true);
            worker = new Thread(this::captureLoop, "kotoba-vad-capture"); worker.start();
        } catch (Throwable error) {
            writeBase("啟動失敗：" + error.getClass().getSimpleName() + " — " + error.getMessage(), false);
            stopCapture("啟動失敗：" + error.getClass().getSimpleName());
        }
        return START_NOT_STICKY;
    }

    private void captureLoop() {
        short[] input = new short[2880]; // 60 ms at 48 kHz
        AudioResampler resampler = new AudioResampler();
        short[] pending = new short[0];
        final AudioRecord activeRecord = record;
        final int epoch = captureGeneration;
        long diagnosticSamples = 0;
        try {
        while (capturing) {
            int count = activeRecord.read(input, 0, input.length, AudioRecord.READ_BLOCKING);
            if (count < 0) throw new IllegalStateException("AudioRecord " + count);
            if (count == 0) continue;
            if (liveMode) { if (live != null) live.accept(input, count); continue; }
            diagnosticSamples += count;
            if (diagnosticSamples >= 48000L * 300) {
                mainHandler.post(() -> { if (epoch == captureGeneration) stopCapture("離線診斷達 5 分鐘安全上限；未完成片段不匯出"); });
                break;
            }
            short[] down = resampler.downsample48To16(input, count);
            short[] joined = new short[pending.length + down.length];
            System.arraycopy(pending, 0, joined, 0, pending.length);
            System.arraycopy(down, 0, joined, pending.length, down.length);
            int cursor = 0;
            while (joined.length - cursor >= 320) {
                short[] frame = new short[320];
                System.arraycopy(joined, cursor, frame, 0, 320);
                if (yamnet != null) yamnet.accept(frame);
                YamnetShadowClassifier.Snapshot snapshot = yamnet == null
                        ? YamnetShadowClassifier.Snapshot.STARTING : yamnet.snapshot();
                segmenter.accept(frame, snapshot); cursor += 320;
            }
            pending = new short[joined.length - cursor];
            System.arraycopy(joined, cursor, pending, 0, pending.length);
        }
        } catch (Throwable error) {
            if (capturing) mainHandler.post(() -> { if (epoch == captureGeneration) stopCapture("音訊擷取中斷"); });
        }
    }

    private final class SegmentListener implements VadSegmenter.Listener {
        @Override public void onFrame(VadSegmenter.Status s) {
            YamnetShadowClassifier.Snapshot y = yamnet == null
                    ? YamnetShadowClassifier.Snapshot.STARTING : yamnet.snapshot();
            analyzedFrames++;
            double guardUs = s.music.processingNanos / 1000.0;
            averageGuardUs += (guardUs - averageGuardUs) / analyzedFrames;
            maxGuardUs = Math.max(maxGuardUs, guardUs);
            if (diagnosticLogger != null)
                diagnosticLogger.log(s, y, capturePreset, captureVadMode);
            getSharedPreferences("probe", MODE_PRIVATE).edit()
                    .putString("vad_state", s.state).putBoolean("speech", s.speech)
                    .putBoolean("raw_vad", s.rawVad).putBoolean("gate_pass", s.gatePass)
                    .putLong("db", Double.doubleToRawLongBits(s.db))
                    .putLong("noise", Double.doubleToRawLongBits(s.noiseDb))
                    .putLong("gate", Double.doubleToRawLongBits(s.gateDb))
                    .putLong("background_music_score", Double.doubleToRawLongBits(s.music.backgroundMusicScore))
                    .putLong("foreground_voice_score", Double.doubleToRawLongBits(s.music.foregroundVoiceScore))
                    .putBoolean("background_music", s.music.backgroundMusic)
                    .putBoolean("background_ready", s.music.backgroundReady)
                    .putBoolean("voice_onset", s.music.voiceOnset)
                    .putBoolean("voice_override", s.music.voiceOverride)
                    .putBoolean("voice_armed", s.music.armed)
                    .putInt("override_frames", s.music.overrideFrames)
                    .putLong("vad_ratio", Double.doubleToRawLongBits(s.music.vadRatio))
                    .putLong("energy_var", Double.doubleToRawLongBits(s.music.energyVariation))
                    .putLong("spectral_flux", Double.doubleToRawLongBits(s.music.spectralFlux))
                    .putLong("harmonic", Double.doubleToRawLongBits(s.music.harmonicPersistence))
                    .putLong("rhythm", Double.doubleToRawLongBits(s.music.rhythmPeriodicity))
                    .putLong("energy_delta", Double.doubleToRawLongBits(s.music.energyDelta))
                    .putLong("flux_z", Double.doubleToRawLongBits(s.music.fluxZ))
                    .putLong("spectrum_residual", Double.doubleToRawLongBits(s.music.spectrumResidual))
                    .putLong("voice_band_delta", Double.doubleToRawLongBits(s.music.voiceBandDelta))
                    .putLong("guard_avg_us", Double.doubleToRawLongBits(averageGuardUs))
                    .putLong("guard_max_us", Double.doubleToRawLongBits(maxGuardUs)).apply();
            getSharedPreferences("probe", MODE_PRIVATE).edit()
                    .putLong("speech_evidence", Double.doubleToRawLongBits(s.fusion.speechEvidence))
                    .putLong("music_evidence", Double.doubleToRawLongBits(s.fusion.musicEvidence))
                    .putLong("yamnet_speech_rolling", Double.doubleToRawLongBits(s.fusion.yamnetSpeechRolling))
                    .putLong("yamnet_music_rolling", Double.doubleToRawLongBits(s.fusion.yamnetMusicRolling))
                    .putBoolean("fusion_ready", s.fusion.yamnetHistoryReady)
                    .putString("fusion_verdict", s.fusion.verdict.name())
                    .putString("fusion_reason", s.fusion.reason).apply();
            getSharedPreferences("probe", MODE_PRIVATE).edit()
                    .putBoolean("yamnet_ready", y.ready)
                    .putString("yamnet_top_label", y.topLabel)
                    .putString("yamnet_top_five", y.topFive)
                    .putString("yamnet_error", y.error)
                    .putLong("yamnet_top_score", Double.doubleToRawLongBits(y.topScore))
                    .putLong("yamnet_speech", Double.doubleToRawLongBits(y.speechScore))
                    .putLong("yamnet_music", Double.doubleToRawLongBits(y.musicScore))
                    .putLong("yamnet_game", Double.doubleToRawLongBits(y.gameScore))
                    .putLong("yamnet_last_ms", Double.doubleToRawLongBits(y.inferenceMs))
                    .putLong("yamnet_avg_ms", Double.doubleToRawLongBits(y.averageMs))
                    .putLong("yamnet_max_ms", Double.doubleToRawLongBits(y.maxMs))
                    .putLong("yamnet_count", y.inferenceCount)
                    .putLong("yamnet_dropped", y.droppedWindows).apply();
        }

        @Override public void onSegment(VadSegmenter.Segment s) {
            segmentId++;
            if (s.kept) keptSegments++; else discardedSegments++;
            String summary = String.format("#%d｜%s｜%.2fs｜核心 %.2fs｜人聲 %.0f%%｜%s",
                    segmentId, s.kept ? "保留" : "丟棄",
                    s.totalMs / 1000.0, s.coreSpeechMs / 1000.0,
                    s.coreSpeechRatio * 100.0, s.reason);
            File file = new File(getCacheDir(), "segment-" + segmentId + ".wav");
            String path = "";
            try {
                WavWriter.writeMono16(file, s.samples, 16_000);
                path = file.getAbsolutePath();
            } catch (Exception error) {
                getSharedPreferences("probe", MODE_PRIVATE).edit().putString("file_error", error.toString()).apply();
            }
            int historyIndex = segmentId - 1;
            getSharedPreferences("probe", MODE_PRIVATE).edit()
                    .putInt("kept", keptSegments).putInt("discarded", discardedSegments)
                    .putString("segment_summary_" + historyIndex, summary)
                    .putString("segment_path_" + historyIndex, path)
                    .putInt("segment_count", segmentId).apply();
        }
    }

    private void clearPreviousSegments() {
        File[] files = getCacheDir().listFiles((dir, name) -> name.startsWith("segment-") && name.endsWith(".wav"));
        if (files != null) for (File file : files) if (file.exists()) file.delete();
    }

    private void startForegroundCompat() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(liveMode ? "Kotoba 即時翻譯" : "Kotoba 離線診斷")
                .setContentText(liveMode ? "未篩選音訊將上傳翻譯；可在 App 停止" : "本機診斷；沒有音訊上傳")
                .setContentIntent(open).setOngoing(true).build();
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    private void stopCapture(String state) {
        ++captureGeneration; capturing = false; releaseResources(); writeBase(state, false);
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }

    private void releaseResources() {
        capturing = false;
        if (live != null) { live.stop(); }
        if (record != null) { try { record.stop(); } catch (Throwable ignored) {} }
        if (worker != null && worker != Thread.currentThread()) {
            try { worker.join(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        worker = null; live = null;
        if (record != null) { record.release(); record = null; }
        if (segmenter != null) { try { segmenter.close(); } catch (Throwable ignored) {} segmenter = null; }
        if (yamnet != null) { try { yamnet.close(); } catch (Throwable ignored) {} yamnet = null; }
        if (diagnosticLogger != null) { diagnosticLogger.close(); diagnosticLogger = null; }
        if (projection != null) { MediaProjection old = projection; projection = null; try { old.stop(); } catch (Throwable ignored) {} }
    }

    private void writeBase(String state, boolean running) {
        getSharedPreferences("probe", MODE_PRIVATE).edit().putString("state", state).putBoolean("running", running).apply();
    }

    @SuppressWarnings("deprecation")
    private static Intent getIntentExtra(Intent source, String key) {
        if (Build.VERSION.SDK_INT >= 33) return source.getParcelableExtra(key, Intent.class);
        return source.getParcelableExtra(key);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { if (capturing) { capturing = false; releaseResources(); } super.onDestroy(); }
}
