package org.telegram.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.NexWallet;

// NexGramm ✦ Nex wallet (Settings → NexGramm features → ✦ Nex): the balance on a violet card, "Top up" (the
// bot sells packages by card), what Nex is for with today's prices, and the last moves.
public class NexWalletActivity extends BaseFragment {

    private TextView balanceView;
    private TextInfoPrivacyCell usesInfo;
    private LinearLayout historyColumn;
    private Context context;

    @Override
    public View createView(Context context) {
        this.context = context;
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.NexWalletTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final ScrollView scrollView = new NexMotion.BounceScrollView(context);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(column, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // the balance card
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        final GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF2A1458, 0xFF7C3AED, 0xFFB48CF5});
        bg.setCornerRadius(AndroidUtilities.dp(24));
        card.setBackground(bg);
        card.setPadding(0, AndroidUtilities.dp(22), 0, AndroidUtilities.dp(22));
        final TextView caption = new TextView(context);
        caption.setText(LocaleController.getString(R.string.NexWalletBalance));
        caption.setTextColor(0xCCFFFFFF);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        card.addView(caption, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        balanceView = new TextView(context);
        balanceView.setTextColor(0xFFFFFFFF);
        balanceView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 40);
        balanceView.setTypeface(AndroidUtilities.bold());
        card.addView(balanceView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 0));
        final TextView top = new TextView(context);
        top.setText(LocaleController.getString(R.string.NexTopUp));
        top.setTextColor(0xFF2A1458);
        top.setTypeface(AndroidUtilities.bold());
        top.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        top.setGravity(Gravity.CENTER);
        top.setPadding(AndroidUtilities.dp(26), 0, AndroidUtilities.dp(26), 0);
        top.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(20), 0xFFFFFFFF, 0xFFE6DAFF));
        top.setOnClickListener(v -> NexWalletUi.openBot(this, "nex"));
        card.addView(top, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 40, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));
        column.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 14, 14, 14));

        final HeaderCell uses = new HeaderCell(context);
        uses.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        uses.setText(LocaleController.getString(R.string.NexWalletUses));
        column.addView(uses, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        usesInfo = new TextInfoPrivacyCell(context);
        usesInfo.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        column.addView(usesInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        column.addView(new ShadowSectionCell(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final HeaderCell history = new HeaderCell(context);
        history.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        history.setText(LocaleController.getString(R.string.NexWalletHistory));
        column.addView(history, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        historyColumn = new LinearLayout(context);
        historyColumn.setOrientation(LinearLayout.VERTICAL);
        historyColumn.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        column.addView(historyColumn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final TextInfoPrivacyCell note = new TextInfoPrivacyCell(context);
        note.setText(LocaleController.getString(R.string.NexWalletNote));
        column.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        show(NexWallet.cached(NexWalletUi.me(this)));
        fragmentView = scrollView;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        final long tg = NexWalletUi.me(this);
        CoreClient.EXECUTOR.execute(() -> {
            try {
                final NexWallet.Wallet w = NexWallet.loadBlocking(tg);
                AndroidUtilities.runOnUIThread(() -> show(w));
            } catch (Exception ignore) {
            }
        });
    }

    private void show(NexWallet.Wallet w) {
        if (balanceView == null) {
            return;
        }
        balanceView.setText(w != null ? NexWalletUi.format(w.balance) : "✦ …");
        if (w != null) {
            usesInfo.setText(LocaleController.formatString(R.string.NexWalletUsesText,
                NexWalletUi.format(w.restore), NexWalletUi.format(w.freezeDay), NexWalletUi.format(w.premium1m)));
        } else {
            usesInfo.setText(LocaleController.getString(R.string.NexWalletUsesShort));
        }
        historyColumn.removeAllViews();
        if (w == null || w.history.isEmpty()) {
            final TextCell empty = new TextCell(context);
            empty.setText(LocaleController.getString(R.string.NexWalletEmpty), false);
            historyColumn.addView(empty);
            return;
        }
        for (int i = 0; i < w.history.size(); i++) {
            final NexWallet.Move m = w.history.get(i);
            final TextCell row = new TextCell(context);
            final String when = m.atMs > 0 ? DateFormat.format("dd.MM.yyyy", m.atMs).toString() : "";
            final String title = kindName(m.kind) + (m.note != null && !m.note.isEmpty() ? " · " + m.note : "");
            row.setTextAndValue(title + (when.isEmpty() ? "" : "  " + when), (m.delta > 0 ? "+" : "−") + NexWalletUi.format(Math.abs(m.delta)), i != w.history.size() - 1);
            row.setColors(Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_windowBackgroundWhiteBlackText);
            if (m.delta > 0) {
                row.valueTextView.setTextColor(0xFF2BB24C);
            }
            historyColumn.addView(row);
        }
    }

    private static String kindName(String kind) {
        switch (kind == null ? "" : kind) {
            case "buy": return LocaleController.getString(R.string.NexKindBuy);
            case "gift_out": return LocaleController.getString(R.string.NexKindGiftOut);
            case "gift_in": return LocaleController.getString(R.string.NexKindGiftIn);
            case "tip_out": return LocaleController.getString(R.string.NexKindTipOut);
            case "tip_in": return LocaleController.getString(R.string.NexKindTipIn);
            case "streak_restore": return LocaleController.getString(R.string.NexKindRestore);
            case "streak_freeze": return LocaleController.getString(R.string.NexKindFreeze);
            case "premium": return LocaleController.getString(R.string.NexKindPremium);
            case "ad_budget": return LocaleController.getString(R.string.NexKindAd);
            default: return LocaleController.getString(R.string.NexKindOther);
        }
    }
}
