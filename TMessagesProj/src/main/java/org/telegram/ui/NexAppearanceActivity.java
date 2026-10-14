package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import uz.nexgramm.core.NexPalette;

// NexGramm: "Ko'rinish" — light / dark / system mode and the app accent color (like ChatGPT's color
// setting). A tap applies it to the whole app at once with a circular reveal from the tapped control.
public class NexAppearanceActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    static final int MODE_SYSTEM = 0;
    static final int MODE_LIGHT = 1;
    static final int MODE_DARK = 2;

    // Picker labels, same order as NexPalette.ACCENTS
    private static final int[] ACCENT_NAMES = {
        R.string.NexAccentStandard, R.string.NexAccentBlue, R.string.NexAccentSky, R.string.NexAccentGreen,
        R.string.NexAccentYellow, R.string.NexAccentOrange, R.string.NexAccentPink, R.string.NexAccentPurple,
        R.string.NexAccentLavender
    };

    private static final int COLUMNS = 5;

    private LinearLayout content;
    // the screens below (tabs: profile, Lenta …) keep their colors until rebuilt after a theme switch
    private boolean rebuildOthers;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme && content != null) {
            build();
            if (rebuildOthers) {
                rebuildOthers = false;
                // after the circular reveal (400 ms)
                AndroidUtilities.runOnUIThread(() -> {
                    if (getParentLayout() != null) {
                        getParentLayout().rebuildAllFragmentViews(false, false);
                    }
                }, 500);
            }
        }
    }

    @Override
    public boolean isLightStatusBar() {
        return !Theme.isCurrentThemeDark();
    }

    @Override
    public View createView(Context context) {
        NexLentaUi.setupActionBar(this, actionBar, null);
        final ScrollView scrollView = new NexMotion.BounceScrollView(context);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), AndroidUtilities.dp(32));
        scrollView.addView(content, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        fragmentView = scrollView;
        build();
        return fragmentView;
    }

    // Current mode as shown in the picker
    static int currentMode() {
        if (Theme.selectedAutoNightType == Theme.AUTO_NIGHT_TYPE_SYSTEM) {
            return MODE_SYSTEM;
        }
        return Theme.isCurrentThemeDark() ? MODE_DARK : MODE_LIGHT;
    }

    static String modeName(int mode) {
        return LocaleController.getString(mode == MODE_SYSTEM ? R.string.NexAppearanceModeSystem
            : mode == MODE_DARK ? R.string.NexAppearanceModeDark : R.string.NexAppearanceModeLight);
    }

    static String accentName(String accentId) {
        for (int i = 0; i < NexPalette.ACCENTS.length; i++) {
            if (NexPalette.ACCENTS[i].id.equals(accentId)) {
                return LocaleController.getString(ACCENT_NAMES[i]);
            }
        }
        return LocaleController.getString(ACCENT_NAMES[0]);
    }

    private void build() {
        NexLentaColors.apply();
        NexLentaUi.setupActionBar(this, actionBar, null);
        final Context context = getContext();
        fragmentView.setBackgroundColor(NexLentaColors.BACKGROUND);
        content.removeAllViews();

        final TextView title = NexLentaUi.serifTitle(context, 46);
        title.setText(LocaleController.getString(R.string.NexAppearanceTitle));
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 4, 0, 18));

        content.addView(createPreview(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        content.addView(header(context, R.string.NexAppearanceMode), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 6, 24, 6, 10));
        content.addView(createModeSwitch(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));

        content.addView(header(context, R.string.NexAppearanceAccent), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 6, 24, 6, 10));
        content.addView(createAccentGrid(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        content.addView(header(context, R.string.NexAppearanceIntensity), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 6, 24, 6, 10));
        content.addView(createIntensity(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(NexLentaColors.SECONDARY_TEXT);
        hint.setGravity(Gravity.CENTER);
        hint.setText(LocaleController.getString(R.string.NexAppearanceHint));
        content.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 18, 12, 0));
    }

    private TextView header(Context context, int textRes) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(NexLentaColors.TEXT);
        view.setText(LocaleController.getString(textRes));
        return view;
    }

    // A small chat with the current colors: incoming bubble, outgoing bubble, a button and a link
    private View createPreview(Context context) {
        final NexPalette palette = Theme.getNexgrammPalette();
        final int bubble = palette != null ? palette.bubble : NexLentaColors.ACCENT;
        final int onBubble = palette != null ? palette.onBubble : NexLentaColors.ON_ACCENT;

        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 28));
        card.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));

        final TextView in = bubbleText(context, R.string.NexAppearancePreviewIn, NexLentaColors.TEXT);
        in.setBackground(NexLentaUi.roundRect(NexLentaColors.BACKGROUND, 20));
        card.addView(in, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.START));

        final TextView out = bubbleText(context, R.string.NexAppearancePreviewOut, onBubble);
        out.setBackground(NexLentaUi.roundRect(bubble, 20));
        card.addView(out, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.END, 0, 8, 0, 0));

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final TextView button = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexAppearancePreviewButton), NexLentaUi.PILL_PRIMARY);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        row.addView(button, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 40));
        final TextView link = new TextView(context);
        link.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        link.setTypeface(AndroidUtilities.bold());
        link.setTextColor(NexLentaColors.dark && palette != null && palette.standard ? NexLentaColors.TEXT : NexLentaColors.ACCENT);
        link.setText(LocaleController.getString(R.string.NexAppearancePreviewLink));
        row.addView(link, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 14, 0, 8, 0));
        row.addView(new ToggleView(context), LayoutHelper.createLinear(46, 28));
        card.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));
        return card;
    }

    private TextView bubbleText(Context context, int textRes, int color) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTextColor(color);
        view.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(14), AndroidUtilities.dp(10));
        view.setText(LocaleController.getString(textRes));
        return view;
    }

    // Segmented control: Tizim / Yorug' / Qorong'i
    private View createModeSwitch(Context context) {
        final LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 26));
        group.setPadding(AndroidUtilities.dp(5), AndroidUtilities.dp(5), AndroidUtilities.dp(5), AndroidUtilities.dp(5));
        final int selected = currentMode();
        final int[] modes = {MODE_SYSTEM, MODE_LIGHT, MODE_DARK};
        for (int mode : modes) {
            final TextView option = new TextView(context);
            option.setGravity(Gravity.CENTER);
            option.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            option.setTypeface(AndroidUtilities.bold());
            option.setSingleLine(true);
            option.setText(modeName(mode));
            if (mode == selected) {
                option.setTextColor(NexLentaColors.ON_ACCENT);
                option.setBackground(NexLentaUi.roundRect(NexLentaColors.ACCENT, 21));
            } else {
                option.setTextColor(NexLentaColors.TEXT);
                option.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(NexLentaColors.TEXT, 0.08f), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
                option.setOnClickListener(v -> setMode(mode, v));
                org.telegram.ui.Components.ScaleStateListAnimator.apply(option, 0.05f, 1.6f);
            }
            group.addView(option, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        }
        return group;
    }

    private View createAccentGrid(Context context) {
        final LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 28));
        grid.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(10));
        final String current = NexPalette.findAccent(Theme.getNexgrammAccent()).id;
        LinearLayout row = null;
        for (int i = 0; i < NexPalette.ACCENTS.length; i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
            final NexPalette.Accent accent = NexPalette.ACCENTS[i];
            final LinearLayout item = new LinearLayout(context);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(0, 0, 0, AndroidUtilities.dp(8));
            final SwatchView swatch = new SwatchView(context, accent, accent.id.equals(current));
            item.addView(swatch, LayoutHelper.createLinear(52, 52));
            final TextView label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            label.setTextColor(accent.id.equals(current) ? NexLentaColors.TEXT : NexLentaColors.SECONDARY_TEXT);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setText(LocaleController.getString(ACCENT_NAMES[i]));
            item.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 6, 2, 0));
            item.setOnClickListener(v -> setAccent(accent.id, swatch));
            org.telegram.ui.Components.ScaleStateListAnimator.apply(item, 0.06f, 1.6f);
            row.addView(item, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        }
        if (row != null) {
            for (int i = row.getChildCount(); i < COLUMNS; i++) {
                row.addView(new View(context), LayoutHelper.createLinear(0, 1, 1f));
            }
        }
        return grid;
    }

    private void setAccent(String accentId, View from) {
        // compared with what is on screen, not the saved choice: a tap during the reveal may have been dropped
        final NexPalette applied = Theme.getNexgrammPalette();
        if (accentId.equals(Theme.getNexgrammAccent()) && applied != null && accentId.equals(applied.accentId)) {
            return;
        }
        Theme.setNexgrammAccent(accentId);
        if (Theme.isNexgrammTheme(Theme.getActiveTheme())) {
            switchTheme(Theme.getActiveTheme(), Theme.isCurrentThemeNight(), true, from);
        } else {
            // a custom theme is active: the accent shows once a built-in theme is used again
            build();
        }
    }

    // Color strength: from soft to the full color; applied when the finger lets go
    private View createIntensity(Context context) {
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 22));
        card.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        final android.widget.SeekBar bar = new android.widget.SeekBar(context);
        bar.setMax(100);
        bar.setProgress(Theme.getNexgrammIntensity());
        final android.content.res.ColorStateList tint = android.content.res.ColorStateList.valueOf(NexLentaColors.ACCENT);
        bar.setProgressTintList(tint);
        bar.setThumbTintList(tint);
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Theme.multAlpha(NexLentaColors.TEXT, 0.2f)));
        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                final int value = seekBar.getProgress();
                if (value == Theme.getNexgrammIntensity()) {
                    return;
                }
                Theme.setNexgrammIntensity(value);
                if (Theme.isNexgrammTheme(Theme.getActiveTheme())) {
                    switchTheme(Theme.getActiveTheme(), Theme.isCurrentThemeNight(), false, seekBar);
                }
            }
        });
        card.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40));
        final android.widget.FrameLayout labels = new android.widget.FrameLayout(context);
        final TextView soft = new TextView(context);
        soft.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        soft.setTextColor(NexLentaColors.SECONDARY_TEXT);
        soft.setText(LocaleController.getString(R.string.NexAppearanceSoft));
        labels.addView(soft, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));
        final TextView vivid = new TextView(context);
        vivid.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        vivid.setTextColor(NexLentaColors.SECONDARY_TEXT);
        vivid.setText(LocaleController.getString(R.string.NexAppearanceVivid));
        labels.addView(vivid, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT));
        card.addView(labels, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 0, 8, 0));
        return card;
    }

    private void setMode(int mode, View from) {
        if (mode == MODE_SYSTEM) {
            Theme.selectedAutoNightType = Theme.AUTO_NIGHT_TYPE_SYSTEM;
            Theme.saveAutoNightThemeConfig();
            rebuildOthers = true;
            Theme.checkAutoNightThemeConditions(true);
            build();
            return;
        }
        Theme.selectedAutoNightType = Theme.AUTO_NIGHT_TYPE_NONE;
        Theme.saveAutoNightThemeConfig();
        final boolean dark = mode == MODE_DARK;
        if (Theme.isCurrentThemeDark() == dark) {
            build();
            return;
        }
        Theme.ThemeInfo theme;
        if (dark) {
            theme = Theme.getCurrentNightTheme();
            if (theme == null || !Theme.isNexgrammTheme(theme)) {
                theme = Theme.getTheme("Night");
            }
        } else {
            theme = Theme.getTheme("Blue");
        }
        if (theme != null) {
            switchTheme(theme, false, dark, from);
        }
    }

    // Same path as Telegram's day/night switch: snapshot, circular reveal from the control, theme re-applied
    private void switchTheme(Theme.ThemeInfo theme, boolean nightTheme, boolean reveal, View from) {
        final int[] pos = new int[2];
        from.getLocationInWindow(pos);
        pos[0] += from.getMeasuredWidth() / 2;
        pos[1] += from.getMeasuredHeight() / 2;
        rebuildOthers = true;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme, theme, nightTheme, pos, -1, reveal, null);
    }

    // Accent circle; "Standart" is split black / white. Selected: ring around it and a check mark
    private static final class SwatchView extends View {
        private final NexPalette.Accent accent;
        private final boolean selected;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path check = new Path();
        private final RectF rect = new RectF();

        SwatchView(Context context, NexPalette.Accent accent, boolean selected) {
            super(context);
            this.accent = accent;
            this.selected = selected;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;
            final float outer = Math.min(cx, cy);
            final float r = outer - AndroidUtilities.dp(selected ? 6 : 3);
            if (selected) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(AndroidUtilities.dp(2));
                paint.setColor(NexLentaColors.TEXT);
                canvas.drawCircle(cx, cy, outer - AndroidUtilities.dp(1), paint);
            }
            paint.setStyle(Paint.Style.FILL);
            // as it is applied: softened by the color strength
            final int color = NexPalette.tone(NexLentaColors.dark ? accent.dark : accent.light, NexLentaColors.dark, Theme.getNexgrammIntensity());
            if (NexPalette.DEFAULT_ACCENT.equals(accent.id)) {
                rect.set(cx - r, cy - r, cx + r, cy + r);
                paint.setColor(accent.light);
                canvas.drawArc(rect, 135, 180, true, paint);
                paint.setColor(accent.dark);
                canvas.drawArc(rect, 315, 180, true, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1, AndroidUtilities.dp(1)));
                paint.setColor(Theme.multAlpha(NexLentaColors.TEXT, 0.15f));
                canvas.drawCircle(cx, cy, r, paint);
            } else {
                paint.setColor(color);
                canvas.drawCircle(cx, cy, r, paint);
            }
            if (selected) {
                final int mark = NexPalette.DEFAULT_ACCENT.equals(accent.id) ? 0xFFFFFFFF : NexPalette.onColor(color);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStrokeWidth(AndroidUtilities.dp(2.5f));
                paint.setColor(mark);
                if (NexPalette.DEFAULT_ACCENT.equals(accent.id)) {
                    // readable on both halves
                    paint.setShadowLayer(AndroidUtilities.dp(2), 0, 0, 0x99000000);
                }
                check.reset();
                check.moveTo(cx - r * 0.38f, cy + r * 0.02f);
                check.lineTo(cx - r * 0.1f, cy + r * 0.3f);
                check.lineTo(cx + r * 0.4f, cy - r * 0.26f);
                canvas.drawPath(check, paint);
                paint.clearShadowLayer();
            }
        }
    }

    // Static "on" switch in the accent, for the preview
    private static final class ToggleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        ToggleView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float h = getHeight();
            rect.set(0, 0, getWidth(), h);
            paint.setColor(NexLentaColors.ACCENT);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);
            paint.setColor(NexLentaColors.ON_ACCENT);
            canvas.drawCircle(getWidth() - h / 2f, h / 2f, h / 2f - AndroidUtilities.dp(3), paint);
        }
    }
}
