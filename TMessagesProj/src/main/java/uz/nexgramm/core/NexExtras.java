package uz.nexgramm.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

// NexGramm: small comfort switches (NexGramm features → Comfort)
public final class NexExtras {

    // ask "Call …?" before a call starts (no calls from a stray tap); on by default
    public static boolean confirmCalls() {
        return prefs().getBoolean("confirm_calls", true);
    }

    public static void setConfirmCalls(boolean value) {
        prefs().edit().putBoolean("confirm_calls", value).apply();
    }

    private NexExtras() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nexgramm_extras", Context.MODE_PRIVATE);
    }
}
