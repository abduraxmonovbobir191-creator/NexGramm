package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.CoreLogin;
import uz.nexgramm.core.NexAi;

// NexGramm: shared bits of the NEX AI features — first-use consent, core login before a request,
// error texts, the answer language and the iridescent ✦ orb of the design.
final class NexAiUi {

    private static final String PREF_CONSENT = "nex_ai_consent";

    private NexAiUi() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(Theme.NEXGRAMM_PREFS, Context.MODE_PRIVATE);
    }

    static final int FREE_PER_DAY = 5; // NEX AI requests a day without NexGramm Premium

    // Counts one request for today; false (and the Premium screen) when the free ones are used up
    static boolean takeFreeUse(BaseFragment fragment) {
        final long userId = fragment.getUserConfig().getClientUserId();
        if (uz.nexgramm.core.NexPremium.isActive(userId)) {
            return true;
        }
        final String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date());
        final SharedPreferences p = prefs();
        final int used = today.equals(p.getString("ai_day_" + userId, "")) ? p.getInt("ai_used_" + userId, 0) : 0;
        if (used >= FREE_PER_DAY) {
            uz.nexgramm.core.NexPremium.refresh(userId);
            if (fragment.getParentActivity() != null) {
                android.widget.Toast.makeText(fragment.getParentActivity(),
                    LocaleController.formatString(R.string.NexAiLimitReached, FREE_PER_DAY), android.widget.Toast.LENGTH_LONG).show();
            }
            fragment.presentFragment(new NexPremiumActivity());
            return false;
        }
        p.edit().putString("ai_day_" + userId, today).putInt("ai_used_" + userId, used + 1).apply();
        return true;
    }

    // Asks once what NEX AI receives; runs onAgree right away when already agreed
    static void withConsent(BaseFragment fragment, Runnable onAgree) {
        if (prefs().getBoolean(PREF_CONSENT, false)) {
            onAgree.run();
            return;
        }
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(context)
            .setTitle(LocaleController.getString(R.string.NexAiConsentTitle))
            .setMessage(LocaleController.getString(R.string.NexAiConsentText))
            .setPositiveButton(LocaleController.getString(R.string.NexAiConsentAgree), (d, w) -> {
                prefs().edit().putBoolean(PREF_CONSENT, true).apply();
                onAgree.run();
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create();
        fragment.showDialog(dialog);
    }

    // The core login (silent, with the Telegram session) is needed for NEX AI. Starts it when missing and
    // waits up to 20 s; onReady / onFail on the UI thread.
    static void withCoreLogin(int account, long tgUserId, Runnable onReady, Runnable onFail) {
        if (CoreClient.getTokens(tgUserId) != null) {
            onReady.run();
            return;
        }
        final CoreLogin login = CoreLogin.getInstance(account);
        if (login.getState() != CoreLogin.STATE_STARTING && login.getState() != CoreLogin.STATE_WAITING) {
            login.startInitData();
        }
        waitForLogin(tgUserId, System.currentTimeMillis() + 20_000, onReady, onFail);
    }

    private static void waitForLogin(long tgUserId, long deadline, Runnable onReady, Runnable onFail) {
        AndroidUtilities.runOnUIThread(() -> {
            if (CoreClient.getTokens(tgUserId) != null) {
                onReady.run();
            } else if (System.currentTimeMillis() > deadline) {
                onFail.run();
            } else {
                waitForLogin(tgUserId, deadline, onReady, onFail);
            }
        }, 500);
    }

    static String errorText(int code, String message) {
        switch (code) {
            case NexAi.ERROR_NOT_CONFIGURED:
                return LocaleController.getString(R.string.NexAiNotConfigured);
            case NexAi.ERROR_LOGIN:
                return LocaleController.getString(R.string.NexAiLogin);
            case NexAi.ERROR_LIMIT:
                return LocaleController.getString(R.string.NexAiLimit);
            case NexAi.ERROR_UNAVAILABLE:
                return message != null ? message : LocaleController.getString(R.string.NexAiUnavailable);
            default:
                return LocaleController.getString(R.string.NexAiNetwork);
        }
    }

    // English name of the app language ("Uzbek", "Russian"…), what NEX answers in
    static String language() {
        final LocaleController.LocaleInfo info = LocaleController.getInstance().getCurrentLocaleInfo();
        if (info != null && info.nameEnglish != null && !info.nameEnglish.isEmpty()) {
            return info.nameEnglish;
        }
        return "the language of my message";
    }

    // Iridescent glass sphere with a black ✦, as in the design
    static final class OrbDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint star = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final boolean withStar;

        OrbDrawable(boolean withStar) {
            this.withStar = withStar;
            ring.setStyle(Paint.Style.STROKE);
            star.setColor(0xFF1F1F24);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final float cx = getBounds().exactCenterX();
            final float cy = getBounds().exactCenterY();
            final float r = Math.min(getBounds().width(), getBounds().height()) / 2f;
            if (r <= 0) {
                return;
            }
            paint.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                new int[]{0xFFF8F1EA, 0xFFE4D9F7, 0xFFD5E4F5, 0xFFF3E1EC, 0xFFEDEAF7},
                new float[]{0f, 0.3f, 0.55f, 0.8f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, paint);
            paint.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 0.9f,
                new int[]{0xCCFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, r, paint);
            ring.setStrokeWidth(Math.max(1, r / 40f));
            ring.setColor(0x66FFFFFF);
            canvas.drawCircle(cx, cy, r - ring.getStrokeWidth(), ring);
            if (withStar) {
                drawStar(canvas, path, star, cx, cy, r * 0.32f);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // Four-point ✦ star (concave sides)
    static void drawStar(Canvas canvas, Path path, Paint paint, float cx, float cy, float r) {
        final float k = r * 0.18f;
        path.reset();
        path.moveTo(cx, cy - r);
        path.quadTo(cx + k, cy - k, cx + r, cy);
        path.quadTo(cx + k, cy + k, cx, cy + r);
        path.quadTo(cx - k, cy + k, cx - r, cy);
        path.quadTo(cx - k, cy - k, cx, cy - r);
        path.close();
        canvas.drawPath(path, paint);
    }
}
