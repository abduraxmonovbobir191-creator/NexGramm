package uz.nexgramm.core;

import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
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
    private static final int UPLOAD_TIMEOUT_MS = 180000; // the server processes the file before it answers
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
        public final String stage; // apps, token, verify, refresh, api, media, post, profile
        public final int code;
        // DEBUG(oauth-reason): short error name from WWW-Authenticate / the JSON body, never a value or URL
        public final String detail;

        HttpException(String stage, int code) {
            this(stage, code, null);
        }

        HttpException(String stage, Response response) {
            this(stage, response.code, errorDetail(response));
        }

        private HttpException(String stage, int code, String detail) {
            super("pixelfed " + stage + " http " + code);
            this.stage = stage;
            this.code = code;
            this.detail = detail;
        }

        public String reason() {
            return stage + "_http_" + code + (detail == null ? "" : ":" + detail);
        }
    }

    // error="..." of WWW-Authenticate, else "error" / "message" of the JSON body; [a-z0-9_.-] only, ≤ 40 chars
    static String errorDetail(Response response) {
        String detail = null;
        if (response.wwwAuthenticate != null) {
            final java.util.regex.Matcher m = java.util.regex.Pattern.compile("error=\"([^\"]*)\"").matcher(response.wwwAuthenticate);
            if (m.find()) {
                detail = m.group(1);
            }
        }
        if (detail == null || detail.isEmpty()) {
            try {
                final JSONObject json = new JSONObject(response.body);
                detail = json.optString("error", "");
                if (detail.isEmpty()) {
                    detail = json.optString("message", "");
                }
            } catch (JSONException ignore) {
            }
        }
        if (detail == null) {
            return null;
        }
        detail = detail.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_").replaceAll("^_+|_+$", "");
        if (detail.length() > 40) {
            detail = detail.substring(0, 40);
        }
        return detail.isEmpty() ? null : detail;
    }

    static final class Response {
        final int code;
        final String body;
        final String wwwAuthenticate;

        Response(int code, String body, String wwwAuthenticate) {
            this.code = code;
            this.body = body;
            this.wwwAuthenticate = wwwAuthenticate;
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

    // Decrypted tokens kept in memory: the feed asks for them on every bind (own post?), Keystore is slow
    private static final HashMap<Long, PixelfedTokens> tokenCache = new HashMap<>();

    public static PixelfedTokens getTokens(long tgUserId) {
        synchronized (tokenCache) {
            if (tokenCache.containsKey(tgUserId)) {
                return tokenCache.get(tgUserId);
            }
        }
        final String json = secrets.load(tokensKey(tgUserId));
        PixelfedTokens tokens = null;
        if (json != null) {
            try {
                tokens = PixelfedTokens.fromJson(json);
            } catch (JSONException e) {
                secrets.clear(tokensKey(tgUserId));
            }
        }
        synchronized (tokenCache) {
            tokenCache.put(tgUserId, tokens);
        }
        return tokens;
    }

    private static void saveTokens(long tgUserId, PixelfedTokens tokens) throws IOException {
        try {
            secrets.save(tokensKey(tgUserId), tokens.toJson());
        } catch (JSONException e) {
            throw new IOException("json");
        }
        synchronized (tokenCache) {
            tokenCache.remove(tgUserId); // next read goes through the store (catches a failed save)
        }
    }

    public static void clearTokens(long tgUserId) {
        synchronized (tokenCache) {
            tokenCache.remove(tgUserId);
        }
        secrets.clear(tokensKey(tgUserId));
    }

    // ---- OAuth ----

    private static Client storedClient() {
        synchronized (clientLock) {
            final String stored = secrets.load(CLIENT_KEY);
            if (stored == null) {
                return null;
            }
            try {
                final JSONObject json = new JSONObject(stored);
                return new Client(json.getString("id"), json.getString("secret"));
            } catch (JSONException e) {
                secrets.clear(CLIENT_KEY);
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
            final Response response = transport.request("POST", "/api/v1/apps",
                form("client_name", "NexGramm", "redirect_uris", REDIRECT_URI, "scopes", SCOPES), null);
            if (response.code != 200) {
                throw new HttpException("apps", response);
            }
            try {
                final JSONObject json = new JSONObject(response.body);
                final Client client = new Client(json.getString("client_id"), json.getString("client_secret"));
                if (client.id.isEmpty() || client.secret.isEmpty()) {
                    throw new IOException("bad apps response");
                }
                secrets.save(CLIENT_KEY, new JSONObject().put("id", client.id).put("secret", client.secret).toString());
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
        final Response response = transport.request("POST", "/oauth/token", form(
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
            throw new HttpException("token", response);
        }
        final PixelfedTokens tokens;
        try {
            final String type = new JSONObject(response.body).optString("token_type", "Bearer").trim();
            if (!"bearer".equalsIgnoreCase(type)) {
                throw new StepException("token_type");
            }
            tokens = PixelfedTokens.fromResponse(response.body, System.currentTimeMillis(), null, "");
        } catch (JSONException e) {
            throw new StepException("token_json");
        }
        if (!isTokenText(tokens.accessToken)) {
            throw new StepException("token_format");
        }
        // Save, read back and compare (equality and length only, nothing is logged), then use the stored copy
        saveTokens(tgUserId, tokens);
        final PixelfedTokens stored = getTokens(tgUserId);
        if (stored == null || !stored.accessToken.equals(tokens.accessToken)) {
            clearTokens(tgUserId);
            throw new StepException("store_mismatch_" + tokens.accessToken.length() + "_" + (stored == null ? 0 : stored.accessToken.length()));
        }
        final String username;
        try {
            username = verifyUsernameBlocking(stored.accessToken);
        } catch (IOException e) {
            clearTokens(tgUserId);
            if (e instanceof HttpException && ((HttpException) e).code == 401) {
                // Passport treats a token whose user id equals the client id as a client token (no user) when the
                // client may use client_credentials, and /api/v1/apps clients may. Next attempt: a new client id.
                forgetClient();
            }
            throw e;
        }
        if (auth.expectedUsername != null && !auth.expectedUsername.equalsIgnoreCase(username)) {
            clearTokens(tgUserId);
            throw new WrongAccountException();
        }
        final PixelfedTokens result = stored.withUsername(username);
        saveTokens(tgUserId, result);
        return result;
    }

    // A failed step of the token exchange; message = short code for the screen (no values)
    public static final class StepException extends IOException {
        StepException(String code) {
            super(code);
        }
    }

    // Access tokens are JWTs: printable ASCII, no spaces or line breaks
    private static boolean isTokenText(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            final char c = token.charAt(i);
            if (c <= ' ' || c > '~') {
                return false;
            }
        }
        return true;
    }

    private static String verifyUsernameBlocking(String accessToken) throws IOException {
        final Response response = transport.request("GET", "/api/v1/accounts/verify_credentials", null, accessToken);
        if (response.code != 200) {
            throw new HttpException("verify", response);
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
            secrets.clear(CLIENT_KEY);
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
            final Response response = transport.request("POST", "/oauth/token", form(
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
                throw new HttpException("refresh", response);
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
        final Response response = transport.request(method, path, formBody, tokens.accessToken);
        if (response.code != 401) {
            return response;
        }
        final PixelfedTokens refreshed = refreshBlocking(tgUserId, tokens.accessToken);
        final Response retry = transport.request(method, path, formBody, refreshed.accessToken);
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
            throw new HttpException("api", response);
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
            throw new HttpException("api", response);
        }
    }

    // POST /api/v1/statuses: a post (mediaIds, caption) or a comment (inReplyToId, text); returns the new status
    public static PixelfedStatus createStatusBlocking(long tgUserId, String text, ArrayList<String> mediaIds, String inReplyToId) throws IOException, NotConnectedException {
        final ArrayList<String> pairs = new ArrayList<>();
        pairs.add("status");
        pairs.add(text == null ? "" : text);
        pairs.add("visibility");
        pairs.add("public");
        if (mediaIds != null) {
            for (String id : mediaIds) {
                pairs.add("media_ids[]");
                pairs.add(id);
            }
        }
        if (inReplyToId != null) {
            pairs.add("in_reply_to_id");
            pairs.add(inReplyToId);
        }
        final Response response = apiBlocking(tgUserId, "POST", "/api/v1/statuses", form(pairs.toArray(new String[0])));
        if (response.code != 200) {
            throw new HttpException("post", response);
        }
        try {
            return PixelfedStatus.oneFromJson(response.body);
        } catch (JSONException e) {
            throw new IOException("bad status");
        }
    }

    // GET /api/v1/statuses/:id/context → the comments (descendants), oldest first
    public static ArrayList<PixelfedStatus> commentsBlocking(long tgUserId, String statusId) throws IOException, NotConnectedException {
        final Response response = apiBlocking(tgUserId, "GET", "/api/v1/statuses/" + encode(statusId) + "/context", null);
        if (response.code != 200) {
            throw new HttpException("api", response);
        }
        try {
            return PixelfedStatus.listFromArray(new JSONObject(response.body).optJSONArray("descendants"));
        } catch (JSONException e) {
            throw new IOException("bad context");
        }
    }

    // GET /api/v1/notifications: newest first, one page of 30; maxId = id of the last one shown (null = first page)
    public static ArrayList<PixelfedNotification> notificationsBlocking(long tgUserId, String maxId) throws IOException, NotConnectedException {
        String path = "/api/v1/notifications?limit=30";
        if (maxId != null) {
            path += "&max_id=" + encode(maxId);
        }
        final Response response = apiBlocking(tgUserId, "GET", path, null);
        if (response.code != 200) {
            throw new HttpException("api", response);
        }
        try {
            return PixelfedNotification.listFromJson(response.body);
        } catch (JSONException e) {
            throw new IOException("bad notifications");
        }
    }

    // GET /api/v1/statuses/:id
    public static PixelfedStatus statusBlocking(long tgUserId, String statusId) throws IOException, NotConnectedException {
        final Response response = apiBlocking(tgUserId, "GET", "/api/v1/statuses/" + encode(statusId), null);
        if (response.code != 200) {
            throw new HttpException("api", response);
        }
        try {
            return PixelfedStatus.oneFromJson(response.body);
        } catch (JSONException e) {
            throw new IOException("bad status");
        }
    }

    // GET /api/v1/accounts/:id
    public static PixelfedAccount accountBlocking(long tgUserId, String accountId) throws IOException, NotConnectedException {
        final Response response = apiBlocking(tgUserId, "GET", "/api/v1/accounts/" + encode(accountId), null);
        if (response.code != 200) {
            throw new HttpException("api", response);
        }
        try {
            return PixelfedAccount.fromJson(response.body);
        } catch (JSONException e) {
            throw new IOException("bad account");
        }
    }

    // GET /api/v1/accounts/relationships?id[]=: true when this user follows the account
    public static boolean isFollowingBlocking(long tgUserId, String accountId) throws IOException, NotConnectedException {
        final Response response = apiBlocking(tgUserId, "GET", "/api/v1/accounts/relationships?" + encode("id[]") + "=" + encode(accountId), null);
        if (response.code != 200) {
            throw new HttpException("api", response);
        }
        try {
            final JSONArray array = new JSONArray(response.body);
            final JSONObject relation = array.length() > 0 ? array.optJSONObject(0) : null;
            return relation != null && (relation.optBoolean("following", false) || relation.optBoolean("requested", false));
        } catch (JSONException e) {
            throw new IOException("bad relationship");
        }
    }

    // POST /api/v1/accounts/:id/follow or /unfollow
    public static void setFollowBlocking(long tgUserId, String accountId, boolean follow) throws IOException, NotConnectedException {
        final Response response = apiBlocking(tgUserId, "POST", "/api/v1/accounts/" + encode(accountId) + (follow ? "/follow" : "/unfollow"), "");
        if (response.code != 200) {
            throw new HttpException("api", response);
        }
    }

    // GET /api/v1/accounts/relationships?id[]=..: following (or requested) per account id; unknown ids are absent
    public static java.util.HashMap<String, Boolean> relationshipsBlocking(long tgUserId, java.util.Collection<String> accountIds) throws IOException, NotConnectedException {
        final java.util.HashMap<String, Boolean> result = new java.util.HashMap<>();
        if (accountIds.isEmpty()) {
            return result;
        }
        final StringBuilder query = new StringBuilder();
        for (String id : accountIds) {
            query.append(query.length() == 0 ? "?" : "&").append(encode("id[]")).append('=').append(encode(id));
        }
        final Response response = apiBlocking(tgUserId, "GET", "/api/v1/accounts/relationships" + query, null);
        if (response.code != 200) {
            throw new HttpException("api", response);
        }
        try {
            final JSONArray array = new JSONArray(response.body);
            for (int i = 0; i < array.length(); i++) {
                final JSONObject relation = array.optJSONObject(i);
                if (relation != null && !relation.optString("id", "").isEmpty()) {
                    result.put(relation.optString("id"), relation.optBoolean("following", false) || relation.optBoolean("requested", false));
                }
            }
            return result;
        } catch (JSONException e) {
            throw new IOException("bad relationship");
        }
    }

    // GET /api/v1/accounts/verify_credentials → this user's own Pixelfed account
    public static PixelfedAccount ownAccountBlocking(long tgUserId) throws IOException, NotConnectedException {
        final Response response = apiBlocking(tgUserId, "GET", "/api/v1/accounts/verify_credentials", null);
        if (response.code != 200) {
            throw new HttpException("api", response);
        }
        try {
            return PixelfedAccount.fromJson(response.body);
        } catch (JSONException e) {
            throw new IOException("bad account");
        }
    }

    // GET /api/v1/bookmarks (first 40; Pixelfed pages them by bookmark id, which statuses do not carry)
    public static ArrayList<PixelfedStatus> bookmarksBlocking(long tgUserId) throws IOException, NotConnectedException {
        final Response response = apiBlocking(tgUserId, "GET", "/api/v1/bookmarks?limit=40", null);
        if (response.code != 200) {
            throw new HttpException("api", response);
        }
        try {
            return PixelfedStatus.listFromJson(response.body);
        } catch (JSONException e) {
            throw new IOException("bad timeline");
        }
    }

    // GET /api/v1/favourites → the posts the user liked, newest like first (one page of 40)
    public static ArrayList<PixelfedStatus> favouritesBlocking(long tgUserId) throws IOException, NotConnectedException {
        final Response response = apiBlocking(tgUserId, "GET", "/api/v1/favourites?limit=40", null);
        if (response.code != 200) {
            throw new HttpException("api", response);
        }
        try {
            return PixelfedStatus.listFromJson(response.body);
        } catch (JSONException e) {
            throw new IOException("bad timeline");
        }
    }

    // POST /api/v1/accounts/update_credentials (form): name ≤ 30, bio ≤ 200, website ≤ 120 (http/https only;
    // empty clears it). Pixelfed v0.14.4 limits; returns the updated account
    public static PixelfedAccount updateProfileBlocking(long tgUserId, String displayName, String note, String website) throws IOException, NotConnectedException {
        final String site = website == null ? "" : website.trim();
        final String body = form(
            "display_name", cut(displayName == null ? "" : displayName.trim(), 30),
            "note", cut(note == null ? "" : note.trim(), 200),
            "website", site.isEmpty() || site.startsWith("http://") || site.startsWith("https://") ? cut(site, 120) : cut("https://" + site, 120));
        final Response response = apiBlocking(tgUserId, "POST", "/api/v1/accounts/update_credentials", body);
        if (response.code != 200) {
            throw new HttpException("profile", response);
        }
        try {
            return PixelfedAccount.fromJson(response.body);
        } catch (JSONException e) {
            throw new IOException("bad account");
        }
    }

    private static String cut(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    // POST /api/v1/statuses/:id/bookmark or /unbookmark
    public static void setBookmarkBlocking(long tgUserId, String statusId, boolean bookmark) throws IOException, NotConnectedException {
        final Response response = apiBlocking(tgUserId, "POST",
            "/api/v1/statuses/" + encode(statusId) + (bookmark ? "/bookmark" : "/unbookmark"), "");
        if (response.code != 200) {
            throw new HttpException("api", response);
        }
    }

    // DELETE /api/v1/statuses/:id (own posts and comments only; Pixelfed answers 403 otherwise)
    public static void deleteStatusBlocking(long tgUserId, String statusId) throws IOException, NotConnectedException {
        final Response response = apiBlocking(tgUserId, "DELETE", "/api/v1/statuses/" + encode(statusId), null);
        if (response.code != 200 && response.code != 204) {
            throw new HttpException("api", response);
        }
    }

    // Pixelfed v0.14.4 report types (ApiV1Dot1Controller::REPORT_TYPES)
    public static final String[] REPORT_TYPES = {"spam", "sensitive", "abusive", "underage", "violence", "copyright", "impersonation", "scam", "terrorism"};

    // POST /api/v1.1/report (Pixelfed has no Mastodon /api/v1/reports): report_type, object_id, object_type=post
    public static void reportStatusBlocking(long tgUserId, String statusId, String reportType) throws IOException, NotConnectedException {
        reportBlocking(tgUserId, "post", statusId, reportType);
    }

    // objectType: "post" or "user"
    public static void reportBlocking(long tgUserId, String objectType, String objectId, String reportType) throws IOException, NotConnectedException {
        final Response response = apiBlocking(tgUserId, "POST", "/api/v1.1/report",
            form("report_type", reportType, "object_id", objectId, "object_type", objectType));
        if (response.code < 200 || response.code >= 300) {
            throw new HttpException("api", response);
        }
    }

    // POST /api/v1/accounts/update_credentials (Pixelfed accepts POST and PATCH): display_name (<= 30) and/or
    // avatar (jpeg/png, <= MAX_AVATAR_SIZE, 2 MB by default). Null leaves a field unchanged.
    public static void updateCredentialsBlocking(long tgUserId, String displayName, File avatarJpeg) throws IOException, NotConnectedException {
        final ArrayList<String[]> fields = new ArrayList<>();
        if (displayName != null) {
            fields.add(new String[]{"display_name", displayName.length() > 30 ? displayName.substring(0, 30) : displayName});
        }
        final PixelfedTokens tokens = freshTokensBlocking(tgUserId);
        Response response = transport.multipart("/api/v1/accounts/update_credentials", fields, "avatar", avatarJpeg, "avatar.jpg", "image/jpeg", tokens.accessToken, null);
        if (response.code == 401) {
            final PixelfedTokens refreshed = refreshBlocking(tgUserId, tokens.accessToken);
            response = transport.multipart("/api/v1/accounts/update_credentials", fields, "avatar", avatarJpeg, "avatar.jpg", "image/jpeg", refreshed.accessToken, null);
            if (response.code == 401) {
                clearTokens(tgUserId);
                throw new NotConnectedException();
            }
        }
        if (response.code != 200) {
            throw new HttpException("profile", response);
        }
    }

    // GET /api/v1/accounts/:id/statuses?only_media=true (profile grid)
    public static ArrayList<PixelfedStatus> accountMediaBlocking(long tgUserId, String accountId, String maxId) throws IOException, NotConnectedException {
        String path = "/api/v1/accounts/" + encode(accountId) + "/statuses?only_media=true&limit=" + PAGE_SIZE;
        if (maxId != null) {
            path += "&max_id=" + encode(maxId);
        }
        final Response response = apiBlocking(tgUserId, "GET", path, null);
        if (response.code != 200) {
            throw new HttpException("api", response);
        }
        try {
            return PixelfedStatus.listFromJson(response.body);
        } catch (JSONException e) {
            throw new IOException("bad timeline");
        }
    }

    // GET /api/v1/accounts/:id/statuses (max_id paging)
    public static ArrayList<PixelfedStatus> accountStatusesBlocking(long tgUserId, String accountId, String maxId) throws IOException, NotConnectedException {
        String path = "/api/v1/accounts/" + encode(accountId) + "/statuses?limit=" + PAGE_SIZE;
        if (maxId != null) {
            path += "&max_id=" + encode(maxId);
        }
        final Response response = apiBlocking(tgUserId, "GET", path, null);
        if (response.code != 200) {
            throw new HttpException("api", response);
        }
        try {
            return PixelfedStatus.listFromJson(response.body);
        } catch (JSONException e) {
            throw new IOException("bad timeline");
        }
    }

    // The Pixelfed username this app's token belongs to (null when not connected)
    public static String ownUsername(long tgUserId) {
        final PixelfedTokens tokens = getTokens(tgUserId);
        return tokens == null || tokens.username.isEmpty() ? null : tokens.username;
    }

    public interface UploadProgress {
        // called on the upload thread
        void onProgress(long sent, long total);
    }

    // POST /api/v1/media (multipart "file") → media id. 401 → refresh once and send again.
    public static String uploadMediaBlocking(long tgUserId, File file, String mime, UploadProgress progress) throws IOException, NotConnectedException {
        final PixelfedTokens tokens = freshTokensBlocking(tgUserId);
        Response response = transport.multipart("/api/v1/media", null, "file", file, mediaFileName(mime), mime, tokens.accessToken, progress);
        if (response.code == 401) {
            final PixelfedTokens refreshed = refreshBlocking(tgUserId, tokens.accessToken);
            response = transport.multipart("/api/v1/media", null, "file", file, mediaFileName(mime), mime, refreshed.accessToken, progress);
            if (response.code == 401) {
                clearTokens(tgUserId);
                throw new NotConnectedException();
            }
        }
        if (response.code != 200 && response.code != 202) {
            throw new HttpException("media", response);
        }
        try {
            final String id = new JSONObject(response.body).getString("id");
            if (id.isEmpty()) {
                throw new IOException("bad media");
            }
            return id;
        } catch (JSONException e) {
            throw new IOException("bad media");
        }
    }

    // ---- HTTP ----

    private static String mediaFileName(String mime) {
        return mime.startsWith("video/") ? "video.mp4" : "photo.jpg";
    }

    // HTTP layer and secret storage are replaceable so JVM tests can run the client without a network
    interface Transport {
        // HTTPS to HOST only, no redirects, form-encoded body (null = no body)
        Response request(String method, String path, String formBody, String bearer) throws IOException;

        // multipart/form-data POST: text fields (name, value pairs; may be null) and one file part (file may be null)
        Response multipart(String path, ArrayList<String[]> fields, String fileField, File file, String fileName, String mime, String bearer, UploadProgress progress) throws IOException;
    }

    interface Secrets {
        String load(String key);

        void save(String key, String value);

        void clear(String key);
    }

    private static Transport transport = new HttpsTransport();
    private static Secrets secrets = new Secrets() {
        @Override
        public String load(String key) {
            return CoreTokenStore.loadSecret(key);
        }

        @Override
        public void save(String key, String value) {
            CoreTokenStore.saveSecret(key, value);
        }

        @Override
        public void clear(String key) {
            CoreTokenStore.clearSecret(key);
        }
    };

    // tests only
    static void setForTests(Transport testTransport, Secrets testSecrets) {
        transport = testTransport;
        secrets = testSecrets;
        synchronized (tokenCache) {
            tokenCache.clear();
        }
    }

    private static final class HttpsTransport implements Transport {

        private static HttpsURLConnection open(String path) throws IOException {
            final URL url = new URL(BASE + path);
            if (!"https".equals(url.getProtocol()) || !HOST.equals(url.getHost())) {
                throw new IOException("bad pixelfed url");
            }
            final HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            return connection;
        }

        private static Response read(HttpsURLConnection connection) throws IOException {
            final int code = connection.getResponseCode();
            final InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(code, CoreClient.read(in), connection.getHeaderField("WWW-Authenticate"));
        }

        @Override
        public Response request(String method, String path, String formBody, String bearer) throws IOException {
            final HttpsURLConnection connection = open(path);
            try {
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setRequestMethod(method);
                if (bearer != null) {
                    connection.setRequestProperty("Authorization", "Bearer " + bearer.trim());
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
                return read(connection);
            } finally {
                connection.disconnect();
            }
        }

        @Override
        public Response multipart(String path, ArrayList<String[]> fields, String fileField, File file, String fileName, String mime, String bearer, UploadProgress progress) throws IOException {
            final String boundary = "NexGramm" + randomToken(12).replace('-', 'a').replace('_', 'b');
            final StringBuilder textParts = new StringBuilder();
            if (fields != null) {
                for (String[] field : fields) {
                    textParts.append("--").append(boundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"").append(field[0]).append("\"\r\n\r\n")
                        .append(field[1]).append("\r\n");
                }
            }
            final byte[] head = textParts.toString().getBytes(StandardCharsets.UTF_8);
            final byte[] fileHead = file == null ? new byte[0] : ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            final byte[] tail = ((file == null ? "" : "\r\n") + "--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            final long fileLength = file == null ? 0 : file.length();
            final long total = head.length + fileHead.length + fileLength + tail.length;
            final HttpsURLConnection connection = open(path);
            try {
                connection.setReadTimeout(UPLOAD_TIMEOUT_MS);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Authorization", "Bearer " + bearer.trim());
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(total);
                long sent = 0;
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(head);
                    out.write(fileHead);
                    sent += head.length + fileHead.length;
                    if (file != null) {
                        try (InputStream in = new java.io.FileInputStream(file)) {
                            final byte[] buffer = new byte[64 * 1024];
                            int n;
                            while ((n = in.read(buffer)) != -1) {
                                out.write(buffer, 0, n);
                                sent += n;
                                if (progress != null) {
                                    progress.onProgress(sent, total);
                                }
                            }
                        }
                    }
                    out.write(tail);
                    sent += tail.length;
                }
                if (progress != null) {
                    progress.onProgress(sent, total);
                }
                return read(connection);
            } finally {
                connection.disconnect();
            }
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
