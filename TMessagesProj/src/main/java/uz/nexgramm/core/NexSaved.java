package uz.nexgramm.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// NexGramm: copies of messages that others deleted or edited, kept on this phone only (opt-in, both off by
// default). The chat itself behaves as in Telegram — a deleted message goes away there — and the copy is
// listed in "Deleted and edited" (per chat from its ⋮ menu, all of them from NexGramm settings). Only text
// and a short media label are kept (no files), the newest 3000 entries, in the app's private files.
public final class NexSaved {

    public static final int DELETED = 0;
    public static final int EDITED = 1;
    private static final int MAX = 3000;

    public static final class Entry {
        public final int type;
        public final int account;
        public final long dialogId;
        public final int messageId;
        public final long fromId;
        public final int date;       // when the message was sent (s)
        public final long savedAt;   // when it was deleted / edited (ms)
        public final String text;    // the deleted text, or the text before the edit
        public final String newText; // edits only

        public Entry(int type, int account, long dialogId, int messageId, long fromId, int date, long savedAt, String text, String newText) {
            this.type = type;
            this.account = account;
            this.dialogId = dialogId;
            this.messageId = messageId;
            this.fromId = fromId;
            this.date = date;
            this.savedAt = savedAt;
            this.text = text == null ? "" : text;
            this.newText = newText;
        }
    }

    private static final ExecutorService DISK = Executors.newSingleThreadExecutor();
    private static ArrayList<Entry> entries;

    private NexSaved() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nexgramm_extras", Context.MODE_PRIVATE);
    }

    public static boolean keepDeleted() {
        return prefs().getBoolean("keep_deleted", false);
    }

    public static boolean keepEdits() {
        return prefs().getBoolean("keep_edits", false);
    }

    public static void setKeepDeleted(boolean value) {
        prefs().edit().putBoolean("keep_deleted", value).apply();
    }

    public static void setKeepEdits(boolean value) {
        prefs().edit().putBoolean("keep_edits", value).apply();
    }

    private static File file() {
        return new File(ApplicationLoader.getFilesDirFixed(), "nexgramm_saved.json");
    }

    private static synchronized ArrayList<Entry> all() {
        if (entries == null) {
            entries = new ArrayList<>();
            try {
                final File f = file();
                if (f.exists()) {
                    final byte[] bytes = new byte[(int) f.length()];
                    try (FileInputStream in = new FileInputStream(f)) {
                        int read = 0;
                        while (read < bytes.length) {
                            final int n = in.read(bytes, read, bytes.length - read);
                            if (n < 0) {
                                break;
                            }
                            read += n;
                        }
                    }
                    entries.addAll(fromJson(new String(bytes, StandardCharsets.UTF_8)));
                }
            } catch (Exception ignore) {
                // a broken file starts a new list
            }
        }
        return entries;
    }

    public static synchronized void add(Entry entry) {
        final ArrayList<Entry> list = all();
        list.add(0, entry);
        while (list.size() > MAX) {
            list.remove(list.size() - 1);
        }
        save();
    }

    // newest first; dialogId 0 = every chat
    public static synchronized ArrayList<Entry> list(int account, long dialogId) {
        final ArrayList<Entry> out = new ArrayList<>();
        for (Entry e : all()) {
            if (e.account == account && (dialogId == 0 || e.dialogId == dialogId)) {
                out.add(e);
            }
        }
        return out;
    }

    public static synchronized void clear(int account, long dialogId) {
        final ArrayList<Entry> list = all();
        for (int i = list.size() - 1; i >= 0; i--) {
            final Entry e = list.get(i);
            if (e.account == account && (dialogId == 0 || e.dialogId == dialogId)) {
                list.remove(i);
            }
        }
        save();
    }

    private static void save() {
        final String json = toJson(new ArrayList<>(all()));
        DISK.execute(() -> {
            final File f = file();
            final File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        });
    }

    static String toJson(ArrayList<Entry> list) {
        final JSONArray array = new JSONArray();
        try {
            for (Entry e : list) {
                final JSONObject o = new JSONObject();
                o.put("t", e.type);
                o.put("a", e.account);
                o.put("d", e.dialogId);
                o.put("m", e.messageId);
                o.put("f", e.fromId);
                o.put("dt", e.date);
                o.put("s", e.savedAt);
                o.put("x", e.text);
                if (e.newText != null) {
                    o.put("n", e.newText);
                }
                array.put(o);
            }
        } catch (JSONException ignore) {
        }
        return array.toString();
    }

    static ArrayList<Entry> fromJson(String body) {
        final ArrayList<Entry> out = new ArrayList<>();
        try {
            final JSONArray array = new JSONArray(body);
            for (int i = 0; i < array.length(); i++) {
                final JSONObject o = array.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                out.add(new Entry(o.optInt("t"), o.optInt("a"), o.optLong("d"), o.optInt("m"), o.optLong("f"), o.optInt("dt"),
                    o.optLong("s"), o.optString("x", ""), o.has("n") ? o.optString("n", "") : null));
            }
        } catch (JSONException ignore) {
        }
        return out;
    }
}
