package uz.nexgramm.core;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

// NexGramm: client of the NexGramm core API (CONTRACT v1.2), kept apart from Telegram code.
// Methods named *Blocking do network I/O: call them on EXECUTOR, never on the UI thread.
// Tokens, nonces and response bodies are never logged.
public final class CoreClient {

    public static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private static final int TIMEOUT_MS = 15000;
    private static final long REFRESH_EARLY_MS = 60_000;

    public static final class Response {
        public final int code;
        public final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    // Thrown when there are no tokens or the refresh token was rejected: the user must log in again
    public static final class NotLoggedInException extends Exception {
    }

    private static final HashMap<Long, Object> refreshLocks = new HashMap<>();

    private CoreClient() {
    }

    // HTTPS only: CORE_API_URL is checked at build time and again here
    static Response requestBlocking(String method, String path, JSONObject body, String bearer) throws IOException {
        final URL url = new URL(BuildConfig.CORE_API_URL + path);
        if (!"https".equals(url.getProtocol())) {
            throw new IOException("core url must be https");
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
            if (body != null) {
                final byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            final int code = connection.getResponseCode();
            final InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(code, read(in));
        } finally {
            connection.disconnect();
        }
    }

    static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            int n;
            while ((n = stream.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    public static CoreTokens getTokens(long tgUserId) {
        return CoreTokenStore.load(tgUserId);
    }

    static boolean saveTokens(long tgUserId, CoreTokens tokens) {
        return CoreTokenStore.save(tgUserId, tokens);
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

    // One refresh at a time per account. The refresh token rotates on every call, so a caller that
    // waited for another refresh gets that result instead of sending the (now dead) old token again.
    static CoreTokens refreshBlocking(long tgUserId, String usedRefreshToken) throws IOException, NotLoggedInException {
        synchronized (refreshLock(tgUserId)) {
            final CoreTokens current = CoreTokenStore.load(tgUserId);
            if (current == null) {
                throw new NotLoggedInException();
            }
            if (!current.refreshToken.equals(usedRefreshToken)) {
                return current;
            }
            final Response response;
            try {
                response = requestBlocking("POST", "/v1/auth/refresh", new JSONObject().put("refresh_token", current.refreshToken), null);
            } catch (JSONException e) {
                throw new IOException("json");
            }
            if (response.code == 401) {
                // invalid_refresh_token: never retry the same token, drop it and show login
                CoreTokenStore.clear(tgUserId);
                throw new NotLoggedInException();
            }
            if (response.code != 200) {
                throw new IOException("refresh http " + response.code);
            }
            try {
                final CoreTokens tokens = CoreTokens.fromResponse(response.body, System.currentTimeMillis());
                CoreTokenStore.save(tgUserId, tokens);
                return tokens;
            } catch (JSONException e) {
                throw new IOException("bad refresh response");
            }
        }
    }

    // Returns tokens whose access token is valid for at least a minute, refreshing when needed
    public static CoreTokens freshTokensBlocking(long tgUserId) throws IOException, NotLoggedInException {
        final CoreTokens tokens = CoreTokenStore.load(tgUserId);
        if (tokens == null) {
            throw new NotLoggedInException();
        }
        if (tokens.accessExpiresAt - REFRESH_EARLY_MS > System.currentTimeMillis()) {
            return tokens;
        }
        return refreshBlocking(tgUserId, tokens.refreshToken);
    }

    // Authed call: on 401 refresh once and retry once; if that also fails the caller must show login
    public static Response authedBlocking(long tgUserId, String method, String path, JSONObject body) throws IOException, NotLoggedInException {
        final CoreTokens tokens = freshTokensBlocking(tgUserId);
        final Response response = requestBlocking(method, path, body, tokens.accessToken);
        if (response.code != 401) {
            return response;
        }
        final CoreTokens refreshed = refreshBlocking(tgUserId, tokens.refreshToken);
        final Response retry = requestBlocking(method, path, body, refreshed.accessToken);
        if (retry.code == 401) {
            throw new NotLoggedInException();
        }
        return retry;
    }

    // Authed multipart upload (covers): form fields + one file; 401 → refresh once and send again
    public static Response authedMultipartBlocking(long tgUserId, String method, String path, String[][] fields, String fileField,
                                                   java.io.File file, String fileName, String mime) throws IOException, NotLoggedInException {
        final CoreTokens tokens = freshTokensBlocking(tgUserId);
        final Response response = multipartBlocking(method, path, fields, fileField, file, fileName, mime, tokens.accessToken);
        if (response.code != 401) {
            return response;
        }
        final CoreTokens refreshed = refreshBlocking(tgUserId, tokens.refreshToken);
        final Response retry = multipartBlocking(method, path, fields, fileField, file, fileName, mime, refreshed.accessToken);
        if (retry.code == 401) {
            throw new NotLoggedInException();
        }
        return retry;
    }

    static Response multipartBlocking(String method, String path, String[][] fields, String fileField, java.io.File file,
                                      String fileName, String mime, String bearer) throws IOException {
        final URL url = new URL(BuildConfig.CORE_API_URL + path);
        if (!"https".equals(url.getProtocol())) {
            throw new IOException("core url must be https");
        }
        final String boundary = "nexgramm" + Long.toHexString(System.nanoTime()) + Long.toHexString(Double.doubleToLongBits(Math.random()));
        final ByteArrayOutputStream head = new ByteArrayOutputStream();
        for (String[] field : fields) {
            head.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field[0] + "\"\r\n\r\n" + field[1] + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        head.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField + "\"; filename=\"" + fileName
            + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        final byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        final HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(60_000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(head.size() + file.length() + tail.length);
            try (OutputStream out = connection.getOutputStream(); InputStream in = new java.io.FileInputStream(file)) {
                head.writeTo(out);
                final byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
                out.write(tail);
            }
            final int code = connection.getResponseCode();
            final InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(code, read(in));
        } finally {
            connection.disconnect();
        }
    }

    // Core answered with an error: CONTRACT {"error": {"code", "message_uz"}}; message is null when absent
    public static final class ApiException extends IOException {
        public final int code;
        public final String messageUz;

        ApiException(int code, String messageUz) {
            super("core http " + code);
            this.code = code;
            this.messageUz = messageUz;
        }
    }

    // POST /v1/lenta/session (core_api/routers/lenta.py) → {url, expires_in, username?}. The url carries a
    // one-time ticket (60 s): open it at once, never store or log it. username (CONTRACT v1.4) is the
    // user's Pixelfed username; null when an older core does not send it (the check is then skipped).
    public static final class LentaSession {
        public final String url;
        public final String username;

        LentaSession(String url, String username) {
            this.url = url;
            this.username = username;
        }

        @Override
        public String toString() {
            return "LentaSession{hidden}";
        }
    }

    public static LentaSession lentaSessionBlocking(long tgUserId) throws IOException, NotLoggedInException {
        final Response response = authedBlocking(tgUserId, "POST", "/v1/lenta/session", new JSONObject());
        if (response.code != 200) {
            String message = null;
            try {
                message = new JSONObject(response.body).getJSONObject("error").optString("message_uz", null);
            } catch (JSONException ignore) {
            }
            throw new ApiException(response.code, message);
        }
        try {
            final JSONObject json = new JSONObject(response.body);
            final String url = json.getString("url");
            if (url.isEmpty()) {
                throw new IOException("bad lenta response");
            }
            final String username = json.optString("username", "");
            return new LentaSession(url, username.isEmpty() ? null : username);
        } catch (JSONException e) {
            throw new IOException("bad lenta response");
        }
    }

    public static String lentaSessionUrlBlocking(long tgUserId) throws IOException, NotLoggedInException {
        return lentaSessionBlocking(tgUserId).url;
    }

    // Deletes local tokens at once; the core is told in the background (logout is idempotent, 204)
    public static void logout(long tgUserId) {
        final CoreTokens tokens;
        synchronized (refreshLock(tgUserId)) {
            tokens = CoreTokenStore.load(tgUserId);
            CoreTokenStore.clear(tgUserId);
        }
        PixelfedClient.clearTokens(tgUserId);
        if (tokens == null) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                requestBlocking("POST", "/v1/auth/logout", new JSONObject().put("refresh_token", tokens.refreshToken), null);
            } catch (Exception ignore) {
            }
        });
    }
}
