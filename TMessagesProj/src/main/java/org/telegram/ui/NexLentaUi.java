package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import uz.nexgramm.core.PixelfedFormat;

// NexGramm: shared look of the Lenta screens (design screenshots: serif display titles, white/black pills,
// frosted panels over photos, pastel initials avatars), light and dark
public final class NexLentaUi {

    static final int PILL_PRIMARY = 0;   // black (white in dark mode)
    static final int PILL_CARD = 1;      // white card pill
    static final int PILL_SECONDARY = 2; // lilac

    // Pastel avatar backgrounds of the design (initials in dark text)
    private static final int[] PASTELS = {0xFFEBDDD8, 0xFFE8E2D3, 0xFFD6DCE8, 0xFFD8E6DD, 0xFFE4DCF1, 0xFFD3E2EF, 0xFFF0DCE3};

    private static Typeface serif;

    private NexLentaUi() {
    }

    // Display serif of the design (Instrument Serif, OFL, assets/fonts/nex_serif.ttf); system serif if missing
    public static Typeface serif() {
        if (serif == null) {
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    // Instrument Serif has Latin only: Cyrillic etc. fall back to the system serif, not to sans
                    final Font font = new Font.Builder(ApplicationLoader.applicationContext.getAssets(), "fonts/nex_serif.ttf").build();
                    serif = new Typeface.CustomFallbackBuilder(new FontFamily.Builder(font).build())
                        .setSystemFallback("serif")
                        .build();
                } else {
                    serif = Typeface.createFromAsset(ApplicationLoader.applicationContext.getAssets(), "fonts/nex_serif.ttf");
                }
            } catch (Throwable e) {
                serif = Typeface.SERIF;
            }
        }
        return serif;
    }

    static void setupActionBar(BaseFragment fragment, ActionBar actionBar, CharSequence title) {
        NexLentaColors.apply();
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setBackgroundColor(NexLentaColors.BACKGROUND);
        actionBar.setItemsColor(NexLentaColors.TEXT, false);
        actionBar.setItemsBackgroundColor(Theme.multAlpha(NexLentaColors.TEXT, 0.1f), false);
        actionBar.setTitleColor(NexLentaColors.TEXT);
        actionBar.setCastShadows(false);
        actionBar.setTitle(title);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    fragment.finishFragment();
                }
            }
        });
    }

    static Drawable roundRect(int color, int radiusDp) {
        final GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(AndroidUtilities.dp(radiusDp));
        return d;
    }

    // Frosted panel over a photo: translucent fill with a thin light border
    static Drawable glass(int radiusDp) {
        final GradientDrawable d = new GradientDrawable();
        d.setColor(NexLentaColors.GLASS);
        d.setCornerRadius(AndroidUtilities.dp(radiusDp));
        d.setStroke(Math.max(1, AndroidUtilities.dp(1)), NexLentaColors.dark ? 0x33FFFFFF : 0x99FFFFFF);
        return d;
    }

    static TextView pillButton(Context context, CharSequence text, boolean primary) {
        return pillButton(context, text, primary ? PILL_PRIMARY : PILL_SECONDARY);
    }

    static TextView pillButton(Context context, CharSequence text, int style) {
        final TextView button = new TextView(context);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        button.setTypeface(AndroidUtilities.bold());
        button.setPadding(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(18), 0);
        button.setSingleLine(true);
        stylePill(button, style);
        button.setText(text);
        // soft iOS-like press: shrinks a little, springs back
        org.telegram.ui.Components.ScaleStateListAnimator.apply(button, 0.04f, 1.6f);
        return button;
    }

    static void stylePill(TextView button, int style) {
        final int bg = style == PILL_PRIMARY ? NexLentaColors.ACCENT : style == PILL_CARD ? NexLentaColors.CARD : NexLentaColors.SECONDARY;
        final int fg = style == PILL_PRIMARY ? NexLentaColors.ON_ACCENT : style == PILL_CARD ? NexLentaColors.TEXT : NexLentaColors.ON_SECONDARY;
        button.setTextColor(fg);
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(28), bg, Theme.multAlpha(bg, 0.8f)));
        for (Drawable d : button.getCompoundDrawablesRelative()) {
            if (d != null) {
                d.setColorFilter(new PorterDuffColorFilter(fg, PorterDuff.Mode.SRC_IN));
            }
        }
    }

    // Round icon button: PILL_PRIMARY (black), PILL_CARD (white) or a frosted one over photos (glass = true)
    static ImageView circleButton(Context context, int iconRes, int style, boolean glass) {
        final ImageView button = new ImageView(context);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageResource(iconRes);
        final int bg = style == PILL_PRIMARY ? NexLentaColors.ACCENT : NexLentaColors.CARD;
        final int fg = style == PILL_PRIMARY ? NexLentaColors.ON_ACCENT : NexLentaColors.TEXT;
        button.setColorFilter(new PorterDuffColorFilter(fg, PorterDuff.Mode.SRC_IN));
        if (glass) {
            final GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(NexLentaColors.GLASS);
            d.setStroke(Math.max(1, AndroidUtilities.dp(1)), NexLentaColors.dark ? 0x33FFFFFF : 0x99FFFFFF);
            button.setBackground(d);
        } else {
            button.setBackground(Theme.createSimpleSelectorCircleDrawable(AndroidUtilities.dp(56), bg, Theme.multAlpha(bg, 0.8f)));
        }
        org.telegram.ui.Components.ScaleStateListAnimator.apply(button, 0.08f, 1.6f);
        return button;
    }

    static TextView serifTitle(Context context, float sizeSp) {
        final TextView view = new TextView(context);
        view.setTypeface(serif());
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeSp);
        view.setTextColor(NexLentaColors.TEXT);
        view.setIncludeFontPadding(false);
        return view;
    }

    // "2 soat", "5 daqiqa", "hozir", or a short date
    static String timeAgo(long createdAt) {
        final int[] ago = PixelfedFormat.ago(System.currentTimeMillis(), createdAt);
        switch (ago[0]) {
            case PixelfedFormat.AGO_NOW:
                return LocaleController.getString(R.string.NexTimeNow);
            case PixelfedFormat.AGO_MINUTES:
                return LocaleController.formatString(R.string.NexTimeMinutes, ago[1]);
            case PixelfedFormat.AGO_HOURS:
                return LocaleController.formatString(R.string.NexTimeHours, ago[1]);
            case PixelfedFormat.AGO_DAYS:
                return LocaleController.formatString(R.string.NexTimeDays, ago[1]);
            case PixelfedFormat.AGO_WEEKS:
                return LocaleController.formatString(R.string.NexTimeWeeks, ago[1]);
            default:
                return createdAt > 0 ? LocaleController.formatDateChat(createdAt / 1000) : "";
        }
    }

    // Keeps the view's bottom above the navigation bar and the keyboard
    static void padForKeyboard(View view) {
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            final Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), Math.max(ime.bottom, bars.bottom));
            return insets;
        });
    }

    // Pastel circle with the initials of a name (placeholder until the photo is loaded, or when there is none)
    static Drawable initialsAvatar(String name, boolean serifLetters) {
        return new InitialsDrawable(PixelfedFormat.initials(name), PASTELS[Math.abs(name == null ? 0 : name.hashCode()) % PASTELS.length], serifLetters);
    }

    private static final class InitialsDrawable extends Drawable {
        private final String text;
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean serifLetters;
        private final Rect bounds = new Rect();

        InitialsDrawable(String text, int color, boolean serifLetters) {
            this.text = text;
            this.serifLetters = serifLetters;
            bgPaint.setColor(color);
            textPaint.setColor(0xFF2A2A30);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(serifLetters ? serif() : AndroidUtilities.bold());
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            final Rect b = getBounds();
            final float size = Math.min(b.width(), b.height());
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), size / 2f, bgPaint);
            if (text.isEmpty()) {
                return;
            }
            textPaint.setTextSize(size * (serifLetters ? 0.46f : 0.34f));
            textPaint.getTextBounds(text, 0, text.length(), bounds);
            canvas.drawText(text, b.exactCenterX(), b.exactCenterY() - bounds.exactCenterY(), textPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            bgPaint.setAlpha(alpha);
            textPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
