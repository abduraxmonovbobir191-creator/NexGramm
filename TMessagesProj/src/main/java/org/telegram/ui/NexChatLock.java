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

    // ---- Hidden chats: gone from the chats list, folders, search, the share picker and notifications; they
    // are listed only in "Hidden chats" (behind the phone's own unlock) and always open locked ----

    private static final HashSet<String> hiddenCache = new HashSet<>();
    private static boolean hiddenLoaded;

    private static synchronized Set<String> hidden() {
        if (!hiddenLoaded) {
            hiddenCache.addAll(prefs().getStringSet("hidden", new HashSet<>()));
            hiddenLoaded = true;
        }
        return hiddenCache;
    }

    public static synchronized boolean isHidden(int account, long dialogId) {
        return dialogId != 0 && !hidden().isEmpty() && hidden().contains(key(account, dialogId));
    }

    public static synchronized void setHidden(int account, long dialogId, boolean value) {
        final Set<String> set = hidden();
        if (value) {
            set.add(key(account, dialogId));
        } else {
            set.remove(key(account, dialogId));
        }
        prefs().edit().putStringSet("hidden", new HashSet<>(set)).apply();
    }

    // the hidden chats of an account, newest hidden last
    public static synchronized java.util.ArrayList<Long> hiddenIds(int account) {
        final java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
        final String prefix = account + ":";
        for (String k : hidden()) {
            if (k.startsWith(prefix)) {
                try {
                    ids.add(Long.parseLong(k.substring(prefix.length())));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return ids;
    }

    // a hidden chat is locked too: its preview, notifications and opening are protected
    public static boolean isProtected(int account, long dialogId) {
        return isLocked(account, dialogId) || isHidden(account, dialogId);
    }

    // the list without the hidden chats (the same list when this account hides none)
    public static java.util.ArrayList<org.telegram.tgnet.TLRPC.Dialog> withoutHidden(int account, java.util.ArrayList<org.telegram.tgnet.TLRPC.Dialog> dialogs) {
        if (dialogs == null || dialogs.isEmpty()) {
            return dialogs;
        }
        synchronized (NexChatLock.class) {
            if (hidden().isEmpty() || hiddenIds(account).isEmpty()) {
                return dialogs;
            }
        }
        java.util.ArrayList<org.telegram.tgnet.TLRPC.Dialog> out = null;
        for (int i = 0, n = dialogs.size(); i < n; i++) {
            final org.telegram.tgnet.TLRPC.Dialog d = dialogs.get(i);
            final boolean skip = d != null && isHidden(account, d.id);
            if (skip && out == null) {
                out = new java.util.ArrayList<>(dialogs.subList(0, i));
            } else if (!skip && out != null) {
                out.add(d);
            }
        }
        return out != null ? out : dialogs;
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

    // The cover over a locked chat, the whole screen: pitch black with ✦ stars floating through it, 🔒, a line
    // of text and "Unlock"; ← goes back
    static FrameLayout createCover(Context context, Runnable onUnlockClick, Runnable onBack) {
        return createCover(context, LocaleController.getString(R.string.NexLockLocked), onUnlockClick, onBack);
    }

    static FrameLayout createCover(Context context, CharSequence text, Runnable onUnlockClick, Runnable onBack) {
        final FrameLayout cover = new FrameLayout(context);
        cover.setBackgroundColor(0xFF000000);
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
        title.setText(text);
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 16, 24, 0));
        final TextView button = new TextView(context);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(0xFF000000);
        button.setGravity(Gravity.CENTER);
        button.setPadding(AndroidUtilities.dp(28), 0, AndroidUtilities.dp(28), 0);
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(24), 0xFFFFFFFF, 0xFFD7C4FA));
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

    // A starry sky in black: three depths of ✦ and dots floating slowly in their own directions (the near ones
    // faster, a soft sway on top), each twinkling on its own beat, a few in lilac; now and then a shooting star
    private static final class StarsView extends View {
        private static final int COUNT = 96;
        private static final long SHOOT_MS = 900;
        private final float[] x = new float[COUNT];
        private final float[] y = new float[COUNT];
        private final float[] vx = new float[COUNT];
        private final float[] vy = new float[COUNT];
        private final float[] size = new float[COUNT];
        private final float[] phase = new float[COUNT];
        private final float[] speed = new float[COUNT];
        private final boolean[] spark = new boolean[COUNT];
        private final int[] color = new int[COUNT];
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();
        private final java.util.Random random = new java.util.Random();
        private final long start = android.os.SystemClock.elapsedRealtime();
        private long shootAt;
        private float shootX, shootY, shootDx, shootDy;

        StarsView(Context context) {
            super(context);
            for (int i = 0; i < COUNT; i++) {
                final float depth = i % 3 == 0 ? 1f : i % 3 == 1 ? 0.55f : 0.25f; // near, middle, far
                x[i] = random.nextFloat();
                y[i] = random.nextFloat();
                final double angle = -Math.PI / 2 + (random.nextFloat() - 0.5f) * Math.PI * 1.2; // mostly upwards
                final float v = (0.006f + random.nextFloat() * 0.012f) * depth; // screens per second
                vx[i] = (float) Math.cos(angle) * v;
                vy[i] = (float) Math.sin(angle) * v;
                spark[i] = depth == 1f && i % 2 == 0;
                size[i] = spark[i] ? AndroidUtilities.dp(4 + random.nextFloat() * 5) : AndroidUtilities.dp((0.6f + random.nextFloat() * 1.2f) * (0.6f + depth * 0.6f));
                phase[i] = random.nextFloat() * (float) (Math.PI * 2);
                speed[i] = 0.6f + random.nextFloat() * 1.8f;
                color[i] = i % 7 == 0 ? 0xD7C4FA : 0xFFFFFF;
            }
            shootAt = start + 1800 + random.nextInt(2500);
        }

        private static float wrap(float v) {
            return (v % 1f + 1f) % 1f;
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            final long now = android.os.SystemClock.elapsedRealtime();
            final float t = (now - start) / 1000f;
            final int w = getWidth();
            final int h = getHeight();
            paint.setStyle(android.graphics.Paint.Style.FILL);
            for (int i = 0; i < COUNT; i++) {
                final float twinkle = 0.5f + 0.5f * (float) Math.sin(t * speed[i] + phase[i]);
                paint.setColor(0xFF000000 | color[i]);
                paint.setAlpha((int) (35 + 220 * twinkle * twinkle));
                final float sway = 0.012f * (float) Math.sin(t * 0.4f * speed[i] + phase[i]);
                final float cx = wrap(x[i] + vx[i] * t + sway) * w;
                final float cy = wrap(y[i] + vy[i] * t) * h;
                if (spark[i]) {
                    NexAiUi.drawStar(canvas, path, paint, cx, cy, size[i] * (0.7f + 0.4f * twinkle));
                } else {
                    canvas.drawCircle(cx, cy, size[i], paint);
                }
            }
            drawShootingStar(canvas, now, w, h);
            if (isAttachedToWindow()) {
                postInvalidateOnAnimation();
            }
        }

        // a bright head with a fading tail, crossing part of the sky
        private void drawShootingStar(android.graphics.Canvas canvas, long now, int w, int h) {
            if (now < shootAt) {
                return;
            }
            if (shootDx == 0 && shootDy == 0) {
                shootX = w * (0.15f + random.nextFloat() * 0.7f);
                shootY = h * (0.05f + random.nextFloat() * 0.4f);
                final float dir = random.nextBoolean() ? 1f : -1f;
                shootDx = dir * w * (0.35f + random.nextFloat() * 0.2f);
                shootDy = h * (0.12f + random.nextFloat() * 0.1f);
            }
            final float p = (now - shootAt) / (float) SHOOT_MS;
            if (p >= 1f) {
                shootDx = shootDy = 0;
                shootAt = now + 3000 + random.nextInt(4000);
                return;
            }
            final float eased = 1f - (1f - p) * (1f - p);
            final float headX = shootX + shootDx * eased;
            final float headY = shootY + shootDy * eased;
            final float fade = p < 0.8f ? 1f : (1f - p) / 0.2f;
            paint.setColor(0xFFFFFFFF);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            final int parts = 10;
            for (int k = 0; k < parts; k++) {
                final float a = k / (float) parts;
                final float b = (k + 1) / (float) parts;
                final float tail = 0.28f; // of the whole path
                paint.setStrokeWidth(AndroidUtilities.dp(2.2f) * (1f - a));
                paint.setAlpha((int) (230 * fade * (1f - a)));
                canvas.drawLine(headX - shootDx * tail * a, headY - shootDy * tail * a,
                    headX - shootDx * tail * b, headY - shootDy * tail * b, paint);
            }
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setAlpha((int) (255 * fade));
            canvas.drawCircle(headX, headY, AndroidUtilities.dp(2f), paint);
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
