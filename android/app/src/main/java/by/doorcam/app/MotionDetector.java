package by.doorcam.app;

import java.util.Arrays;

final class MotionDetector {
    private final double fraction;
    private final int[] histogram = new int[511];
    private byte[] previous;
    private MotionZone zone;
    private int columns, rows, rotation, left, top, right, bottom;
    MotionDetector(int sensitivity) {
        fraction = 0.10 - 0.00085 * Math.max(0, Math.min(100, sensitivity));
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
        int tolerance = Math.min(20, 10 + Math.abs(middle - 255) / 2);
        int unchanged = 0;
        for (int bin = Math.max(0, middle - tolerance); bin <= Math.min(histogram.length - 1, middle + tolerance); bin++)
            unchanged += histogram[bin];
        System.arraycopy(pixels, 0, previous, 0, pixels.length);
        return countPixels - unchanged >= fraction * countPixels;
    }

    private static int sampleBoundary(int percent, int samples) {
        // Each sample represents the center of its grid cell.
        return (percent * samples + 49) / 100;
    }
}
