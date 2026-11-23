package by.doorcam.app;

import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class ResourceUsageTest {
    @Test public void cpuUsesTheLatestIntervalAndCanExceedOneCore() {
        ResourceUsage usage = new ResourceUsage();
        assertEquals("ЦП — · RAM 32 МиБ", usage.sample(10_000, 5_000, 32_768));
        assertEquals(String.format(Locale.getDefault(), "ЦП %.1f %% · RAM 32 МиБ", 125.0),
                usage.sample(15_000, 11_250, 32_768));
        assertEquals(String.format(Locale.getDefault(), "ЦП %.1f %% · RAM 65 МиБ", 2.5),
                usage.sample(19_000, 11_350, 66_560));
    }

    @Test public void unavailableMeasurementsUseADash() {
        ResourceUsage usage = new ResourceUsage();
        assertEquals("ЦП — · RAM —", usage.sample(10_000, 5_000, 0));
        assertEquals("ЦП — · RAM —", usage.sample(10_000, 5_000, 0));
    }
}
