package by.doorcam.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Owned by the foreground service. The camera and archive never depend on connectivity. */
public final class RemoteLink {
    private static final String ASSET_ORIGIN = "https://doorcam.invalid";
    private static final String PAGE = ASSET_ORIGIN + "/device.html";
    private static final long MAX_FILE = 32L * 1024 * 1024;
    private static final Comparator<File> BY_NEWEST = (a, b) ->
            Long.compare(stamp(b), stamp(a));
    private final Context context;
    private final String origin, key;
    private final File archive, live, outbox;
    private final Archive recordings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView view;
    private Bridge bridge;
    private boolean started;
    private volatile boolean liveRequested;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!started) return;
            if (view != null) view.evaluateJavascript("window.device && device.tick()", null);
            main.postDelayed(this, 25000);
        }
    };
    private ScheduledThreadPoolExecutor uploads;
    private ScheduledFuture<?> nextUpload;
    private int backoff;

    RemoteLink(Context context, String origin, String key, Archive recordings) {
        this.context = context.getApplicationContext();
        this.origin = cleanOrigin(origin);
        this.key = key == null ? "" : key.trim();
        this.recordings = recordings;
        this.archive = recordings.archiveDir;
        this.live = new File(context.getFilesDir(), "live");
        this.outbox = new File(context.getFilesDir(), "outbox");
    }

    private static String cleanOrigin(String value) {
        try {
            URI uri = URI.create(value.trim());
            if ("https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null
                    && uri.getFragment() == null && ("".equals(uri.getPath()) || "/".equals(uri.getPath()))) {
                return "https://" + uri.getRawAuthority();
            }
        } catch (RuntimeException ignored) { }
        return "";
    }

    public void start() {
        main.post(() -> {
            if (started || origin.isEmpty() || key.isEmpty()) return;
            started = true;
            uploads = new ScheduledThreadPoolExecutor(1);
            uploads.setRemoveOnCancelPolicy(true);
            backoff = 0;
            scheduleUpload(0);
            mount();
            main.post(heartbeat);
        });
    }

    public void stop() {
        main.post(() -> {
            started = false;
            main.removeCallbacks(heartbeat);
            status("stopped");
            if (uploads != null) uploads.shutdownNow();
            uploads = null;
            WebView closing = view;
            if (closing == null) return;
            Runnable release = () -> { if (view == closing) unmount(); };
            // Allow the WebSocket close handshake to send the final capture state.
            closing.evaluateJavascript("window.device && device.stop()", ignored -> main.postDelayed(release, 500));
            main.postDelayed(release, 1500);
        });
    }

    public boolean wantsLive() { return liveRequested; }

    private void status(String status) {
        context.getSharedPreferences("doorcam.remote", Context.MODE_PRIVATE)
                .edit().putString("status", status).apply();
    }

    /** Camera adds the completed video to the outbox before invoking this method. */
    public void onEvent() {
        main.post(() -> {
            if (started && backoff == 0) scheduleUpload(0);
        });
    }

    /** A viewer pulls from the finalized live directory, including the latest segment on entry. */
    public void onSegment(File segment) {
        // Publication is the camera's atomic rename. There is no work when nobody is watching.
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void mount() {
        if (!started || view != null) return;
        status("connecting");
        try {
            WebView web = new WebView(context);
            view = web;
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            settings.setSupportMultipleWindows(false);
            settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
            Bridge local = new Bridge(web);
            bridge = local;
            web.addJavascriptInterface(local, "Archive");
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest req) {
                    return true;
                }
                @Override public WebResourceResponse shouldInterceptRequest(WebView w, WebResourceRequest req) {
                    String url = req.getUrl().toString();
                    try {
                        if (PAGE.equals(url) || (ASSET_ORIGIN + "/device.js").equals(url)) {
                            boolean html = PAGE.equals(url);
                            return new WebResourceResponse(html ? "text/html" : "application/javascript",
                                    "utf-8", context.getAssets().open(html ? "device.html" : "device.js"));
                        }
                    } catch (IOException ignored) { }
                    return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden",
                            null, new ByteArrayInputStream(new byte[0]));
                }
                @Override public void onPageFinished(WebView w, String url) {
                    if (view == w && PAGE.equals(url)) {
                        w.evaluateJavascript("device.start(" + JSONObject.quote("wss" + origin.substring(5) + "/api/device")
                                + "," + JSONObject.quote(key) + ")", null);
                    }
                }
                @Override public boolean onRenderProcessGone(WebView w, RenderProcessGoneDetail detail) {
                    if (view == w) {
                        unmount();
                        main.postDelayed(RemoteLink.this::mount, 3000);
                    }
                    return true;
                }
            });
            web.loadUrl(PAGE);
        } catch (RuntimeException e) {
            Log.w("Doorcam", "WebView unavailable for remote viewing", e);
            unmount();
            main.postDelayed(this::mount, 30000);
        }
    }

    private void unmount() {
        if (bridge != null) bridge.close();
        liveRequested = false;
        bridge = null;
        WebView old = view;
        view = null;
        if (old != null) {
            old.removeJavascriptInterface("Archive");
            old.destroy();
        }
    }

    private HttpURLConnection connect(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(origin + path).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        c.setRequestProperty("Authorization", "Bearer " + key);
        return c;
    }

    private void scheduleUpload(long seconds) {
        if (uploads == null) return;
        if (nextUpload != null && !nextUpload.isDone()) {
            if (seconds != 0 || nextUpload.getDelay(TimeUnit.MILLISECONDS) <= 0) return;
            nextUpload.cancel(false);
        }
        ScheduledThreadPoolExecutor owner = uploads;
        nextUpload = owner.schedule(() -> {
            boolean ok = true;
            File[] pending = outbox.listFiles((d, name) -> name.matches("[0-9]{1,15}\\.(mp4|jpg|done)"));
            if (pending != null) {
                Arrays.sort(pending, Comparator.comparing((File file) -> file.getName().endsWith(".done"))
                        .thenComparing(BY_NEWEST.reversed()));
                for (File file : pending) {
                    if (Thread.currentThread().isInterrupted()) return;
                    if (!file.isFile()) continue;
                    boolean complete = file.getName().endsWith(".done");
                    try {
                        if (complete && new File(outbox, stamp(file) + ".mp4").isFile()) continue;
                        int code = complete ? uploadCompletion(file) : upload(stamp(file), file, false);
                        if (code >= 200 && code < 300) {
                            if (!file.delete() && file.exists()) ok = false;
                        } else {
                            ok = false;
                            // Skip rejected media so other pending events can still arrive.
                            if (!complete && code != 400 && code != 413 && code != 415) break;
                        }
                    } catch (IOException | RuntimeException e) {
                        if (!file.exists()) continue; // The archive evicted this event.
                        ok = false;
                        if (!complete) break;
                    }
                }
            }
            boolean success = ok;
            main.post(() -> {
                if (uploads != owner) return;
                backoff = success ? 0 : Math.min(backoff + 1, 7);
                nextUpload = null;
                scheduleUpload(success ? 20 : Math.min(300, 2L << backoff));
            });
        }, seconds, TimeUnit.SECONDS);
    }

    private int uploadCompletion(File marker) throws IOException {
        long id = stamp(marker);
        ArrayList<File> clips = new ArrayList<>();
        File event = new File(archive, Long.toString(id));
        for (String name : Files.readAllLines(marker.toPath(), StandardCharsets.US_ASCII)) {
            if (!name.matches("[0-9]{1,15}\\.mp4")) throw new IOException("Неверное имя фрагмента");
            clips.add(new File(event, name));
        }
        if (clips.isEmpty()) return upload(id, null, true);
        File joined = null;
        try {
            try {
                joined = Files.createTempFile(outbox.toPath(), id + ".", ".part").toFile();
                SegmentEncoder.join(joined, clips);
            } catch (IOException | RuntimeException e) {
                if (Thread.currentThread().isInterrupted() || !marker.isFile()) throw new IOException(e);
                Log.w("Doorcam", "Не удалось собрать полное видео для Telegram", e);
                return upload(id, null, true);
            }
            if (joined.length() > Archive.TELEGRAM_COMPLETE_BYTES) return upload(id, null, true);
            int code = upload(id, joined, true);
            return code == 400 || code == 413 || code == 415 ? upload(id, null, true) : code;
        } finally {
            if (joined != null) Files.deleteIfExists(joined.toPath());
        }
    }

    private int upload(long eventId, File media, boolean complete) throws IOException {
        String boundary = "doorcam-" + UUID.randomUUID();
        int offset = ZoneId.systemDefault().getRules().getOffset(Instant.ofEpochMilli(eventId)).getTotalSeconds() / 60;
        boolean video = complete || media != null && media.getName().endsWith(".mp4");
        String extension = video ? ".mp4" : ".jpg";
        String part = video ? "video" : "photo";
        String mime = video ? "video/mp4" : "image/jpeg";
        byte[] header = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"id\"\r\n\r\n"
                + eventId + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"offset\"\r\n\r\n"
                + offset + (complete ? "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"phase\"\r\n\r\ncomplete" : "")
                + (media == null ? "" : "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + part
                + "\"; filename=\"event" + extension + "\"\r\nContent-Type: " + mime + "\r\n\r\n")).getBytes(StandardCharsets.US_ASCII);
        byte[] footer = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        HttpURLConnection c = connect("/api/event");
        try (FileInputStream input = media == null ? null : new FileInputStream(media)) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setFixedLengthStreamingMode(header.length + (input == null ? 0 : input.getChannel().size()) + footer.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(header);
                if (input != null) {
                    byte[] chunk = new byte[8192];
                    int read;
                    while ((read = input.read(chunk)) != -1) out.write(chunk, 0, read);
                }
                out.write(footer);
            }
            return c.getResponseCode();
        } finally {
            c.disconnect();
        }
    }

    private static long stamp(File file) {
        String name = file.getName();
        int dot = name.indexOf('.');
        return Long.parseLong(dot < 0 ? name : name.substring(0, dot));
    }

    private static File[] segments(File dir) {
        File[] files = dir.listFiles((d, name) -> name.matches("[0-9]{1,15}\\.mp4"));
        return files == null ? new File[0] : files;
    }

    private final class Bridge {
        private final WebView owner;
        private final Map<Integer, FileInputStream> handles = new HashMap<>();
        private final ExecutorService network = Executors.newSingleThreadExecutor();
        private final byte[] chunk = new byte[16384];
        private int sequence;
        private boolean closed;

        Bridge(WebView owner) { this.owner = owner; }

        @JavascriptInterface public String captureState() { return MonitorService.captureState(); }

        @JavascriptInterface public synchronized void live(boolean needed) {
            if (!closed) liveRequested = needed;
        }

        @JavascriptInterface public synchronized void connection(String current) {
            if (!closed && ("online".equals(current) || "offline".equals(current)
                    || "connecting".equals(current) || "unauthorized".equals(current))) status(current);
        }

        @JavascriptInterface public synchronized void requestIce(int ticket) {
            if (closed) return;
            network.execute(() -> {
                String servers = "null";
                HttpURLConnection c = null;
                try {
                    c = connect("/api/ice");
                    if (c.getResponseCode() == 200) {
                        try (InputStream input = c.getInputStream()) {
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            byte[] buf = new byte[2048];
                            int n;
                            while ((n = input.read(buf)) != -1) {
                                if (bytes.size() + n > 65536) throw new IOException("Oversize ICE response");
                                bytes.write(buf, 0, n);
                            }
                            servers = new JSONObject(bytes.toString("UTF-8")).getJSONArray("iceServers").toString();
                        }
                    }
                } catch (Exception ignored) {
                    // The remote user can reconnect after the signaling service recovers.
                } finally {
                    if (c != null) c.disconnect();
                }
                String js = "device.ice(" + ticket + "," + servers + ")";
                main.post(() -> { if (view == owner) owner.evaluateJavascript(js, null); });
            });
        }

        @JavascriptInterface public String page(String before) {
            try {
                long cursor = before.isEmpty() ? Long.MAX_VALUE : Long.parseLong(before);
                File[] dirs = archive.listFiles((d, name) -> name.matches("[0-9]{1,15}"));
                JSONArray events = new JSONArray();
                long last = 0;
                if (dirs != null) {
                    Arrays.sort(dirs, BY_NEWEST);
                    for (File dir : dirs) {
                        long id = stamp(dir);
                        if (id >= cursor) continue;
                        int count = segments(dir).length;
                        if (count == 0) continue;
                        events.put(new JSONObject().put("id", dir.getName()).put("time", id).put("segments", count));
                        last = id;
                        if (events.length() == 50) break;
                    }
                }
                return new JSONObject().put("events", events)
                        .put("next", events.length() == 50 ? Long.toString(last) : "").toString();
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface public synchronized String open(String kind, String eventId, String after) {
            try {
                if (closed) return "";
                boolean current = "live".equals(kind);
                if (!current && !eventId.matches("[0-9]{1,15}")) return "";
                File dir = current ? live : new File(archive, eventId);
            if (!current && !dir.isDirectory()) return "{\"expired\":true}";
                File file;
                if ("preview".equals(kind)) {
                    file = new File(dir, "preview.jpg");
                } else if (current || "segment".equals(kind)) {
                    long cursor = after.isEmpty() ? 0 : Long.parseLong(after);
                    if (current) cursor = Math.max(cursor, System.currentTimeMillis() - 20000);
                boolean growing = !current && recordings.isGrowing(eventId);
                    file = null;
                    for (File candidate : segments(dir)) {
                        long t = stamp(candidate);
                        if (t <= cursor) continue;
                        if (file == null || (current ? t > stamp(file) : t < stamp(file))) file = candidate;
                    }
                    if (file == null) {
                        if (current) return "{}";
                        return growing ? "{\"growing\":true}" : "{}";
                    }
                } else return "";
                long size = file.length();
                if (size <= 0 || size > MAX_FILE || handles.size() >= 16) return "";
                int token = ++sequence;
                handles.put(token, new FileInputStream(file));
                return new JSONObject().put("token", token).put("size", size)
                        .put("stamp", "preview".equals(kind) ? eventId : Long.toString(stamp(file))).toString();
            } catch (Exception e) { return ""; }
        }

        @JavascriptInterface public synchronized String read(int token) {
            FileInputStream f = handles.get(token);
            if (f == null) return "";
            try {
                int read = f.read(chunk);
                return read > 0 ? Base64.encodeToString(chunk, 0, read, Base64.NO_WRAP) : "";
            } catch (IOException e) { return ""; }
        }
        @JavascriptInterface public synchronized void release(int token) {
            FileInputStream f = handles.remove(token);
            if (f != null) try { f.close(); } catch (IOException ignored) { }
        }
        synchronized void close() {
            closed = true;
            network.shutdownNow();
            for (int token : handles.keySet().toArray(new Integer[0])) release(token);
        }
    }
}
