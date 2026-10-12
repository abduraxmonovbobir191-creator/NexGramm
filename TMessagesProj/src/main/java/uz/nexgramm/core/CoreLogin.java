package uz.nexgramm.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;
import android.util.SparseArray;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.net.URLEncoder;

// NexGramm: core login for one Telegram account.
// Main path, startInitData: silent, no UI. A Mini App request for the bot gives a signed URL; its
// tgWebAppData (initData) is exchanged at POST /v1/auth/app/initdata for tokens. Requests are tried in
// turn (requestSimpleWebView, requestWebView from the bot menu, requestMainWebView), the one that
// worked last time first. Only codes, hosts and parameter names are logged, never URLs or values.
// Fallback, start (CONTRACT v1.2 "Login flows -> App"): start -> nonce; this account visibly sends
// "/start app_<nonce>" to the bot; the user presses [Ha, kirish] in the bot; poll every 2.5 s until
// 200/403/410 or expires_in runs out.
// State lives here, not in the screen, so polling goes on while the bot chat is open.
public final class CoreLogin {

    public static final int STATE_IDLE = 0;
    public static final int STATE_STARTING = 1;
    public static final int STATE_WAITING = 2;
    public static final int STATE_DONE = 3;
    public static final int STATE_DENIED = 4;
    public static final int STATE_EXPIRED = 5;
    public static final int STATE_ERROR = 6;
    public static final int STATE_NO_BOT = 7;

    public interface Listener {
        void onCoreLoginChanged();
    }

    private static final long POLL_INTERVAL_MS = 2500;
    private static final long POLL_SLOW_MS = 6000;

    private static final SparseArray<CoreLogin> instances = new SparseArray<>();

    public static CoreLogin getInstance(int account) {
        CoreLogin login = instances.get(account);
        if (login == null) {
            instances.put(account, login = new CoreLogin(account));
        }
        return login;
    }

    private final int account;
    private int state = STATE_IDLE;
    private boolean slowedDown;
    private int generation;
    private long tgUserId;
    private String nonce;
    private long deadline;
    private long botUserId;
    private Listener listener;
    private boolean viaBot;
    private boolean autoTried;
    private String errorDetail;
    private int workingMethod;

    private CoreLogin(int account) {
        this.account = account;
    }

    public int getState() {
        return state;
    }

    public boolean isSlowedDown() {
        return slowedDown;
    }

    public long getBotUserId() {
        return botUserId;
    }

    // True while the state belongs to the bot (nonce) flow, false for the silent initData flow
    public boolean isViaBot() {
        return viaBot;
    }

    // Short error reason for the screen: a Telegram error ("400 BOT_INVALID") or "core HTTP 404"
    public String getErrorDetail() {
        return errorDetail;
    }

    // The silent login runs by itself only once per app run; after that only from a button
    public boolean shouldAutoStart() {
        return (!autoTried && state == STATE_IDLE) || state == STATE_DONE;
    }

    // UI thread. Silent login with the Telegram session: no bot chat, no message, no WebView.
    public void startInitData() {
        if (state == STATE_STARTING || state == STATE_WAITING) {
            return;
        }
        autoTried = true;
        viaBot = false;
        errorDetail = null;
        if (!hasBotUsername()) {
            setState(STATE_NO_BOT);
            return;
        }
        final int gen = ++generation;
        tgUserId = UserConfig.getInstance(account).getClientUserId();
        nonce = null;
        slowedDown = false;
        setState(STATE_STARTING);

        MessagesController.getInstance(account).getUserNameResolver().resolve(BuildConfig.NEXGRAMM_BOT_USERNAME, peerId -> {
            if (gen != generation) {
                return;
            }
            final TLRPC.User bot = peerId != null && peerId > 0 ? MessagesController.getInstance(account).getUser(peerId) : null;
            if (bot == null || !bot.bot) {
                failInitData("BOT_NOT_FOUND");
                return;
            }
            botUserId = bot.id;
            requestInitData(gen, bot, methodOrder(), 0, new StringBuilder());
        });
    }

    private static final String[] METHOD_NAMES = {"requestSimpleWebView", "requestWebView", "requestMainWebView"};
    private static final String PREF_METHOD = "initdata_method";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nexgramm_core", Context.MODE_PRIVATE);
    }

    // Name of the request that gave initData last time, or null
    public static String getWorkingMethodName() {
        final int method = prefs().getInt(PREF_METHOD, -1);
        return method >= 0 && method < METHOD_NAMES.length ? METHOD_NAMES[method] : null;
    }

    // The request that worked last time first, the others after it in the default order
    private static int[] methodOrder() {
        final int preferred = prefs().getInt(PREF_METHOD, 0);
        final int[] order = new int[METHOD_NAMES.length];
        int i = 0;
        order[i++] = preferred >= 0 && preferred < METHOD_NAMES.length ? preferred : 0;
        for (int m = 0; m < METHOD_NAMES.length; m++) {
            if (m != order[0]) {
                order[i++] = m;
            }
        }
        return order;
    }

    private TLObject createRequest(int method, TLRPC.User bot, String url) {
        final MessagesController controller = MessagesController.getInstance(account);
        switch (method) {
            case 1: {
                final TLRPC.TL_messages_requestWebView req = new TLRPC.TL_messages_requestWebView();
                req.peer = controller.getInputPeer(bot.id);
                req.bot = controller.getInputUser(bot);
                req.url = url;
                req.flags |= 2;
                req.from_bot_menu = true;
                req.platform = "android";
                return req;
            }
            case 2: {
                final TLRPC.TL_messages_requestMainWebView req = new TLRPC.TL_messages_requestMainWebView();
                req.peer = controller.getInputPeer(bot.id);
                req.bot = controller.getInputUser(bot);
                req.platform = "android";
                return req;
            }
            default: {
                final TLRPC.TL_messages_requestSimpleWebView req = new TLRPC.TL_messages_requestSimpleWebView();
                req.bot = controller.getInputUser(bot);
                req.url = url;
                req.flags |= 8;
                req.platform = "android";
                return req;
            }
        }
    }

    // Tries order[index]; on a Telegram error or a URL without initData notes why and goes to the next one
    private void requestInitData(int gen, TLRPC.User bot, int[] order, int index, StringBuilder failures) {
        if (index >= order.length) {
            failInitData(failures.toString());
            return;
        }
        final int method = order[index];
        final String url = BuildConfig.CORE_API_URL + CoreInitData.APP_AUTH_PATH;
        ConnectionsManager.getInstance(account).sendRequest(createRequest(method, bot, url), (response, error) -> {
            String initData = null;
            final String failure;
            if (error != null) {
                failure = error.code + " " + error.text;
            } else if (response instanceof TLRPC.TL_webViewResultUrl) {
                final String resultUrl = ((TLRPC.TL_webViewResultUrl) response).url;
                initData = CoreInitData.extract(resultUrl);
                failure = initData == null ? "NO_INIT_DATA " + CoreInitData.describe(url, resultUrl) : null;
            } else {
                failure = "type=" + (response == null ? "null" : response.getClass().getSimpleName());
            }
            if (failure != null) {
                FileLog.d("NexGramm initdata " + METHOD_NAMES[method] + ": " + failure);
            }
            final String finalInitData = initData;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation) {
                    return;
                }
                if (finalInitData != null) {
                    workingMethod = method;
                    exchangeInitData(gen, finalInitData);
                    return;
                }
                if (failures.length() > 0) {
                    failures.append('\n');
                }
                failures.append(METHOD_NAMES[method]).append(": ").append(failure);
                requestInitData(gen, bot, order, index + 1, failures);
            });
        });
    }

    private void exchangeInitData(int gen, String initData) {
        final long userId = tgUserId;
        CoreClient.EXECUTOR.execute(() -> {
            String detail = null;
            int code = 0;
            try {
                if (!CoreClient.saveTokens(userId, CoreInitData.exchangeBlocking(CoreInitData.HTTPS, initData, System.currentTimeMillis()))) {
                    detail = "TOKEN_SAVE_FAILED";
                }
            } catch (CoreInitData.HttpException e) {
                code = e.code;
                detail = "core HTTP " + e.code;
            } catch (Exception e) {
                detail = "core: " + e.getClass().getSimpleName();
            }
            final String finalDetail = detail;
            final int finalCode = code;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation) {
                    return;
                }
                if (finalDetail == null) {
                    prefs().edit().putInt(PREF_METHOD, workingMethod).apply();
                    finish(STATE_DONE);
                } else {
                    slowedDown = finalCode == 429;
                    failInitData(finalDetail);
                }
            });
        });
    }

    private void failInitData(String detail) {
        generation++;
        errorDetail = detail;
        setState(STATE_ERROR);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public static boolean hasBotUsername() {
        return !TextUtils.isEmpty(BuildConfig.NEXGRAMM_BOT_USERNAME);
    }

    // UI thread. Fallback bot login, runs after "Bot orqali kirish" was pressed.
    public void start() {
        if (state == STATE_STARTING || state == STATE_WAITING) {
            return;
        }
        viaBot = true;
        errorDetail = null;
        if (!hasBotUsername()) {
            setState(STATE_NO_BOT);
            return;
        }
        final int gen = ++generation;
        tgUserId = UserConfig.getInstance(account).getClientUserId();
        nonce = null;
        slowedDown = false;
        setState(STATE_STARTING);

        CoreClient.EXECUTOR.execute(() -> {
            String newNonce = null;
            long expiresIn = 0;
            int code = 0;
            try {
                final JSONObject body = new JSONObject().put("device", "NexGramm Android, " + Build.MANUFACTURER + " " + Build.MODEL);
                final CoreClient.Response response = CoreClient.requestBlocking("POST", "/v1/auth/app/start", body, null);
                code = response.code;
                if (code == 200) {
                    final JSONObject json = new JSONObject(response.body);
                    newNonce = json.getString("nonce");
                    expiresIn = json.getLong("expires_in");
                }
            } catch (Exception ignore) {
            }
            final String finalNonce = newNonce;
            final long finalExpiresIn = expiresIn;
            final int finalCode = code;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation) {
                    return;
                }
                if (finalNonce == null) {
                    slowedDown = finalCode == 429;
                    setState(STATE_ERROR);
                    return;
                }
                nonce = finalNonce;
                deadline = System.currentTimeMillis() + finalExpiresIn * 1000L;
                sendStartToBot(gen);
            });
        });
    }

    // Same request Telegram makes for a t.me/<bot>?start=<param> link: the user sees "/start app_<nonce>" in the bot chat
    private void sendStartToBot(int gen) {
        MessagesController.getInstance(account).getUserNameResolver().resolve(BuildConfig.NEXGRAMM_BOT_USERNAME, peerId -> {
            if (gen != generation) {
                return;
            }
            final TLRPC.User bot = peerId != null && peerId > 0 ? MessagesController.getInstance(account).getUser(peerId) : null;
            if (bot == null || !bot.bot) {
                setState(STATE_ERROR);
                return;
            }
            botUserId = bot.id;
            final TLRPC.TL_messages_startBot req = new TLRPC.TL_messages_startBot();
            req.bot = MessagesController.getInstance(account).getInputUser(bot);
            req.peer = MessagesController.getInstance(account).getInputPeer(bot.id);
            req.start_param = "app_" + nonce;
            req.random_id = Utilities.random.nextLong();
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                if (error == null) {
                    MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (gen != generation) {
                        return;
                    }
                    if (error != null) {
                        setState(STATE_ERROR);
                        return;
                    }
                    setState(STATE_WAITING);
                    schedulePoll(gen, POLL_INTERVAL_MS);
                });
            });
        });
    }

    private void schedulePoll(int gen, long delay) {
        AndroidUtilities.runOnUIThread(() -> {
            if (gen != generation) {
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                finish(STATE_EXPIRED);
                return;
            }
            poll(gen);
        }, delay);
    }

    private void poll(int gen) {
        final String pollNonce = nonce;
        final long userId = tgUserId;
        CoreClient.EXECUTOR.execute(() -> {
            int code = 0;
            boolean saved = false;
            try {
                final CoreClient.Response response = CoreClient.requestBlocking("GET", "/v1/auth/app/poll?nonce=" + URLEncoder.encode(pollNonce, "UTF-8"), null, null);
                code = response.code;
                if (code == 200) {
                    saved = CoreClient.saveTokens(userId, CoreTokens.fromResponse(response.body, System.currentTimeMillis()));
                }
            } catch (JSONException e) {
                code = -1;
            } catch (Exception ignore) {
                // network error: try again on the next tick until the nonce expires
            }
            final int finalCode = code;
            final boolean finalSaved = saved;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation) {
                    return;
                }
                if (finalCode == 200) {
                    finish(finalSaved ? STATE_DONE : STATE_ERROR);
                } else if (finalCode == 403) {
                    finish(STATE_DENIED);
                } else if (finalCode == 410) {
                    finish(STATE_EXPIRED);
                } else if (finalCode == -1) {
                    finish(STATE_ERROR);
                } else {
                    // 202 pending, 429 slow down, or a temporary error
                    final boolean slow = finalCode == 429;
                    if (slow != slowedDown) {
                        slowedDown = slow;
                        notifyListener();
                    }
                    schedulePoll(gen, slow ? POLL_SLOW_MS : POLL_INTERVAL_MS);
                }
            });
        });
    }

    private void finish(int newState) {
        generation++;
        nonce = null;
        slowedDown = false;
        setState(newState);
    }

    // Stops a running login (after logout) and forgets the last result; no silent login by itself after this
    public void reset() {
        generation++;
        autoTried = true;
        viaBot = false;
        errorDetail = null;
        nonce = null;
        slowedDown = false;
        setState(STATE_IDLE);
    }

    private void setState(int newState) {
        state = newState;
        notifyListener();
    }

    private void notifyListener() {
        if (listener != null) {
            listener.onCoreLoginChanged();
        }
    }
}
