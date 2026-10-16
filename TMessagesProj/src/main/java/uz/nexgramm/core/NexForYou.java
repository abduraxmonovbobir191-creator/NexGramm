package uz.nexgramm.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// NexGramm: the "For you" Lenta. Learns on the device what this user likes and ranks the candidates
// (local public timeline, home timeline, the month's most liked posts) for them, as Instagram / TikTok do:
//
//   interest model: author, hashtag and photo / video scores, raised by likes, saves, shares, comments,
//   opened videos and profiles, follows and long looks (dwell), lowered by quick skips, unlikes and
//   "Not interested" (the strongest no); everything fades by 3 % a day so new tastes take over.
//
//   score = interest in the author + its hashtags + its kind (photo / video)
//         + how liked it is (log of likes + 2 × comments; counts more while the model knows little,
//           so a new user first sees what everyone likes and then more and more what they like)
//         + followed author
//         − age (log, so a day hurts less than a week) − already seen − own post
//         + a small per-session random part (exploration, so new things get a chance)
//
// then the same author is kept apart (not twice in three posts) and one topic does not fill the screen
// (not three posts in a row with the same first hashtag). Nothing leaves the phone.
public final class NexForYou {

    // signal weights: {author, hashtags, kind}
    public static final float[] LIKE = {3f, 2f, 1f};
    public static final float[] UNLIKE = {-3f, -2f, -1f};
    public static final float[] SAVE = {3f, 2f, 1f};
    public static final float[] SHARE = {2.5f, 1.5f, 0.5f};
    public static final float[] COMMENTS = {1.5f, 1f, 0.5f};
    public static final float[] VIDEO_OPEN = {2f, 1f, 1.5f};
    public static final float[] PROFILE_OPEN = {2f, 0f, 0f};
    public static final float[] FOLLOW = {4f, 1f, 0f};
    public static final float[] LONG_LOOK = {0.6f, 0.4f, 0.4f};    // ≥ 3 s on screen
    public static final float[] VERY_LONG_LOOK = {1.2f, 0.8f, 0.8f}; // ≥ 8 s
    public static final float[] QUICK_SKIP = {-0.15f, -0.1f, -0.1f}; // < 0.7 s while scrolling
    public static final float[] NOT_INTERESTED = {-5f, -3f, -0.5f};  // the post menu's "Not interested"

    private static final float DAILY_DECAY = 0.97f;
    private static final int MAX_KEYS = 300;
    private static final int MAX_SEEN = 2000;
    private static final Pattern HASHTAG = Pattern.compile("#([\\p{L}\\p{N}_]{2,40})");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    // One post as the ranker sees it
    public static final class Candidate {
        public final String id;
        public final String authorId;
        public final ArrayList<String> tags;
        public final boolean video;
        public final int likes;
        public final int replies;
        public final long createdAt;
        public final boolean followed;
        public final boolean own;

        public Candidate(String id, String authorId, ArrayList<String> tags, boolean video, int likes, int replies,
                         long createdAt, boolean followed, boolean own) {
            this.id = id;
            this.authorId = authorId;
            this.tags = tags;
            this.video = video;
            this.likes = likes;
            this.replies = replies;
            this.createdAt = createdAt;
            this.followed = followed;
            this.own = own;
        }
    }

    // ---- interest model ----

    final HashMap<String, Float> authors = new HashMap<>();
    final HashMap<String, Float> tags = new HashMap<>();
    float video;
    float photo;
    final HashMap<String, Long> seen = new HashMap<>(); // status id → when it was looked at
    long updatedAt;

    public NexForYou() {
    }

    public synchronized void record(String authorId, List<String> postTags, boolean isVideo, float[] weights, long now) {
        decayTo(now);
        if (authorId != null && !authorId.isEmpty() && weights[0] != 0) {
            add(authors, authorId, weights[0], -6f, 12f);
        }
        if (postTags != null && weights[1] != 0) {
            for (String t : postTags) {
                add(tags, t, weights[1], -4f, 10f);
            }
        }
        if (weights[2] != 0) {
            if (isVideo) {
                video = clamp(video + weights[2], -10f, 20f);
            } else {
                photo = clamp(photo + weights[2], -10f, 20f);
            }
        }
        trim(authors);
        trim(tags);
    }

    public synchronized void markSeen(String statusId, long now) {
        seen.put(statusId, now);
        if (seen.size() > MAX_SEEN) {
            // drop the oldest quarter
            final ArrayList<Map.Entry<String, Long>> list = new ArrayList<>(seen.entrySet());
            Collections.sort(list, (a, b) -> Long.compare(a.getValue(), b.getValue()));
            for (int i = 0; i < MAX_SEEN / 4; i++) {
                seen.remove(list.get(i).getKey());
            }
        }
    }

    public synchronized boolean wasSeen(String statusId) {
        return seen.containsKey(statusId);
    }

    // scores fade by 3 % a day since the last update
    void decayTo(long now) {
        if (updatedAt > 0 && now > updatedAt) {
            final float days = (now - updatedAt) / 86_400_000f;
            if (days >= 0.04f) { // about an hour
                final float k = (float) Math.pow(DAILY_DECAY, days);
                scale(authors, k);
                scale(tags, k);
                video *= k;
                photo *= k;
                updatedAt = now;
            }
        } else if (updatedAt == 0) {
            updatedAt = now;
        }
    }

    // ---- ranking ----

    public synchronized float score(Candidate c, long now, long sessionSeed) {
        float s = 0;
        final Float author = authors.get(c.authorId);
        if (author != null) {
            s += 0.35f * author;
        }
        // the three strongest hashtags of the post
        final ArrayList<Float> tagScores = new ArrayList<>();
        for (String t : c.tags) {
            final Float v = tags.get(t);
            if (v != null) {
                tagScores.add(v);
            }
        }
        Collections.sort(tagScores, Collections.reverseOrder());
        for (int i = 0; i < Math.min(3, tagScores.size()); i++) {
            s += 0.25f * tagScores.get(i);
        }
        final float kind = clamp(video - photo, -10f, 10f) * 0.06f;
        s += c.video ? kind : -kind;
        s += (0.55f + 0.35f * (1f - knowledge())) * (float) Math.log1p(Math.max(0, c.likes) + 2.0 * Math.max(0, c.replies));
        if (c.followed) {
            s += 0.6f;
        }
        if (c.createdAt > 0 && now > c.createdAt) {
            final float hours = (now - c.createdAt) / 3_600_000f;
            s -= 0.6f * (float) Math.log1p(hours / 12f);
        }
        if (seen.containsKey(c.id)) {
            s -= 2.5f;
        }
        if (c.own) {
            s -= 1.5f;
        }
        s += 0.35f * jitter(c.id, sessionSeed);
        return s;
    }

    // 0 (knows nothing yet) .. 1 (a clear taste): how much the model has learned
    synchronized float knowledge() {
        float sum = Math.abs(video) + Math.abs(photo);
        for (Float v : authors.values()) {
            sum += Math.abs(v);
        }
        for (Float v : tags.values()) {
            sum += Math.abs(v);
        }
        return Math.min(1f, sum / 30f);
    }

    // Best first; the same author not twice within three posts and the same first hashtag not three times in a
    // row, when something else is left
    public List<Candidate> rank(List<Candidate> candidates, long now, long sessionSeed) {
        final HashMap<String, Float> scores = new HashMap<>();
        for (Candidate c : candidates) {
            scores.put(c.id, score(c, now, sessionSeed));
        }
        final ArrayList<Candidate> pool = new ArrayList<>(candidates);
        Collections.sort(pool, (a, b) -> Float.compare(scores.get(b.id), scores.get(a.id)));
        final ArrayList<Candidate> out = new ArrayList<>(pool.size());
        while (!pool.isEmpty()) {
            int pick = -1;
            int authorOnly = -1;
            for (int i = 0; i < pool.size() && pick < 0; i++) {
                if (!recentAuthor(out, pool.get(i).authorId)) {
                    if (!sameTopicTwice(out, pool.get(i))) {
                        pick = i;
                    } else if (authorOnly < 0) {
                        authorOnly = i;
                    }
                }
            }
            out.add(pool.remove(pick >= 0 ? pick : Math.max(authorOnly, 0)));
        }
        return out;
    }

    private static boolean recentAuthor(ArrayList<Candidate> out, String authorId) {
        for (int i = Math.max(0, out.size() - 2); i < out.size(); i++) {
            if (out.get(i).authorId.equals(authorId)) {
                return true;
            }
        }
        return false;
    }

    // the last two posts already share this post's first hashtag
    private static boolean sameTopicTwice(ArrayList<Candidate> out, Candidate c) {
        if (c.tags.isEmpty() || out.size() < 2) {
            return false;
        }
        final String topic = c.tags.get(0);
        for (int i = out.size() - 2; i < out.size(); i++) {
            final ArrayList<String> t = out.get(i).tags;
            if (t.isEmpty() || !t.get(0).equals(topic)) {
                return false;
            }
        }
        return true;
    }

    // 0..1, the same for a post during one app session
    static float jitter(String id, long seed) {
        long h = seed * 0x9E3779B97F4A7C15L + id.hashCode();
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        return (h & 0xFFFF) / 65535f;
    }

    // "#Tag" words of a post's text (HTML allowed), lower case, at most 10
    public static ArrayList<String> hashtags(String html) {
        final ArrayList<String> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        final Matcher m = HASHTAG.matcher(TAG.matcher(html).replaceAll("")); // Pixelfed writes #<span>tag</span>
        final HashSet<String> unique = new HashSet<>();
        while (m.find() && out.size() < 10) {
            final String t = m.group(1).toLowerCase(Locale.ROOT);
            if (unique.add(t)) {
                out.add(t);
            }
        }
        return out;
    }

    // ---- storage (JSON in the app's private preferences) ----

    public synchronized String toJson() {
        try {
            final JSONObject json = new JSONObject();
            json.put("a", new JSONObject(toObjectMap(authors)));
            json.put("t", new JSONObject(toObjectMap(tags)));
            json.put("v", (double) video);
            json.put("p", (double) photo);
            json.put("s", new JSONObject(new HashMap<String, Object>(seen)));
            json.put("u", updatedAt);
            return json.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public static NexForYou fromJson(String body) {
        final NexForYou model = new NexForYou();
        if (body == null || body.isEmpty()) {
            return model;
        }
        try {
            final JSONObject json = new JSONObject(body);
            readFloats(json.optJSONObject("a"), model.authors);
            readFloats(json.optJSONObject("t"), model.tags);
            model.video = (float) json.optDouble("v", 0);
            model.photo = (float) json.optDouble("p", 0);
            final JSONObject s = json.optJSONObject("s");
            if (s != null) {
                for (Iterator<String> it = s.keys(); it.hasNext(); ) {
                    final String k = it.next();
                    model.seen.put(k, s.optLong(k, 0));
                }
            }
            model.updatedAt = json.optLong("u", 0);
        } catch (JSONException ignore) {
            // a broken file starts a fresh model
        }
        return model;
    }

    private static HashMap<String, Object> toObjectMap(HashMap<String, Float> map) {
        final HashMap<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Float> e : map.entrySet()) {
            out.put(e.getKey(), (double) e.getValue());
        }
        return out;
    }

    private static void readFloats(JSONObject json, HashMap<String, Float> into) {
        if (json == null) {
            return;
        }
        for (Iterator<String> it = json.keys(); it.hasNext(); ) {
            final String k = it.next();
            into.put(k, (float) json.optDouble(k, 0));
        }
    }

    // ---- helpers ----

    private static void add(HashMap<String, Float> map, String key, float delta, float min, float max) {
        final Float old = map.get(key);
        map.put(key, clamp((old == null ? 0f : old) + delta, min, max));
    }

    private static void scale(HashMap<String, Float> map, float k) {
        for (Map.Entry<String, Float> e : map.entrySet()) {
            e.setValue(e.getValue() * k);
        }
    }

    // keeps the strongest (by absolute value) MAX_KEYS entries
    private static void trim(HashMap<String, Float> map) {
        if (map.size() <= MAX_KEYS) {
            return;
        }
        final ArrayList<Map.Entry<String, Float>> list = new ArrayList<>(map.entrySet());
        Collections.sort(list, (a, b) -> Float.compare(Math.abs(a.getValue()), Math.abs(b.getValue())));
        for (int i = 0; i < list.size() - MAX_KEYS; i++) {
            map.remove(list.get(i).getKey());
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
