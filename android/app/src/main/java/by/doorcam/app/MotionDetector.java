package by.doorcam.app;

import java.util.Arrays;

final class MotionDetector {
    private static final int DIMMING_TOLERANCE = 12;
    private final double fraction;
    private final int[] histogram = new int[511];
    private byte[] previous;
    private MotionZone zone;
    private int columns, rows, rotation, left, top, right, bottom;
    private boolean dimming;
    MotionDetector(int sensitivity) {
        fraction = 0.10 - 0.00085 * Math.max(0, Math.min(100, sensitivity));
    }

    void reset() {
        previous = null;
    }

    boolean observe(byte[] pixels) {
        return observe(pixels, pixels.length, 1, 0, MotionZone.FULL);
    }

    boolean observe(byte[] pixels, int columns, int rows, int rotation, MotionZone zone) {
        if (pixels.length == 0) return false;
        if (previous == null || this.zone != zone || this.columns != columns || this.rows != rows || this.rotation != rotation) {
            this.zone = zone;
            this.columns = columns;
            this.rows = rows;
            this.rotation = rotation;
            int[] bounds = zone.sensorBounds(rotation);
            left = sampleBoundary(bounds[0], columns);
            top = sampleBoundary(bounds[1], rows);
            right = sampleBoundary(bounds[2], columns);
            bottom = sampleBoundary(bounds[3], rows);
            previous = Arrays.copyOf(pixels, pixels.length);
            dimming = false;
            return false;
        }
        int countPixels = (right - left) * (bottom - top);
        if (countPixels == 0) return false;
        // Compensate automatic exposure with the median signed change of sampled pixels.
        Arrays.fill(histogram, 0);
        for (int row = top; row < bottom; row++) {
            int offset = row * columns;
            for (int i = offset + left, end = offset + right; i < end; i++)
                histogram[255 + (pixels[i] & 255) - (previous[i] & 255)]++;
        }
        int middle = 0, count = 0;
        while ((count += histogram[middle]) < (countPixels + 1) / 2) middle++;
        // Larger exposure shifts can change highlights and shadows by different amounts.
        int shift = middle - 255;
        int tolerance = Math.min(20, 10 + Math.abs(shift) / 2);
        int unchanged = 0;
        for (int bin = Math.max(0, middle - tolerance); bin <= Math.min(histogram.length - 1, middle + tolerance); bin++)
            unchanged += histogram[bin];
        double threshold = fraction * countPixels;
        boolean moving = countPixels - unchanged >= threshold;
        // Keep compensating while the scene settles after the initial drop.
        dimming = shift < -tolerance || (dimming && shift < 0);
        if (moving && dimming) moving = motionAfterDimming(pixels, threshold);
        System.arraycopy(pixels, 0, previous, 0, pixels.length);
        return moving;
    }

    private boolean motionAfterDimming(byte[] pixels, double threshold) {
        // With a sharp drop in light, bright surfaces can lose more luma than dark ones.
        // Fit the median brightness ratio so a moving patch does not set the exposure.
        Arrays.fill(histogram, 0);
        int samples = 0;
        for (int row = top; row < bottom; row++) {
            int offset = row * columns;
            for (int i = offset + left, end = offset + right; i < end; i++) {
                int before = previous[i] & 255;
                if (before == 0) continue;
                int after = pixels[i] & 255;
                histogram[Math.min(255, (255 * after + before / 2) / before)]++;
                samples++;
            }
        }
        int gain = 0, cumulative = 0;
        while ((cumulative += histogram[gain]) < (samples + 1) / 2) gain++;
        int changed = 0;
        for (int row = top; row < bottom; row++) {
            int offset = row * columns;
            for (int i = offset + left, end = offset + right; i < end; i++) {
                int residual = 255 * (pixels[i] & 255) - gain * (previous[i] & 255);
                if (Math.abs(residual) > 255 * DIMMING_TOLERANCE && ++changed >= threshold) return true;
            }
        }
        return false;
    }

    private static int sampleBoundary(int percent, int samples) {
        // Each sample represents the center of its grid cell.
        return (percent * samples + 49) / 100;
    }
}
