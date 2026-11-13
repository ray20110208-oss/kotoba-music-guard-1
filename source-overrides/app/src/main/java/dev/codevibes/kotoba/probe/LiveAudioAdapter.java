package dev.codevibes.kotoba.probe;

/** Isolated streaming 48 -> 24 kHz FIR adapter. Never gates speech or marks sentence ends. */
final class LiveAudioAdapter {
    private static final int TAPS = 63;
    private final double[] coefficients = new double[TAPS];
    private final short[] history = new short[TAPS];
    private final byte[] packet = new byte[9600]; // 200 ms of PCM16 mono at 24 kHz
    private int cursor, phase, used;
    interface Sink { void accept(byte[] pcm); }
    LiveAudioAdapter() {
        double sum = 0, cutoff = 10000.0 / 48000;
        for (int i = 0; i < TAPS; i++) {
            int n = i - (TAPS - 1) / 2;
            double sinc = n == 0 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * n) / (Math.PI * n);
            coefficients[i] = sinc * (0.54 - 0.46 * Math.cos(2 * Math.PI * i / (TAPS - 1)));
            sum += coefficients[i];
        }
        for (int i = 0; i < TAPS; i++) coefficients[i] /= sum;
    }
    void accept(short[] input, int count, Sink sink) {
        for (int i = 0; i < count; i++) {
            history[cursor] = input[i]; cursor = (cursor + 1) % TAPS;
            if (++phase < 2) continue;
            phase = 0;
            double value = 0;
            for (int j = 0; j < TAPS; j++) value += coefficients[j] * history[(cursor - 1 - j + TAPS) % TAPS];
            int sample = (int) Math.max(-32768, Math.min(32767, Math.round(value)));
            packet[used++] = (byte) sample; packet[used++] = (byte) (sample >> 8);
            if (used == packet.length) { sink.accept(packet.clone()); used = 0; }
        }
    }
}
