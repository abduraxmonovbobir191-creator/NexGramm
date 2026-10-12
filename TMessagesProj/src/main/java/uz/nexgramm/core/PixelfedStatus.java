package uz.nexgramm.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

// NexGramm: one post of a Pixelfed (Mastodon API) timeline, only the fields the native Lenta shows.
// A boost shows the boosted post; `id` stays the timeline id (max_id paging).
public final class PixelfedStatus {

    public static final class Media {
        public final boolean video; // "video" or "gifv"
        public final String url;
        public final String previewUrl;
        public final int width;
        public final int height;

        Media(boolean video, String url, String previewUrl, int width, int height) {
            this.video = video;
            this.url = url;
            this.previewUrl = previewUrl;
            this.width = width;
            this.height = height;
        }
    }

    public final String id;
    public final String statusId;
    public final String authorId;
    public final String authorName;
    public final String authorUsername;
    public final String avatarUrl;
    public final String contentHtml;
    public final ArrayList<Media> media;
    // Changed by the comments screen (UI thread only)
    public int repliesCount;
    // Changed by the like button (UI thread only)
    public int favouritesCount;
    public boolean favourited;
    public boolean likePending;

    private PixelfedStatus(String id, String statusId, String authorId, String authorName, String authorUsername, String avatarUrl,
                           String contentHtml, ArrayList<Media> media, int favouritesCount, int repliesCount, boolean favourited) {
        this.id = id;
        this.statusId = statusId;
        this.authorId = authorId;
        this.authorName = authorName;
        this.authorUsername = authorUsername;
        this.avatarUrl = avatarUrl;
        this.contentHtml = contentHtml;
        this.media = media;
        this.favouritesCount = favouritesCount;
        this.repliesCount = repliesCount;
        this.favourited = favourited;
    }

    static ArrayList<PixelfedStatus> listFromJson(String body) throws JSONException {
        return listFromArray(new JSONArray(body));
    }

    static PixelfedStatus oneFromJson(String body) throws JSONException {
        final PixelfedStatus status = fromJson(new JSONObject(body));
        if (status == null) {
            throw new JSONException("no status id");
        }
        return status;
    }

    static ArrayList<PixelfedStatus> listFromArray(JSONArray array) {
        if (array == null) {
            return new ArrayList<>();
        }
        final ArrayList<PixelfedStatus> result = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            final JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            final PixelfedStatus status = fromJson(item);
            if (status != null) {
                result.add(status);
            }
        }
        return result;
    }

    private static PixelfedStatus fromJson(JSONObject outer) {
        final String id = outer.optString("id", "");
        if (id.isEmpty()) {
            return null;
        }
        final JSONObject reblog = outer.optJSONObject("reblog");
        final JSONObject json = reblog != null ? reblog : outer;
        final JSONObject account = json.optJSONObject("account");
        String username = "";
        String name = "";
        String avatar = null;
        String authorId = "";
        if (account != null) {
            authorId = account.optString("id", "");
            username = account.optString("acct", account.optString("username", ""));
            name = account.optString("display_name", "");
            avatar = httpsOrNull(account.optString("avatar", ""));
        }
        if (name.trim().isEmpty()) {
            name = username;
        }
        final ArrayList<Media> media = new ArrayList<>();
        final JSONArray attachments = json.optJSONArray("media_attachments");
        if (attachments != null) {
            for (int i = 0; i < attachments.length(); i++) {
                final JSONObject a = attachments.optJSONObject(i);
                if (a == null) {
                    continue;
                }
                final String type = a.optString("type", "");
                final boolean video = "video".equals(type) || "gifv".equals(type);
                if (!video && !"image".equals(type)) {
                    continue;
                }
                final String url = httpsOrNull(a.optString("url", ""));
                final String preview = httpsOrNull(a.optString("preview_url", ""));
                if (url == null && preview == null) {
                    continue;
                }
                int width = 0;
                int height = 0;
                final JSONObject meta = a.optJSONObject("meta");
                if (meta != null) {
                    JSONObject size = meta.optJSONObject("original");
                    if (size == null) {
                        size = meta.optJSONObject("small");
                    }
                    if (size != null) {
                        width = size.optInt("width", 0);
                        height = size.optInt("height", 0);
                    }
                }
                media.add(new Media(video && url != null, url != null ? url : preview, preview, width, height));
            }
        }
        final String statusId = json.optString("id", id);
        return new PixelfedStatus(id, statusId.isEmpty() ? id : statusId, authorId, name, username, avatar,
            json.optString("content", ""), media,
            json.optInt("favourites_count", 0), json.optInt("replies_count", 0), json.optBoolean("favourited", false));
    }

    // Only https media is loaded
    private static String httpsOrNull(String url) {
        return url != null && url.startsWith("https://") ? url : null;
    }
}
