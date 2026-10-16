package uz.nexgramm.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

// NexGramm: ads (CONTRACT "Ads"): our own and the advertisers' (bought in the bot, /start reklama).
// GET /v1/ads (all active, re-read at most every 10 minutes), GET /v1/ads/next (the Lenta's slots, drawn by
// the server for this person), GET /v1/ads/feed (the E'lonlar screen, best first), GET /v1/ads/meta (filters).
// POST /v1/ads/{id}/view|click: the server counts a person once a day and bills the advertiser. Calls carry
// the account's token when it has one, so the order follows what this person looks at. Only https links and
// images are kept.
public final class NexOwnAds {

    public static final class Ad {
        public final String id;
        public final String title;
        public final String text;
        public final String url;
        public final String imageUrl; // null = text only
        public String category = "";
        public String city = "";
        public boolean sponsored;     // an advertiser's paid ad
        public boolean top;           // paid TOP right now (first in E'lonlar)
        public String videoUrl;       // a short muted loop instead of the photo; null = none

        Ad(String id, String title, String text, String url, String imageUrl) {
            this.id = id;
            this.title = title;
            this.text = text;
            this.url = url;
            this.imageUrl = imageUrl;
        }
    }

    // One page of the E'lonlar feed; nextOffset < 0 = the end
    public static final class Page {
        public final ArrayList<Ad> ads;
        public final int nextOffset;

        Page(ArrayList<Ad> ads, int nextOffset) {
            this.ads = ads;
            this.nextOffset = nextOffset;
        }
    }

    // Filters of the E'lonlar screen: code → name, in the server's order
    public static final class Meta {
        public final ArrayList<String[]> categories = new ArrayList<>();
        public final ArrayList<String[]> cities = new ArrayList<>();
        public String botUsername = "";
    }

    private static final long TTL_MS = 10 * 60_000;
    private static volatile ArrayList<Ad> ads = new ArrayList<>();
    private static long loadedAt;

    private NexOwnAds() {
    }

    public static ArrayList<Ad> current() {
        return ads;
    }

    static ArrayList<Ad> parse(String body) throws JSONException {
        final JSONArray array = new JSONObject(body).optJSONArray("ads");
        final ArrayList<Ad> list = new ArrayList<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            final JSONObject a = array.optJSONObject(i);
            if (a == null || a.optString("id").isEmpty() || !a.optString("url").startsWith("https://")) {
                continue;
            }
            final String image = a.optString("image_url", "");
            final Ad ad = new Ad(a.optString("id"), a.optString("title"), a.optString("text"), a.optString("url"),
                image.startsWith("https://") ? image : null);
            ad.category = a.optString("category", "");
            ad.city = a.optString("city", "");
            ad.sponsored = a.optBoolean("sponsored", false);
            ad.top = a.optBoolean("top", false);
            final String video = a.optString("video_url", "");
            ad.videoUrl = video.startsWith("https://") ? video : null;
            list.add(ad);
        }
        return list;
    }

    static Page parsePage(String body) throws JSONException {
        final JSONObject o = new JSONObject(body);
        return new Page(parse(body), o.isNull("next_offset") ? -1 : o.optInt("next_offset", -1));
    }

    static Meta parseMeta(String body) throws JSONException {
        final JSONObject o = new JSONObject(body);
        final Meta meta = new Meta();
        final JSONArray categories = o.optJSONArray("categories");
        for (int i = 0; categories != null && i < categories.length(); i++) {
            final JSONObject c = categories.optJSONObject(i);
            if (c != null && !c.optString("code").isEmpty()) {
                meta.categories.add(new String[]{c.optString("code"), c.optString("name", c.optString("code"))});
            }
        }
        final JSONArray cities = o.optJSONArray("cities");
        for (int i = 0; cities != null && i < cities.length(); i++) {
            final JSONObject c = cities.optJSONObject(i);
            if (c != null && !c.optString("code").isEmpty()) {
                meta.cities.add(new String[]{c.optString("code"), c.optString("name", c.optString("code"))});
            }
        }
        meta.botUsername = o.optString("bot_username", "");
        return meta;
    }

    // With the account's token when it has one (personal order, the server learns), else without
    private static CoreClient.Response call(long tgUserId, String method, String path) throws Exception {
        if (tgUserId != 0 && CoreClient.getTokens(tgUserId) != null) {
            try {
                return CoreClient.authedBlocking(tgUserId, method, path, null);
            } catch (CoreClient.NotLoggedInException ignore) {
                // logged out meanwhile: the same call without a token
            }
        }
        return CoreClient.requestBlocking(method, path, null, null);
    }

    public static Page feedBlocking(long tgUserId, String category, String city, int offset) throws Exception {
        final StringBuilder path = new StringBuilder("/v1/ads/feed?limit=20&offset=").append(Math.max(0, offset));
        if (category != null && category.matches("[a-z_]{1,16}")) {
            path.append("&category=").append(category);
        }
        if (city != null && city.matches("[a-z_]{1,16}")) {
            path.append("&city=").append(city);
        }
        final CoreClient.Response response = call(tgUserId, "GET", path.toString());
        if (response.code != 200) {
            throw new java.io.IOException("ads feed http " + response.code);
        }
        return parsePage(response.body);
    }

    private static volatile Meta meta;

    public static Meta metaBlocking() throws Exception {
        final Meta cached = meta;
        if (cached != null) {
            return cached;
        }
        final CoreClient.Response response = CoreClient.requestBlocking("GET", "/v1/ads/meta", null, null);
        if (response.code != 200) {
            throw new java.io.IOException("ads meta http " + response.code);
        }
        return meta = parseMeta(response.body);
    }

    // ---- the Lenta's ad slots: a few ads the server drew for this person, used one by one ----

    private static final long NEXT_TTL_MS = 3 * 60_000;
    private static final java.util.ArrayDeque<Ad> next = new java.util.ArrayDeque<>();
    private static long nextLoadedAt;
    private static boolean nextLoading;

    public static void refreshNext(long tgUserId) {
        synchronized (next) {
            final long now = System.currentTimeMillis();
            if (nextLoading || !next.isEmpty() && now - nextLoadedAt < NEXT_TTL_MS || now - nextLoadedAt < 20_000) {
                return;
            }
            nextLoading = true;
            nextLoadedAt = now;
        }
        CoreClient.EXECUTOR.execute(() -> {
            ArrayList<Ad> got = null;
            try {
                final CoreClient.Response response = call(tgUserId, "GET", "/v1/ads/next?count=5");
                if (response.code == 200) {
                    got = parse(response.body);
                }
            } catch (Exception ignore) {
                // no network: the slots use the general list
            }
            synchronized (next) {
                nextLoading = false;
                if (got != null) {
                    next.clear();
                    next.addAll(got);
                }
            }
        });
    }

    // The next drawn ad for a slot, or null (then a slot falls back to AdMob / the general list)
    public static Ad pollNext(long tgUserId) {
        final Ad ad;
        synchronized (next) {
            ad = next.poll();
        }
        refreshNext(tgUserId);
        return ad;
    }

    public static synchronized void refresh() {
        final long now = System.currentTimeMillis();
        if (now - loadedAt < TTL_MS) {
            return;
        }
        loadedAt = now;
        CoreClient.EXECUTOR.execute(() -> {
            try {
                final CoreClient.Response response = CoreClient.requestBlocking("GET", "/v1/ads", null, null);
                if (response.code == 200) {
                    ads = parse(response.body);
                }
            } catch (Exception ignore) {
                // no network: keep what we had
            }
        });
    }

    // event: "view" or "click"
    public static void report(String id, String event) {
        report(0, id, event);
    }

    public static void report(long tgUserId, String id, String event) {
        CoreClient.EXECUTOR.execute(() -> {
            try {
                call(tgUserId, "POST", "/v1/ads/" + id + "/" + event);
            } catch (Exception ignore) {
            }
        });
    }
}
