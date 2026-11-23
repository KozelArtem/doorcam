package by.doorcam.app;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LanServer implements AutoCloseable {
    static final int PORT = 8080;
    static final String USER = "viewer";
    private static final String ID = "[0-9]{1,15}";
    private static final Pattern RECORDING = Pattern.compile("/archive/(" + ID + ")/(" + ID + ")\\.mp4");
    private static final Pattern RANGE = Pattern.compile("bytes=([0-9]*)-([0-9]*)");
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(15);
    private final ServerSocket server;
    private final File archive;
    private final Assets assets;
    private final LiveMp4 live;
    private final byte[] credential;
    private final Map<Socket, Long> deadlines = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>());
    private final Thread listener;

    interface Assets { InputStream open(String name) throws IOException; }

    LanServer(InetAddress address, int port, String password, File archive, Assets assets, LiveMp4 live)
            throws IOException {
        this.archive = archive;
        this.assets = assets;
        this.live = live;
        credential = utf8("Basic " + Base64.getEncoder().encodeToString(utf8(USER + ":" + password)));
        server = new ServerSocket();
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(address, port), 8);
            server.setSoTimeout(1000);
        } catch (IOException e) {
            server.close();
            throw e;
        }
        listener = new Thread(this::listen, "DoorcamLAN");
        listener.start();
    }

    int port() { return server.getLocalPort(); }
    String authority() { return server.getInetAddress().getHostAddress() + ":" + port(); }

    private void listen() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                deadlines.put(socket, System.nanoTime() + TIMEOUT);
                try {
                    clients.execute(() -> serve(socket));
                } catch (RejectedExecutionException e) {
                    disconnect(socket);
                }
            } catch (SocketTimeoutException ignored) {
            } catch (IOException e) {
                break;
            }
            long now = System.nanoTime();
            deadlines.forEach((socket, deadline) -> {
                if (now - deadline >= 0 && deadlines.remove(socket, deadline)) closeSocket(socket);
            });
        }
    }

    private void serve(Socket socket) {
        try (Socket connection = socket) {
            connection.setSoTimeout(4000);
            connection.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(connection.getInputStream());
            OutputStream out = connection.getOutputStream();
            String[] line = readLine(in, 2048).split(" ", -1);
            if (line.length != 3 || !("HTTP/1.1".equals(line[2]) || "HTTP/1.0".equals(line[2]))) {
                respond(out, "400 Bad Request", "", null, false);
                return;
            }
            String method = line[0], path = line[1];
            boolean head = "HEAD".equals(method);
            Map<String, String> headers = new HashMap<>();
            int remaining = 8192;
            while (true) {
                String value = readLine(in, remaining);
                if (value.isEmpty()) break;
                remaining -= value.length() + 2;
                int colon = value.indexOf(':');
                if (colon < 1 || headers.putIfAbsent(value.substring(0, colon).toLowerCase(java.util.Locale.ROOT),
                        value.substring(colon + 1).trim()) != null) throw new IOException("Invalid HTTP header");
            }
            if (!authority().equals(headers.get("host"))) {
                respond(out, "400 Bad Request", "", null, head);
                return;
            }
            if (!head && !"GET".equals(method)) {
                respond(out, "405 Method Not Allowed", "Allow: GET, HEAD\r\n", null, false);
                return;
            }
            if (!MessageDigest.isEqual(credential, utf8(headers.getOrDefault("authorization", "")))) {
                respond(out, "401 Unauthorized", "WWW-Authenticate: Basic realm=\"Doorcam\"\r\n", null, head);
                return;
            }
            Matcher clip = RECORDING.matcher(path);
            if (clip.matches()) {
                video(connection, out, new File(new File(archive, clip.group(1)), clip.group(2) + ".mp4"),
                        headers.get("range"), head);
            } else if ("/events".equals(path)) {
                listing(out, archive, false, head);
            } else if (path.matches("/archive/" + ID)) {
                listing(out, new File(archive, path.substring("/archive/".length())), true, head);
            } else if ("/live.mp4".equals(path) && !head) {
                stream(connection, out);
            } else if ("/".equals(path)) {
                asset(out, "lan.html", "text/html; charset=utf-8", head);
            } else if ("/lan.js".equals(path)) {
                asset(out, "lan.js", "text/javascript; charset=utf-8", head);
            } else {
                respond(out, "404 Not Found", "", null, head);
            }
        } catch (IOException ignored) {
        } finally {
            deadlines.remove(socket);
        }
    }

    private static String readLine(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (line.size() < limit) {
            int c = in.read();
            if (c == '\r' && in.read() == '\n') return line.toString("US-ASCII");
            if (c < 32 || c > 126) throw new IOException("Invalid HTTP line");
            line.write(c);
        }
        throw new IOException("HTTP headers too large");
    }

    private void asset(OutputStream out, String name, String type, boolean head) throws IOException {
        try (InputStream source = assets.open(name)) {
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = source.read(buffer)) != -1;) content.write(buffer, 0, n);
            respond(out, "200 OK", "Content-Type: " + type + "\r\n", content.toByteArray(), head);
        }
    }

    private static void listing(OutputStream out, File dir, boolean clips, boolean head) throws IOException {
        String[] names = dir.list((parent, name) -> clips ? name.matches(ID + "\\.mp4")
                : name.matches(ID) && new File(parent, name).isDirectory());
        if (names == null && clips) {
            respond(out, "404 Not Found", "", null, head);
            return;
        }
        long[] timestamps = names == null ? new long[0] : Arrays.stream(names)
                .mapToLong(name -> Long.parseLong(clips ? name.substring(0, name.length() - 4) : name)).sorted().toArray();
        respond(out, "200 OK", "Content-Type: application/json\r\n", utf8(Arrays.toString(timestamps)), head);
    }

    private void video(Socket socket, OutputStream out, File file, String range, boolean head) throws IOException {
        RandomAccessFile recording;
        try { recording = new RandomAccessFile(file, "r"); }
        catch (FileNotFoundException e) {
            respond(out, "404 Not Found", "", null, head);
            return;
        }
        try (RandomAccessFile source = recording) {
            long length = source.length(), first = 0, last = length - 1;
            String extra = "Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\n";
            Matcher match = range == null ? null : RANGE.matcher(range);
            boolean partial = !head && match != null && match.matches()
                    && (!match.group(1).isEmpty() || !match.group(2).isEmpty());
            if (partial) {
                String start = match.group(1), end = match.group(2);
                if (start.isEmpty()) first = Math.max(0, length - rangeNumber(end));
                else {
                    first = rangeNumber(start);
                    if (!end.isEmpty()) last = Math.min(last, rangeNumber(end));
                }
                if (first > last) {
                    respond(out, "416 Range Not Satisfiable", extra + "Content-Range: bytes */" + length + "\r\n", null, false);
                    return;
                }
                extra += "Content-Range: bytes " + first + "-" + last + "/" + length + "\r\n";
            }
            long left = last - first + 1;
            headers(out, partial ? "206 Partial Content" : "200 OK", extra + "Content-Length: " + left + "\r\n");
            if (head) return;
            source.seek(first);
            byte[] buffer = new byte[16384];
            while (left > 0) {
                int n = source.read(buffer, 0, (int) Math.min(left, buffer.length));
                if (n < 0) break;
                out.write(buffer, 0, n);
                deadlines.replace(socket, System.nanoTime() + TIMEOUT);
                left -= n;
            }
        }
    }

    private static long rangeNumber(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { return Long.MAX_VALUE; }
    }

    private void stream(Socket socket, OutputStream out) throws IOException {
        try (LiveMp4.Viewer viewer = live.watch()) {
            if (viewer == null) {
                respond(out, "503 Service Unavailable", "", null, false);
                return;
            }
            headers(out, "200 OK", "Content-Type: video/mp4\r\nX-Video-Codec: " + viewer.codec
                    + "\r\nX-Video-Rotation: " + viewer.rotation + "\r\n");
            out.write(viewer.init);
            while (!viewer.closed) {
                byte[] frame = viewer.frames.poll(5, TimeUnit.SECONDS);
                if (frame == null || frame.length == 0) break;
                out.write(frame);
                deadlines.replace(socket, System.nanoTime() + TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(OutputStream out, String status, String extra, byte[] body, boolean head) throws IOException {
        headers(out, status, extra + "Content-Length: " + (body == null ? 0 : body.length) + "\r\n");
        if (!head && body != null) out.write(body);
    }

    private static void headers(OutputStream out, String status, String extra) throws IOException {
        out.write(utf8("HTTP/1.1 " + status + "\r\nConnection: close\r\nCache-Control: no-store\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Content-Security-Policy: default-src 'self'; media-src 'self' blob:; style-src 'self' 'unsafe-inline'; "
                + "frame-ancestors 'none'; base-uri 'none'\r\n" + extra + "\r\n"));
    }

    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static void closeSocket(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    private void disconnect(Socket socket) {
        deadlines.remove(socket);
        closeSocket(socket);
    }

    @Override public void close() {
        try { server.close(); } catch (IOException ignored) { }
        clients.shutdownNow();
        deadlines.keySet().forEach(this::disconnect);
    }
}
