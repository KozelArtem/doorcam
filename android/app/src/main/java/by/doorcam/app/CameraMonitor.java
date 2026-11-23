package by.doorcam.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class CameraMonitor implements AutoCloseable, SegmentEncoder.Listener {
    interface State { void ready(); void failed(Exception e); }
    private static final int SAMPLE_COLUMNS = 48, SAMPLE_ROWS = 32;
    private final Context context;
    private final Handler worker;
    private final Archive archive;
    private final RemoteLink link;
    private final LiveMp4 live;
    private final State state;
    private final MotionDetector detector;
    final CaptureHealth health = new CaptureHealth(SystemClock.elapsedRealtime());
    private final byte[] samples = new byte[SAMPLE_COLUMNS * SAMPLE_ROWS];
    private final long epoch = System.currentTimeMillis() - SystemClock.elapsedRealtime();
    private long previousFrame;
    private long previousPreview;
    private int rotation;
    private boolean closed, ready;
    volatile byte[] previewFrame;
    private final Runnable checkCapture = new Runnable() {
        @Override public void run() {
            if (closed) return;
            if (archive == null) {
                if (previewFrame == null) failed(new IOException("Камера не передаёт кадры"));
                return;
            }
            String failure = health.failure(SystemClock.elapsedRealtime());
            if (failure != null) {
                failed(new IOException(failure));
                return;
            }
            if (!ready && health.ready()) {
                ready = true;
                state.ready();
            }
            worker.postDelayed(this, 2_000);
        }
    };
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private SegmentEncoder encoder;

    CameraMonitor(Context context, Handler worker, Archive archive, RemoteLink link, LiveMp4 live, State state, int sensitivity) {
        this.context = context;
        this.worker = worker;
        this.archive = archive;
        this.link = link;
        this.live = live;
        this.state = state;
        detector = archive == null ? null : new MotionDetector(sensitivity);
    }

    CameraMonitor(Context context, Handler worker, State state) {
        this(context, worker, null, null, null, state, 0);
    }

    @Override public long now() { return epoch + SystemClock.elapsedRealtime(); }
    @Override public void segment(Archive.Segment segment, long end) throws IOException {
        if (archive.finish(segment, end) && link != null) link.onEvent();
        health.segment(SystemClock.elapsedRealtime());
    }
    @Override public void liveSegment(Archive.Segment segment, long end) throws IOException {
        if (link != null && link.wantsLive()) link.onSegment(archive.finishLive(segment, end));
    }
    @Override public void failed(Exception e) {
        if (closed) return;
        if (archive == null) close();
        state.failed(e);
    }

    @SuppressLint("MissingPermission")
    void start(String id) throws Exception {
        CameraManager manager = context.getSystemService(CameraManager.class);
        CameraCharacteristics caps = manager.getCameraCharacteristics(id);
        StreamConfigurationMap streams = caps.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (streams == null) throw new IOException("Сведения о камере недоступны");
        Size analysis = select(streams.getOutputSizes(ImageFormat.YUV_420_888), 640, 480);
        Integer sensor = caps.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int display = context.getSystemService(WindowManager.class).getDefaultDisplay().getRotation() * 90;
        Integer lens = caps.get(CameraCharacteristics.LENS_FACING);
        rotation = ((sensor == null ? 0 : sensor) + (lens != null && lens == CameraCharacteristics.LENS_FACING_FRONT ? display : -display) + 360) % 360;
        reader = ImageReader.newInstance(analysis.getWidth(), analysis.getHeight(), ImageFormat.YUV_420_888, 3);
        reader.setOnImageAvailableListener(this::analyze, worker);
        Surface imageSurface = reader.getSurface();
        boolean recording = archive != null;
        List<Surface> targets;
        if (recording) {
            Size video = select(streams.getOutputSizes(MediaRecorder.class), 1280, 720);
            encoder = new SegmentEncoder(rotation, this, live);
            targets = Arrays.asList(encoder.start(video, worker), imageSurface);
        } else {
            targets = Collections.singletonList(imageSurface);
        }
        worker.postDelayed(checkCapture, recording ? 2_000 : 8_000);
        manager.openCamera(id, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice device) {
                if (closed) { device.close(); return; }
                camera = device;
                try {
                    CaptureRequest.Builder request = camera.createCaptureRequest(
                            recording ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
                    for (Surface target : targets) request.addTarget(target);
                    Range<Integer> rate = frameRate(caps.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES));
                    if (rate != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, rate);
                    camera.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession configured) {
                            if (closed) { configured.close(); return; }
                            session = configured;
                            try {
                                configured.setRepeatingRequest(request.build(), null, worker);
                            } catch (Exception e) { failed(e); }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession cfg) {
                            failed(new IOException("Камера отклонила конфигурацию захвата"));
                        }
                    }, worker);
                } catch (Exception e) { failed(e); }
            }
            @Override public void onDisconnected(CameraDevice device) {
                device.close(); failed(new IOException("Камера отключилась"));
            }
            @Override public void onError(CameraDevice device, int error) {
                device.close(); failed(new IOException("Ошибка камеры " + error));
            }
        }, worker);
    }

    private static Size select(Size[] choices, int width, int height) throws IOException {
        if (choices == null || choices.length == 0) throw new IOException("Нет подходящего видеорежима");
        Size best = choices[0];
        long bestScore = Long.MAX_VALUE;
        for (Size s : choices) {
            long score = Math.abs(width - s.getWidth()) + 2L * Math.abs(height - s.getHeight());
            if (s.getWidth() > width || s.getHeight() > height) score += 10000;
            if (score < bestScore) { best = s; bestScore = score; }
        }
        return best;
    }

    private static Range<Integer> frameRate(Range<Integer>[] ranges) {
        Range<Integer> best = null;
        int bestScore = Integer.MAX_VALUE;
        if (ranges != null) for (Range<Integer> r : ranges) {
            int score = Math.abs(r.getUpper() - 15) * 2 + Math.abs(r.getLower() - 15);
            if (score < bestScore) { best = r; bestScore = score; }
        }
        return best;
    }

    private void analyze(ImageReader input) {
        if (closed) return;
        try (Image image = input.acquireLatestImage()) {
            long now = now();
            if (image == null || now - previousFrame < 180) return;
            previousFrame = now;
            long elapsed = SystemClock.elapsedRealtime();
            if (archive == null) {
                if (elapsed - previousPreview >= 250) {
                    previousPreview = elapsed;
                    previewFrame = jpeg(image);
                }
                return;
            }
            health.frame(elapsed);
            byte[] photo = null;
            if (!MonitorService.motionEnabled) {
                detector.reset();
                MonitorService.calibrationMotionAt = 0;
            } else if (detectMotion(image)) {
                MonitorService.calibrationMotionAt = elapsed;
                if (archive.needsPhoto(now)) photo = jpeg(image);
                if (MonitorService.motionEnabled && archive.motion(now, photo)) {
                    MonitorService.lastEvent = now;
                    encoder.onMotion(now);
                }
            }
            if (MonitorService.previewVisible && elapsed - previousPreview >= 500) {
                previousPreview = elapsed;
                try {
                    MonitorService.previewFrame = photo != null ? photo : jpeg(image);
                } catch (IOException e) {
                    Log.w("Doorcam", "Предпросмотр камеры", e);
                }
            }
        } catch (Exception error) { failed(error); }
    }

    private boolean detectMotion(Image image) {
        Image.Plane y = image.getPlanes()[0];
        ByteBuffer buf = y.getBuffer();
        int width = image.getWidth(), height = image.getHeight();
        int rowStride = y.getRowStride(), pixelStride = y.getPixelStride();
        int base = buf.position(), sample = 0;
        for (int row = 0; row < SAMPLE_ROWS; row++) {
            int offset = base + ((2 * row + 1) * height / (2 * SAMPLE_ROWS)) * rowStride;
            for (int col = 0; col < SAMPLE_COLUMNS; col++) {
                int x = (2 * col + 1) * width / (2 * SAMPLE_COLUMNS);
                samples[sample++] = buf.get(offset + x * pixelStride);
            }
        }
        return detector.observe(samples, SAMPLE_COLUMNS, SAMPLE_ROWS, rotation, MonitorService.motionZone);
    }

    private byte[] jpeg(Image image) throws IOException {
        int w = image.getWidth(), h = image.getHeight();
        byte[] nv21 = new byte[w * h * 3 / 2];
        Image.Plane[] planes = image.getPlanes();
        for (int p = 0; p < 3; p++) {
            int width = p == 0 ? w : w / 2, height = p == 0 ? h : h / 2;
            int start = p == 0 ? 0 : w * h + (p == 1 ? 1 : 0);
            int stride = p == 0 ? 1 : 2;
            ByteBuffer buf = planes[p].getBuffer();
            for (int row = 0; row < height; row++) {
                int pos = buf.position() + row * planes[p].getRowStride();
                for (int x = 0; x < width; x++) {
                    nv21[start + (row * width + x) * stride] = buf.get(pos + x * planes[p].getPixelStride());
                }
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!new YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(new Rect(0, 0, w, h), 82, output))
            throw new IOException("Ошибка кодирования фото");
        byte[] bytes = output.toByteArray();
        if (rotation == 0) return bytes;
        Bitmap raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        Matrix transform = new Matrix();
        transform.postRotate(rotation);
        Bitmap upright = Bitmap.createBitmap(raw, 0, 0, w, h, transform, true);
        try {
            output.reset();
            if (!upright.compress(Bitmap.CompressFormat.JPEG, 82, output)) throw new IOException("Ошибка поворота фото");
            return output.toByteArray();
        } finally {
            upright.recycle();
            if (raw != upright) raw.recycle();
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        previewFrame = null;
        worker.removeCallbacks(checkCapture);
        if (session != null) session.close();
        if (camera != null) camera.close();
        if (reader != null) reader.close();
        if (encoder != null) encoder.close();
        if (archive != null && archive.endCapture() && link != null) link.onEvent();
    }
}
