package by.doorcam.app;

import java.util.Locale;

final class ResourceUsage {
    private long previousUptimeMs = -1;
    private long previousCpuMs;

    String sample(long uptimeMs, long cpuMs, long pssKib) {
        long elapsed = uptimeMs - previousUptimeMs;
        long used = cpuMs - previousCpuMs;
        Locale locale = Locale.getDefault();
        String cpu = previousUptimeMs >= 0 && elapsed > 0 && used >= 0
                ? String.format(locale, "%.1f %%", 100.0 * used / elapsed) : "—";
        String ram = pssKib > 0 ? String.format(locale, "%d МиБ", Math.round(pssKib / 1024.0)) : "—";
        previousUptimeMs = uptimeMs;
        previousCpuMs = cpuMs;
        return "ЦП " + cpu + " · RAM " + ram;
    }
}
