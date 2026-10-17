package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.NexWallet;

// NexGramm ✦ Nex, the shared bits every screen uses: open the bot to top up, send a present / a Lenta reward,
// and pay for something (a streak restore / freeze, Premium) after a clear confirmation.
final class NexWalletUi {

    static final int[] GIFT_AMOUNTS = {5, 10, 25, 50, 100};

    private NexWalletUi() {
    }

    static long me(BaseFragment fragment) {
        return fragment.getUserConfig().getClientUserId();
    }

    static String format(long amount) {
        return "✦ " + LocaleController.formatNumber(amount, ' ');
    }

    // the bot chat opens inside the app with "/start <start>" (like Premium): build's bot first, server's next
    static void openBot(BaseFragment fragment, String start) {
        String bot = username(BuildConfig.NEXGRAMM_BOT_USERNAME);
        if (bot == null) {
            final NexWallet.Wallet w = NexWallet.cached(me(fragment));
            bot = w != null ? username(w.botUsername) : null;
        }
        if (bot == null || fragment.getParentActivity() == null) {
            failed(fragment);
            return;
        }
        fragment.getMessagesController().getUserNameResolver().resolve(bot, peerId -> {
            final TLRPC.User user = peerId != null && peerId > 0 ? fragment.getMessagesController().getUser(peerId) : null;
            if (user == null) {
                failed(fragment);
                return;
            }
            final Bundle args = new Bundle();
            args.putLong("user_id", user.id);
            final ChatActivity chat = new ChatActivity(args);
            if (fragment.presentFragment(chat)) {
                chat.setBotUser(start);
            }
        });
    }

    static String username(String value) {
        final String v = value == null ? "" : value.trim().replaceFirst("^@", "");
        return v.matches("[A-Za-z][A-Za-z0-9_]{4,31}") ? v : null;
    }

    static void failed(BaseFragment fragment) {
        if (fragment.getParentActivity() != null) {
            Toast.makeText(fragment.getParentActivity(), LocaleController.getString(R.string.NexActionFailed), Toast.LENGTH_SHORT).show();
        }
    }

    // the balance line of a dialog, filled from the cache at once and from the server a moment later
    private static TextView balanceLine(BaseFragment fragment, Context context) {
        final TextView balance = new TextView(context);
        balance.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        balance.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        final NexWallet.Wallet cached = NexWallet.cached(me(fragment));
        balance.setText(LocaleController.formatString(R.string.NexWalletBalanceLine, cached != null ? format(cached.balance) : "…"));
        final long tg = me(fragment);
        CoreClient.EXECUTOR.execute(() -> {
            try {
                final NexWallet.Wallet w = NexWallet.loadBlocking(tg);
                AndroidUtilities.runOnUIThread(() -> balance.setText(LocaleController.formatString(R.string.NexWalletBalanceLine, format(w.balance))));
            } catch (Exception ignore) {
            }
        });
        return balance;
    }

    // a present to a Telegram friend (toTgId) or a reward for a Lenta post (toLenta)
    static void gift(BaseFragment fragment, long toTgId, String toLenta, boolean tip) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(4), AndroidUtilities.dp(24), 0);
        column.addView(balanceLine(fragment, context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        final int[] chosen = {GIFT_AMOUNTS[1]};
        final LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] chipViews = new TextView[GIFT_AMOUNTS.length];
        final Runnable paint = () -> {
            for (int i = 0; i < chipViews.length; i++) {
                final boolean on = GIFT_AMOUNTS[i] == chosen[0];
                chipViews[i].setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16),
                    on ? Theme.getColor(Theme.key_featuredStickers_addButton) : Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.07f)));
                chipViews[i].setTextColor(on ? Theme.getColor(Theme.key_featuredStickers_buttonText) : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            }
        };
        for (int i = 0; i < GIFT_AMOUNTS.length; i++) {
            final int amount = GIFT_AMOUNTS[i];
            final TextView chip = new TextView(context);
            chip.setText("✦" + amount);
            chip.setGravity(Gravity.CENTER);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            chip.setTypeface(AndroidUtilities.bold());
            chip.setOnClickListener(v -> {
                chosen[0] = amount;
                paint.run();
            });
            chipViews[i] = chip;
            chips.addView(chip, LayoutHelper.createLinear(0, 34, 1f, i == 0 ? 0 : 6, 0, 0, 0));
        }
        paint.run();
        column.addView(chips, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final EditText note = new EditText(context);
        note.setHint(LocaleController.getString(R.string.NexGiftNoteHint));
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setFilters(new InputFilter[]{new InputFilter.LengthFilter(140)});
        note.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        note.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        column.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        fragment.showDialog(new AlertDialog.Builder(context)
            .setTitle(LocaleController.getString(tip ? R.string.NexTipTitle : R.string.NexGiftTitle))
            .setView(column)
            .setPositiveButton(LocaleController.getString(R.string.NexGiftSend), (d, w) -> {
                final long tg = me(fragment);
                final int amount = chosen[0];
                final String text = note.getText().toString();
                CoreClient.EXECUTOR.execute(() -> {
                    final NexWallet.Result r = NexWallet.giftBlocking(tg, amount, tip ? "tip" : "gift", toTgId, toLenta, text);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (r.ok) {
                            Toast.makeText(context, LocaleController.formatString(R.string.NexGiftSent, format(amount)), Toast.LENGTH_SHORT).show();
                        } else {
                            explain(fragment, r.error);
                        }
                    });
                });
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create());
    }

    // pays for something after showing what it costs; onPaid runs on the UI thread once the server took it
    static void spend(BaseFragment fragment, String what, String kind, int days, String plan, int cost, Runnable onPaid) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(4), AndroidUtilities.dp(24), 0);
        final TextView price = new TextView(context);
        price.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        price.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        price.setText(LocaleController.formatString(R.string.NexSpendAsk, what, format(cost)));
        column.addView(price, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        column.addView(balanceLine(fragment, context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        fragment.showDialog(new AlertDialog.Builder(context)
            .setTitle(LocaleController.getString(R.string.NexWalletTitle))
            .setView(column)
            .setPositiveButton(LocaleController.getString(R.string.NexSpendPay), (d, w) -> {
                final long tg = me(fragment);
                CoreClient.EXECUTOR.execute(() -> {
                    final NexWallet.Result r = NexWallet.spendBlocking(tg, kind, days, plan);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (r.ok) {
                            onPaid.run();
                        } else {
                            explain(fragment, r.error);
                        }
                    });
                });
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create());
    }

    // what went wrong; not enough Nex offers the top-up in the bot
    static void explain(BaseFragment fragment, String error) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        if ("not_enough".equals(error)) {
            fragment.showDialog(new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.NexWalletTitle))
                .setMessage(LocaleController.getString(R.string.NexNotEnough))
                .setPositiveButton(LocaleController.getString(R.string.NexTopUp), (d, w) -> openBot(fragment, "nex"))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create());
            return;
        }
        final int text = "receiver_not_found".equals(error) ? R.string.NexReceiverNotFound
            : "self_gift".equals(error) ? R.string.NexSelfGift : R.string.NexActionFailed;
        Toast.makeText(context, LocaleController.getString(text), Toast.LENGTH_SHORT).show();
    }
}
