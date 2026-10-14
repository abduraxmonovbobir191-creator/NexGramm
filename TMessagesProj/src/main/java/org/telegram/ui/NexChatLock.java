package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.HashSet;
import java.util.Set;

// NexGramm: chat lock. A locked chat opens only after the phone's own unlock (fingerprint / face, or the
// screen PIN / pattern / password, through androidx BiometricPrompt); its preview in the chats list is
// hidden. Every opening asks again. Locks are kept per account in the app's private preferences.
public final class NexChatLock {

    private static final int AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

    private NexChatLock() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nexgramm_locks", Context.MODE_PRIVATE);
    }

    private static final HashSet<String> cache = new HashSet<>();
    private static boolean loaded;

    private static synchronized Set<String> locks() {
        if (!loaded) {
            cache.addAll(prefs().getStringSet("locked", new HashSet<>()));
            loaded = true;
        }
        return cache;
    }

    private static String key(int account, long dialogId) {
        return account + ":" + dialogId;
    }

    public static synchronized boolean isLocked(int account, long dialogId) {
        return dialogId != 0 && locks().contains(key(account, dialogId));
    }

    public static synchronized void setLocked(int account, long dialogId, boolean locked) {
        final Set<String> set = locks();
        if (locked) {
            set.add(key(account, dialogId));
        } else {
            set.remove(key(account, dialogId));
        }
        prefs().edit().putStringSet("locked", new HashSet<>(set)).apply();
    }

    // false when the phone has no screen lock at all (then nothing could protect the chat)
    public static boolean canLock(Context context) {
        try {
            return BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Exception e) {
            return false;
        }
    }

    public static void authenticate(FragmentActivity activity, Runnable onSuccess) {
        if (activity == null) {
            return;
        }
        try {
            final BiometricPrompt prompt = new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    onSuccess.run();
                }
            });
            prompt.authenticate(new BiometricPrompt.PromptInfo.Builder()
                .setTitle(LocaleController.getString(R.string.NexLockUnlockTitle))
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // The cover over a locked chat: 🔒, a line of text and "Unlock"
    static FrameLayout createCover(Context context, Runnable onUnlockClick) {
        final FrameLayout cover = new FrameLayout(context);
        cover.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        cover.setClickable(true); // nothing behind it can be touched
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        final ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.msg_secret);
        icon.setColorFilter(new android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), android.graphics.PorterDuff.Mode.SRC_IN));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        column.addView(icon, LayoutHelper.createLinear(56, 56, Gravity.CENTER_HORIZONTAL));
        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(Gravity.CENTER);
        title.setText(LocaleController.getString(R.string.NexLockLocked));
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));
        final TextView button = new TextView(context);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        button.setGravity(Gravity.CENTER);
        button.setPadding(AndroidUtilities.dp(28), 0, AndroidUtilities.dp(28), 0);
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(24),
            Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        button.setText(LocaleController.getString(R.string.NexLockUnlock));
        button.setOnClickListener(v -> onUnlockClick.run());
        column.addView(button, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 48, Gravity.CENTER_HORIZONTAL, 0, 24, 0, 0));
        cover.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        return cover;
    }
}
