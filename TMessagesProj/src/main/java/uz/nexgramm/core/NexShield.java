package uz.nexgramm.core;

import java.net.URI;
import java.util.Locale;

// NexGramm shield: the app's "immune system", one place the other parts ask before doing something risky.
//  - strangers: photos, videos, files and voice from people who are not in the contacts wait for a tap
//    (zero-click media bugs need the file to be fetched and decoded by itself);
//  - links: a link that is not plain https to a named site (http, a bare IP, an .apk, a look-alike name)
//    always shows where it goes and why it may be a trap before anything opens;
//  - screen: locked and hidden chats cannot be screenshotted or recorded, and show nothing in recent apps;
//  - privacy view: those chats (and hidden accounts) sit dark, only a reading strip under the finger is lit.
// All on by default; switched in Settings → Security center. The prefs part needs Android; linkRisk is pure.
public final class NexShield {

    public static final String STRANGERS = "strangers";
    public static final String LINKS = "links";
    public static final String SCREEN = "screen";
    public static final String PRIVACY = "privacy_view";

    public static final int LINK_OK = 0;
    public static final int LINK_HTTP = 1;
    public static final int LINK_IP = 2;
    public static final int LINK_APK = 3;
    public static final int LINK_LOOKALIKE = 4;

    private static volatile boolean loaded;
    private static volatile boolean strangers = true;
    private static volatile boolean links = true;
    private static volatile boolean screen = true;
    private static volatile boolean privacy = true;

    private NexShield() {
    }

    private static android.content.SharedPreferences prefs() {
        return org.telegram.messenger.ApplicationLoader.applicationContext.getSharedPreferences("nexgramm_shield", android.content.Context.MODE_PRIVATE);
    }

    private static void load() {
        if (loaded) {
            return;
        }
        final android.content.SharedPreferences p = prefs();
        strangers = p.getBoolean(STRANGERS, true);
        links = p.getBoolean(LINKS, true);
        screen = p.getBoolean(SCREEN, true);
        privacy = p.getBoolean(PRIVACY, true);
        loaded = true;
    }

    public static boolean strangers() {
        load();
        return strangers;
    }

    public static boolean links() {
        load();
        return links;
    }

    public static boolean screen() {
        load();
        return screen;
    }

    // privacy view: hidden / locked chats and hidden accounts sit dark, only a reading strip lit
    public static boolean privacyView() {
        load();
        return privacy;
    }

    public static boolean isOn(String key) {
        load();
        return STRANGERS.equals(key) ? strangers : LINKS.equals(key) ? links : PRIVACY.equals(key) ? privacy : screen;
    }

    public static void set(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
        loaded = false;
        load();
    }

    public static final int LAYERS = 4;

    // how many of the layers are on (the security center shows it as "4 / 4")
    public static int activeCount() {
        load();
        return (strangers ? 1 : 0) + (links ? 1 : 0) + (screen ? 1 : 0) + (privacy ? 1 : 0);
    }

    // why opening this web link deserves a second look, or LINK_OK; tg:// and t.me links are not checked here
    public static int linkRisk(String url) {
        if (url == null) {
            return LINK_OK;
        }
        final URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            return LINK_OK; // not a web link we can read; Telegram's own checks apply
        }
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return LINK_OK;
        }
        final String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        final String path = uri.getRawPath() == null ? "" : uri.getRawPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".apk") || path.endsWith(".xapk") || path.endsWith(".apks")) {
            return LINK_APK;
        }
        if (isIp(host)) {
            return LINK_IP;
        }
        if (host.startsWith("xn--") || host.contains(".xn--")) {
            return LINK_LOOKALIKE;
        }
        if (scheme.equals("http")) {
            return LINK_HTTP;
        }
        return LINK_OK;
    }

    static boolean isIp(String host) {
        if (host.isEmpty()) {
            return false;
        }
        if (host.startsWith("[") || host.indexOf(':') >= 0) {
            return true; // IPv6
        }
        final String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }
}
