package by.doorcam.app;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;

final class LiveMp4 {
    private static final int TRACK = 1, TIMESCALE = 1_000_000;
    private static final byte[] MDAT = ascii("mdat");
    private static final byte[] MATRIX = words(65536, 0, 0, 0, 65536, 0, 0, 0, 1 << 30);
    private final List<Viewer> viewers = new ArrayList<>();
    private byte[] init, pending;
    private String codec;
    private int rotation, sequence;
    private long startUs, pendingUs;
    private boolean pendingKey;

    final class Viewer implements AutoCloseable {
        final byte[] init = LiveMp4.this.init;
        final String codec = LiveMp4.this.codec;
        final int rotation = LiveMp4.this.rotation;
        final ArrayBlockingQueue<byte[]> frames = new ArrayBlockingQueue<>(40);
        volatile boolean closed;
        private boolean started;

        @Override public void close() {
            synchronized (LiveMp4.this) {
                closed = true;
                viewers.remove(this);
                frames.clear();
                frames.offer(new byte[0]);
            }
        }
    }

    synchronized Viewer watch() {
        if (init == null || viewers.size() >= 3) return null;
        Viewer viewer = new Viewer();
        viewers.add(viewer);
        return viewer;
    }

    synchronized void reset() {
        while (!viewers.isEmpty()) viewers.get(viewers.size() - 1).close();
        init = null;
        pending = null;
    }

    synchronized void format(int width, int height, int rotation, ByteBuffer csd0, ByteBuffer csd1) {
        reset();
        byte[] sps = parameterSet(csd0, 7), pps = parameterSet(csd1 == null ? csd0 : csd1, 8);
        if (sps == null || sps.length < 4 || pps == null || sps.length > 65535 || pps.length > 65535) return;
        codec = String.format(Locale.ROOT, "avc1.%02x%02x%02x", sps[1] & 255, sps[2] & 255, sps[3] & 255);
        this.rotation = rotation;
        init = initialization(width, height, sps, pps);
    }

    synchronized void sample(byte[] data, long pts, boolean key) {
        if (viewers.isEmpty()) {
            pending = null;
            return;
        }
        if (pending == null) {
            if (!key) return;
            startUs = pts;
            sequence = 0;
        } else {
            pts = Math.max(pts, pendingUs + 1);
            long elapsed = pts - pendingUs;
            byte[] fragment = fragment(++sequence, pendingUs - startUs, (int) Math.min(elapsed, Integer.MAX_VALUE),
                    pendingKey, pending);
            for (int i = viewers.size() - 1; i >= 0; i--) {
                Viewer viewer = viewers.get(i);
                if (!viewer.started && !pendingKey) continue;
                viewer.started = true;
                if (!viewer.frames.offer(fragment)) viewer.close();
            }
        }
        pending = lengthPrefixed(data);
        pendingUs = pts;
        pendingKey = key;
    }

    private static byte[] parameterSet(ByteBuffer source, int type) {
        if (source == null) return null;
        byte[] data = new byte[source.remaining()];
        source.duplicate().get(data);
        int prefix = prefix(data, 0);
        if (prefix == 0) return data.length > 0 && (data[0] & 31) == type ? data : null;
        for (int start = prefix; start < data.length;) {
            int end = nextStart(data, start + 1);
            if ((data[start] & 31) == type) return java.util.Arrays.copyOfRange(data, start, end);
            start = end + prefix(data, end);
        }
        return null;
    }

    private static byte[] lengthPrefixed(byte[] data) {
        ByteBuffer candidate = ByteBuffer.wrap(data);
        while (candidate.remaining() > 4) {
            int length = candidate.getInt();
            if (length <= 0 || length > candidate.remaining()) break;
            candidate.position(candidate.position() + length);
            if (!candidate.hasRemaining()) return data;
        }
        int prefix = prefix(data, 0);
        if (prefix == 0) return join(words(data.length), data);
        ByteArrayOutputStream sample = new ByteArrayOutputStream(data.length + 16);
        for (int start = prefix; start < data.length;) {
            int end = nextStart(data, start + 1);
            byte[] size = words(end - start);
            sample.write(size, 0, size.length);
            sample.write(data, start, end - start);
            start = end + prefix(data, end);
        }
        return sample.toByteArray();
    }

    private static int nextStart(byte[] data, int offset) {
        while (offset < data.length && prefix(data, offset) == 0) offset++;
        return offset;
    }

    private static int prefix(byte[] data, int i) {
        if (i + 2 >= data.length || data[i] != 0 || data[i + 1] != 0) return 0;
        if (data[i + 2] == 1) return 3;
        return i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1 ? 4 : 0;
    }

    private static byte[] initialization(int width, int height, byte[] sps, byte[] pps) {
        byte[] avcC = box("avcC", new byte[]{1, sps[1], sps[2], sps[3], (byte) 255, (byte) 225},
                shorts(sps.length), sps, new byte[]{1}, shorts(pps.length), pps);
        byte[] avc1 = box("avc1", shorts(0, 0, 0, 1), new byte[16],
                shorts(width, height), words(72 << 16, 72 << 16, 0), shorts(1),
                new byte[32], shorts(24, 65535), avcC);
        byte[] stbl = box("stbl", box("stsd", words(0, 1), avc1), box("stts", words(0, 0)),
                box("stsc", words(0, 0)), box("stsz", words(0, 0, 0)), box("stco", words(0, 0)));
        byte[] minf = box("minf", box("vmhd", words(1, 0, 0)),
                box("dinf", box("dref", words(0, 1), box("url ", words(1)))), stbl);
        byte[] mdia = box("mdia", box("mdhd", words(0, 0, 0, TIMESCALE, 0), shorts(0x55c4, 0)),
                box("hdlr", words(0, 0), ascii("vide"), new byte[13]), minf);
        byte[] trak = box("trak", box("tkhd", words(7, 0, 0, TRACK, 0, 0, 0, 0, 0, 0), MATRIX,
                words(width << 16, height << 16)), mdia);
        byte[] mvhd = box("mvhd", words(0, 0, 0, TIMESCALE, 0, 65536, 1 << 24, 0, 0),
                MATRIX, new byte[24], words(TRACK + 1));
        return join(box("ftyp", ascii("iso6"), words(0), ascii("iso6isomavc1")),
                box("moov", mvhd, trak, box("mvex", box("trex", words(0, TRACK, 1, 0, 0, 0)))));
    }

    private static byte[] fragment(int sequence, long time, int duration, boolean key, byte[] sample) {
        byte[] run = words(0x701, 1, 0, duration, sample.length, key ? 0x02000000 : 0x01010000);
        byte[] moof = box("moof", box("mfhd", words(0, sequence)),
                box("traf", box("tfhd", words(0x020000, TRACK)),
                        box("tfdt", words(1 << 24, (int) (time >>> 32), (int) time)), box("trun", run)));
        ByteBuffer.wrap(moof).putInt(moof.length - run.length + 8, moof.length + 8);
        int mdatSize = 8 + sample.length;
        return ByteBuffer.allocate(moof.length + mdatSize)
                .put(moof).putInt(mdatSize).put(MDAT).put(sample).array();
    }

    private static byte[] words(int... values) {
        ByteBuffer bytes = ByteBuffer.allocate(4 * values.length);
        for (int value : values) bytes.putInt(value);
        return bytes.array();
    }

    private static byte[] shorts(int... values) {
        ByteBuffer bytes = ByteBuffer.allocate(2 * values.length);
        for (int value : values) bytes.putShort((short) value);
        return bytes.array();
    }

    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

    private static byte[] box(String type, byte[]... parts) {
        byte[] content = join(parts);
        return join(words(8 + content.length), ascii(type), content);
    }

    private static byte[] join(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) size += part.length;
        ByteBuffer bytes = ByteBuffer.allocate(size);
        for (byte[] part : parts) bytes.put(part);
        return bytes.array();
    }
}
