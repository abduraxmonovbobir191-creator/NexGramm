package uz.nexgramm.core;

import android.net.Uri;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;

import javax.net.ssl.HttpsURLConnection;

// NexGramm: the app's own Pixelfed API access (CONTRACT v1.4 "Lenta", DECISIONS B009 in NexGramm-Bot).
// OAuth 2 authorization code + PKCE S256 inside the Lenta WebView (NexLentaView.startOAuth), then plain
// Mastodon API calls. Client id/secret and tokens are kept encrypted (CoreTokenStore); none of them,
// nor codes, verifiers or response bodies, are ever logged. *Blocking methods: call on CoreClient.EXECUTOR.
public final class PixelfedClient {

    public static final String HOST = "feed.nexecos.com";
    public static final String BASE = "https://" + HOST;
    public static final String REDIRECT_URI = "nexgramm://lenta/oauth";
    public static final String SCOPES = "read write follow";
    static final long MAX_TOKEN_AGE_MS = 30L * 24 * 60 * 60 * 1000;
    private static final long REFRESH_EARLY_MS = 24L * 60 * 60 * 1000;
    private static final int TIMEOUT_MS = 20000;
    private static final int PAGE_SIZE = 20;
    private static final String CLIENT_KEY = "pf_client";

    private static final HashMap<Long, Object> refreshLocks = new HashMap<>();
    private static final Object clientLock = new Object();

    // No usable Pixelfed token (none, rejected, or refresh failed): run the OAuth flow again
    public static final class NotConnectedException extends Exception {
    }

    // The token belongs to another Pixelfed account than the core said: the token is thrown away
    public static final class WrongAccountException extends IOException {
        WrongAccountException() {
            super("wrong pixelfed account");
        }
    }

    public static final class HttpException extends IOException {
        public final String stage; // apps, token, verify, refresh, api
        public final int code;

        HttpException(String stage, int code) {
            super("pixelfed " + stage + " http " + code);
            this.stage = stage;
            this.code = code;
        }
    }

    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private static final class Client {
        final String id;
        final String secret;

        Client(String id, String secret) {
            this.id = id;
            this.secret = secret;
        }
    }

    // One OAuth attempt: open authorizeUrl in the logged-in WebView, give the redirect's code to
    // exchangeCodeBlocking. state and verifier never leave this object (nothing here is logged).
    public static final class Authorization {
        public final String authorizeUrl;
        private final String state;
        private final String verifier;
        private final Client client;
        private final String expectedUsername;

        private Authorization(String authorizeUrl, String state, String verifier, Client client, String expectedUsername) {
            this.authorizeUrl = authorizeUrl;
            this.state = state;
            this.verifier = verifier;
            this.client = client;
            this.expectedUsername = expectedUsername;
        }

        public boolean isState(String value) {
            return value != null && MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String toString() {
            return "Authorization{hidden}";
        }
    }

    private PixelfedClient() {
    }

    private static String tokensKey(long tgUserId) {
        return "pf_tokens_" + tgUserId;
    }

    public static PixelfedTokens getTokens(long tgUserId) {
        final String json = CoreTokenStore.loadSecret(tokensKey(tgUserId));
        if (json == null) {
            return null;
        }
        try {
            return PixelfedTokens.fromJson(json);
        } catch (JSONException e) {
            CoreTokenStore.clearSecret(tokensKey(tgUserId));
            return null;
        }
    }

    private static void saveTokens(long tgUserId, PixelfedTokens tokens) throws IOException {
        try {
            CoreTokenStore.saveSecret(tokensKey(tgUserId), tokens.toJson());
        } catch (JSONException e) {
            throw new IOException("json");
        }
    }

    public static void clearTokens(long tgUserId) {
        CoreTokenStore.clearSecret(tokensKey(tgUserId));
    }

    // ---- OAuth ----

    private static Client storedClient() {
        synchronized (clientLock) {
            final String stored = CoreTokenStore.loadSecret(CLIENT_KEY);
            if (stored == null) {
                return null;
            }
            try {
                final JSONObject json = new JSONObject(stored);
                return new Client(json.getString("id"), json.getString("secret"));
            } catch (JSONException e) {
                CoreTokenStore.clearSecret(CLIENT_KEY);
                return null;
            }
        }
    }

    // Registered once per install: POST /api/v1/apps (Mastodon API), kept encrypted
    private static Client clientBlocking() throws IOException {
        synchronized (clientLock) {
            final Client stored = storedClient();
            if (stored != null) {
                return stored;
            }
            final Response response = requestBlocking("POST", "/api/v1/apps",
                form("client_name", "NexGramm", "redirect_uris", REDIRECT_URI, "scopes", SCOPES), null);
            if (response.code != 200) {
                throw new HttpException("apps", response.code);
            }
            try {
                final JSONObject json = new JSONObject(response.body);
                final Client client = new Client(json.getString("client_id"), json.getString("client_secret"));
                if (client.id.isEmpty() || client.secret.isEmpty()) {
                    throw new IOException("bad apps response");
                }
                CoreTokenStore.saveSecret(CLIENT_KEY, new JSONObject().put("id", client.id).put("secret", client.secret).toString());
                return client;
            } catch (JSONException e) {
                throw new IOException("bad apps response");
            }
        }
    }

    // expectedUsername: from POST /v1/lenta/session (null = older core, check skipped)
    public static Authorization newAuthorizationBlocking(String expectedUsername) throws IOException {
        final Client client = clientBlocking();
        final String state = randomToken(24);
        final String verifier = randomToken(48);
        final String challenge;
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            challenge = Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("sha256");
        }
        final String url = Uri.parse(BASE + "/oauth/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", client.id)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build().toString();
        return new Authorization(url, state, verifier, client, expectedUsername);
    }

    // Code → tokens, then verify_credentials must name the account the core expects
    public static PixelfedTokens exchangeCodeBlocking(long tgUserId, Authorization auth, String code) throws IOException {
        final Response response = requestBlocking("POST", "/oauth/token", form(
            "grant_type", "authorization_code",
            "code", code,
            "redirect_uri", REDIRECT_URI,
            "client_id", auth.client.id,
            "client_secret", auth.client.secret,
            "code_verifier", auth.verifier), null);
        if (response.code == 401) {
            // invalid_client (e.g. the app was deleted on the server): register again next time
            forgetClient();
        }
        if (response.code != 200) {
            throw new HttpException("token", response.code);
        }
        final PixelfedTokens tokens;
        try {
            tokens = PixelfedTokens.fromResponse(response.body, System.currentTimeMillis(), null, "");
        } catch (JSONException e) {
            throw new IOException("bad token response");
        }
        final String username = verifyUsernameBlocking(tokens.accessToken);
        if (auth.expectedUsername != null && !auth.expectedUsername.equalsIgnoreCase(username)) {
            throw new WrongAccountException();
        }
        final PixelfedTokens result = tokens.withUsername(username);
        saveTokens(tgUserId, result);
        return result;
    }

    private static String verifyUsernameBlocking(String accessToken) throws IOException {
        final Response response = requestBlocking("GET", "/api/v1/accounts/verify_credentials", null, accessToken);
        if (response.code != 200) {
            throw new HttpException("verify", response.code);
        }
        try {
            final String username = new JSONObject(response.body).getString("username");
            if (username.isEmpty()) {
                throw new IOException("bad account");
            }
            return username;
        } catch (JSONException e) {
            throw new IOException("bad account");
        }
    }

    private static void forgetClient() {
        synchronized (clientLock) {
            CoreTokenStore.clearSecret(CLIENT_KEY);
        }
    }

    private static Object refreshLock(long tgUserId) {
        synchronized (refreshLocks) {
            Object lock = refreshLocks.get(tgUserId);
            if (lock == null) {
                refreshLocks.put(tgUserId, lock = new Object());
            }
            return lock;
        }
    }

    // One refresh at a time per account; the refresh token rotates, so a waiting caller reuses the result
    private static PixelfedTokens refreshBlocking(long tgUserId, String usedAccessToken) throws IOException, NotConnectedException {
        synchronized (refreshLock(tgUserId)) {
            final PixelfedTokens current = getTokens(tgUserId);
            if (current == null) {
                throw new NotConnectedException();
            }
            if (!current.accessToken.equals(usedAccessToken)) {
                return current;
            }
            if (current.refreshToken.isEmpty()) {
                clearTokens(tgUserId);
                throw new NotConnectedException();
            }
            final Client client = storedClient();
            if (client == null) {
                clearTokens(tgUserId);
                throw new NotConnectedException();
            }
            final Response response = requestBlocking("POST", "/oauth/token", form(
                "grant_type", "refresh_token",
                "refresh_token", current.refreshToken,
                "client_id", client.id,
                "client_secret", client.secret,
                "scope", SCOPES), null);
            if (response.code == 400 || response.code == 401) {
                // refresh token expired, revoked or reused: OAuth again
                clearTokens(tgUserId);
                throw new NotConnectedException();
            }
            if (response.code != 200) {
                throw new HttpException("refresh", response.code);
            }
            try {
                final PixelfedTokens tokens = PixelfedTokens.fromResponse(response.body, System.currentTimeMillis(), current.refreshToken, current.username);
                saveTokens(tgUserId, tokens);
                return tokens;
            } catch (JSONException e) {
                throw new IOException("bad token response");
            }
        }
    }

    // Tokens younger than 30 days and not about to expire; older ones are refreshed first
    private static PixelfedTokens freshTokensBlocking(long tgUserId) throws IOException, NotConnectedException {
        final PixelfedTokens tokens = getTokens(tgUserId);
        if (tokens == null) {
            throw new NotConnectedException();
        }
        final long now = System.currentTimeMillis();
        if (now - tokens.obtainedAt < MAX_TOKEN_AGE_MS - REFRESH_EARLY_MS && tokens.expiresAt - REFRESH_EARLY_MS > now) {
            return tokens;
        }
        return refreshBlocking(tgUserId, tokens.accessToken);
    }

    // Authed call: on 401 refresh once and retry once; still 401 → tokens dropped, OAuth again
    private static Response apiBlocking(long tgUserId, String method, String path, String formBody) throws IOException, NotConnectedException {
        final PixelfedTokens tokens = freshTokensBlocking(tgUserId);
        final Response response = requestBlocking(method, path, formBody, tokens.accessToken);
        if (response.code != 401) {
            return response;
        }
        final PixelfedTokens refreshed = refreshBlocking(tgUserId, tokens.accessToken);
        final Response retry = requestBlocking(method, path, formBody, refreshed.accessToken);
        if (retry.code == 401) {
            clearTokens(tgUserId);
            throw new NotConnectedException();
        }
        return retry;
    }

    // ---- API ----

    // Home timeline, or the local public one; maxId = id of the last post shown (null = first page)
    public static ArrayList<PixelfedStatus> timelineBlocking(long tgUserId, boolean publicLocal, String maxId) throws IOException, NotConnectedException {
        String path = publicLocal
            ? "/api/v1/timelines/public?local=true&limit=" + PAGE_SIZE
            : "/api/v1/timelines/home?limit=" + PAGE_SIZE;
        if (maxId != null) {
            path += "&max_id=" + encode(maxId);
        }
        final Response response = apiBlocking(tgUserId, "GET", path, null);
        if (response.code != 200) {
            throw new HttpException("api", response.code);
        }
        try {
            return PixelfedStatus.listFromJson(response.body);
        } catch (JSONException e) {
            throw new IOException("bad timeline");
        }
    }

    // POST /api/v1/statuses/:id/favourite or /unfavourite
    public static void setFavouriteBlocking(long tgUserId, String statusId, boolean favourite) throws IOException, NotConnectedException {
        final Response response = apiBlocking(tgUserId, "POST",
            "/api/v1/statuses/" + encode(statusId) + (favourite ? "/favourite" : "/unfavourite"), "");
        if (response.code != 200) {
            throw new HttpException("api", response.code);
        }
    }

    // ---- HTTP ----

    // HTTPS to HOST only, no redirects, form-encoded body (null = no body)
    private static Response requestBlocking(String method, String path, String formBody, String bearer) throws IOException {
        final URL url = new URL(BASE + path);
        if (!"https".equals(url.getProtocol()) || !HOST.equals(url.getHost())) {
            throw new IOException("bad pixelfed url");
        }
        final HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            if (bearer != null) {
                connection.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            if (formBody != null) {
                final byte[] bytes = formBody.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            final int code = connection.getResponseCode();
            final InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(code, CoreClient.read(in));
        } finally {
            connection.disconnect();
        }
    }

    private static String form(String... pairs) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(encode(pairs[i])).append('=').append(encode(pairs[i + 1]));
        }
        return sb.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomToken(int bytes) {
        final byte[] data = new byte[bytes];
        new SecureRandom().nextBytes(data);
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
}
