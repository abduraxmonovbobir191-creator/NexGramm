package uz.nexgramm.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;

import java.io.IOException;
import java.util.ArrayList;

// NexGramm ✦ Nex wallet (CONTRACT "Nex"): GET /v1/nex, POST /v1/nex/spend, POST /v1/nex/gift. Nex are bought
// by card in the NexGramm bot (/start nex) and spent here: a streak restore / freeze, Premium, presents to
// friends and rewards on Lenta posts. The last wallet is kept per account so the balance shows at once.
public final class NexWallet {

    public static final class Package {
        public final int amount;
        public final long price; // so'm

        Package(int amount, long price) {
            this.amount = amount;
            this.price = price;
        }
    }

    public static final class Move {
        public final int delta;
        public final String kind;
        public final String note;
        public final long atMs;

        Move(int delta, String kind, String note, long atMs) {
            this.delta = delta;
            this.kind = kind;
            this.note = note;
            this.atMs = atMs;
        }
    }

    public static final class Wallet {
        public final long balance;
        public final String botUsername;
        public final ArrayList<Package> packages;
        public final int restore;
        public final int freezeDay;
        public final int premium2w, premium1m, premium3m;
        public final ArrayList<Move> history;

        Wallet(long balance, String botUsername, ArrayList<Package> packages, int restore, int freezeDay,
               int premium2w, int premium1m, int premium3m, ArrayList<Move> history) {
            this.balance = balance;
            this.botUsername = botUsername;
            this.packages = packages;
            this.restore = restore;
            this.freezeDay = freezeDay;
            this.premium2w = premium2w;
            this.premium1m = premium1m;
            this.premium3m = premium3m;
            this.history = history;
        }

        public int premium(String plan) {
            return "2w".equals(plan) ? premium2w : "1m".equals(plan) ? premium1m : premium3m;
        }
    }

    // the answer of a spend / gift: ok with the new balance, or the server's error code
    public static final class Result {
        public final boolean ok;
        public final String error; // not_enough, receiver_not_found, self_gift, bad_spend, network
        public final long balance;

        Result(boolean ok, String error, long balance) {
            this.ok = ok;
            this.error = error;
            this.balance = balance;
        }
    }

    private NexWallet() {
    }

    static Wallet parse(String body) throws JSONException {
        final JSONObject json = new JSONObject(body);
        final ArrayList<Package> packages = new ArrayList<>();
        final JSONArray p = json.optJSONArray("packages");
        if (p != null) {
            for (int i = 0; i < p.length(); i++) {
                final JSONObject o = p.optJSONObject(i);
                if (o != null && o.optInt("amount") > 0) {
                    packages.add(new Package(o.optInt("amount"), o.optLong("price")));
                }
            }
        }
        final JSONObject prices = json.optJSONObject("prices");
        final JSONObject premium = prices != null ? prices.optJSONObject("premium") : null;
        final ArrayList<Move> history = new ArrayList<>();
        final JSONArray h = json.optJSONArray("history");
        if (h != null) {
            for (int i = 0; i < h.length(); i++) {
                final JSONObject o = h.optJSONObject(i);
                if (o != null) {
                    final String at = o.optString("at", "");
                    history.add(new Move(o.optInt("delta"), o.optString("kind"), o.isNull("note") ? null : o.optString("note", null),
                        at.isEmpty() ? 0 : PixelfedStatus.parseTime(at)));
                }
            }
        }
        return new Wallet(json.optLong("balance"), json.optString("bot_username", ""), packages,
            prices != null ? prices.optInt("streak_restore", 50) : 50,
            prices != null ? prices.optInt("streak_freeze_day", 15) : 15,
            premium != null ? premium.optInt("2w", 120) : 120,
            premium != null ? premium.optInt("1m", 200) : 200,
            premium != null ? premium.optInt("3m", 500) : 500,
            history);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nexgramm_wallet", Context.MODE_PRIVATE);
    }

    public static Wallet cached(long tgUserId) {
        final String body = prefs().getString("w_" + tgUserId, null);
        if (body != null) {
            try {
                return parse(body);
            } catch (JSONException ignore) {
            }
        }
        return null;
    }

    // GET /v1/nex; the answer is cached. Worker thread.
    public static Wallet loadBlocking(long tgUserId) throws IOException, CoreClient.NotLoggedInException {
        final CoreClient.Response response = CoreClient.authedBlocking(tgUserId, "GET", "/v1/nex", null);
        if (response.code != 200) {
            throw new IOException("nex " + response.code);
        }
        try {
            final Wallet wallet = parse(response.body);
            prefs().edit().putString("w_" + tgUserId, response.body).apply();
            return wallet;
        } catch (JSONException e) {
            throw new IOException("bad nex");
        }
    }

    // POST /v1/nex/spend: kind streak_restore | streak_freeze (days) | premium (plan). Worker thread.
    public static Result spendBlocking(long tgUserId, String kind, int days, String plan) {
        try {
            final JSONObject body = new JSONObject().put("kind", kind);
            if (days > 0) {
                body.put("days", days);
            }
            if (plan != null) {
                body.put("plan", plan);
            }
            return result(CoreClient.authedBlocking(tgUserId, "POST", "/v1/nex/spend", body));
        } catch (Exception e) {
            return new Result(false, "network", 0);
        }
    }

    // POST /v1/nex/gift: to a Telegram user (toTgId) or a Lenta author (toLenta); kind gift | tip. Worker thread.
    public static Result giftBlocking(long tgUserId, int amount, String kind, long toTgId, String toLenta, String note) {
        try {
            final JSONObject body = new JSONObject().put("amount", amount).put("kind", kind);
            if (toLenta != null) {
                body.put("to_lenta", toLenta);
            } else {
                body.put("to_tg_id", toTgId);
            }
            if (note != null && !note.trim().isEmpty()) {
                body.put("note", note.trim());
            }
            return result(CoreClient.authedBlocking(tgUserId, "POST", "/v1/nex/gift", body));
        } catch (Exception e) {
            return new Result(false, "network", 0);
        }
    }

    static Result result(CoreClient.Response response) {
        try {
            final JSONObject json = new JSONObject(response.body == null ? "{}" : response.body);
            if (response.code == 200) {
                return new Result(true, null, json.optLong("balance"));
            }
            final JSONObject error = json.optJSONObject("error");
            return new Result(false, error != null ? error.optString("code", "error") : "error", 0);
        } catch (JSONException e) {
            return new Result(false, "error", 0);
        }
    }
}
