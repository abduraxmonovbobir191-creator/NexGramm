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
        public float ratioHint; // height / width learned from the loaded preview when the server sent no size

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
    public final long createdAt; // ms since epoch, 0 = unknown
    public final String url;     // public link of the post (https), null when absent
    public final String placeName; // Pixelfed "place" (city), null when none
    public final ArrayList<Media> media;
    public final String rebloggedBy; // who reposted it into this feed (display name), null when not a repost
    // Changed by the comments screen (UI thread only)
    public int repliesCount;
    // Changed by the like button (UI thread only)
    public int favouritesCount;
    public boolean favourited;
    public boolean bookmarked;
    public boolean bookmarkPending;
    // Changed by the repost button (UI thread only)
    public int reblogsCount;
    public boolean reblogged;
    public boolean reblogPending;
    public boolean captionExpanded;
    public boolean likePending;

    private PixelfedStatus(String id, String statusId, String authorId, String authorName, String authorUsername, String avatarUrl,
                           String contentHtml, long createdAt, String url, String placeName, ArrayList<Media> media, int favouritesCount, int repliesCount,
                           boolean favourited, boolean bookmarked, int reblogsCount, boolean reblogged, String rebloggedBy) {
        this.id = id;
        this.statusId = statusId;
        this.authorId = authorId;
        this.authorName = authorName;
        this.authorUsername = authorUsername;
        this.avatarUrl = avatarUrl;
        this.contentHtml = contentHtml;
        this.createdAt = createdAt;
        this.url = url;
        this.placeName = placeName;
        this.media = media;
        this.favouritesCount = favouritesCount;
        this.repliesCount = repliesCount;
        this.favourited = favourited;
        this.bookmarked = bookmarked;
        this.reblogsCount = reblogsCount;
        this.reblogged = reblogged;
        this.rebloggedBy = rebloggedBy;
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
            json.optString("content", ""), parseTime(json.optString("created_at", "")), httpsOrNull(json.optString("url", "")), placeName(json), media,
            json.optInt("favourites_count", 0), json.optInt("replies_count", 0),
            json.optBoolean("favourited", false), json.optBoolean("bookmarked", false),
            json.optInt("reblogs_count", 0), json.optBoolean("reblogged", false), reblog != null ? reposter(outer) : null);
    }

    private static String reposter(JSONObject outer) {
        final JSONObject account = outer.optJSONObject("account");
        if (account == null) {
            return "";
        }
        final String name = account.optString("display_name", "").trim();
        return name.isEmpty() ? account.optString("acct", account.optString("username", "")) : name;
    }

    private static String placeName(JSONObject json) {
        final JSONObject place = json.optJSONObject("place");
        final String name = place == null ? "" : place.optString("name", "").trim();
        return name.isEmpty() ? null : name;
    }

    // Link to share: the post's url, else Pixelfed's /p/<username>/<id>
    public String shareUrl() {
        return url != null ? url : PixelfedClient.BASE + "/p/" + authorUsername + "/" + statusId;
    }

    public boolean hasVideo() {
        for (Media m : media) {
            if (m.video) {
                return true;
            }
        }
        return false;
    }

    // ISO 8601 as Pixelfed sends it ("2026-10-12T10:00:00.000000Z", also with an offset); 0 when unreadable.
    // Hand-written: java.time needs API 26, the app supports 21.
    static long parseTime(String value) {
        if (value == null) {
            return 0;
        }
        final java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d+))?(Z|[+-]\\d{2}:?\\d{2})?$")
            .matcher(value.trim());
        if (!m.matches()) {
            return 0;
        }
        final java.util.Calendar c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), java.util.Locale.ROOT);
        c.clear();
        c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
            Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
        long ms = c.getTimeInMillis();
        final String fraction = m.group(7);
        if (fraction != null) {
            ms += Integer.parseInt((fraction + "00").substring(0, 3));
        }
        final String zone = m.group(8);
        if (zone != null && !"Z".equals(zone)) {
            final String digits = zone.replace(":", "");
            final int minutes = Integer.parseInt(digits.substring(1, 3)) * 60 + Integer.parseInt(digits.substring(3, 5));
            ms -= (zone.charAt(0) == '-' ? -1 : 1) * minutes * 60_000L;
        }
        return ms;
    }

    // Only https media is loaded
    private static String httpsOrNull(String url) {
        return url != null && url.startsWith("https://") ? url : null;
    }
}
