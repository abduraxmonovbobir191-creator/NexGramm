package org.telegram.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.NexPremium;

// NexGramm Premium: what it gives, the status and the periods. A period opens the NexGramm bot at
// /start premium_<code>: the bot shows the card, the user sends the receipt, the owner confirms; the status
// is read again when the user comes back here.
public class NexPremiumActivity extends BaseFragment {

    private LinearLayout plansColumn;
    private TextView statusView;
    private NexPremium.Status status;

    private long tgUserId() {
        return getUserConfig().getClientUserId();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.NexPremiumTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(20), AndroidUtilities.dp(16), AndroidUtilities.dp(32));
        scroll.addView(column, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // hero: ⭐ on a soft gradient
        final TextView star = new TextView(context);
        star.setText("⭐");
        star.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 44);
        star.setGravity(Gravity.CENTER);
        final GradientDrawable halo = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFB48CF5, 0xFFFFB86B});
        halo.setShape(GradientDrawable.OVAL);
        star.setBackground(halo);
        column.addView(star, LayoutHelper.createLinear(96, 96, Gravity.CENTER_HORIZONTAL));
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.NexPremiumTitle));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(Gravity.CENTER);
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));
        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        column.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        // what it gives
        final LinearLayout perks = card(context);
        perk(context, perks, "🗑", R.string.NexPremiumPerkDeleted);
        perk(context, perks, "🔒", R.string.NexPremiumPerkLock);
        perk(context, perks, "🖼", R.string.NexPremiumPerkSave);
        perk(context, perks, "🔁", R.string.NexPremiumPerkRepeat);
        perk(context, perks, "💬", R.string.NexPremiumPerkSupport);
        perk(context, perks, "✨", R.string.NexPremiumPerkMore);
        column.addView(perks, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 0));

        // periods
        final TextView choose = new TextView(context);
        choose.setText(LocaleController.getString(R.string.NexPremiumChoose));
        choose.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        choose.setTypeface(AndroidUtilities.bold());
        choose.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        column.addView(choose, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 22, 4, 8));
        plansColumn = new LinearLayout(context);
        plansColumn.setOrientation(LinearLayout.VERTICAL);
        column.addView(plansColumn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView how = new TextView(context);
        how.setText(LocaleController.getString(R.string.NexPremiumHow));
        how.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        how.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        column.addView(how, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 14, 4, 0));

        fragmentView = scroll;
        status = NexPremium.cached(tgUserId());
        bind();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // back from the bot: maybe paid now
        final long userId = tgUserId();
        CoreClient.EXECUTOR.execute(() -> {
            NexPremium.Status fresh = null;
            try {
                fresh = NexPremium.loadBlocking(userId);
            } catch (Exception ignore) {
            }
            final NexPremium.Status result = fresh;
            AndroidUtilities.runOnUIThread(() -> {
                if (result != null && fragmentView != null) {
                    status = result;
                    bind();
                }
            });
        });
    }

    private void bind() {
        final NexPremium.Status s = status != null ? status : NexPremium.fallback();
        if (s.activeAt(System.currentTimeMillis())) {
            statusView.setText(LocaleController.formatString(R.string.NexPremiumActiveUntil,
                LocaleController.getInstance().getFormatterYear().format(s.untilMs)));
            statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGreenText));
        } else {
            statusView.setText(LocaleController.getString(R.string.NexPremiumFree));
            statusView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        }
        final Context context = plansColumn.getContext();
        plansColumn.removeAllViews();
        final NexPremium.Status plans = s.plans.isEmpty() ? NexPremium.fallback() : s;
        for (NexPremium.Plan plan : plans.plans) {
            final TextView row = new TextView(context);
            row.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            row.setTypeface(AndroidUtilities.bold());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(18), 0);
            row.setText(periodName(plan.code) + "  ·  " + plan.price);
            if (plan.onSale) {
                row.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
                row.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14),
                    Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
                row.setOnClickListener(v -> buy(s, plan.code));
            } else {
                row.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                row.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(14), Theme.getColor(Theme.key_windowBackgroundWhite)));
                row.setText(row.getText() + "  ·  " + LocaleController.getString(R.string.NexPremiumSoon));
            }
            plansColumn.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54, 0, 0, 0, 10));
        }
    }

    private String periodName(String code) {
        switch (code) {
            case "2w":
                return LocaleController.getString(R.string.NexPremium2w);
            case "1m":
                return LocaleController.getString(R.string.NexPremium1m);
            case "3m":
                return LocaleController.getString(R.string.NexPremium3m);
            default:
                return code;
        }
    }

    private void buy(NexPremium.Status s, String code) {
        final String bot = !TextUtils.isEmpty(s.botUsername) ? s.botUsername : BuildConfig.NEXGRAMM_BOT_USERNAME;
        if (TextUtils.isEmpty(bot) || getParentActivity() == null) {
            return;
        }
        Browser.openUrl(getParentActivity(), "https://t.me/" + bot + "?start=premium_" + code);
    }

    private LinearLayout card(Context context) {
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        card.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16), Theme.getColor(Theme.key_windowBackgroundWhite)));
        return card;
    }

    private void perk(Context context, LinearLayout card, String emoji, int text) {
        final TextView row = new TextView(context);
        row.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        row.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.setText(emoji + "   " + LocaleController.getString(text));
        row.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));
        card.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    // Opens this screen when a Premium feature is used without Premium; true = the feature may run
    static boolean check(BaseFragment from) {
        if (from == null) {
            return false;
        }
        final long userId = from.getUserConfig().getClientUserId();
        if (NexPremium.isActive(userId)) {
            return true;
        }
        NexPremium.refresh(userId);
        from.presentFragment(new NexPremiumActivity());
        return false;
    }
}
