package dev.codevibes.kotoba.probe;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public class LiveAudioAdapterTest {
    static byte[] convert(short[] input, int block) {
        LiveAudioAdapter adapter = new LiveAudioAdapter();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < input.length; i += block) {
            short[] chunk = Arrays.copyOfRange(input, i, Math.min(input.length, i + block));
            adapter.accept(chunk, chunk.length, pcm -> out.write(pcm, 0, pcm.length));
        }
        return out.toByteArray();
    }
    static double rms(byte[] pcm) {
        double sum = 0;
        for (int i = 200; i < pcm.length; i += 2) {
            short sample = (short)((pcm[i] & 255) | (pcm[i+1] << 8));
            sum += (double)sample * sample;
        }
        return Math.sqrt(sum / ((pcm.length-200)/2));
    }
    static short[] tone(int hz) {
        short[] samples = new short[48000];
        for (int i = 0; i < samples.length; i++) samples[i] = (short)(12000 * Math.sin(2*Math.PI*hz*i/48000));
        return samples;
    }
    public static void main(String[] args) {
        byte[] one = convert(tone(1000), 48000), odd = convert(tone(1000), 317);
        if (one.length != 48000 || !Arrays.equals(one, odd)) throw new AssertionError("stream chunk continuity/count");
        if (rms(one) < 8000) throw new AssertionError("speech band attenuation");
        if (rms(convert(tone(16000), 317)) > 100) throw new AssertionError("alias rejection");
        if (rms(convert(new short[48000], 317)) != 0) throw new AssertionError("silence is not preserved");
        System.out.println("PASS: sample count, arbitrary packet continuity, passband, alias rejection, silence");
    }
}
