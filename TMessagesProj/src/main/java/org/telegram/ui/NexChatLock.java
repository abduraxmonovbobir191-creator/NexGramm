package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
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
        authenticate(activity, onSuccess, null);
    }

    // onFail: a finger / face that was not accepted (the prompt stays open)
    public static void authenticate(FragmentActivity activity, Runnable onSuccess, Runnable onFail) {
        if (activity == null) {
            return;
        }
        try {
            final BiometricPrompt prompt = new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                    onSuccess.run();
                }

                @Override
                public void onAuthenticationFailed() {
                    if (onFail != null) {
                        onFail.run();
                    }
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

    // The cover over a locked chat, the whole screen: a deep night sky of the accent with twinkling ✦ stars,
    // 🔒, a line of text and "Unlock"; ← goes back
    static FrameLayout createCover(Context context, Runnable onUnlockClick, Runnable onBack) {
        final FrameLayout cover = new FrameLayout(context);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        cover.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xFF07070C, uz.nexgramm.core.NexPalette.blend(0xFF0B0B14, accent, 0.18f), uz.nexgramm.core.NexPalette.blend(0xFF0B0B14, accent, 0.42f)}));
        cover.setClickable(true); // nothing behind it can be touched
        cover.addView(new StarsView(context), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        final ImageView back = new ImageView(context);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setImageResource(R.drawable.ic_ab_back);
        back.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN));
        back.setBackground(Theme.createSelectorDrawable(0x33FFFFFF, Theme.RIPPLE_MASK_CIRCLE_20DP));
        back.setOnClickListener(v -> onBack.run());
        cover.addView(back, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.TOP, 8, AndroidUtilities.statusBarHeight / AndroidUtilities.density + 6, 0, 0));
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        final ImageView icon = new ImageView(context);
        icon.setTag(ICON_TAG);
        icon.setImageResource(R.drawable.msg_secret);
        icon.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        column.addView(icon, LayoutHelper.createLinear(56, 56, Gravity.CENTER_HORIZONTAL));
        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(0xFFFFFFFF);
        title.setGravity(Gravity.CENTER);
        title.setText(LocaleController.getString(R.string.NexLockLocked));
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));
        final TextView button = new TextView(context);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(uz.nexgramm.core.NexPalette.onColor(accent));
        button.setGravity(Gravity.CENTER);
        button.setPadding(AndroidUtilities.dp(28), 0, AndroidUtilities.dp(28), 0);
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(24),
            accent, Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        button.setText(LocaleController.getString(R.string.NexLockUnlock));
        button.setOnClickListener(v -> onUnlockClick.run());
        column.addView(button, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 48, Gravity.CENTER_HORIZONTAL, 0, 24, 0, 0));
        column.setTag(COLUMN_TAG);
        cover.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        // the lock drops in with a small spring
        icon.setScaleX(0.4f);
        icon.setScaleY(0.4f);
        icon.setAlpha(0f);
        icon.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(60).setDuration(520).setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
        NexMotion.enter(title, 1);
        NexMotion.enter(button, 2);
        return cover;
    }

    private static final String ICON_TAG = "nex_lock_icon";
    private static final String COLUMN_TAG = "nex_lock_column";

    // Unlocked: the lock opens, then the cover lifts off and fades
    static void reveal(View cover) {
        final View icon = cover.findViewWithTag(ICON_TAG);
        if (icon instanceof ImageView) {
            ((ImageView) icon).setImageResource(R.drawable.menu_unlock);
            NexMotion.pulse(icon);
        }
        cover.setClickable(false);
        cover.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setStartDelay(160).setDuration(340).setInterpolator(NexMotion.EASE)
            .withEndAction(() -> AndroidUtilities.removeFromParent(cover)).start();
    }

    // Stars of a night sky: small ✦ and dots, each twinkling on its own slow beat; a few drift a little
    private static final class StarsView extends View {
        private static final int COUNT = 46;
        private final float[] x = new float[COUNT];
        private final float[] y = new float[COUNT];
        private final float[] size = new float[COUNT];
        private final float[] phase = new float[COUNT];
        private final float[] speed = new float[COUNT];
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final long start = android.os.SystemClock.elapsedRealtime();

        StarsView(Context context) {
            super(context);
            final java.util.Random random = new java.util.Random();
            for (int i = 0; i < COUNT; i++) {
                x[i] = random.nextFloat();
                y[i] = random.nextFloat();
                // a quarter are ✦, the rest dots
                size[i] = i % 4 == 0 ? AndroidUtilities.dp(4 + random.nextFloat() * 5) : AndroidUtilities.dp(0.8f + random.nextFloat() * 1.4f);
                phase[i] = random.nextFloat() * (float) (Math.PI * 2);
                speed[i] = 0.6f + random.nextFloat() * 1.6f;
            }
            paint.setColor(0xFFFFFFFF);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            final float t = (android.os.SystemClock.elapsedRealtime() - start) / 1000f;
            final int w = getWidth();
            final int h = getHeight();
            for (int i = 0; i < COUNT; i++) {
                final float twinkle = 0.5f + 0.5f * (float) Math.sin(t * speed[i] + phase[i]);
                paint.setAlpha((int) (40 + 215 * twinkle * twinkle));
                final float cx = x[i] * w;
                final float cy = ((y[i] - t * 0.004f * speed[i]) % 1f + 1f) % 1f * h; // a slow drift upwards
                if (i % 4 == 0) {
                    NexAiUi.drawStar(canvas, path, paint, cx, cy, size[i] * (0.75f + 0.35f * twinkle));
                } else {
                    canvas.drawCircle(cx, cy, size[i], paint);
                }
            }
            if (isAttachedToWindow()) {
                postInvalidateOnAnimation();
            }
        }
    }

    // Not accepted: the lock shakes "no"
    static void shake(View cover) {
        final View column = cover == null ? null : cover.findViewWithTag(COLUMN_TAG);
        if (column != null) {
            NexMotion.shake(column);
        }
    }
}
