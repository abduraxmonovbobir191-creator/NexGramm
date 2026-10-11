package uz.nexgramm.core;

import org.json.JSONException;
import org.json.JSONObject;

// NexGramm: token pair from the core (CONTRACT "Tokens"). Never log or print these values.
public final class CoreTokens {

    public final String accessToken;
    public final String refreshToken;
    public final long accessExpiresAt;  // ms, receive time + expires_in
    public final long refreshExpiresAt; // ms, receive time + refresh_expires_in

    CoreTokens(String accessToken, String refreshToken, long accessExpiresAt, long refreshExpiresAt) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.accessExpiresAt = accessExpiresAt;
        this.refreshExpiresAt = refreshExpiresAt;
    }

    // Token response of app/poll and refresh; expiry = receive time + expires_in
    static CoreTokens fromResponse(String body, long receivedAt) throws JSONException {
        final JSONObject json = new JSONObject(body);
        final String access = json.getString("access_token");
        final String refresh = json.getString("refresh_token");
        if (access.isEmpty() || refresh.isEmpty()) {
            throw new JSONException("empty token");
        }
        return new CoreTokens(access, refresh,
            receivedAt + json.getLong("expires_in") * 1000L,
            receivedAt + json.getLong("refresh_expires_in") * 1000L);
    }

    String toJson() throws JSONException {
        return new JSONObject()
            .put("a", accessToken)
            .put("r", refreshToken)
            .put("ae", accessExpiresAt)
            .put("re", refreshExpiresAt)
            .toString();
    }

    static CoreTokens fromJson(String s) throws JSONException {
        final JSONObject json = new JSONObject(s);
        return new CoreTokens(json.getString("a"), json.getString("r"), json.getLong("ae"), json.getLong("re"));
    }

    @Override
    public String toString() {
        return "CoreTokens{hidden}";
    }
}
