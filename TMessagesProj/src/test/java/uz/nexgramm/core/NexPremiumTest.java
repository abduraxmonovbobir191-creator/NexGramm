package uz.nexgramm.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NexPremiumTest {

    @Test
    public void parsesActiveStatus() throws Exception {
        final NexPremium.Status s = NexPremium.parse("{\"active\":true,\"plan\":\"pro\",\"until\":\"2026-11-01T10:00:00+00:00\","
            + "\"bot_username\":\"NexGrammBot\",\"plans\":[{\"code\":\"2w\",\"days\":14,\"price\":\"1 $\",\"method\":\"card\"},"
            + "{\"code\":\"1m\",\"days\":30,\"price\":\"4 $\",\"method\":\"soon\"}]}");
        assertTrue(s.active);
        assertTrue(s.activeAt(s.untilMs - 1));
        assertFalse(s.activeAt(s.untilMs + 1));
        assertEquals("NexGrammBot", s.botUsername);
        assertEquals(2, s.plans.size());
        assertTrue(s.plans.get(0).onSale);
        assertFalse(s.plans.get(1).onSale);
    }

    @Test
    public void parsesFreeStatus() throws Exception {
        final NexPremium.Status s = NexPremium.parse("{\"active\":false,\"plan\":\"free\",\"until\":null,\"plans\":[]}");
        assertFalse(s.active);
        assertEquals(0, s.untilMs);
        assertFalse(s.activeAt(System.currentTimeMillis()));
    }
}
