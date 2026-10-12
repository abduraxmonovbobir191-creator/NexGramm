package uz.nexgramm.core;

import org.json.JSONException;
import org.json.JSONObject;

// NexGramm: a Pixelfed (Mastodon API) account, only what the small profile screen shows
public final class PixelfedAccount {

    public final String id;
    public final String username;
    public final String displayName;
    public final String avatarUrl;
    public final String noteHtml;
    public final String website; // shown as a link chip, null when none
    public final String url;     // public profile link
    public final int statusesCount;
    // Changed by the follow button (UI thread only)
    public int followersCount;
    public final int followingCount;

    private PixelfedAccount(String id, String username, String displayName, String avatarUrl, String noteHtml,
                            String website, String url, int statusesCount, int followersCount, int followingCount) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.noteHtml = noteHtml;
        this.website = website;
        this.url = url;
        this.statusesCount = statusesCount;
        this.followersCount = followersCount;
        this.followingCount = followingCount;
    }

    static PixelfedAccount fromJson(String body) throws JSONException {
        final JSONObject json = new JSONObject(body);
        final String id = json.getString("id");
        final String username = json.optString("acct", json.optString("username", ""));
        String name = json.optString("display_name", "");
        if (name.trim().isEmpty()) {
            name = username;
        }
        final String avatar = json.optString("avatar", "");
        final String website = json.optString("website", "").trim();
        final String url = json.optString("url", "");
        return new PixelfedAccount(id, username, name, avatar.startsWith("https://") ? avatar : null,
            json.optString("note", ""), website.isEmpty() || "null".equals(website) ? null : website,
            url.startsWith("https://") ? url : PixelfedClient.BASE + "/" + username, json.optInt("statuses_count", 0),
            json.optInt("followers_count", 0), json.optInt("following_count", 0));
    }
}
