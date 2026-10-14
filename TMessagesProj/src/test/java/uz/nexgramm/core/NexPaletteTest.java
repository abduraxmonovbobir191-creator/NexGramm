package uz.nexgramm.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NexPaletteTest {

    @Test
    public void standardLightKeepsTheDesignPalette() {
        final NexPalette p = NexPalette.of(false, "standard");
        assertEquals(0xFF1F1F24, p.mapTemplate("chats_actionBackground", 0xFF1F1F24));
        assertEquals(0xFFFFFFFF, p.mapTemplate("chats_actionIcon", 0xFFFFFFFF));
        assertEquals(0xFF1F1F24, p.mapTemplate("chat_outBubble", 0xFF1F1F24));
        assertEquals(0xFFD7C4FA, p.mapTemplate("chat_outSentCheck", 0xFFD7C4FA));
        assertEquals(0xFFF4F4F7, p.mapTemplate("windowBackgroundGray", 0xFFF4F4F7));
        assertEquals(0xFFFFFFFF, p.mapTemplate("windowBackgroundWhite", 0xFFFFFFFF));
    }

    @Test
    public void accentRecolorsInteractiveKeysOnly() {
        final NexPalette p = NexPalette.of(false, "green");
        final int green = NexPalette.findAccent("green").light;
        assertEquals(green, p.mapTemplate("chats_actionBackground", 0xFF1F1F24));
        assertEquals(green, p.mapTemplate("switchTrackChecked", 0xFF1F1F24));
        assertEquals(green, p.mapTemplate("chat_outBubble", 0xFF1F1F24));
        // text stays neutral
        assertEquals(0xFF1F1F24, p.mapTemplate("windowBackgroundWhiteBlackText", 0xFF1F1F24));
        assertEquals(0xFF1F1F24, p.mapTemplate("chats_name", 0xFF1F1F24));
        // text inside the green outgoing bubble is white, alpha kept
        assertEquals(0xFFFFFFFF, p.mapTemplate("chat_messageTextOut", 0xFFFFFFFF));
        assertEquals(0xB3FFFFFF, p.mapTemplate("chat_outTimeText", 0xB3FFFFFF));
        // ripple of an accent element keeps its alpha
        assertEquals(0x17000000 | (green & 0xFFFFFF), p.mapTemplate("switchTrackBlueSelector", 0x171F1F24));
    }

    @Test
    public void yellowAccentGetsDarkTextOnIt() {
        final NexPalette p = NexPalette.of(false, "yellow");
        assertEquals(0xFF1F1F24, p.onAccent);
        assertEquals(0xFF1F1F24, p.mapTemplate("chats_actionIcon", 0xFFFFFFFF));
        assertEquals(0xFF1F1F24, p.mapTemplate("chat_messageTextOut", 0xFFFFFFFF));
        assertEquals(0xFFFFFFFF, NexPalette.of(false, "blue").onAccent);
    }

    @Test
    public void darkModeIsNeutralNotTelegramBlue() {
        final NexPalette p = NexPalette.of(true, "standard");
        assertEquals(0xFF0F0F12, p.mapTemplate("windowBackgroundGray", 0xFFF4F4F7));
        assertEquals(0xFF1C1C21, p.mapTemplate("windowBackgroundWhite", 0xFFFFFFFF));
        assertEquals(0xFFF2F2F5, p.mapTemplate("windowBackgroundWhiteBlackText", 0xFF1F1F24));
        // white buttons with dark text, grey outgoing bubble with white text
        assertEquals(0xFFF2F2F5, p.mapTemplate("chats_actionBackground", 0xFF1F1F24));
        assertEquals(0xFF1F1F24, p.mapTemplate("chats_actionIcon", 0xFFFFFFFF));
        assertEquals(0xFF2C2C33, p.mapTemplate("chat_outBubble", 0xFF1F1F24));
        assertEquals(0xFFFFFFFF, p.mapTemplate("chat_messageTextOut", 0xFFFFFFFF));
        // Telegram Dark Blue background (#17212B) -> neutral grey
        final int bg = p.neutralizeBlue(0xFF17212B);
        assertEquals((bg >> 16) & 0xFF, bg & 0xFF);
        assertEquals((bg >> 8) & 0xFF, bg & 0xFF);
        // Telegram blue (#3390EC) -> the accent
        assertEquals(0xFFF2F2F5, p.neutralizeBlue(0xFF3390EC));
    }

    @Test
    public void neutralizeLeavesOtherHuesAndKeepsAlpha() {
        final NexPalette p = NexPalette.of(false, "pink");
        assertEquals(0xFFE53935, p.neutralizeBlue(0xFFE53935)); // red
        assertEquals(0xFF4CAF50, p.neutralizeBlue(0xFF4CAF50)); // green
        assertEquals(0xFF808080, p.neutralizeBlue(0xFF808080)); // grey
        final int pink = NexPalette.findAccent("pink").light;
        assertEquals(0x80000000 | (pink & 0xFFFFFF), p.neutralizeBlue(0x803390EC));
    }

    @Test
    public void keepGroupsAndUnknownAccent() {
        assertTrue(NexPalette.keepsOwnColor("avatar_backgroundBlue"));
        assertTrue(NexPalette.keepsOwnColor("chart_line"));
        assertTrue(NexPalette.keepsOwnColor(null));
        assertFalse(NexPalette.keepsOwnColor("windowBackgroundWhiteBlueText"));
        assertEquals("standard", NexPalette.findAccent("no-such-color").id);
        assertEquals(9, NexPalette.ACCENTS.length);
    }

    @Test
    public void colorStrengthSoftensOnlyBelowFull() {
        final int green = NexPalette.findAccent("green").light;
        assertEquals(green, NexPalette.tone(green, false, 100));
        final int soft = NexPalette.tone(green, false, 0);
        assertTrue(NexPalette.hsv(soft)[1] < NexPalette.hsv(green)[1]);
        assertEquals(NexPalette.of(false, "green", 40).accent, NexPalette.tone(green, false, 40));
        // the black / white standard accent has no color to soften
        assertEquals(0xFF1F1F24, NexPalette.of(false, "standard", 0).accent);
    }

    @Test
    public void lightAccentsGetDarkTextOnThem() {
        assertEquals(0xFF1F1F24, NexPalette.onColor(0xFF34C46E)); // dark-mode green
        assertEquals(0xFF1F1F24, NexPalette.onColor(0xFFB48CF5)); // lavender
        assertEquals(0xFFFFFFFF, NexPalette.onColor(0xFF7C3AED)); // purple
        assertEquals(0xFF8A8A93, NexPalette.of(false, "green").mapTemplate("dialogTextGray", 0xFF1F1F24));
    }
}
