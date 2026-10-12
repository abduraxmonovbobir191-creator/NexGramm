package uz.nexgramm.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class NexCoverTest {

    @Test
    public void parsesEveryMode() throws Exception {
        final NexCover video = NexCover.fromJson("{\"mode\":\"video\",\"url\":\"https://media.test/covers/a.mp4\",\"duration_s\":6}");
        assertTrue(video.isMedia());
        assertEquals(6, video.durationS);
        assertEquals("https://media.test/covers/a.mp4", video.url);

        final NexCover pattern = NexCover.fromJson("{\"mode\":\"color_emoji\",\"color\":\"#D7C4FA\",\"emoji_glyph\":\"✦\"}");
        assertFalse(pattern.isMedia());
        assertEquals(Integer.valueOf(0xFFD7C4FA), pattern.color);
        assertEquals("✦", pattern.emojiGlyph);

        final NexCover emoji = NexCover.fromJson("{\"mode\":\"emoji\",\"emoji_glyph\":\"♥\"}");
        assertNull(emoji.color);
        // only https media URLs are used
        assertNull(NexCover.fromJson("{\"mode\":\"photo\",\"url\":\"http://x/a.jpg\"}").url);
    }

    @Test
    public void plainBodyMatchesContract() throws Exception {
        final JSONObject body = NexCover.plainBody(NexCover.MODE_COLOR_EMOJI, 0xFFD9CDF5, "★");
        assertEquals("color_emoji", body.getString("mode"));
        assertEquals("#D9CDF5", body.getString("color"));
        assertEquals("★", body.getString("emoji_glyph"));
        final JSONObject color = NexCover.plainBody(NexCover.MODE_COLOR, 0xFF2A2A31, null);
        assertFalse(color.has("emoji_glyph"));
        assertEquals("#2A2A31", color.getString("color"));
    }
}
