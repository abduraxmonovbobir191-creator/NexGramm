package uz.nexgramm.core;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

public class NexParallelTest {

    @Before
    public void setUp() {
        NexParallel.reset();
    }

    @Test
    public void limitsByNetworkType() {
        assertEquals(1, NexParallel.limit(NexParallel.NETWORK_SLOW, -1, false));
        assertEquals(2, NexParallel.limit(NexParallel.NETWORK_SLOW, -1, true));
        assertEquals(2, NexParallel.limit(NexParallel.NETWORK_MOBILE, -1, false));
        assertEquals(4, NexParallel.limit(NexParallel.NETWORK_WIFI, -1, false));
        assertEquals(6, NexParallel.limit(NexParallel.NETWORK_WIFI, -1, true));
    }

    @Test
    public void limitsByMeasuredSpeed() {
        assertEquals(1, NexParallel.limit(NexParallel.NETWORK_WIFI, 20, false));
        assertEquals(3, NexParallel.limit(NexParallel.NETWORK_MOBILE, 900, false));
        assertEquals(6, NexParallel.limit(NexParallel.NETWORK_WIFI, 5000, false));
        assertEquals(8, NexParallel.limit(NexParallel.NETWORK_WIFI, 5000, true));
    }

    @Test
    public void speedIsAMovingAverage() {
        NexParallel.onUploaded(1024 * 1024, 1000); // 1024 KB/s
        assertEquals(1024, NexParallel.measuredKbps(), 0.01);
        NexParallel.onUploaded(1024 * 1024, 4000); // 256 KB/s
        assertEquals(1024 * 0.7 + 256 * 0.3, NexParallel.measuredKbps(), 0.01);
        NexParallel.onUploaded(1000, 10); // too small: ignored
        assertEquals(1024 * 0.7 + 256 * 0.3, NexParallel.measuredKbps(), 0.01);
        assertEquals(4, NexParallel.maxUploads(NexParallel.NETWORK_WIFI, false));
    }
}
