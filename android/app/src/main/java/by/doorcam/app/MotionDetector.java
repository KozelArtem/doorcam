package by.doorcam.app;

import java.util.Arrays;

final class MotionDetector {
    private final double fraction;
    private final int[] histogram = new int[511];
    private byte[] previous;
    MotionDetector(int sensitivity) {
        fraction = 0.13 - 0.0011 * Math.max(0, Math.min(100, sensitivity));
    }

    boolean observe(byte[] pixels) {
        if (previous == null || previous.length != pixels.length || pixels.length == 0) {
            previous = Arrays.copyOf(pixels, pixels.length);
            return false;
        }
        // Compensate automatic exposure with the median signed change of sampled pixels.
        Arrays.fill(histogram, 0);
        for (int i = 0; i < pixels.length; i++)
            histogram[255 + (pixels[i] & 255) - (previous[i] & 255)]++;
        int middle = 0, count = 0;
        while ((count += histogram[middle]) < (pixels.length + 1) / 2) middle++;
        int unchanged = 0;
        for (int bin = Math.max(0, middle - 20); bin <= Math.min(histogram.length - 1, middle + 20); bin++)
            unchanged += histogram[bin];
        System.arraycopy(pixels, 0, previous, 0, pixels.length);
        return pixels.length - unchanged >= fraction * pixels.length;
    }
}
