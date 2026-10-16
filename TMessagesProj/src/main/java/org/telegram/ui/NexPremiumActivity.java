package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
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
    private android.animation.ValueAnimator glowLoop;
    private android.animation.ValueAnimator starLoop;
    private boolean plansShown; // the plans rise in once
    private static final int[] HERO_COLORS = {0xFF7B5CF0, 0xFFD96BB0, 0xFFFFA45C};

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

        final ScrollView scroll = new NexMotion.BounceScrollView(context);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(32));
        scroll.addView(column, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // hero: a warm gradient card, the glowing ⭐, the name in the NexGramm serif and the status
        final LinearLayout heroCard = new LinearLayout(context);
        heroCard.setOrientation(LinearLayout.VERTICAL);
        heroCard.setGravity(Gravity.CENTER_HORIZONTAL);
        final GradientDrawable heroBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, HERO_COLORS);
        heroBg.setCornerRadius(AndroidUtilities.dp(28));
        heroCard.setBackground(heroBg);
        heroCard.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(22), AndroidUtilities.dp(20), AndroidUtilities.dp(24));
        final android.widget.FrameLayout hero = new android.widget.FrameLayout(context);
        final View glow = new View(context);
        final GradientDrawable glowShape = new GradientDrawable();
        glowShape.setShape(GradientDrawable.OVAL);
        glowShape.setColor(0x55FFFFFF);
        glow.setBackground(glowShape);
        hero.addView(glow, LayoutHelper.createFrame(88, 88, Gravity.CENTER));
        final TextView star = new TextView(context);
        star.setText("⭐");
        star.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 40);
        star.setGravity(Gravity.CENTER);
        final GradientDrawable halo = new GradientDrawable();
        halo.setShape(GradientDrawable.OVAL);
        halo.setColor(0x33FFFFFF);
        halo.setStroke(AndroidUtilities.dp(1.5f), 0x66FFFFFF);
        star.setBackground(halo);
        hero.addView(star, LayoutHelper.createFrame(88, 88, Gravity.CENTER));
        heroCard.addView(hero, LayoutHelper.createLinear(124, 124, Gravity.CENTER_HORIZONTAL));
        hero.setScaleX(0.5f);
        hero.setScaleY(0.5f);
        hero.setAlpha(0f);
        hero.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(620).setInterpolator(new android.view.animation.OvershootInterpolator(1.8f)).start();
        glowLoop = NexMotion.breathe(glow, 1.35f, 0.3f, 0.9f);
        starLoop = NexMotion.breathe(star, 1.05f, 0f, 0f);
        final TextView title = NexLentaUi.serifTitle(context, 34);
        title.setText(LocaleController.getString(R.string.NexPremiumTitle));
        title.setTextColor(0xFFFFFFFF);
        title.setGravity(Gravity.CENTER);
        heroCard.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextColor(0xE6FFFFFF);
        heroCard.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        column.addView(heroCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // periods first: what people came for
        final TextView choose = sectionTitle(context, R.string.NexPremiumChoose);
        column.addView(choose, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 6, 22, 6, 10));
        plansColumn = new LinearLayout(context);
        plansColumn.setOrientation(LinearLayout.VERTICAL);
        column.addView(plansColumn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView how = new TextView(context);
        how.setText(LocaleController.getString(R.string.NexPremiumHow));
        how.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        how.setLineSpacing(AndroidUtilities.dp(2), 1f);
        how.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        column.addView(how, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 6, 4, 6, 0));

        // invite friends: free Premium days for each friend who starts using the app
        final LinearLayout invite = card(context);
        final TextView inviteText = new TextView(context);
        inviteText.setText(LocaleController.getString(R.string.NexPremiumInvite));
        inviteText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        inviteText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        inviteText.setLineSpacing(AndroidUtilities.dp(2), 1f);
        invite.addView(inviteText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final TextView inviteButton = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexPremiumInviteButton), NexLentaUi.PILL_PRIMARY);
        inviteButton.setOnClickListener(v -> shareInvite());
        invite.addView(inviteButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 12, 0, 0));
        column.addView(invite, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

        // what it gives: a colored tile per perk
        final TextView gives = sectionTitle(context, R.string.NexPremiumGives);
        column.addView(gives, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 6, 24, 6, 10));
        final LinearLayout perks = card(context);
        perk(context, perks, "🚫", 0xFFFF6B6B, R.string.NexPremiumPerkNoAds);
        perk(context, perks, "✦", 0xFF8B6CF6, R.string.NexPremiumPerkAi);
        perk(context, perks, "🗑", 0xFFFF9F43, R.string.NexPremiumPerkDeleted);
        perk(context, perks, "🔒", 0xFF2EB872, R.string.NexPremiumPerkLock);
        perk(context, perks, "🖼", 0xFF3BA7FF, R.string.NexPremiumPerkSave);
        perk(context, perks, "🔁", 0xFF00B8A9, R.string.NexPremiumPerkRepeat);
        perk(context, perks, "💬", 0xFFF368E0, R.string.NexPremiumPerkSupport);
        perk(context, perks, "✨", 0xFFFFC312, R.string.NexPremiumPerkMore);
        column.addView(perks, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        NexMotion.enter(heroCard, 0);
        NexMotion.enter(choose, 1);
        NexMotion.enter(perks, 5);

        fragmentView = scroll;
        status = NexPremium.cached(tgUserId());
        bind();
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        if (glowLoop != null) {
            glowLoop.cancel();
        }
        if (starLoop != null) {
            starLoop.cancel();
        }
        super.onFragmentDestroy();
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
        } else {
            statusView.setText(LocaleController.getString(R.string.NexPremiumFree));
        }
        final Context context = plansColumn.getContext();
        plansColumn.removeAllViews();
        if (!"direct".equals(BuildConfig.NEX_STORE)) {
            // Google Play / Galaxy Store build: buying goes only through the store's own billing, coming later
            final TextView note = new TextView(context);
            note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            note.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            note.setGravity(Gravity.CENTER);
            note.setText(LocaleController.getString(R.string.NexPremiumStoreSoon));
            plansColumn.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 8));
            plansShown = true;
            return;
        }
        final NexPremium.Status plans = s.plans.isEmpty() ? NexPremium.fallback() : s;
        for (NexPremium.Plan plan : plans.plans) {
            final boolean best = "3m".equals(plan.code);
            final android.widget.FrameLayout row = new android.widget.FrameLayout(context);
            row.setPadding(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(18), 0);
            final int text = best ? 0xFFFFFFFF : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            if (best) {
                final GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, HERO_COLORS);
                bg.setCornerRadius(AndroidUtilities.dp(18));
                row.setBackground(bg);
            } else {
                final GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(AndroidUtilities.dp(18));
                bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                bg.setStroke(AndroidUtilities.dp(1), Theme.getColor(Theme.key_divider));
                row.setBackground(bg);
            }
            final LinearLayout left = new LinearLayout(context);
            left.setOrientation(LinearLayout.VERTICAL);
            final TextView name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            name.setTypeface(AndroidUtilities.bold());
            name.setTextColor(text);
            name.setText(periodName(plan.code));
            left.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            if (best || !plan.onSale) {
                final TextView badge = new TextView(context);
                badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                badge.setTextColor(best ? 0xE6FFFFFF : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                badge.setText(LocaleController.getString(plan.onSale ? R.string.NexPremiumBest : R.string.NexPremiumSoon));
                left.addView(badge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            }
            row.addView(left, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL));
            final TextView price = new TextView(context);
            price.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            price.setTypeface(AndroidUtilities.bold());
            price.setTextColor(text);
            price.setText(plan.price);
            row.addView(price, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
            if (plan.onSale) {
                row.setOnClickListener(v -> {
                    NexMotion.pulse(v); // a heartbeat, then the bot
                    AndroidUtilities.runOnUIThread(() -> buy(s, plan.code), 180);
                });
            } else {
                row.setAlpha(0.6f);
            }
            plansColumn.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 66, 0, 0, 0, 10));
            if (!plansShown) {
                NexMotion.enter(row, 1 + plansColumn.getChildCount());
            }
        }
        plansShown = true;
    }

    private TextView sectionTitle(Context context, int res) {
        final TextView view = new TextView(context);
        view.setText(LocaleController.getString(res));
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        return view;
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

    // The bot chat opens inside the app and gets "/start premium_<code>" at once (no t.me link, so a wrong
    // server value cannot break it): the build's bot first, the server's only when it is a valid username.
    private void buy(NexPremium.Status s, String code) {
        String bot = username(BuildConfig.NEXGRAMM_BOT_USERNAME);
        if (bot == null) {
            bot = username(s.botUsername);
        }
        if (bot == null || getParentActivity() == null) {
            failed();
            return;
        }
        getMessagesController().getUserNameResolver().resolve(bot, peerId -> {
            final TLRPC.User user = peerId != null && peerId > 0 ? getMessagesController().getUser(peerId) : null;
            if (user == null || fragmentView == null) {
                failed();
                return;
            }
            final Bundle args = new Bundle();
            args.putLong("user_id", user.id);
            final ChatActivity chat = new ChatActivity(args);
            if (presentFragment(chat)) {
                chat.setBotUser("premium_" + code);
            }
        });
    }

    // "Join NexGramm: t.me/<bot>?start=ref_<my id>" through the phone's share sheet
    private void shareInvite() {
        String bot = username(BuildConfig.NEXGRAMM_BOT_USERNAME);
        if (bot == null && status != null) {
            bot = username(status.botUsername);
        }
        if (bot == null || getParentActivity() == null) {
            failed();
            return;
        }
        final String link = "https://t.me/" + bot + "?start=ref_" + tgUserId();
        final android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(android.content.Intent.EXTRA_TEXT, LocaleController.getString(R.string.NexPremiumInviteShare) + "\n" + link);
        try {
            getParentActivity().startActivity(android.content.Intent.createChooser(send, LocaleController.getString(R.string.NexPremiumInviteButton)));
        } catch (Exception ignore) {
            failed();
        }
    }

    private static String username(String value) {
        final String v = value == null ? "" : value.trim().replaceFirst("^@", "");
        return v.matches("[A-Za-z][A-Za-z0-9_]{4,31}") ? v : null;
    }

    private void failed() {
        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), LocaleController.getString(R.string.NexActionFailed), Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout card(Context context) {
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(6), AndroidUtilities.dp(14), AndroidUtilities.dp(6));
        card.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(20), Theme.getColor(Theme.key_windowBackgroundWhite)));
        return card;
    }

    // a soft tile in the perk's own color, then the text
    private void perk(Context context, LinearLayout card, String emoji, int color, int text) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, AndroidUtilities.dp(9), 0, AndroidUtilities.dp(9));
        final TextView tile = new TextView(context);
        tile.setText(emoji);
        tile.setGravity(Gravity.CENTER);
        tile.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        tile.setTextColor(color);
        tile.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(11), Theme.multAlpha(color, 0.16f)));
        row.addView(tile, LayoutHelper.createLinear(38, 38));
        final TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        label.setText(LocaleController.getString(text));
        row.addView(label, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 14, 0, 0, 0));
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
