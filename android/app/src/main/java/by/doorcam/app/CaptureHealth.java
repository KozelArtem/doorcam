package by.doorcam.app;

final class CaptureHealth {
    private static final long FRAME_TIMEOUT = 5_000, SEGMENT_TIMEOUT = 20_000;
    private final long started;
    private volatile long frame = -1, segment = -1;

    CaptureHealth(long started) { this.started = started; }

    void frame(long now) { frame = now; }
    void segment(long now) { segment = now; }
    boolean ready() { return frame >= 0 && segment >= 0; }

    String failure(long now) {
        if (now - Math.max(started, frame) >= FRAME_TIMEOUT) return "Нет кадров с камеры";
        if (now - Math.max(started, segment) >= SEGMENT_TIMEOUT) return "Кодировщик не выдаёт видео";
        return null;
    }
}
