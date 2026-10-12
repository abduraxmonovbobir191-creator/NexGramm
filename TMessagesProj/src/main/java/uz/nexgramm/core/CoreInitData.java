package uz.nexgramm.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;

// NexGramm: silent core login with Telegram Mini App initData (POST /v1/auth/app/initdata).
// Plain Java (no Android classes) so it runs in JVM unit tests with a mocked Transport.
// initData and tokens are never logged.
final class CoreInitData {

    static final String PATH = "/v1/auth/app/initdata";
    static final String APP_AUTH_PATH = "/app-auth";

    interface Transport {
        CoreClient.Response post(String path, JSONObject body) throws IOException;
    }

    static final Transport HTTPS = (path, body) -> CoreClient.requestBlocking("POST", path, body, null);

    // The core answered with anything but a token pair
    static final class HttpException extends IOException {
        final int code;

        HttpException(int code) {
            super("initdata http " + code);
            this.code = code;
        }
    }

    private CoreInitData() {
    }

    // Mini App URL from messages.request*WebView: initData is the tgWebAppData parameter, decoded once
    // (same as Telegram.WebApp.initData). Looked up in the fragment first, then in the query. Null when missing.
    static String extract(String webViewUrl) {
        if (webViewUrl == null) {
            return null;
        }
        final String value = param(fragment(webViewUrl), "tgWebAppData");
        return value != null ? value : param(query(webViewUrl), "tgWebAppData");
    }

    // Safe description of a Mini App URL for the screen and the log: host, whether it is the requested
    // URL, and the parameter names of fragment and query. Never values: they hold the signed initData.
    static String describe(String requestedUrl, String webViewUrl) {
        if (webViewUrl == null) {
            return "url=null";
        }
        String host;
        try {
            host = new URI(webViewUrl).getHost();
        } catch (Exception e) {
            host = "?";
        }
        final String base = stripParams(webViewUrl);
        final String same = base.equals(requestedUrl) ? "yes" : base.startsWith(requestedUrl) ? "prefix" : "no";
        return "host=" + host + " same=" + same + " #" + keys(fragment(webViewUrl)) + " ?" + keys(query(webViewUrl));
    }

    private static String fragment(String url) {
        final int hash = url.indexOf('#');
        return hash < 0 ? null : url.substring(hash + 1);
    }

    private static String query(String url) {
        final int hash = url.indexOf('#');
        final String noFragment = hash < 0 ? url : url.substring(0, hash);
        final int q = noFragment.indexOf('?');
        return q < 0 ? null : noFragment.substring(q + 1);
    }

    private static String stripParams(String url) {
        int end = url.length();
        final int hash = url.indexOf('#');
        if (hash >= 0) {
            end = hash;
        }
        final int q = url.indexOf('?');
        if (q >= 0 && q < end) {
            end = q;
        }
        return url.substring(0, end);
    }

    private static String param(String params, String name) {
        if (params == null) {
            return null;
        }
        for (String param : params.split("&")) {
            final int eq = param.indexOf('=');
            if (eq > 0 && name.equals(param.substring(0, eq))) {
                try {
                    final String value = URLDecoder.decode(param.substring(eq + 1), "UTF-8");
                    return value.isEmpty() ? null : value;
                } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String keys(String params) {
        final StringBuilder out = new StringBuilder("[");
        if (params != null) {
            for (String param : params.split("&")) {
                if (param.isEmpty()) {
                    continue;
                }
                final int eq = param.indexOf('=');
                // A part without "=" may itself be a value: only its length is shown
                String key = eq < 0 ? "(" + param.length() + " chars)" : param.substring(0, eq);
                if (key.length() > 32) {
                    key = key.substring(0, 32) + "…";
                }
                if (out.length() > 1) {
                    out.append(',');
                }
                out.append(key);
            }
        }
        return out.append(']').toString();
    }

    // initData -> token pair (same format as app/poll). Network I/O: call on CoreClient.EXECUTOR.
    static CoreTokens exchangeBlocking(Transport transport, String initData, long now) throws IOException {
        final JSONObject body;
        try {
            body = new JSONObject().put("init_data", initData);
        } catch (JSONException e) {
            throw new IOException("json");
        }
        final CoreClient.Response response = transport.post(PATH, body);
        if (response.code != 200) {
            throw new HttpException(response.code);
        }
        try {
            return CoreTokens.fromResponse(response.body, now);
        } catch (JSONException e) {
            throw new IOException("bad initdata response");
        }
    }
}
