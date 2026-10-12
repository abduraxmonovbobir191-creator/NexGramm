package uz.nexgramm.core;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
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

    // Mini App URL from messages.requestSimpleWebView: initData is the tgWebAppData parameter of the
    // URL fragment, decoded once (same as Telegram.WebApp.initData). Null when it is missing.
    static String extract(String webViewUrl) {
        if (webViewUrl == null) {
            return null;
        }
        final int hash = webViewUrl.indexOf('#');
        if (hash < 0) {
            return null;
        }
        for (String param : webViewUrl.substring(hash + 1).split("&")) {
            final int eq = param.indexOf('=');
            if (eq > 0 && "tgWebAppData".equals(param.substring(0, eq))) {
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
