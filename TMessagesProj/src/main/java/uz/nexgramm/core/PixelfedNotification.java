package uz.nexgramm.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

// NexGramm: one Pixelfed notification (GET /api/v1/notifications, Pixelfed v0.14.4): a like ("favourite"),
// a comment or mention ("mention"; a comment carries in_reply_to_id), a follow, a share ("reblog").
public final class PixelfedNotification {

    public static final String FAVOURITE = "favourite";
    public static final String MENTION = "mention";
    public static final String FOLLOW = "follow";
    public static final String REBLOG = "reblog";

    public final String id;
    public final String type;
    public final String accountId;
    public final String accountName;
    public final String avatarUrl;
    public final long createdAt;
    public final PixelfedStatus status; // null for a follow
    public final String inReplyToId;    // the post a comment was left on, else null

    private PixelfedNotification(String id, String type, String accountId, String accountName, String avatarUrl,
                                 long createdAt, PixelfedStatus status, String inReplyToId) {
        this.id = id;
        this.type = type;
        this.accountId = accountId;
        this.accountName = accountName;
        this.avatarUrl = avatarUrl;
        this.createdAt = createdAt;
        this.status = status;
        this.inReplyToId = inReplyToId;
    }

    public boolean isComment() {
        return MENTION.equals(type) && inReplyToId != null;
    }

    static ArrayList<PixelfedNotification> listFromJson(String body) throws JSONException {
        final JSONArray array = new JSONArray(body);
        final ArrayList<PixelfedNotification> result = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            final JSONObject json = array.optJSONObject(i);
            final PixelfedNotification n = json == null ? null : fromJson(json);
            if (n != null) {
                result.add(n);
            }
        }
        return result;
    }

    private static PixelfedNotification fromJson(JSONObject json) {
        final String id = json.optString("id", "");
        final String type = json.optString("type", "");
        final JSONObject account = json.optJSONObject("account");
        if (id.isEmpty() || account == null
            || !(FAVOURITE.equals(type) || MENTION.equals(type) || FOLLOW.equals(type) || REBLOG.equals(type))) {
            return null;
        }
        final String username = account.optString("acct", account.optString("username", ""));
        String name = account.optString("display_name", "");
        if (name.trim().isEmpty()) {
            name = username;
        }
        final String avatar = account.optString("avatar", "");
        PixelfedStatus status = null;
        String inReplyTo = null;
        final JSONObject statusJson = json.optJSONObject("status");
        if (statusJson != null) {
            final ArrayList<PixelfedStatus> one = PixelfedStatus.listFromArray(new JSONArray().put(statusJson));
            status = one.isEmpty() ? null : one.get(0);
            final String parent = statusJson.optString("in_reply_to_id", "");
            inReplyTo = parent.isEmpty() || "null".equals(parent) ? null : parent;
        }
        if (status == null && !FOLLOW.equals(type)) {
            return null;
        }
        return new PixelfedNotification(id, type, account.optString("id", ""), name,
            avatar.startsWith("https://") ? avatar : null, PixelfedStatus.parseTime(json.optString("created_at", "")),
            status, inReplyTo);
    }
}
