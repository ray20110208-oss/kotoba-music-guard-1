package dev.codevibes.kotoba.probe;

public class SubtitleBufferTest {
    static void equal(String expected, String actual) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        SubtitleBuffer b = new SubtitleBuffer();
        equal("今天", b.accept(1, 3000, 3200, "今天", false, 10000));
        equal("今天玩遊戲", b.accept(1, 3000, 3400, "今天玩遊戲", true, 10200));
        equal(null, b.accept(1, 3000, 3600, "不應改寫歷史", false, 10400));
        equal("今天玩遊戲\n一起", b.accept(2, 6000, 6200, "一起", false, 13000));
        equal("今天玩遊戲\n一起出發", b.accept(2, 6000, 6400, "一起出發", true, 13200));
        equal("回來了", b.accept(3, 18000, 18200, "回來了", false, 25000));
        equal(null, b.accept(2, 6000, 18200, "過期回報", true, 25001));
        equal(null, b.accept(4, 9000, 18200, "過期音訊", false, 25002));
        equal(null, b.accept(4, 20000, 18200, "超前時間", false, 25003));
        equal(null, new SubtitleBuffer().accept(1, 3000, 3000, "", true, 3000));
        System.out.println("PASS: current-caption updates, final immutability, expired history, stale IDs/audio, silence");
    }
}
