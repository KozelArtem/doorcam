package by.doorcam.app;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Handler;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import java.io.IOException;
import java.util.function.Consumer;

final class CameraPreview extends TextureView implements TextureView.SurfaceTextureListener {
    private final Handler worker = MonitorService.captureHandler();
    private final String cameraId;
    private final Runnable firstFrame;
    private final Consumer<Exception> failure;
    private Output output;
    private Size bufferSize;
    private int sensorOrientation;
    private boolean hasFrame;
    private final Runnable timeout = () -> reportTimeout();

    CameraPreview(Context context, String cameraId, Runnable firstFrame, Consumer<Exception> failure) {
        super(context);
        this.cameraId = cameraId;
        this.firstFrame = firstFrame;
        this.failure = failure;
        setOpaque(false);
        setSurfaceTextureListener(this);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        Output target = new Output(texture);
        output = target;
        hasFrame = false;
        postDelayed(timeout, 8_000);
        worker.post(() -> {
            if (cameraId == null) {
                if (!MonitorService.setDisplayPreview(target, true))
                    target.failed(new IOException("Наблюдение остановлено"));
                return;
            }
            target.camera = new CameraMonitor(getContext().getApplicationContext(), worker, new CameraMonitor.State() {
                @Override public void ready() { }
                @Override public void failed(Exception e) { target.failed(e); }
            });
            try {
                target.camera.setPreview(target);
                target.camera.start(cameraId);
            } catch (Exception e) { target.camera.failed(e); }
        });
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        updateTransform();
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        removeCallbacks(timeout);
        Output target = output;
        output = null;
        bufferSize = null;
        worker.post(() -> {
            if (target.camera != null) target.camera.close();
            else MonitorService.setDisplayPreview(target, false);
            target.surface.release();
            texture.release();
        });
        return false;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        if (!hasFrame) {
            hasFrame = true;
            removeCallbacks(timeout);
            firstFrame.run();
        }
    }

    private void reportTimeout() {
        failure.accept(new IOException("Камера не передаёт кадры"));
    }

    private void updateTransform() {
        if (bufferSize == null || getWidth() == 0 || getHeight() == 0 || getDisplay() == null) return;
        boolean sensorSwapped = sensorOrientation % 180 != 0;
        float width = sensorSwapped ? bufferSize.getHeight() : bufferSize.getWidth();
        float height = sensorSwapped ? bufferSize.getWidth() : bufferSize.getHeight();
        int displayRotation = getDisplay().getRotation() * 90;
        boolean displaySwapped = displayRotation % 180 != 0;
        float scale = Math.min(getWidth() / (displaySwapped ? height : width),
                getHeight() / (displaySwapped ? width : height));
        float x = getWidth() / 2f, y = getHeight() / 2f;
        Matrix transform = new Matrix();
        transform.setScale(width * scale / getWidth(), height * scale / getHeight(), x, y);
        transform.postRotate(-displayRotation, x, y);
        setTransform(transform);
    }

    final class Output {
        private final SurfaceTexture texture;
        final Surface surface;
        private CameraMonitor camera;

        Output(SurfaceTexture texture) {
            this.texture = texture;
            surface = new Surface(texture);
        }

        void configure(CameraCharacteristics characteristics, Size size) {
            texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            post(() -> {
                if (output != this) return;
                bufferSize = size;
                sensorOrientation = orientation == null ? 0 : orientation;
                updateTransform();
            });
        }

        void failed(Exception error) {
            post(() -> {
                if (output != this) return;
                removeCallbacks(timeout);
                failure.accept(error);
            });
        }
    }
}
