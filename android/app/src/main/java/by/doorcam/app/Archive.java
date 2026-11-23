package by.doorcam.app;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;

final class Archive {
    private static final long PRE_ROLL_MS = 4_000, TAIL_MS = 8_000, LIVE_MS = 16_000;
    final File archiveDir, liveDir;
    private final File outbox;
    private final long limit;
    private static final long FREE_RESERVE = 128L * 1024 * 1024;
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
    private long size;
    private Event active;

    private static final class Event {
        final File dir;
        final Deque<File> clips = new ArrayDeque<>();
        long bytes, started, lastMotion = Long.MIN_VALUE + TAIL_MS;
        Event(File dir) { this.dir = dir; }
    }

    Archive(File root, long limit) throws IOException {
        this.limit = limit;
        archiveDir = directory(root, "archive");
        liveDir = directory(root, "live");
        outbox = directory(root, "outbox");
        for (File old : children(liveDir)) Files.deleteIfExists(old.toPath());
        for (File f : children(outbox)) {
            String name = f.getName();
            if (name.endsWith(".part") || (name.endsWith(".jpg") &&
                    !new File(archiveDir, name.substring(0, name.length() - 4)).exists()))
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

    File motion(long now, byte[] jpeg) throws IOException {
        boolean start = needsPhoto(now);
        if (!start) {
            active.lastMotion = now;
            return null;
        }
        long id = now;
        while (new File(archiveDir, Long.toString(id)).exists() || new File(outbox, id + ".jpg").exists()) id++;
        active = new Event(directory(archiveDir, Long.toString(id)));
        active.started = now;
        active.lastMotion = now;
        events.add(active);
        File preview = new File(active.dir, "preview.jpg");
        writeAtomic(preview, jpeg);
        active.bytes += jpeg.length;
        size += jpeg.length;
        File pending = new File(outbox, id + ".jpg");
        writeAtomic(pending, jpeg);
        for (Finished clip : recent) {
            if (clip.end >= now - PRE_ROLL_MS) save(active, clip.segment);
        }
        trim();
        return pending;
    }

    void finish(Segment segment, long end) throws IOException {
        recent.add(new Finished(segment, end));
        while (recent.peek().end < end - PRE_ROLL_MS) recent.remove();
        trim();
        for (Iterator<Event> iterator = events.descendingIterator(); iterator.hasNext();) {
            Event event = iterator.next();
            if (event.lastMotion + TAIL_MS < segment.start) break;
            if (end >= event.started - PRE_ROLL_MS) save(event, segment);
        }
        trim();
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

    private void save(Event event, Segment segment) throws IOException {
        if (archiveDir.getUsableSpace() < FREE_RESERVE) throw new IOException("Недостаточно памяти для записи");
        File dst = segment.store(event.dir);
        if (!dst.equals(event.clips.peekLast())) {
            event.clips.add(dst);
            event.bytes += dst.length();
            size += dst.length();
        }
    }

    private void trim() throws IOException {
        while (!events.isEmpty() && (size > limit || archiveDir.getUsableSpace() < FREE_RESERVE)) {
            Event oldest = events.getFirst();
            if (oldest == active) {
                if (oldest.clips.isEmpty()) break;
                File first = oldest.clips.remove();
                long bytes = first.length();
                Files.deleteIfExists(first.toPath());
                oldest.bytes -= bytes;
                size -= bytes;
            } else {
                for (File f : children(oldest.dir)) Files.deleteIfExists(f.toPath());
                Files.deleteIfExists(oldest.dir.toPath());
                Files.deleteIfExists(new File(outbox, oldest.dir.getName() + ".jpg").toPath());
                events.remove();
                size -= oldest.bytes;
            }
        }
    }
}
