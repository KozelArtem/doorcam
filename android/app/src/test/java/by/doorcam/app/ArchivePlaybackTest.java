package by.doorcam.app;

import static org.junit.Assert.*;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ArchivePlaybackTest {
    @Rule public TemporaryFolder root = new TemporaryFolder();

    @Test public void anEventGrowsUntilVideoCoversItsTailOrCaptureStops() throws Exception {
        Archive archive = new Archive(root.getRoot(), 1024 * 1024);
        archive.motion(11_000, new byte[] {1});
        archive.finish(new Archive.Segment(10_000, file -> Files.write(file.toPath(), new byte[] {2})), 18_000);
        assertTrue(archive.isGrowing("11000"));
        archive.finish(new Archive.Segment(18_001, file -> Files.write(file.toPath(), new byte[] {3})), 28_000);
        assertFalse(archive.isGrowing("11000"));
        archive.motion(30_000, new byte[] {4});
        assertTrue(archive.isGrowing("30000"));
        archive.endCapture();
        assertFalse(archive.isGrowing("30000"));
    }
}
