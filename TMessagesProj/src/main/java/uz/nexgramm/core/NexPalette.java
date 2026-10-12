package uz.nexgramm.core;

import java.util.Arrays;
import java.util.HashSet;

// NexGramm app palette, pure Java (unit tested). One light template (assets/nexgramm_light.attheme)
// is turned into the colors for {light, dark} x {accent}: template values are roles (background,
// card, text, accent, outgoing bubble, lilac secondary), each role is mapped to the chosen accent
// and mode. Telegram blue left in keys outside the template becomes the accent, blue-tinted greys
// become neutral greys, so no Telegram blue is left in either mode.
public final class NexPalette {

    public static final class Accent {
        public final String id;
        public final int light;
        public final int dark;

        Accent(String id, int light, int dark) {
            this.id = id;
            this.light = light;
            this.dark = dark;
        }
    }

    // Order of the picker. "standard" = black in light mode, white in dark mode (the design's look)
    public static final Accent[] ACCENTS = {
        new Accent("standard", 0xFF1F1F24, 0xFFF2F2F5),
        new Accent("blue", 0xFF2563EB, 0xFF4C8DFF),
        new Accent("sky", 0xFF0EA5E9, 0xFF38BDF8),
        new Accent("green", 0xFF16A34A, 0xFF34C46E),
        new Accent("yellow", 0xFFEAB308, 0xFFFACC15),
        new Accent("orange", 0xFFF97316, 0xFFFF8A3D),
        new Accent("pink", 0xFFEC4899, 0xFFF472B6),
        new Accent("purple", 0xFF7C3AED, 0xFFA78BFA),
        new Accent("lavender", 0xFFB48CF5, 0xFFC9AEFF),
    };

    public static final String DEFAULT_ACCENT = "standard";

    // Light template neutrals and their dark counterparts
    static final int L_BACKGROUND = 0xF4F4F7, D_BACKGROUND = 0x0F0F12;
    static final int L_SURFACE = 0xFFFFFF, D_SURFACE = 0x1C1C21;
    static final int L_TEXT = 0x1F1F24, D_TEXT = 0xF2F2F5;
    static final int L_TEXT2 = 0x8A8A93, D_TEXT2 = 0x8E8E99;
    static final int L_MUTED = 0xC5C5CC, D_MUTED = 0x4A4A52;
    static final int L_TRACK = 0xE4E4EA, D_TRACK = 0x34343C;
    static final int L_PRESSED_CARD = 0xECECF0, D_PRESSED_CARD = 0x2A2A30;
    static final int LILAC = 0xD7C4FA, LILAC_DARK = 0xC9AEFF;
    static final int ACCENT_PRESSED = 0x3A3A42;
    static final int OUT_BUBBLE_SELECTED = 0x2E2E36;

    public static final int ONLINE = 0xFF2EB872;

    // #1F1F24 keys that are text or neutral icons (all other #1F1F24 keys are the accent)
    private static final HashSet<String> TEXT_KEYS = new HashSet<>(Arrays.asList(
        "actionBarDefaultIcon", "actionBarDefaultTitle", "actionBarActionModeReactionText", "actionBarTabActiveText",
        "actionBarTabLine", "profile_tabSelectedText", "profile_tabSelectedLine", "profile_status",
        "chat_messageTextIn", "chat_inAudioTitleText", "chat_inContactNameText", "chat_inFileNameText",
        "chat_inReplyMessageText", "chat_inAudioDurationSelectedText", "chat_inContactPhoneSelectedText",
        "chat_inFileInfoSelectedText", "chat_inReplyMediaMessageSelectedText", "chat_inSentClockSelected",
        "chat_inTimeSelectedText", "chat_inVenueInfoSelectedText", "chat_inViewsSelected", "chat_inMenuSelected",
        "chat_topPanelTitle", "chat_searchPanelText", "chat_searchPanelIcons", "chat_replyPanelIcons",
        "chat_fieldOverlayText", "chat_emojiPanelIconSelected", "chat_inlineResultIcon", "chat_unreadMessagesStartText",
        "chats_name", "chats_nameMessage", "chats_nameMessageArchived_threeLines", "chats_nameMessage_threeLines",
        "chats_attachMessage", "dialogTextBlack", "windowBackgroundWhiteBlackText", "windowBackgroundWhiteValueText",
        "telegram_color", "telegram_color_text", "telegram_color_dialogsLogo"
    ));

    // #1F1F24 keys that are the outgoing bubble or icons cut out of it
    private static final HashSet<String> BUBBLE_KEYS = new HashSet<>(Arrays.asList(
        "chat_outBubble", "chat_outContactIcon", "chat_outFileProgress", "chat_outFileProgressSelected",
        "chat_outMediaIcon", "chat_outMediaIconSelected", "chat_outReactionButtonTextSelected"
    ));

    // Dark round avatars with a white icon (saved messages, archive)
    private static final HashSet<String> NEUTRAL_AVATAR_KEYS = new HashSet<>(Arrays.asList(
        "avatar_backgroundSaved", "avatar_background2Saved", "avatar_backgroundArchivedHidden",
        "chats_archiveBackground", "chats_archivePullDownBackgroundActive"
    ));

    // Translucent #1F1F24 keys that are pressed / ripple states of accent elements
    private static final HashSet<String> ACCENT_ALPHA_KEYS = new HashSet<>(Arrays.asList(
        "switchTrackBlueSelector", "switchTrackBlueSelectorChecked", "dialogFloatingButtonPressed"
    ));

    // White keys that sit on the accent (FAB icon, counters, button text)
    private static final HashSet<String> ON_ACCENT_TEMPLATE_KEYS = new HashSet<>(Arrays.asList(
        "chats_actionIcon", "chats_unreadCounterText", "featuredStickers_buttonText"
    ));

    // Keys outside the template that draw on the accent; Telegram makes them white, which is invisible
    // on the white accent of dark mode
    public static final String[] ON_ACCENT_EXTRA_KEYS = {
        "checkboxCheck", "checkboxSquareCheck", "dialogCheckboxSquareCheck", "dialogRoundCheckBoxCheck",
        "chat_attachCheckBoxCheck", "dialogFloatingIcon", "profile_actionIcon", "chat_inMediaIcon",
        "chat_inMediaIconSelected", "passport_authorizeText", "location_sendLocationIcon", "windowBackgroundCheckText",
        "glass_tabSelectedText", "chat_goDownButtonCounter", "topics_unreadCounterText", "chat_messagePanelVoicePressed"
    };

    // Key groups that keep their own colors (avatars, charts, code, premium gradients, calls ...)
    private static final String[] KEEP_PREFIXES = {
        "avatar_", "chart", "statisticChart", "code_", "premium", "gift", "stories", "voipgroup_", "calls_",
        "chat_attach", "location_", "picker", "iv_", "chat_wallpaper", "wallpaper", "chat_service", "starsGradient",
        "dialogReaction", "reactionStar", "emoji", "chat_inCode", "chat_outCode", "returnToCall", "inappPlayer"
    };

    public final boolean dark;
    public final boolean standard;
    public final int accent;
    public final int onAccent;
    public final int accentPressed;
    public final int bubble;
    public final int onBubble;
    public final int bubbleSecondary;
    public final int selection;
    public final int background;
    public final int surface;
    public final int text;

    private NexPalette(boolean dark, Accent a) {
        this.dark = dark;
        this.standard = DEFAULT_ACCENT.equals(a.id);
        accent = dark ? a.dark : a.light;
        onAccent = onColor(accent);
        accentPressed = blend(accent, onAccent, 0.15f);
        bubble = standard && dark ? 0xFF2C2C33 : accent;
        onBubble = onColor(bubble);
        bubbleSecondary = standard ? opaque(dark ? LILAC_DARK : LILAC) : blend(bubble, onBubble, 0.8f);
        selection = standard ? opaque(dark ? LILAC_DARK : LILAC) : accent;
        background = opaque(dark ? D_BACKGROUND : L_BACKGROUND);
        surface = opaque(dark ? D_SURFACE : L_SURFACE);
        text = opaque(dark ? D_TEXT : L_TEXT);
    }

    public static NexPalette of(boolean dark, String accentId) {
        return new NexPalette(dark, findAccent(accentId));
    }

    public static Accent findAccent(String id) {
        for (Accent a : ACCENTS) {
            if (a.id.equals(id)) {
                return a;
            }
        }
        return ACCENTS[0];
    }

    // Color of a template key in this palette
    public int mapTemplate(String key, int value) {
        final int alpha = value >>> 24;
        final int rgb = value & 0xFFFFFF;
        final boolean out = key.startsWith("chat_out") || key.contains("Out");
        switch (rgb) {
            case L_TEXT:
                if ("chat_serviceBackground".equals(key)) {
                    return dark ? withAlpha(0x2C2C33, alpha) : value;
                }
                if (BUBBLE_KEYS.contains(key)) {
                    return withAlpha(bubble, alpha);
                }
                if (NEUTRAL_AVATAR_KEYS.contains(key)) {
                    return withAlpha(dark ? 0x3A3A42 : L_TEXT, alpha);
                }
                if (ACCENT_ALPHA_KEYS.contains(key)) {
                    return withAlpha(accent, alpha);
                }
                if (alpha != 0xFF || TEXT_KEYS.contains(key)) {
                    return withAlpha(text, alpha);
                }
                return withAlpha(accent, alpha);
            case L_SURFACE:
                if (out || "chats_menuPhoneCats".equals(key)) {
                    return withAlpha(onBubble, alpha);
                }
                if (ON_ACCENT_TEMPLATE_KEYS.contains(key)) {
                    return withAlpha(onAccent, alpha);
                }
                return withAlpha(surface, alpha);
            case LILAC:
                return withAlpha(out ? bubbleSecondary : selection, alpha);
            case ACCENT_PRESSED:
                return withAlpha(accentPressed, alpha);
            case OUT_BUBBLE_SELECTED:
                return withAlpha(blend(bubble, onBubble, 0.12f), alpha);
            case L_BACKGROUND:
                return withAlpha(dark ? D_BACKGROUND : rgb, alpha);
            case L_TEXT2:
                return withAlpha(dark ? D_TEXT2 : rgb, alpha);
            case L_MUTED:
                return withAlpha(dark ? D_MUTED : rgb, alpha);
            case L_TRACK:
                return withAlpha(dark ? D_TRACK : rgb, alpha);
            case L_PRESSED_CARD:
                return withAlpha(dark ? D_PRESSED_CARD : rgb, alpha);
            default:
                return value;
        }
    }

    public static boolean keepsOwnColor(String key) {
        if (key == null) {
            return true;
        }
        for (String p : KEEP_PREFIXES) {
            if (key.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    // Telegram blue -> accent (light saturated tints -> a soft accent tint), blue-tinted greys
    // (Dark Blue backgrounds) -> neutral greys; other colors are returned as they are
    public int neutralizeBlue(int color) {
        final float[] hsv = hsv(color);
        if (hsv[0] < 185f || hsv[0] > 240f || hsv[1] < 0.04f) {
            return color;
        }
        final int alpha = color >>> 24;
        if (hsv[1] >= 0.28f && hsv[2] >= 0.35f) {
            if (hsv[1] < 0.5f && hsv[2] > 0.85f) {
                return withAlpha(blend(surface, accent, 0.25f), alpha);
            }
            return withAlpha(accent, alpha);
        }
        final int grey = Math.round(hsv[2] * 255f);
        return (alpha << 24) | (grey << 16) | (grey << 8) | grey;
    }

    // Text / icon color readable on the given color
    public static int onColor(int color) {
        return luminance(color) > 0.45 ? 0xFF1F1F24 : 0xFFFFFFFF;
    }

    static double luminance(int color) {
        return 0.2126 * channel((color >> 16) & 0xFF) + 0.7152 * channel((color >> 8) & 0xFF) + 0.0722 * channel(color & 0xFF);
    }

    private static double channel(int c) {
        final double v = c / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    public static int blend(int from, int to, float t) {
        final int a = Math.round(((from >>> 24) * (1 - t)) + ((to >>> 24) * t));
        final int r = Math.round((((from >> 16) & 0xFF) * (1 - t)) + (((to >> 16) & 0xFF) * t));
        final int g = Math.round((((from >> 8) & 0xFF) * (1 - t)) + (((to >> 8) & 0xFF) * t));
        final int b = Math.round(((from & 0xFF) * (1 - t)) + ((to & 0xFF) * t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    static int opaque(int rgb) {
        return 0xFF000000 | rgb;
    }

    // h in [0, 360), s and v in [0, 1]
    static float[] hsv(int color) {
        final float r = ((color >> 16) & 0xFF) / 255f;
        final float g = ((color >> 8) & 0xFF) / 255f;
        final float b = (color & 0xFF) / 255f;
        final float max = Math.max(r, Math.max(g, b));
        final float min = Math.min(r, Math.min(g, b));
        final float d = max - min;
        float h = 0;
        if (d > 0) {
            if (max == r) {
                h = 60f * (((g - b) / d) % 6f);
            } else if (max == g) {
                h = 60f * (((b - r) / d) + 2f);
            } else {
                h = 60f * (((r - g) / d) + 4f);
            }
        }
        if (h < 0) {
            h += 360f;
        }
        return new float[]{h, max == 0 ? 0 : d / max, max};
    }
}
