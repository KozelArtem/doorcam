package by.doorcam.app;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

final class Archive {
    private static final long PRE_ROLL_MS = 4_000, TAIL_MS = 8_000, LIVE_MS = 16_000;
    private static final long TELEGRAM_MOTION_MS = 120_000;
    static final long TELEGRAM_COMPLETE_BYTES = 24L * 1024 * 1024;
    private static final Logger LOG = Logger.getLogger("Doorcam");
    final File archiveDir, liveDir;
    private final File outbox;
    private final long limit;
    private final boolean queueVideos;
    static final long FREE_RESERVE = 128L * 1024 * 1024;
    private final Deque<Finished> recent = new ArrayDeque<>();
    private final Deque<File> liveFiles = new ArrayDeque<>();

    interface Writer { void write(File destination) throws IOException; }

    static final class Segment {
        final long start;
        final Writer writer;
        Segment(long start, Writer writer) { this.start = start; this.writer = writer; }
        File store(File dir) throws IOException {
            File result = new File(dir, start + ".mp4");
            if (!result.exists()) {
                File temp = new File(dir, start + ".part");
                try {
                    writer.write(temp);
                    Files.move(temp.toPath(), result.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(temp.toPath());
                }
            }
            return result;
        }
    }
    private static final class Finished {
        final Segment segment;
        final long end;
        Finished(Segment segment, long end) { this.segment = segment; this.end = end; }
    }

    private final Deque<Event> events = new ArrayDeque<>();
    private final Map<String, Long> growing = new ConcurrentHashMap<>();
    private long size;
    private Event active;

    private static final class Event {
        final File dir;
        final Deque<File> clips = new ArrayDeque<>();
        File queuedClip;
        boolean videoQueued, completionQueued, prefixEvicted;
        long bytes, started, lastMotion = Long.MIN_VALUE + TAIL_MS;
        Event(File dir) { this.dir = dir; }
    }

    Archive(File root, long limit) throws IOException {
        this(root, limit, true);
    }

    Archive(File root, long limit, boolean queueVideos) throws IOException {
        this.limit = limit;
        this.queueVideos = queueVideos;
        archiveDir = directory(root, "archive");
        liveDir = directory(root, "live");
        outbox = directory(root, "outbox");
        for (File old : children(liveDir)) Files.deleteIfExists(old.toPath());
        for (File f : children(outbox)) {
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            if (name.endsWith(".part") || (dot > 0 && (name.endsWith(".jpg") || name.endsWith(".mp4") || name.endsWith(".done")) &&
                    !new File(archiveDir, name.substring(0, dot)).isDirectory()))
                Files.deleteIfExists(f.toPath());
        }
        for (File dir : children(archiveDir)) {
            if (!dir.isDirectory()) continue;
            Event event = new Event(dir);
            for (File f : children(dir)) {
                if (f.getName().endsWith(".part")) {
                    Files.deleteIfExists(f.toPath());
                    continue;
                }
                if (f.getName().endsWith(".mp4")) event.clips.add(f);
                event.bytes += f.length();
            }
            events.add(event);
            size += event.bytes;
        }
        trim();
    }

    private static File directory(File parent, String name) throws IOException {
        File result = new File(parent, name);
        Files.createDirectories(result.toPath());
        return result;
    }

    private static File[] children(File dir) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) throw new IOException("Не удалось прочитать " + dir.getName());
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    boolean needsPhoto(long now) { return active == null || now - active.lastMotion > TAIL_MS; }

    boolean isGrowing(String id) { return growing.containsKey(id); }

    boolean endCapture() {
        boolean queued = active != null && queueCompletion(active);
        active = null;
        growing.clear();
        return queued;
    }

    boolean motion(long now, byte[] jpeg) throws IOException {
        boolean start = needsPhoto(now);
        if (!start) {
            active.lastMotion = now;
            growing.put(active.dir.getName(), now);
            return false;
        }
        long id = now;
        while (new File(archiveDir, Long.toString(id)).exists() || new File(outbox, id + ".jpg").exists() ||
                new File(outbox, id + ".mp4").exists()) id++;
        active = new Event(directory(archiveDir, Long.toString(id)));
        active.started = now;
        active.lastMotion = now;
        growing.put(active.dir.getName(), now);
        events.add(active);
        File preview = new File(active.dir, "preview.jpg");
        writeAtomic(preview, jpeg);
        active.bytes += jpeg.length;
        size += jpeg.length;
        for (Finished clip : recent) {
            if (clip.end >= now - PRE_ROLL_MS) save(active, clip.segment);
        }
        trim();
        return true;
    }

    boolean finish(Segment segment, long end) throws IOException {
        recent.add(new Finished(segment, end));
        while (recent.peek().end < end - PRE_ROLL_MS) recent.remove();
        trim();
        boolean queued = false;
        for (Iterator<Event> iterator = events.descendingIterator(); iterator.hasNext();) {
            Event event = iterator.next();
            if (event.lastMotion + TAIL_MS < segment.start) {
                if (event.queuedClip != null && !event.videoQueued && event.queuedClip.isFile()) queued |= queueVideo(event);
                queued |= queueCompletion(event);
                break;
            }
            if (end < event.started - PRE_ROLL_MS) continue;
            File clip = save(event, segment);
            if (queueVideos && !event.videoQueued && end >= event.started) {
                if (event.queuedClip == null || !event.queuedClip.isFile()) event.queuedClip = clip;
                queued |= queueVideo(event);
            }
            if (end > event.lastMotion + TAIL_MS) queued |= queueCompletion(event);
        }
        growing.values().removeIf(motion -> end >= motion + TAIL_MS);
        trim();
        return queued;
    }

    private boolean queueCompletion(Event event) {
        if (!event.videoQueued || event.completionQueued) return false;
        File marker = new File(outbox, event.dir.getName() + ".done");
        StringBuilder clips = new StringBuilder();
        if (!event.prefixEvicted && event.lastMotion - event.started <= TELEGRAM_MOTION_MS && event.bytes <= TELEGRAM_COMPLETE_BYTES) {
            for (File clip : event.clips) clips.append(clip.getName()).append('\n');
        }
        try {
            writeAtomic(marker, clips.toString().getBytes(StandardCharsets.US_ASCII));
            event.completionQueued = true;
            return true;
        } catch (IOException error) {
            LOG.log(Level.WARNING, "Не удалось поставить обновление Telegram в очередь", error);
            return false;
        }
    }

    private boolean queueVideo(Event event) {
        File pending = new File(outbox, event.dir.getName() + ".mp4");
        File temporary = new File(outbox, pending.getName() + ".part");
        try {
            if (outbox.getUsableSpace() - FREE_RESERVE < event.queuedClip.length())
                throw new IOException("Недостаточно памяти для очереди Telegram");
            try {
                Files.copy(event.queuedClip.toPath(), temporary.toPath());
                Files.move(temporary.toPath(), pending.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary.toPath());
            }
            event.videoQueued = true;
            return true;
        } catch (IOException | UnsupportedOperationException error) {
            LOG.log(Level.WARNING, "Не удалось поставить видео в очередь Telegram", error);
            return false;
        }
    }

    File finishLive(Segment segment, long end) throws IOException {
        while (!liveFiles.isEmpty() && epoch(liveFiles.peek()) < end - LIVE_MS)
            Files.deleteIfExists(liveFiles.remove().toPath());
        if (archiveDir.getUsableSpace() < FREE_RESERVE) throw new IOException("Недостаточно памяти для записи");
        File file = segment.store(liveDir);
        liveFiles.add(file);
        return file;
    }

    private static long epoch(File f) {
        String name = f.getName();
        return Long.parseLong(name.substring(0, name.indexOf('.')));
    }

    private static void writeAtomic(File dst, byte[] data) throws IOException {
        File tmp = new File(dst.getParentFile(), dst.getName() + ".part");
        Files.write(tmp.toPath(), data);
        Files.move(tmp.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private File save(Event event, Segment segment) throws IOException {
        if (archiveDir.getUsableSpace() < FREE_RESERVE) throw new IOException("Недостаточно памяти для записи");
        File dst = segment.store(event.dir);
        if (!dst.equals(event.clips.peekLast())) {
            event.clips.add(dst);
            event.bytes += dst.length();
            size += dst.length();
        }
        return dst;
    }

    private void trim() throws IOException {
        while (!events.isEmpty() && (size > limit || archiveDir.getUsableSpace() < FREE_RESERVE)) {
            Event oldest = events.getFirst();
            if (oldest == active) {
                if (oldest.clips.isEmpty()) break;
                File first = oldest.clips.remove();
                oldest.prefixEvicted = true;
                long bytes = first.length();
                if (first.equals(oldest.queuedClip))
                    Files.deleteIfExists(new File(outbox, oldest.dir.getName() + ".mp4").toPath());
                Files.deleteIfExists(first.toPath());
                oldest.bytes -= bytes;
                size -= bytes;
            } else {
                for (File f : children(oldest.dir)) Files.deleteIfExists(f.toPath());
                Files.deleteIfExists(oldest.dir.toPath());
                Files.deleteIfExists(new File(outbox, oldest.dir.getName() + ".jpg").toPath());
                Files.deleteIfExists(new File(outbox, oldest.dir.getName() + ".mp4").toPath());
                Files.deleteIfExists(new File(outbox, oldest.dir.getName() + ".done").toPath());
                growing.remove(oldest.dir.getName());
                events.remove();
                size -= oldest.bytes;
            }
        }
    }
}
