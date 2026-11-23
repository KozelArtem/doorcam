package by.doorcam.app;

import android.content.SharedPreferences;

final class MotionZone {
    static final int MIN_SIZE = 10;
    static final MotionZone FULL = new MotionZone(0, 0, 100, 100);
    final int left, top, right, bottom;

    MotionZone(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    static MotionZone read(SharedPreferences prefs) {
        int left = prefs.getInt("motionLeft", 0), top = prefs.getInt("motionTop", 0);
        int right = prefs.getInt("motionRight", 100), bottom = prefs.getInt("motionBottom", 100);
        return left >= 0 && top >= 0 && right > left && bottom > top && right <= 100 && bottom <= 100 &&
                right - left >= MIN_SIZE && bottom - top >= MIN_SIZE
                ? new MotionZone(left, top, right, bottom) : FULL;
    }

    void save(SharedPreferences prefs) {
        prefs.edit().putInt("motionLeft", left).putInt("motionTop", top)
                .putInt("motionRight", right).putInt("motionBottom", bottom).apply();
    }

    int[] sensorBounds(int rotation) {
        switch (rotation) {
            case 90: return new int[] {top, 100 - right, bottom, 100 - left};
            case 180: return new int[] {100 - right, 100 - bottom, 100 - left, 100 - top};
            case 270: return new int[] {100 - bottom, left, 100 - top, right};
            default: return new int[] {left, top, right, bottom};
        }
    }
}
