package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.util.HashSet;
import java.util.Set;

// NexGramm: hidden accounts (Premium). The user hides an account by hand (long press on it in the account
// switcher, or Security center); it stays logged in and keeps receiving messages, but is gone from the
// account switcher, Settings, the app badge and account pickers. It shows again for this visit only after
// the phone's own unlock (long press on "Add account", or Security center); half a minute away from the app
// hides it again and the app leaves it for a visible account. Its notifications are either masked
// ("NexGramm: new message", no name, no text, opening nothing of it) or not shown at all — the user picks.
// Accounts are kept by their Telegram user id, so logging other accounts in or out never shifts them.
public final class NexAccounts {

    public static final int NOTIFY_MASKED = 0;
    public static final int NOTIFY_SILENT = 1;

    private static final long AWAY_MS = 30_000;

    private static volatile long[] hiddenUsers; // sorted; null = not loaded yet
    private static volatile boolean revealed;
    private static volatile int notifyMode = -1;
    private static long pausedAt;

    private NexAccounts() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nexgramm_accounts", Context.MODE_PRIVATE);
    }

    private static long[] hidden() {
        long[] ids = hiddenUsers;
        if (ids == null) {
            synchronized (NexAccounts.class) {
                if (hiddenUsers == null) {
                    final Set<String> set = prefs().getStringSet("hidden", new HashSet<>());
                    final long[] out = new long[set.size()];
                    int n = 0;
                    for (String s : set) {
                        try {
                            out[n++] = Long.parseLong(s);
                        } catch (NumberFormatException ignore) {
                        }
                    }
                    final long[] sorted = java.util.Arrays.copyOf(out, n);
                    java.util.Arrays.sort(sorted);
                    hiddenUsers = sorted;
                }
                ids = hiddenUsers;
            }
        }
        return ids;
    }

    public static boolean hasHidden() {
        return hidden().length > 0;
    }

    // hidden by the user (whether or not it is shown for this visit)
    public static boolean isHidden(int account) {
        final long[] ids = hidden();
        if (ids.length == 0 || !UserConfig.isValidAccount(account)) {
            return false;
        }
        final long userId = UserConfig.getInstance(account).getClientUserId();
        return userId != 0 && java.util.Arrays.binarySearch(ids, userId) >= 0;
    }

    // listed in the account switcher, Settings and account pickers now
    public static boolean isListed(int account) {
        return revealed || !isHidden(account);
    }

    public static synchronized void setHidden(int account, boolean hide) {
        final long userId = UserConfig.getInstance(account).getClientUserId();
        if (userId == 0) {
            return;
        }
        final HashSet<String> set = new HashSet<>(prefs().getStringSet("hidden", new HashSet<>()));
        if (hide) {
            set.add(Long.toString(userId));
        } else {
            set.remove(Long.toString(userId));
        }
        prefs().edit().putStringSet("hidden", set).apply();
        hiddenUsers = null;
        NotificationsController.getInstance(account).updateBadge();
    }

    public static boolean isRevealed() {
        return revealed;
    }

    public static void setRevealed(boolean value) {
        revealed = value;
    }

    public static int notifyMode() {
        if (notifyMode < 0) {
            notifyMode = prefs().getInt("notify", NOTIFY_MASKED);
        }
        return notifyMode;
    }

    public static void setNotifyMode(int mode) {
        notifyMode = mode;
        prefs().edit().putInt("notify", mode).apply();
    }

    // a notification of this account must not tell who wrote or what
    public static boolean masked(int account) {
        return isHidden(account);
    }

    // this account shows no notifications at all
    public static boolean silent(int account) {
        return notifyMode() == NOTIFY_SILENT && isHidden(account);
    }

    public static int listedCount() {
        int n = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && isListed(a)) {
                n++;
            }
        }
        return n;
    }

    public static int visibleCount() {
        int n = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && !isHidden(a)) {
                n++;
            }
        }
        return n;
    }

    // the account the app goes to instead of a hidden one (-1 if every account is hidden)
    public static int firstVisible() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated() && !isHidden(a)) {
                return a;
            }
        }
        return -1;
    }

    public static void onAppPaused() {
        pausedAt = SystemClock.elapsedRealtime();
    }

    // back in the app: after half a minute away the hidden accounts are hidden again; returns the account to
    // switch to when the current one is hidden, or -1
    public static int onAppResumed(int currentAccount) {
        if (pausedAt == 0 || SystemClock.elapsedRealtime() - pausedAt < AWAY_MS) {
            return -1;
        }
        pausedAt = 0;
        revealed = false;
        if (!isHidden(currentAccount)) {
            return -1;
        }
        final int visible = firstVisible();
        return visible == currentAccount ? -1 : visible;
    }
}
