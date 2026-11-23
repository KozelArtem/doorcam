package by.doorcam.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class CaptureHealthTest {
    @Test public void noMotionIsHealthyWhenFramesAndEncodedSegmentsContinue() {
        CaptureHealth health = new CaptureHealth(0);
        assertFalse(health.ready());
        health.frame(11_000);
        health.segment(12_000);
        assertTrue(health.ready());
        assertNull(health.failure(13_000));
        assertNotNull(health.failure(17_000));
    }

    @Test public void framesAloneCannotMaskAStalledEncoder() {
        CaptureHealth health = new CaptureHealth(10_000);
        health.frame(28_000);
        assertFalse(health.ready());
        assertNull(health.failure(29_000));
        assertNotNull(health.failure(30_000));
    }
}
