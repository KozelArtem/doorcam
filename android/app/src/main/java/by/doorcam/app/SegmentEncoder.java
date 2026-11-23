package by.doorcam.app;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Handler;
import android.util.Size;
import android.view.Surface;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SegmentEncoder implements AutoCloseable {
    interface Listener {
        void segment(Archive.Segment segment, long end) throws IOException;
        void liveSegment(Archive.Segment segment, long end) throws IOException;
        void failed(Exception error);
        long now();
    }

    private final Listener listener;
    private final int rotation;
    private final LiveMp4 live;
    private MediaCodec codec;
    private Surface surface;
    private MediaFormat outputFormat;
    private static final class Sample {
        final byte[] data;
        final long pts;
        final int flags;
        Sample(byte[] data, long pts, int flags) { this.data = data; this.pts = pts; this.flags = flags; }
    }

    private List<Sample> samples;
    private long startMs, liveStartMs, motionStartMs = Long.MIN_VALUE;
    private int liveIndex;
    private int bytes;

    SegmentEncoder(int rotation, Listener listener, LiveMp4 live) {
        this.rotation = rotation;
        this.listener = listener;
        this.live = live;
    }

    Surface start(Size size, Handler handler) throws IOException {
        int w = size.getWidth(), h = size.getHeight();
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, w >= 1000 ? 950_000 : 550_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            String name = info.getName().toLowerCase(Locale.ROOT);
            boolean hardware = Build.VERSION.SDK_INT >= 29 ? info.isHardwareAccelerated()
                    : !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
                      && !name.contains("ffmpeg") && !name.startsWith("c2.google.");
            if (!hardware) continue;
            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
                if (!caps.getVideoCapabilities().isSizeSupported(w, h)) continue;
                for (int color : caps.colorFormats) {
                    if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface) {
                        codec = MediaCodec.createByCodecName(info.getName());
                        break;
                    }
                }
            } catch (IllegalArgumentException ignored) { /* Different media type. */ }
            if (codec != null) break;
        }
        if (codec == null) throw new IOException("Аппаратный кодировщик H.264 недоступен");
        codec.setCallback(new MediaCodec.Callback() {
            @Override public void onInputBufferAvailable(MediaCodec c, int index) { }
            @Override public void onOutputFormatChanged(MediaCodec c, MediaFormat format) {
                if (codec != c) return;
                outputFormat = format;
                live.format(w, h, rotation, format.getByteBuffer("csd-0"), format.getByteBuffer("csd-1"));
            }
            @Override public void onError(MediaCodec c, MediaCodec.CodecException error) {
                if (codec == c) listener.failed(error);
            }
            @Override public void onOutputBufferAvailable(MediaCodec c, int index, MediaCodec.BufferInfo info) {
                if (codec != c) return;
                try {
                    try {
                        write(c.getOutputBuffer(index), info);
                    } finally {
                        c.releaseOutputBuffer(index, false);
                    }
                } catch (Exception error) { listener.failed(error); }
            }
        }, handler);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        surface = codec.createInputSurface();
        codec.start();
        return surface;
    }

    void onMotion(long now) { motionStartMs = now; }

    static boolean archiveBoundary(long durationUs, long now, long onset) {
        // Keep the start of a visit in one MP4 if it falls just before a regular cut.
        return durationUs >= 10_000_000L && (durationUs >= 11_750_000L || onset < now - 750);
    }

    private void write(ByteBuffer buffer, MediaCodec.BufferInfo info) throws IOException {
        if (buffer == null || info.size == 0 || (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return;
        boolean sync = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        if (sync && outputFormat != null) {
            long pts = info.presentationTimeUs;
            boolean archiveBoundary = samples == null ||
                    archiveBoundary(pts - samples.get(0).pts, listener.now(), motionStartMs);
            if (samples != null && (archiveBoundary || pts - samples.get(liveIndex).pts >= 750_000L)) {
                publish(samples.subList(liveIndex, samples.size()), liveStartMs, true);
                liveStartMs = listener.now();
                liveIndex = samples.size();
            }
            if (archiveBoundary) {
                finish();
                startMs = liveStartMs = listener.now();
                samples = new ArrayList<>();
                liveIndex = bytes = 0;
            }
        }
        if (samples == null) return;
        if (bytes + info.size > 4 * 1024 * 1024 || (liveIndex < samples.size() &&
                info.presentationTimeUs - samples.get(liveIndex).pts > 12_000_000))
            throw new IOException("Слишком большой фрагмент H.264 или нет ключевого кадра");
        byte[] data = new byte[info.size];
        buffer.position(info.offset);
        buffer.get(data);
        live.sample(data, info.presentationTimeUs, sync);
        bytes += data.length;
        samples.add(new Sample(data, info.presentationTimeUs, info.flags));
    }

    private static void mux(File file, MediaFormat format, int rotation, List<Sample> samples) throws IOException {
        MediaMuxer mux = new MediaMuxer(file.toString(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            mux.setOrientationHint(rotation);
            int track = mux.addTrack(format);
            mux.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long first = samples.get(0).pts;
            for (Sample s : samples) {
                info.set(0, s.data.length, s.pts - first, s.flags);
                mux.writeSampleData(track, ByteBuffer.wrap(s.data), info);
            }
            mux.stop();
        } finally { mux.release(); }
    }

    static void join(File destination, List<File> clips) throws IOException {
        long bytes = 0;
        for (File clip : clips) {
            long length = clip.length();
            if (length == 0) throw new IOException("Фрагмент отсутствует в архиве");
            bytes += length;
            if (bytes > Archive.TELEGRAM_COMPLETE_BYTES) throw new IOException("Запись слишком большая для Telegram");
        }
        if (destination.getUsableSpace() - Archive.FREE_RESERVE < bytes)
            throw new IOException("Недостаточно памяти для сборки записи");
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        int rotation;
        try {
            metadata.setDataSource(clips.get(0).getPath());
            String value = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            rotation = value == null ? 0 : Integer.parseInt(value);
        } finally { metadata.release(); }
        MediaMuxer mux = new MediaMuxer(destination.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            mux.setOrientationHint(rotation);
            ByteBuffer buffer = ByteBuffer.allocate(4 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long offset = 0;
            int track = -1;
            for (File clip : clips) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Сборка видео прервана");
                MediaExtractor input = new MediaExtractor();
                try {
                    input.setDataSource(clip.getPath());
                    MediaFormat format = input.getTrackFormat(0);
                    input.selectTrack(0);
                    if (track < 0) {
                        track = mux.addTrack(format);
                        mux.start();
                    }
                    long first = input.getSampleTime(), last = 0;
                    if (first < 0) throw new IOException("Фрагмент не содержит видео");
                    for (int size; (size = input.readSampleData(buffer, 0)) >= 0; input.advance()) {
                        long time = input.getSampleTime() - first;
                        int flags = (input.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                                ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        info.set(0, size, offset + time, flags);
                        mux.writeSampleData(track, buffer, info);
                        last = Math.max(last, time);
                        buffer.clear();
                    }
                    offset += Math.max(last + 1, format.containsKey(MediaFormat.KEY_DURATION)
                            ? format.getLong(MediaFormat.KEY_DURATION) : last + 66_667);
                } finally { input.release(); }
            }
            mux.stop();
        } finally { mux.release(); }
    }

    private void finish() throws IOException {
        if (samples == null) return;
        List<Sample> complete = samples;
        samples = null;
        publish(complete, startMs, false);
    }

    private void publish(List<Sample> complete, long start, boolean forLive) throws IOException {
        MediaFormat format = outputFormat;
        Archive.Segment segment = new Archive.Segment(start, dest -> mux(dest, format, rotation, complete));
        long end = start + Math.max(0, complete.get(complete.size() - 1).pts - complete.get(0).pts) / 1000;
        if (forLive) listener.liveSegment(segment, end);
        else listener.segment(segment, end);
    }

    @Override public void close() {
        live.reset();
        MediaCodec closing = codec;
        codec = null;
        if (closing != null) {
            try { closing.stop(); } catch (RuntimeException ignored) { }
            try { closing.release(); } catch (RuntimeException ignored) { }
        }
        if (surface != null) { surface.release(); surface = null; }
        try {
            if (samples != null && liveIndex < samples.size())
                publish(samples.subList(liveIndex, samples.size()), liveStartMs, true);
        } catch (Exception error) { android.util.Log.w("Doorcam", "Закрытие эфира", error); }
        try { finish(); } catch (Exception error) { android.util.Log.w("Doorcam", "Закрытие MP4", error); }
    }
}
