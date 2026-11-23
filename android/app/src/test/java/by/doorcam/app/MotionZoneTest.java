package by.doorcam.app;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class MotionZoneTest {
    @Test public void motionOutsideTheZoneIsIgnored() {
        MotionDetector detector = new MotionDetector(50);
        MotionZone leftHalf = new MotionZone(0, 0, 50, 100);
        byte[] pixels = new byte[32];
        assertFalse(detector.observe(pixels, 8, 4, 0, leftHalf));
        Arrays.fill(pixels, 4, 8, (byte) 100);
        assertFalse(detector.observe(pixels, 8, 4, 0, leftHalf));
        Arrays.fill(pixels, 0, 2, (byte) 100);
        assertTrue(detector.observe(pixels, 8, 4, 0, leftHalf));
    }

    @Test public void zoneMatchesTheUprightPreviewAtEveryRightAngle() {
        MotionZone topLeft = new MotionZone(0, 0, 50, 50);
        int[] rotations = {0, 90, 180, 270};
        int[] inside = {0, 24, 31, 7};
        for (int i = 0; i < rotations.length; i++) {
            MotionDetector detector = new MotionDetector(50);
            byte[] pixels = new byte[32];
            assertFalse(detector.observe(pixels, 8, 4, rotations[i], topLeft));
            pixels[31 - inside[i]] = 100;
            assertFalse(detector.observe(pixels, 8, 4, rotations[i], topLeft));
            pixels[inside[i]] = 100;
            assertTrue(detector.observe(pixels, 8, 4, rotations[i], topLeft));
        }
    }

    @Test public void exposureCompensationAndNewZoneBaselineUseOnlySelectedPixels() {
        MotionDetector detector = new MotionDetector(50);
        MotionZone leftHalf = new MotionZone(0, 0, 50, 100);
        byte[] pixels = new byte[32];
        assertFalse(detector.observe(pixels, 8, 4, 0, leftHalf));
        for (int row = 0; row < 4; row++) Arrays.fill(pixels, row * 8, row * 8 + 4, (byte) 50);
        assertFalse(detector.observe(pixels, 8, 4, 0, leftHalf));
        MotionZone rightHalf = new MotionZone(50, 0, 100, 100);
        pixels[4] = pixels[5] = 100;
        assertFalse(detector.observe(pixels, 8, 4, 0, rightHalf));
        pixels[4] = pixels[5] = 0;
        assertTrue(detector.observe(pixels, 8, 4, 0, rightHalf));
    }
}
