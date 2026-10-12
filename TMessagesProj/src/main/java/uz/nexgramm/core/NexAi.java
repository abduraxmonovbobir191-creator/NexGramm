package uz.nexgramm.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.BuildConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import javax.net.ssl.HttpsURLConnection;

// NexGramm: client of the NEX AI backend (NexCode, POST /api/v1/chat, server-sent events). The user is the
// NexGramm core identity (core JWT, NexCode B5.1). AI runs only on an explicit user action; nothing here
// stores or logs chats, prompts or answers.
public final class NexAi {

    public static final int MAX_MESSAGE = 8000;

    public static final int ERROR_NOT_CONFIGURED = 1;
    public static final int ERROR_LOGIN = 2;
    public static final int ERROR_LIMIT = 3;
    public static final int ERROR_UNAVAILABLE = 4;
    public static final int ERROR_NETWORK = 5;

    public interface Listener {
        // a piece of the answer (called on the callback executor, in order)
        void onText(String chunk);

        // the backend asks a question back (image / video / build intake); options may be empty
        default void onQuestion(String question, List<String> options) {
        }

        // the answer is complete
        void onDone();

        // code = ERROR_*; message = a user-safe text from the backend, or null
        void onError(int code, String message);
    }

    // A running request; cancel() stops the stream and no more callbacks follow
    public static final class Call {
        private volatile boolean cancelled;
        private volatile HttpsURLConnection connection;

        public void cancel() {
            cancelled = true;
            final HttpsURLConnection c = connection;
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Exception ignore) {
                }
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    // One parsed SSE event
    public static final class Event {
        public final String type;
        public final String content;
        public final String message;
        public final String question;
        public final List<String> options;

        Event(String type, String content, String message, String question, List<String> options) {
            this.type = type;
            this.content = content;
            this.message = message;
            this.question = question;
            this.options = options;
        }
    }

    private NexAi() {
    }

    public static boolean configured() {
        return !BuildConfig.NEX_API_URL.isEmpty();
    }

    public static String newThreadId() {
        return "ng-" + UUID.randomUUID();
    }

    // "data: {...}" → the event, null for other SSE lines (comments, blank lines, bad JSON)
    public static Event parseLine(String line) {
        if (line == null || !line.startsWith("data:")) {
            return null;
        }
        try {
            final JSONObject json = new JSONObject(line.substring(5).trim());
            final ArrayList<String> options = new ArrayList<>();
            final JSONArray array = json.optJSONArray("options");
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    options.add(array.optString(i));
                }
            }
            return new Event(json.optString("type", ""), json.optString("content", ""),
                json.isNull("message") ? null : json.optString("message", null),
                json.isNull("question") ? null : json.optString("question", null), options);
        } catch (JSONException e) {
            return null;
        }
    }

    // Starts a request on CoreClient.EXECUTOR; listener calls go through callbackExecutor (the UI thread).
    // plainChat = answer as text, never the image / video / build intake (in-chat tools).
    public static Call chat(long tgUserId, String threadId, String message, boolean plainChat, Executor callbackExecutor, Listener listener) {
        final Call call = new Call();
        CoreClient.EXECUTOR.execute(() -> run(call, tgUserId, threadId, message, plainChat, callbackExecutor, listener));
        return call;
    }

    private static void run(Call call, long tgUserId, String threadId, String message, boolean plainChat, Executor ui, Listener listener) {
        if (!configured()) {
            post(call, ui, () -> listener.onError(ERROR_NOT_CONFIGURED, null));
            return;
        }
        final String text = message.length() > MAX_MESSAGE ? message.substring(message.length() - MAX_MESSAGE) : message;
        try {
            CoreTokens tokens = CoreClient.freshTokensBlocking(tgUserId);
            int code = stream(call, tokens.accessToken, threadId, text, plainChat, ui, listener);
            if (code == 401 && !call.cancelled) {
                tokens = CoreClient.refreshBlocking(tgUserId, tokens.refreshToken);
                code = stream(call, tokens.accessToken, threadId, text, plainChat, ui, listener);
                if (code == 401) {
                    post(call, ui, () -> listener.onError(ERROR_LOGIN, null));
                }
            }
        } catch (CoreClient.NotLoggedInException e) {
            post(call, ui, () -> listener.onError(ERROR_LOGIN, null));
        } catch (IOException e) {
            post(call, ui, () -> listener.onError(ERROR_NETWORK, null));
        }
    }

    // Returns the HTTP code; 200 = the stream was delivered (done or error events included)
    private static int stream(Call call, String bearer, String threadId, String text, boolean plainChat, Executor ui, Listener listener) throws IOException {
        if (call.cancelled) {
            return 0;
        }
        final URL url = new URL(BuildConfig.NEX_API_URL + "/api/v1/chat");
        if (!"https".equals(url.getProtocol())) {
            throw new IOException("nex url must be https");
        }
        final byte[] body;
        try {
            final JSONObject json = new JSONObject().put("thread_id", threadId).put("user_message", text);
            if (plainChat) {
                json.put("mode", "chat");
            }
            body = json.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            throw new IOException("json");
        }
        final HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        call.connection = connection;
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(90_000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
            final int code = connection.getResponseCode();
            if (code == 401) {
                return code;
            }
            if (code != 200) {
                // 429 limit_exceeded / 501 not_available / 503: the UI shows its own text
                final int kind = code == 429 ? ERROR_LIMIT : code == 501 || code == 503 ? ERROR_UNAVAILABLE : ERROR_NETWORK;
                post(call, ui, () -> listener.onError(kind, null));
                return code;
            }
            boolean finished = false;
            try (InputStream in = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while (!call.cancelled && (line = reader.readLine()) != null) {
                    final Event event = parseLine(line);
                    if (event == null) {
                        continue;
                    }
                    switch (event.type) {
                        case "text":
                            if (!event.content.isEmpty()) {
                                post(call, ui, () -> listener.onText(event.content));
                            }
                            break;
                        case "clarification":
                            post(call, ui, () -> listener.onQuestion(event.question != null ? event.question : event.content, event.options));
                            break;
                        case "error":
                            finished = true;
                            post(call, ui, () -> listener.onError(ERROR_UNAVAILABLE, event.message));
                            break;
                        case "done":
                            if (!finished) {
                                finished = true;
                                post(call, ui, listener::onDone);
                            }
                            break;
                        default:
                            break; // stage / handoff: nothing to show here
                    }
                }
            }
            if (!finished) {
                post(call, ui, listener::onDone);
            }
            return code;
        } finally {
            call.connection = null;
            connection.disconnect();
        }
    }

    private static void post(Call call, Executor ui, Runnable runnable) {
        ui.execute(() -> {
            if (!call.cancelled) {
                runnable.run();
            }
        });
    }
}
