package dev.codevibes.kotoba.probe;

/** Pure subtitle state; final captions are immutable and old context expires. */
final class SubtitleBuffer {
    private long id = -1, previousAt;
    private boolean finalized;
    private String previous = "";
    String accept(long nextId, long audioEndMs, long sentAudioMs, String text, boolean done, long now) {
        if (nextId < id || (nextId == id && finalized)
                || sentAudioMs - audioEndMs > 6000 || audioEndMs > sentAudioMs + 200) return null;
        if (nextId != id) { id = nextId; finalized = false; }
        if (now - previousAt >= 6000) previous = "";
        String display = text.isEmpty() ? null : (previous.isEmpty() ? "" : previous + "\n") + text;
        if (done) {
            finalized = true;
            if (!text.isEmpty()) { previous = text; previousAt = now; }
        }
        return display;
    }
}
