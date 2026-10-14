package uz.nexgramm.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;

import java.io.IOException;
import java.util.ArrayList;

// NexGramm Premium (CONTRACT "Premium", GET /v1/premium). The status is kept per account in the app's
// private preferences so locked features open at once; it is re-read on start / resume. Paid by card in
// the NexGramm bot (/start premium_<code>), confirmed by the owner. A feature checks isActive().
public final class NexPremium {

    public static final class Plan {
        public final String code;
        public final int days;
        public final String price;
        public final boolean onSale; // method "card"

        Plan(String code, int days, String price, boolean onSale) {
            this.code = code;
            this.days = days;
            this.price = price;
            this.onSale = onSale;
        }
    }

    public static final class Status {
        public final boolean active;
        public final long untilMs; // 0 = none
        public final String botUsername;
        public final ArrayList<Plan> plans;

        Status(boolean active, long untilMs, String botUsername, ArrayList<Plan> plans) {
            this.active = active;
            this.untilMs = untilMs;
            this.botUsername = botUsername;
            this.plans = plans;
        }

        public boolean activeAt(long nowMs) {
            return active && untilMs > nowMs;
        }
    }

    private NexPremium() {
    }

    static Status parse(String body) throws JSONException {
        final JSONObject json = new JSONObject(body);
        final ArrayList<Plan> plans = new ArrayList<>();
        final JSONArray array = json.optJSONArray("plans");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                final JSONObject p = array.optJSONObject(i);
                if (p != null && !p.optString("code").isEmpty()) {
                    plans.add(new Plan(p.optString("code"), p.optInt("days"), p.optString("price"), "card".equals(p.optString("method"))));
                }
            }
        }
        final String until = json.isNull("until") ? "" : json.optString("until", "");
        return new Status(json.optBoolean("active", false), until.isEmpty() ? 0 : PixelfedStatus.parseTime(until),
            json.optString("bot_username", ""), plans);
    }

    // The plans shown before the server answered (prices as on the server by default)
    public static Status fallback() {
        final ArrayList<Plan> plans = new ArrayList<>();
        plans.add(new Plan("2w", 14, "1 $", true));
        plans.add(new Plan("1m", 30, "4 $", true));
        plans.add(new Plan("3m", 91, "11 $", true));
        return new Status(false, 0, "", plans);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nexgramm_premium", Context.MODE_PRIVATE);
    }

    public static Status cached(long tgUserId) {
        final String body = prefs().getString("s_" + tgUserId, null);
        if (body != null) {
            try {
                return parse(body);
            } catch (JSONException ignore) {
            }
        }
        return null;
    }

    public static boolean isActive(long tgUserId) {
        final Status s = cached(tgUserId);
        return s != null && s.activeAt(System.currentTimeMillis());
    }

    // GET /v1/premium; the answer is cached. Worker thread.
    public static Status loadBlocking(long tgUserId) throws IOException, CoreClient.NotLoggedInException {
        final CoreClient.Response response = CoreClient.authedBlocking(tgUserId, "GET", "/v1/premium", null);
        if (response.code != 200) {
            throw new IOException("premium " + response.code);
        }
        try {
            final Status status = parse(response.body);
            prefs().edit().putString("s_" + tgUserId, response.body).apply();
            return status;
        } catch (JSONException e) {
            throw new IOException("bad premium");
        }
    }

    private static final java.util.HashMap<Long, Long> lastRefresh = new java.util.HashMap<>();

    // Re-reads the status in the background, at most every 10 minutes (quietly: no login, no network →
    // the cached one stays)
    public static void refresh(long tgUserId) {
        if (CoreClient.getTokens(tgUserId) == null) {
            return;
        }
        synchronized (lastRefresh) {
            final Long last = lastRefresh.get(tgUserId);
            final long now = System.currentTimeMillis();
            if (last != null && now - last < 10 * 60_000) {
                return;
            }
            lastRefresh.put(tgUserId, now);
        }
        CoreClient.EXECUTOR.execute(() -> {
            try {
                loadBlocking(tgUserId);
            } catch (Exception ignore) {
            }
        });
    }
}
