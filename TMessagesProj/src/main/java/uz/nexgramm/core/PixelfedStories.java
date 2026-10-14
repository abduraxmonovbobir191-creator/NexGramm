package uz.nexgramm.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

// NexGramm: Lenta stories (Pixelfed v0.14.4 GET /api/v1.1/stories/carousel): the user's own stories and
// those of the accounts they follow, unseen authors first. A story lives 24 h on the server.
public final class PixelfedStories {

    public static final class Item {
        public final String id;
        public final boolean video;
        public final String src;
        public final int durationS;
        public final long createdAt;
        public boolean seen; // changed by the viewer (UI thread only)

        Item(String id, boolean video, String src, int durationS, long createdAt, boolean seen) {
            this.id = id;
            this.video = video;
            this.src = src;
            this.durationS = durationS;
            this.createdAt = createdAt;
            this.seen = seen;
        }
    }

    public static final class Group {
        public final String accountId;
        public final String username;
        public final String avatarUrl;
        public final boolean self;
        public final ArrayList<Item> items;

        Group(String accountId, String username, String avatarUrl, boolean self, ArrayList<Item> items) {
            this.accountId = accountId;
            this.username = username;
            this.avatarUrl = avatarUrl;
            this.self = self;
            this.items = items;
        }

        public boolean allSeen() {
            for (Item item : items) {
                if (!item.seen) {
                    return false;
                }
            }
            return true;
        }

        // the first story not seen yet (0 when all are seen)
        public int firstUnseen() {
            for (int i = 0; i < items.size(); i++) {
                if (!items.get(i).seen) {
                    return i;
                }
            }
            return 0;
        }
    }

    public final Group self;              // null when the user has no active story
    public final ArrayList<Group> others; // followed accounts, unseen first

    private PixelfedStories(Group self, ArrayList<Group> others) {
        this.self = self;
        this.others = others;
    }

    static PixelfedStories fromJson(String body) throws JSONException {
        final JSONObject json = new JSONObject(body);
        Group self = null;
        final JSONObject selfJson = json.optJSONObject("self"); // [] when empty, so optJSONObject → null
        if (selfJson != null) {
            self = group(selfJson, true);
        }
        final ArrayList<Group> others = new ArrayList<>();
        final JSONArray nodes = json.optJSONArray("nodes");
        if (nodes != null) {
            for (int i = 0; i < nodes.length(); i++) {
                final JSONObject node = nodes.optJSONObject(i);
                final Group g = node == null ? null : group(node, false);
                if (g != null && !(g.self && self != null)) {
                    others.add(g);
                }
            }
        }
        return new PixelfedStories(self != null && !self.items.isEmpty() ? self : null, others);
    }

    private static Group group(JSONObject json, boolean self) {
        final JSONObject user = json.optJSONObject("user");
        if (user == null) {
            return null;
        }
        final ArrayList<Item> items = new ArrayList<>();
        final JSONArray nodes = json.optJSONArray("nodes");
        if (nodes != null) {
            for (int i = 0; i < nodes.length(); i++) {
                final JSONObject n = nodes.optJSONObject(i);
                if (n == null) {
                    continue;
                }
                final String id = n.optString("id", "");
                final String src = n.optString("src", "");
                if (id.isEmpty() || !src.startsWith("http")) {
                    continue;
                }
                items.add(new Item(id, "video".equals(n.optString("type")), src, Math.max(0, n.optInt("duration", 0)),
                    PixelfedStatus.parseTime(n.optString("created_at", "")), self || n.optBoolean("seen", false)));
            }
        }
        if (items.isEmpty()) {
            return null;
        }
        final String username = user.optString("username", user.optString("username_acct", ""));
        final String avatar = user.optString("avatar", "");
        return new Group(user.optString("id", ""), username, avatar.startsWith("https://") ? avatar : null,
            self || user.optBoolean("is_author", false), items);
    }
}
