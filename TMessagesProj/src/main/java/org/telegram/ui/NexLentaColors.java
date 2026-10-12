package org.telegram.ui;

import org.telegram.ui.ActionBar.Theme;

// NexGramm: the Lenta palette. Light = the app's palette (#F4F4F7 background, white cards, #1F1F24 accent,
// #D7C4FA lilac, #8A8A93 secondary text); dark = the same roles on near-black. apply() picks the set for the
// current Telegram theme; each Lenta screen calls it before it builds its views.
final class NexLentaColors {
    static int BACKGROUND;
    static int CARD;          // cards, white pills, round buttons
    static int TEXT;
    static int SECONDARY_TEXT;
    static int ACCENT;        // black pills / round buttons (white in dark mode)
    static int ON_ACCENT;     // text and icons on ACCENT
    static int SECONDARY = 0xFFD7C4FA;
    static int ON_SECONDARY = 0xFF1F1F24;
    static int PLACEHOLDER;
    static int GLASS;         // frosted panel over photos
    static int DIVIDER;
    static int LIKED = 0xFFFF3B5C;
    static int ERROR = 0xFFE0394E;
    static boolean dark;

    static {
        apply();
    }

    private NexLentaColors() {
    }

    static void apply() {
        dark = Theme.isCurrentThemeDark();
        if (dark) {
            BACKGROUND = 0xFF0F0F12;
            CARD = 0xFF1C1C21;
            TEXT = 0xFFF2F2F5;
            SECONDARY_TEXT = 0xFF8A8A93;
            ACCENT = 0xFFF2F2F5;
            ON_ACCENT = 0xFF1F1F24;
            PLACEHOLDER = 0xFF26262C;
            GLASS = 0xD91C1C21;
            DIVIDER = 0xFF2A2A30;
        } else {
            BACKGROUND = 0xFFF4F4F7;
            CARD = 0xFFFFFFFF;
            TEXT = 0xFF1F1F24;
            SECONDARY_TEXT = 0xFF8A8A93;
            ACCENT = 0xFF1F1F24;
            ON_ACCENT = 0xFFFFFFFF;
            PLACEHOLDER = 0xFFE9E9EE;
            GLASS = 0xD9FFF8F0;
            DIVIDER = 0xFFE3E3E8;
        }
    }
}
