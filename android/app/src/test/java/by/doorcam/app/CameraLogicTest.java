package by.doorcam.app;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class CameraLogicTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void localChangeTriggersButGlobalBrightnessDoesNot() {
        MotionDetector detector = new MotionDetector(50);
        byte[] pixels = new byte[1600];
        Arrays.fill(pixels, (byte) 100);
        assertFalse(detector.observe(pixels));
        Arrays.fill(pixels, (byte) 132);
        assertFalse(detector.observe(pixels));
        Arrays.fill(pixels, 0, 220, (byte) 222);
        assertTrue(detector.observe(pixels));
        assertFalse(detector.observe(pixels));
    }

    @Test public void exposureCompensationIncludesTwentyLevelsAtHistogramEdges() {
        MotionDetector detector = new MotionDetector(50);
        byte[] pixels = new byte[100];
        assertFalse(detector.observe(pixels));
        Arrays.fill(pixels, (byte) 255);
        Arrays.fill(pixels, 0, 10, (byte) 235);
        assertFalse(detector.observe(pixels));
        Arrays.fill(pixels, (byte) 0);
        Arrays.fill(pixels, 0, 10, (byte) 1);
        assertTrue(detector.observe(pixels));
        Arrays.fill(pixels, (byte) 255);
        assertFalse(detector.observe(pixels));
    }

    @Test public void twoLowContrastPassesQueueSeparateAlerts() throws Exception {
        MotionDetector detector = new MotionDetector(50);
        Archive archive = new Archive(temp.getRoot(), 1024 * 1024);
        byte[] pixels = new byte[48 * 32];
        Arrays.fill(pixels, (byte) 70);
        assertFalse(detector.observe(pixels, 48, 32, 0, MotionZone.FULL));
        File outbox = new File(temp.getRoot(), "outbox");
        for (long start : new long[]{10_000, 30_000}) {
            for (int row = 8; row < 20; row++)
                Arrays.fill(pixels, row * 48 + 20, row * 48 + 28, (byte) 86);
            assertTrue(detector.observe(pixels, 48, 32, 0, MotionZone.FULL));
            assertTrue(archive.motion(start + 1000, new byte[]{1}));
            assertTrue(archive.finish(segment(start), start + 10_000));
            assertTrue(new File(outbox, (start + 1000) + ".mp4").isFile());
            Arrays.fill(pixels, (byte) 70);
            assertTrue(detector.observe(pixels, 48, 32, 0, MotionZone.FULL));
            assertFalse(detector.observe(pixels, 48, 32, 0, MotionZone.FULL));
        }
    }

    @Test public void exposureStepCanVaryAcrossTheFrameWithoutTriggering() {
        MotionDetector detector = new MotionDetector(50);
        byte[] pixels = new byte[100];
        Arrays.fill(pixels, (byte) 50);
        assertFalse(detector.observe(pixels));
        Arrays.fill(pixels, (byte) 85);
        Arrays.fill(pixels, 0, 20, (byte) 104);
        assertFalse(detector.observe(pixels));
    }

    @Test public void shortPassKeepsPreRollAndTailAndQueuesTheSegmentWithMotion() throws Exception {
        Archive archive = new Archive(temp.getRoot(), 1024 * 1024);
        Archive.Segment first = segment(10000);
        archive.finish(first, 12000);
        assertEquals(0, archive.liveDir.list().length);
        Archive.Segment second = segment(12000);
        archive.motion(13000, new byte[]{1});
        archive.motion(14000, new byte[]{2});
        File outbox = new File(temp.getRoot(), "outbox");
        assertEquals(0, outbox.list().length);
        archive.finish(second, 14000);
        archive.finish(segment(18000), 20000);
        archive.finish(segment(26000), 28000);
        File event = new File(archive.archiveDir, "13000");
        assertTrue(new File(event, "10000.mp4").exists());
        assertTrue(new File(event, "12000.mp4").exists());
        assertTrue(new File(event, "18000.mp4").exists());
        assertFalse(new File(event, "26000.mp4").exists());
        assertTrue(new File(outbox, "13000.mp4").isFile());
        File pending = new File(outbox, "13000.mp4");
        assertQueuedCopy(new File(event, "12000.mp4"), pending);
        new Archive(temp.getRoot(), 1024 * 1024);
        assertTrue(pending.isFile());
    }

    @Test public void twoMinutesOfMotionKeepEveryClipAndQueueOneTelegramVideo() throws Exception {
        Archive archive = new Archive(temp.getRoot(), 1024 * 1024);
        assertTrue(archive.motion(10_000, new byte[]{1}));
        File event = new File(archive.archiveDir, "10000");
        for (long start = 10_000; start <= 120_000; start += 10_000) {
            assertFalse(archive.motion(start + 4_000, null));
            assertFalse(archive.motion(start + 9_999, null));
            assertEquals(start == 10_000, archive.finish(segment(start), start + 10_000));
            assertTrue(new File(event, start + ".mp4").isFile());
            assertTrue(archive.isGrowing("10000"));
        }
        assertTrue(archive.finish(segment(130_000), 140_000));
        assertTrue(new File(event, "130000.mp4").isFile());
        assertFalse(archive.isGrowing("10000"));
        assertArrayEquals(new String[]{"10000"}, archive.archiveDir.list());
        File outbox = new File(temp.getRoot(), "outbox");
        assertTrue(new File(outbox, "10000.mp4").isFile());
        java.util.List<String> clips = Files.readAllLines(new File(outbox, "10000.done").toPath());
        assertEquals(13, clips.size());
        assertEquals("10000.mp4", clips.get(0));
        assertEquals("130000.mp4", clips.get(12));
        assertEquals(2, outbox.list().length);
    }

    @Test public void longerMotionKeepsTheArchiveAndQueuesOnlyACaptionUpdate() throws Exception {
        Archive archive = new Archive(temp.getRoot(), 1024 * 1024);
        archive.motion(10_000, new byte[]{1});
        for (long start = 10_000; start <= 130_000; start += 10_000) {
            archive.motion(start + 4_000, null);
            archive.motion(start + 9_999, null);
            archive.finish(segment(start), start + 10_000);
        }
        assertTrue(archive.finish(segment(140_000), 150_000));
        assertTrue(new File(archive.archiveDir, "10000/140000.mp4").isFile());
        File marker = new File(temp.getRoot(), "outbox/10000.done");
        assertEquals(0, Files.size(marker.toPath()));
    }

    @Test public void evictionBoundsContinuousMotionAndRemovesOrphans() throws Exception {
        Archive archive = new Archive(temp.getRoot(), 55);
        archive.motion(10000, new byte[]{1});
        for (long t = 10000; t <= 30000; t += 2000) {
            archive.motion(t, new byte[]{1});
            archive.finish(segment(t), t + 2000);
        }
        File active = new File(archive.archiveDir, "10000");
        assertFalse(new File(active, "10000.mp4").exists());
        assertTrue(new File(active, "30000.mp4").exists());
        Files.write(new File(active, "99999.part").toPath(), new byte[]{2});
        new Archive(temp.getRoot(), 55);
        assertFalse(new File(active, "99999.part").exists());
    }

    @Test public void longClipCoversTwoMotionEventsAndProvidesPreRoll() throws Exception {
        Archive archive = new Archive(temp.getRoot(), 1024 * 1024);
        archive.motion(11000, new byte[]{1});
        archive.motion(19500, new byte[]{2});
        archive.finish(segment(10000), 20000);
        archive.motion(28500, new byte[]{3});
        assertTrue(new File(archive.archiveDir, "11000/10000.mp4").isFile());
        assertTrue(new File(archive.archiveDir, "19500/10000.mp4").isFile());
        archive.finish(segment(20000), 30000);
        assertTrue(new File(archive.archiveDir, "28500/20000.mp4").isFile());
        assertFalse(new File(archive.archiveDir, "28500/10000.mp4").exists());
    }

    @Test public void videoEvictionAlsoDropsPendingVideoAndLiveIsOnDemand() throws Exception {
        Archive archive = new Archive(temp.getRoot(), 25);
        archive.motion(10000, new byte[]{1});
        archive.finish(segment(10000), 12000);
        archive.motion(30000, new byte[]{1});
        archive.finish(segment(30000), 32000);
        assertNotNull(archive.finishLive(segment(30000), 32000));
        assertFalse(new File(temp.getRoot(), "outbox/10000.mp4").exists());
        assertTrue(new File(temp.getRoot(), "outbox/30000.mp4").exists());
        assertEquals(1, archive.liveDir.list().length);
    }

    @Test public void localOnlyEventsKeepPreviewAndArchiveWithoutAnUpload() throws Exception {
        Archive archive = new Archive(temp.getRoot(), 1024 * 1024, false);
        archive.motion(15000, new byte[]{1});
        assertFalse(archive.finish(segment(10000), 20000));
        assertTrue(new File(archive.archiveDir, "15000/preview.jpg").isFile());
        assertTrue(new File(archive.archiveDir, "15000/10000.mp4").isFile());
        assertEquals(0, new File(temp.getRoot(), "outbox").list().length);
    }

    @Test public void blockedOutboxDoesNotStopRecordingAndRetriesTheSameClip() throws Exception {
        Archive archive = new Archive(temp.getRoot(), 1024 * 1024);
        archive.motion(15000, new byte[]{1});
        File pending = new File(temp.getRoot(), "outbox/15000.mp4");
        assertTrue(pending.mkdir());
        assertFalse(archive.finish(segment(10000), 20000));
        File first = new File(archive.archiveDir, "15000/10000.mp4");
        assertTrue(first.isFile());
        assertTrue(pending.delete());
        assertTrue(archive.finish(segment(20000), 30000));
        assertQueuedCopy(first, pending);
        assertFalse(new File(temp.getRoot(), "outbox/15000.mp4.part").exists());
    }

    @Test public void splitNearTheMotionOnsetIsDeferredWithABoundedDuration() {
        assertFalse(SegmentEncoder.archiveBoundary(9_999_999, 0, Long.MIN_VALUE));
        assertTrue(SegmentEncoder.archiveBoundary(10_000_000, 20_000, 16_000));
        assertFalse(SegmentEncoder.archiveBoundary(10_000_000, 20_000, 19_850));
        assertTrue(SegmentEncoder.archiveBoundary(11_000_000, 21_000, 19_850));
        assertTrue(SegmentEncoder.archiveBoundary(11_750_000, 21_750, 21_700));
    }

    private Archive.Segment segment(long start) {
        byte[] bytes = new byte[17];
        java.nio.ByteBuffer.wrap(bytes).putLong(start);
        return new Archive.Segment(start, f -> Files.write(f.toPath(), bytes));
    }

    private static void assertQueuedCopy(File archived, File pending) throws Exception {
        assertArrayEquals(Files.readAllBytes(archived.toPath()), Files.readAllBytes(pending.toPath()));
        assertFalse(Files.isSameFile(archived.toPath(), pending.toPath()));
    }
}
