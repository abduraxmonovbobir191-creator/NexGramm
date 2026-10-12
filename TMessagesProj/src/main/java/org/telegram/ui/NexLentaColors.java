package org.telegram.ui;

import org.telegram.ui.ActionBar.Theme;

// NexGramm: the Lenta palette, taken from the app palette (Theme / NexPalette): background, cards, text and
// the accent the user picked, light or dark. Each Lenta screen calls apply() before it builds its views.
final class NexLentaColors {
    static int BACKGROUND;
    static int CARD;          // cards, white pills, round buttons
    static int TEXT;
    static int SECONDARY_TEXT;
    static int ACCENT;        // black pills / round buttons (white in dark mode)
    static int ON_ACCENT;     // text and icons on ACCENT
    static int SECONDARY;
    static int ON_SECONDARY;
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
        final uz.nexgramm.core.NexPalette palette = Theme.getNexgrammPalette();
        BACKGROUND = Theme.getColor(Theme.key_windowBackgroundGray);
        CARD = Theme.getColor(Theme.key_windowBackgroundWhite);
        TEXT = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        SECONDARY_TEXT = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        // the accent the user picked (black / white for "Standart"), from the app palette
        ACCENT = palette != null ? palette.accent : (dark ? 0xFFF2F2F5 : 0xFF1F1F24);
        ON_ACCENT = palette != null ? palette.onAccent : (dark ? 0xFF1F1F24 : 0xFFFFFFFF);
        // soft tint of the accent (lilac for "Standart")
        SECONDARY = palette != null && !palette.standard ? uz.nexgramm.core.NexPalette.blend(CARD, palette.accent, 0.24f) : 0xFFD7C4FA;
        ON_SECONDARY = palette != null && !palette.standard ? TEXT : 0xFF1F1F24;
        if (dark) {
            PLACEHOLDER = 0xFF26262C;
            GLASS = 0xD91C1C21;
            DIVIDER = 0xFF2A2A30;
        } else {
            PLACEHOLDER = 0xFFE9E9EE;
            GLASS = 0xD9FFF8F0;
            DIVIDER = 0xFFE3E3E8;
        }
    }
}
