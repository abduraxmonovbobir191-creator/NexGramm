package uz.nexgramm.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

// NexGramm: ghost mode. One master switch (the 👻 in the chats header) and five parts the user can turn off
// one by one: no "online", no read receipts (users and groups; channels show no readers anyway), no
// "typing…", stories viewed without showing up in the viewers, voice / round messages heard without the
// "listened" mark. Everything still happens on this phone (chats are marked read here); only what would
// tell the other side is not sent. Optionally a chat is marked read for real when the user replies there.
public final class NexGhost {

    public static final String ONLINE = "online";
    public static final String READ = "read";
    public static final String TYPING = "typing";
    public static final String STORIES = "stories";
    public static final String LISTENED = "listened";
    public static final String READ_ON_REPLY = "read_on_reply";

    private static volatile boolean loaded;
    private static volatile boolean enabled;
    private static volatile boolean online;
    private static volatile boolean read;
    private static volatile boolean typing;
    private static volatile boolean stories;
    private static volatile boolean listened;
    private static volatile boolean readOnReply;

    private NexGhost() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nexgramm_ghost", Context.MODE_PRIVATE);
    }

    private static void load() {
        if (loaded) {
            return;
        }
        final SharedPreferences p = prefs();
        enabled = p.getBoolean("enabled", false);
        online = p.getBoolean(ONLINE, true);
        read = p.getBoolean(READ, true);
        typing = p.getBoolean(TYPING, true);
        stories = p.getBoolean(STORIES, true);
        listened = p.getBoolean(LISTENED, true);
        readOnReply = p.getBoolean(READ_ON_REPLY, true);
        loaded = true;
    }

    public static boolean isEnabled() {
        load();
        return enabled;
    }

    public static void setEnabled(boolean value) {
        load();
        enabled = value;
        prefs().edit().putBoolean("enabled", value).apply();
    }

    public static boolean get(String part) {
        load();
        switch (part) {
            case ONLINE:
                return online;
            case READ:
                return read;
            case TYPING:
                return typing;
            case STORIES:
                return stories;
            case LISTENED:
                return listened;
            case READ_ON_REPLY:
                return readOnReply;
            default:
                return false;
        }
    }

    public static void set(String part, boolean value) {
        load();
        switch (part) {
            case ONLINE:
                online = value;
                break;
            case READ:
                read = value;
                break;
            case TYPING:
                typing = value;
                break;
            case STORIES:
                stories = value;
                break;
            case LISTENED:
                listened = value;
                break;
            case READ_ON_REPLY:
                readOnReply = value;
                break;
            default:
                return;
        }
        prefs().edit().putBoolean(part, value).apply();
    }

    public static boolean hideOnline() {
        return isEnabled() && online;
    }

    public static boolean hideRead() {
        return isEnabled() && read;
    }

    public static boolean hideTyping() {
        return isEnabled() && typing;
    }

    public static boolean hideStoryViews() {
        return isEnabled() && stories;
    }

    public static boolean hideListened() {
        return isEnabled() && listened;
    }

    public static boolean readOnReply() {
        return hideRead() && readOnReply;
    }
}
