package uz.nexgramm.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

// NexGramm: our own ads (CONTRACT "Ads"): GET /v1/ads (public), re-read at most every 10 minutes;
// POST /v1/ads/{id}/view|click counters. Only https links and images are kept.
public final class NexOwnAds {

    public static final class Ad {
        public final String id;
        public final String title;
        public final String text;
        public final String url;
        public final String imageUrl; // null = text only

        Ad(String id, String title, String text, String url, String imageUrl) {
            this.id = id;
            this.title = title;
            this.text = text;
            this.url = url;
            this.imageUrl = imageUrl;
        }
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
            list.add(new Ad(a.optString("id"), a.optString("title"), a.optString("text"), a.optString("url"),
                image.startsWith("https://") ? image : null));
        }
        return list;
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
        CoreClient.EXECUTOR.execute(() -> {
            try {
                CoreClient.requestBlocking("POST", "/v1/ads/" + id + "/" + event, null, null);
            } catch (Exception ignore) {
            }
        });
    }
}
