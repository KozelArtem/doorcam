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

    @Test public void shortPassKeepsPreRollAndTailAndQueuesOnePhoto() throws Exception {
        Archive archive = new Archive(temp.getRoot(), 1024 * 1024);
        Archive.Segment first = segment(10000);
        archive.finish(first, 12000);
        assertEquals(0, archive.liveDir.list().length);
        Archive.Segment second = segment(12000);
        assertNotNull(archive.motion(13000, new byte[]{1}));
        assertNull(archive.motion(14000, new byte[]{2}));
        archive.finish(second, 14000);
        archive.finish(segment(18000), 20000);
        archive.finish(segment(26000), 28000);
        File event = new File(archive.archiveDir, "13000");
        assertTrue(new File(event, "10000.mp4").exists());
        assertTrue(new File(event, "12000.mp4").exists());
        assertTrue(new File(event, "18000.mp4").exists());
        assertFalse(new File(event, "26000.mp4").exists());
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(new File(temp.getRoot(), "outbox/13000.jpg").toPath()));
        assertNotNull(archive.motion(30000, new byte[]{3}));
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

    @Test public void videoEvictionAlsoDropsPendingPhotoAndLiveIsOnDemand() throws Exception {
        Archive archive = new Archive(temp.getRoot(), 25);
        archive.motion(10000, new byte[]{1});
        archive.finish(segment(10000), 12000);
        archive.motion(30000, new byte[]{1});
        archive.finish(segment(30000), 32000);
        assertNotNull(archive.finishLive(segment(30000), 32000));
        assertFalse(new File(temp.getRoot(), "outbox/10000.jpg").exists());
        assertTrue(new File(temp.getRoot(), "outbox/30000.jpg").exists());
        assertEquals(1, archive.liveDir.list().length);
    }

    private Archive.Segment segment(long start) {
        return new Archive.Segment(start, f -> Files.write(f.toPath(), new byte[17]));
    }
}
