package by.doorcam.app;

import android.Manifest;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.*;
import android.util.Log;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.UUID;

public final class MonitorService extends Service {
    private static final String CHANNEL = "monitor";
    private static final int NOTIFICATION = 17;
    static final String STOP = "by.doorcam.app.STOP";
    static final String MOTION_ENABLED = "motionEnabled";
    static volatile boolean running;
    static volatile String status = "Наблюдение остановлено";
    static volatile long lastEvent;
    static volatile String lanUrl = "";
    static volatile MotionZone motionZone = MotionZone.FULL;
    static volatile boolean motionEnabled = true;
    static volatile boolean previewVisible;
    static volatile byte[] previewFrame;
    static volatile long calibrationMotionAt;
    private static volatile CaptureHealth captureHealth;
    private static volatile String capture = "stopped";
    // A rapid Stop/Start must finish the previous camera and archive before reopening them.
    private static final HandlerThread cameraThread = new HandlerThread("DoorcamCapture");
    static { cameraThread.start(); }
    static Handler captureHandler() { return new Handler(cameraThread.getLooper()); }

    static void setMotionEnabled(SharedPreferences settings, boolean enabled) {
        motionEnabled = enabled;
        if (!enabled) calibrationMotionAt = 0;
        settings.edit().putBoolean(MOTION_ENABLED, enabled).apply();
    }

    private final Handler worker = captureHandler();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;
    private boolean started;
    private long retryMs = 1000;
    private PowerManager.WakeLock wakeLock;
    private Archive archive;
    private RemoteLink remote;
    private CameraMonitor camera;
    private final LiveMp4 live = new LiveMp4();
    private LanServer lan;
    private Network wifi;
    private ConnectivityManager.NetworkCallback wifiCallback;

    static String captureState() {
        String state = capture;
        CaptureHealth health = captureHealth;
        if (("ok".equals(state) || "starting".equals(state)) && health != null
                && health.failure(SystemClock.elapsedRealtime()) != null) return "failed";
        return state;
    }

    static synchronized String lanPassword(SharedPreferences settings) {
        String password = settings.getString("lanPassword", "");
        if (password.isEmpty()) {
            password = UUID.randomUUID().toString();
            settings.edit().putString("lanPassword", password).apply();
        }
        return password;
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Наблюдение", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), flags);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, MonitorService.class).setAction(STOP), flags);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera).setContentTitle("Doorcam")
                .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "Остановить", stop).build()).build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if ((intent != null && STOP.equals(intent.getAction())) || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (started) return START_STICKY;
        try {
            startForeground(NOTIFICATION, notification("Подготовка камеры"));
        } catch (RuntimeException e) {
            status = "Не удалось запустить службу камеры. Откройте приложение и запустите наблюдение.";
            stopSelf();
            return START_NOT_STICKY;
        }
        started = true;
        running = true;
        captureHealth = null;
        capture = "starting";
        status = "Подготовка камеры";
        SharedPreferences settings = getSharedPreferences("doorcam", MODE_PRIVATE);
        motionZone = MotionZone.read(settings);
        motionEnabled = settings.getBoolean(MOTION_ENABLED, true);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Doorcam:Capture");
        wakeLock.acquire();
        worker.post(this::open);
        return START_STICKY;
    }

    private void report(String state, String text) {
        main.post(() -> {
            if (destroyed) return;
            capture = state;
            status = text;
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(text));
        });
    }

    private void open() {
        if (destroyed) return;
        SharedPreferences settings = getSharedPreferences("doorcam", MODE_PRIVATE);
        try {
            String origin = settings.getString("origin", ""), key = settings.getString("key", "");
            boolean remoteEnabled = !origin.isEmpty() && !key.isEmpty();
            if (archive == null) archive = new Archive(getFilesDir(), settings.getInt("limit", 1024) * 1024L * 1024L, remoteEnabled);
            if (wifiCallback == null) watchWifi(lanPassword(settings));
            if (remote == null && remoteEnabled) {
                remote = new RemoteLink(getApplicationContext(), origin, key, archive);
                remote.start();
            }
            camera = new CameraMonitor(this, worker, archive, remote, live, new CameraMonitor.State() {
                @Override public void ready() {
                    retryMs = 1000;
                    report("ok", "Наблюдение включено");
                }
                @Override public void failed(Exception e) { retry(e); }
            }, settings.getInt("sensitivity", 50));
            captureHealth = camera.health;
            camera.start(settings.getString("camera", "0"));
        } catch (Exception e) { retry(e); }
    }

    private void watchWifi(String password) {
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                if (destroyed) return;
                wifi = network;
                InetAddress address = null;
                for (LinkAddress link : properties.getLinkAddresses()) {
                    if (link.getAddress() instanceof Inet4Address && !link.getAddress().isLinkLocalAddress()) {
                        address = link.getAddress();
                        break;
                    }
                }
                if (address != null && lan != null &&
                        lan.authority().equals(address.getHostAddress() + ":" + LanServer.PORT)) return;
                closeLan();
                if (address == null) return;
                try {
                    lan = new LanServer(address, LanServer.PORT, password, archive.archiveDir,
                            name -> getAssets().open(name), live);
                    lanUrl = "http://" + lan.authority();
                } catch (IOException e) {
                    lanUrl = "Не удалось открыть веб-сервер на порту " + LanServer.PORT;
                    Log.w("Doorcam", lanUrl, e);
                }
            }

            @Override public void onLost(Network network) {
                if (!destroyed && network.equals(wifi)) {
                    wifi = null;
                    closeLan();
                }
            }
        };
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
        connectivity.registerNetworkCallback(request, callback, worker);
        wifiCallback = callback;
    }

    private void closeLan() {
        lanUrl = "";
        if (lan != null) { lan.close(); lan = null; }
    }

    private void retry(Exception error) {
        if (destroyed) return;
        previewFrame = null;
        calibrationMotionAt = 0;
        Log.w("Doorcam", "Повторный запуск захвата", error);
        if (camera != null) { camera.close(); camera = null; }
        String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        report("failed", "Повтор через " + retryMs / 1000 + " с · " + reason);
        worker.postDelayed(this::open, retryMs);
        retryMs = Math.min(retryMs * 2, 30000);
    }

    @Override public void onDestroy() {
        destroyed = true;
        running = false;
        if (started) {
            capture = "stopped";
            captureHealth = null;
            status = "Наблюдение остановлено";
            previewFrame = null;
            calibrationMotionAt = 0;
        }
        worker.removeCallbacksAndMessages(null);
        worker.post(() -> {
            try {
                if (wifiCallback != null)
                    getSystemService(ConnectivityManager.class).unregisterNetworkCallback(wifiCallback);
                closeLan();
                if (camera != null) camera.close();
                if (remote != null) remote.stop();
            } finally {
                if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            }
        });
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
