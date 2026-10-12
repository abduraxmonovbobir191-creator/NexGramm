package uz.nexgramm.core;

import org.json.JSONException;
import org.json.JSONObject;

// NexGramm: the app's own Pixelfed API token (CONTRACT v1.4, Lenta). Never log or print these values.
public final class PixelfedTokens {

    public final String accessToken;
    public final String refreshToken; // may be empty when Pixelfed sent none
    public final long obtainedAt;     // ms; the app asks for a new token after 30 days
    public final long expiresAt;      // ms, receive time + expires_in
    public final String username;     // from verify_credentials

    PixelfedTokens(String accessToken, String refreshToken, long obtainedAt, long expiresAt, String username) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.obtainedAt = obtainedAt;
        this.expiresAt = expiresAt;
        this.username = username;
    }

    // /oauth/token response; expires_in is optional (then the 30-day rule decides)
    static PixelfedTokens fromResponse(String body, long receivedAt, String oldRefreshToken, String username) throws JSONException {
        final JSONObject json = new JSONObject(body);
        final String access = json.getString("access_token");
        if (access.isEmpty()) {
            throw new JSONException("empty token");
        }
        String refresh = json.optString("refresh_token", "");
        if (refresh.isEmpty() && oldRefreshToken != null) {
            refresh = oldRefreshToken;
        }
        final long expiresIn = json.optLong("expires_in", 0);
        final long expiresAt = expiresIn > 0 ? receivedAt + expiresIn * 1000L : receivedAt + PixelfedClient.MAX_TOKEN_AGE_MS;
        return new PixelfedTokens(access, refresh, receivedAt, expiresAt, username);
    }

    PixelfedTokens withUsername(String username) {
        return new PixelfedTokens(accessToken, refreshToken, obtainedAt, expiresAt, username);
    }

    String toJson() throws JSONException {
        return new JSONObject()
            .put("a", accessToken)
            .put("r", refreshToken)
            .put("o", obtainedAt)
            .put("e", expiresAt)
            .put("u", username == null ? "" : username)
            .toString();
    }

    static PixelfedTokens fromJson(String s) throws JSONException {
        final JSONObject json = new JSONObject(s);
        return new PixelfedTokens(json.getString("a"), json.optString("r", ""), json.getLong("o"), json.getLong("e"), json.optString("u", ""));
    }

    @Override
    public String toString() {
        return "PixelfedTokens{hidden}";
    }
}
