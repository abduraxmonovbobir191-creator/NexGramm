package uz.nexgramm.core;

import android.os.Build;
import android.text.TextUtils;
import android.util.SparseArray;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.net.URLEncoder;

// NexGramm: app login (CONTRACT v1.2 "Login flows -> App") for one Telegram account.
// start -> nonce; this account visibly sends "/start app_<nonce>" to the bot; the user presses
// [Ha, kirish] in the bot; poll every 2.5 s until 200/403/410 or expires_in runs out.
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

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public static boolean hasBotUsername() {
        return !TextUtils.isEmpty(BuildConfig.NEXGRAMM_BOT_USERNAME);
    }

    // UI thread. Runs after the consent screen ("Davom etish") was pressed.
    public void start() {
        if (state == STATE_STARTING || state == STATE_WAITING) {
            return;
        }
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

    // Stops a running login (e.g. after logout) and forgets the last result
    public void reset() {
        generation++;
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
