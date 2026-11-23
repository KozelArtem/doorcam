package by.doorcam.app;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LanTest {
    private static final String AUTH = "Authorization: Basic dmlld2VyOnNlY3JldA==\r\n";
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void archiveRequiresPasswordAndSupportsRanges() throws Exception {
        File event = temp.newFolder("1000");
        Files.write(new File(event, "2000.mp4").toPath(), "abcdef".getBytes(StandardCharsets.US_ASCII));
        try (LanServer server = new LanServer(InetAddress.getLoopbackAddress(), 0, "secret",
                temp.getRoot(), name -> null, new LiveMp4())) {
            String path = "/archive/1000/2000.mp4";
            assertTrue(request(server, "GET", path, "", "").startsWith("HTTP/1.1 401"));
            assertTrue(request(server, "GET", "/events", AUTH, "").contains("[1000]"));
            assertTrue(request(server, "GET", "/archive/1000", AUTH, "").contains("[2000]"));
            assertTrue(request(server, "GET", "/live.mp4", "", "").startsWith("HTTP/1.1 401"));
            assertTrue(request(server, "GET", "/live.mp4", AUTH, "").startsWith("HTTP/1.1 503"));
            String part = request(server, "GET", path, AUTH, "Range: bytes=2-4\r\n");
            assertTrue(part.startsWith("HTTP/1.1 206"));
            assertTrue(part.contains("Content-Range: bytes 2-4/6"));
            assertTrue(part.endsWith("\r\n\r\ncde"));
            assertTrue(request(server, "GET", path, AUTH, "Range: bytes=-2\r\n").endsWith("\r\n\r\nef"));
            assertTrue(request(server, "GET", path, AUTH, "Range: bytes=3-\r\n").endsWith("\r\n\r\ndef"));
            assertTrue(request(server, "GET", path, AUTH, "Range: bytes=6-\r\n").startsWith("HTTP/1.1 416"));
            assertTrue(request(server, "HEAD", path, AUTH, "").endsWith("\r\n\r\n"));
            assertTrue(request(server, "GET", "/archive/1000/../2000.mp4", AUTH, "").startsWith("HTTP/1.1 404"));
        }
    }

    @Test public void liveStartsAtKeyFrameAndKeepsDecodeTimeline() throws Exception {
        LiveMp4 live = live();
        try (LiveMp4.Viewer viewer = live.watch()) {
            assertEquals("avc1.42001e", viewer.codec);
            assertEquals(90, viewer.rotation);
            assertEquals("ftyp", type(viewer.init, 4));
            assertEquals("moov", type(viewer.init, ByteBuffer.wrap(viewer.init).getInt() + 4));
            live.sample(new byte[]{0, 0, 0, 1, 65, 1}, 1_000_000, false);
            live.sample(new byte[]{0, 0, 0, 1, 101, 2}, 1_060_000, true);
            assertTrue(viewer.frames.isEmpty());
            live.sample(new byte[]{0, 0, 0, 1, 65, 3}, 1_120_000, false);
            byte[] key = viewer.frames.remove();
            assertEquals("moof", type(key, 4));
            int mdat = ByteBuffer.wrap(key).getInt();
            assertEquals("mdat", type(key, mdat + 4));
            assertEquals(2, ByteBuffer.wrap(key).getInt(mdat + 8));
            live.sample(new byte[]{0, 0, 0, 1, 65, 4}, 1_181_000, false);
            byte[] delta = viewer.frames.remove();
            assertEquals("tfdt", type(delta, 52));
            assertEquals(0x01000000, ByteBuffer.wrap(delta).getInt(56));
            assertEquals(60_000L, ByteBuffer.wrap(delta).getLong(60));
            live.reset();
            assertTrue(viewer.closed);
        }
    }

    @Test public void authenticatedLiveResponseCarriesFramesOnOneConnection() throws Exception {
        LiveMp4 live = live();
        try (LanServer server = new LanServer(InetAddress.getLoopbackAddress(), 0, "secret",
                temp.getRoot(), name -> null, live);
             Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            socket.setSoTimeout(3000);
            send(socket, server, "GET", "/live.mp4", AUTH, "");
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            StringBuilder headers = new StringBuilder();
            while (!headers.toString().endsWith("\r\n\r\n")) headers.append((char) input.readUnsignedByte());
            assertTrue(headers.toString().startsWith("HTTP/1.1 200"));
            assertTrue(headers.toString().contains("X-Video-Codec: avc1.42001e"));
            readBox(input, "ftyp");
            readBox(input, "moov");
            live.sample(new byte[]{101, 1}, 66_000, true);
            live.sample(new byte[]{65, 2}, 132_000, false);
            live.sample(new byte[]{65, 3}, 198_000, false);
            readBox(input, "moof");
            readBox(input, "mdat");
            readBox(input, "moof");
            readBox(input, "mdat");
            live.reset();
            assertEquals(-1, input.read());
        }
    }

    private static LiveMp4 live() {
        LiveMp4 live = new LiveMp4();
        live.format(640, 480, 90, ByteBuffer.wrap(new byte[]{0, 0, 0, 1, 103, 66, 0, 30}),
                ByteBuffer.wrap(new byte[]{0, 0, 1, 104, 1}));
        return live;
    }

    private static void readBox(DataInputStream input, String expected) throws Exception {
        int size = input.readInt();
        assertTrue(size >= 8 && size < 1024);
        byte[] payload = new byte[size - 4];
        input.readFully(payload);
        assertEquals(expected, type(payload, 0));
    }

    private static String type(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }

    private static String request(LanServer server, String method, String path, String auth, String extra) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            socket.setSoTimeout(3000);
            send(socket, server, method, path, auth, extra);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[1024];
            for (int n; (n = input.read(buffer)) != -1;) body.write(buffer, 0, n);
            return body.toString("US-ASCII");
        }
    }

    private static void send(Socket socket, LanServer server, String method, String path, String auth, String extra)
            throws Exception {
        String headers = method + " " + path + " HTTP/1.1\r\nHost: " + server.authority() + "\r\n" + auth + extra + "\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
    }
}
