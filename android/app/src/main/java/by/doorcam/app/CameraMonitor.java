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
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

final class CameraMonitor implements AutoCloseable, SegmentEncoder.Listener {
    interface State { void ready(); void failed(Exception e); }
    private final Context context;
    private final Handler worker;
    private final Archive archive;
    private final RemoteLink link;
    private final LiveMp4 live;
    private final State state;
    private final MotionDetector detector;
    private final long epoch = System.currentTimeMillis() - SystemClock.elapsedRealtime();
    private long previousFrame;
    private int rotation;
    private boolean closed;
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
        detector = new MotionDetector(sensitivity);
    }

    @Override public long now() { return epoch + SystemClock.elapsedRealtime(); }
    @Override public void segment(Archive.Segment segment, long end) throws IOException {
        archive.finish(segment, end);
    }
    @Override public void liveSegment(Archive.Segment segment, long end) throws IOException {
        if (link != null && link.wantsLive()) link.onSegment(archive.finishLive(segment, end));
    }
    @Override public void failed(Exception e) { if (!closed) state.failed(e); }

    @SuppressLint("MissingPermission")
    void start(String id) throws Exception {
        CameraManager manager = context.getSystemService(CameraManager.class);
        CameraCharacteristics caps = manager.getCameraCharacteristics(id);
        StreamConfigurationMap streams = caps.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (streams == null) throw new IOException("Сведения о камере недоступны");
        Size video = select(streams.getOutputSizes(MediaRecorder.class), 1280, 720);
        Size analysis = select(streams.getOutputSizes(ImageFormat.YUV_420_888), 640, 480);
        Integer sensor = caps.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int display = context.getSystemService(WindowManager.class).getDefaultDisplay().getRotation() * 90;
        Integer lens = caps.get(CameraCharacteristics.LENS_FACING);
        rotation = ((sensor == null ? 0 : sensor) + (lens != null && lens == CameraCharacteristics.LENS_FACING_FRONT ? display : -display) + 360) % 360;
        reader = ImageReader.newInstance(analysis.getWidth(), analysis.getHeight(), ImageFormat.YUV_420_888, 3);
        reader.setOnImageAvailableListener(this::analyze, worker);
        encoder = new SegmentEncoder(rotation, this, live);
        Surface videoSurface = encoder.start(video, worker);
        manager.openCamera(id, new CameraDevice.StateCallback() {
            @Override public void onOpened(CameraDevice device) {
                if (closed) { device.close(); return; }
                camera = device;
                try {
                    Surface imageSurface = reader.getSurface();
                    CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    request.addTarget(imageSurface);
                    request.addTarget(videoSurface);
                    Range<Integer> rate = frameRate(caps.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES));
                    if (rate != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, rate);
                    camera.createCaptureSession(Arrays.asList(videoSurface, imageSurface), new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession configured) {
                            if (closed) { configured.close(); return; }
                            session = configured;
                            try {
                                configured.setRepeatingRequest(request.build(), null, worker);
                                state.ready();
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
            Image.Plane y = image.getPlanes()[0];
            ByteBuffer buf = y.getBuffer();
            byte[] samples = new byte[48 * 32];
            for (int row = 0; row < 32; row++) for (int col = 0; col < 48; col++) {
                int x = col * image.getWidth() / 48;
                int yy = row * image.getHeight() / 32;
                samples[row * 48 + col] = buf.get(buf.position() + yy * y.getRowStride() + x * y.getPixelStride());
            }
            if (detector.observe(samples)) {
                File pending = archive.motion(now, archive.needsPhoto(now) ? jpeg(image) : null);
                if (pending != null) MonitorService.lastEvent = now;
                if (pending != null && link != null) {
                    String name = pending.getName();
                    link.onEvent(name.substring(0, name.length() - 4), pending);
                }
            }
        } catch (Exception error) { failed(error); }
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
        closed = true;
        if (session != null) session.close();
        if (camera != null) camera.close();
        if (reader != null) reader.close();
        if (encoder != null) encoder.close();
    }
}
