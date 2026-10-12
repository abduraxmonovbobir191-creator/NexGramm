package uz.nexgramm.core;

import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

// NexGramm: profile cover (CONTRACT v1.5 "Covers"), shown on the Lenta profile. Stored in the core:
// color / color_emoji / emoji as a row, photo / video in R2. GET is public (by Pixelfed username),
// PUT / DELETE need the core login. *Blocking methods do network I/O: call them on CoreClient.EXECUTOR.
public final class NexCover {

    public static final String MODE_PHOTO = "photo";
    public static final String MODE_VIDEO = "video";
    public static final String MODE_COLOR = "color";
    public static final String MODE_COLOR_EMOJI = "color_emoji";
    public static final String MODE_EMOJI = "emoji";

    // Colors and pattern glyphs of the cover editor (design: lilac, peach, sky, mint, pink, sand, ink)
    public static final int[] COLORS = {0xFFD9CDF5, 0xFFF3D9C8, 0xFFCFE0F2, 0xFFD3E9DD, 0xFFF1D3E0, 0xFFE9DFC8, 0xFF2A2A31};
    public static final String[] GLYPHS = {"✦", "♥", "★", "☾", "⚡", "✤"};

    public final String mode;
    public final String url;
    public final int durationS;
    public final Integer color; // ARGB, null when the mode has no color
    public final String emojiGlyph;

    public NexCover(String mode, String url, int durationS, Integer color, String emojiGlyph) {
        this.mode = mode;
        this.url = url;
        this.durationS = durationS;
        this.color = color;
        this.emojiGlyph = emojiGlyph;
    }

    public boolean isMedia() {
        return MODE_PHOTO.equals(mode) || MODE_VIDEO.equals(mode);
    }

    public static NexCover fromJson(String body) throws JSONException {
        final JSONObject json = new JSONObject(body);
        final String mode = json.getString("mode");
        Integer color = null;
        final String hex = json.optString("color", "");
        if (hex.matches("#[0-9A-Fa-f]{6}")) {
            color = 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
        }
        final String url = json.optString("url", "");
        final String glyph = json.optString("emoji_glyph", "");
        return new NexCover(mode, url.startsWith("https://") ? url : null, json.optInt("duration_s", 0), color,
            glyph.isEmpty() ? null : glyph);
    }

    static String hex(int color) {
        return String.format(java.util.Locale.US, "#%06X", color & 0xFFFFFF);
    }

    public static JSONObject plainBody(String mode, Integer color, String glyph) throws JSONException {
        final JSONObject body = new JSONObject().put("mode", mode);
        if (color != null) {
            body.put("color", hex(color));
        }
        if (glyph != null) {
            body.put("emoji_glyph", glyph);
        }
        return body;
    }

    // ---- network + memory cache (username lower-case → cover; NONE = known to have no cover) ----

    private static final NexCover NONE = new NexCover("", null, 0, null, null);
    private static final HashMap<String, NexCover> cache = new HashMap<>();

    public static NexCover cached(String username) {
        synchronized (cache) {
            final NexCover cover = cache.get(username.toLowerCase());
            return cover == NONE ? null : cover;
        }
    }

    public static boolean isCached(String username) {
        synchronized (cache) {
            return cache.containsKey(username.toLowerCase());
        }
    }

    private static void remember(String username, NexCover cover) {
        synchronized (cache) {
            cache.put(username.toLowerCase(), cover == null ? NONE : cover);
        }
    }

    // GET /v1/covers/lenta/{username} → the cover, or null when there is none (404)
    public static NexCover loadBlocking(String username) throws IOException {
        final CoreClient.Response response = CoreClient.requestBlocking("GET", "/v1/covers/lenta/" + Uri.encode(username.toLowerCase()), null, null);
        NexCover cover = null;
        if (response.code == 200) {
            try {
                cover = fromJson(response.body);
            } catch (JSONException e) {
                throw new IOException("bad cover");
            }
        } else if (response.code != 404) {
            throw new IOException("cover http " + response.code);
        }
        remember(username, cover);
        return cover;
    }

    // PUT /v1/covers (JSON): color / color_emoji / emoji
    public static NexCover savePlainBlocking(long tgUserId, String username, String mode, Integer color, String glyph)
        throws IOException, CoreClient.NotLoggedInException {
        final CoreClient.Response response;
        try {
            response = CoreClient.authedBlocking(tgUserId, "PUT", "/v1/covers", plainBody(mode, color, glyph));
        } catch (JSONException e) {
            throw new IOException("json");
        }
        return saved(username, response);
    }

    // PUT /v1/covers (multipart): photo (JPEG ≤ 2 MB) or video (MP4 ≤ 3 MB, 4 | 6 s)
    public static NexCover saveMediaBlocking(long tgUserId, String username, File file, boolean video, int durationS)
        throws IOException, CoreClient.NotLoggedInException {
        final String[][] fields = video
            ? new String[][]{{"mode", MODE_VIDEO}, {"duration_s", String.valueOf(durationS)}}
            : new String[][]{{"mode", MODE_PHOTO}};
        final CoreClient.Response response = CoreClient.authedMultipartBlocking(tgUserId, "PUT", "/v1/covers", fields, "file",
            file, video ? "cover.mp4" : "cover.jpg", video ? "video/mp4" : "image/jpeg");
        return saved(username, response);
    }

    // DELETE /v1/covers → back to the default band
    public static void deleteBlocking(long tgUserId, String username) throws IOException, CoreClient.NotLoggedInException {
        final CoreClient.Response response = CoreClient.authedBlocking(tgUserId, "DELETE", "/v1/covers", null);
        if (response.code != 204 && response.code != 200) {
            throw error(response);
        }
        remember(username, null);
    }

    private static NexCover saved(String username, CoreClient.Response response) throws IOException {
        if (response.code != 200) {
            throw error(response);
        }
        try {
            final NexCover cover = fromJson(response.body);
            remember(username, cover);
            return cover;
        } catch (JSONException e) {
            throw new IOException("bad cover");
        }
    }

    // Core error with its code (cover_invalid, cover_too_large, covers_storage_unavailable, ...)
    public static final class CoverException extends IOException {
        public final int status;
        public final String code;

        CoverException(int status, String code) {
            super("cover " + status + " " + code);
            this.status = status;
            this.code = code;
        }
    }

    private static CoverException error(CoreClient.Response response) {
        String code = "";
        try {
            code = new JSONObject(response.body).getJSONObject("error").optString("code", "");
        } catch (Exception ignore) {
        }
        return new CoverException(response.code, code);
    }
}
