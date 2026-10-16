package uz.nexgramm.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// JVM test of the ads API bodies (CONTRACT "Ads": feed pages, meta filters, unsafe links dropped)
public class NexOwnAdsTest {

    @Test
    public void feedPage() throws Exception {
        final NexOwnAds.Page page = NexOwnAds.parsePage("{\"ads\":["
            + "{\"id\":\"a1\",\"title\":\"Ish\",\"text\":\"Sotuvchi\",\"url\":\"https://t.me/ish\",\"category\":\"job\","
            + "\"city\":\"tashkent\",\"sponsored\":true,\"image_url\":\"https://media.x/a.jpg\"},"
            + "{\"id\":\"a2\",\"title\":\"Bad\",\"url\":\"http://x.uz\"},"
            + "{\"id\":\"a3\",\"title\":\"Own\",\"url\":\"https://nex.uz\",\"image_url\":\"javascript:x\"}"
            + "],\"next_offset\":20}");
        assertEquals(20, page.nextOffset);
        assertEquals(2, page.ads.size()); // http link dropped
        final NexOwnAds.Ad first = page.ads.get(0);
        assertEquals("job", first.category);
        assertEquals("tashkent", first.city);
        assertTrue(first.sponsored);
        assertEquals("https://media.x/a.jpg", first.imageUrl);
        assertFalse(page.ads.get(1).sponsored);
        assertNull(page.ads.get(1).imageUrl); // not an https image
        assertEquals(-1, NexOwnAds.parsePage("{\"ads\":[],\"next_offset\":null}").nextOffset);
    }

    @Test
    public void meta() throws Exception {
        final NexOwnAds.Meta meta = NexOwnAds.parseMeta("{\"categories\":[{\"code\":\"job\",\"name\":\"💼 Ish\"},{\"code\":\"\"}],"
            + "\"cities\":[{\"code\":\"all\",\"name\":\"Butun O'zbekiston\"}],\"bot_username\":\"NexGrammBot\"}");
        assertEquals(1, meta.categories.size());
        assertEquals("💼 Ish", meta.categories.get(0)[1]);
        assertEquals("all", meta.cities.get(0)[0]);
        assertEquals("NexGrammBot", meta.botUsername);
    }
}
