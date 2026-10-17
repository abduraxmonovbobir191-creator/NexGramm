package org.telegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NexStreaks;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.StoryEntry;
import org.telegram.ui.Stories.recorder.StoryRecorder;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import uz.nexgramm.core.NexWallet;

// NexGramm ✦ streak card of a chat: the ✦ in its level color with the number of days, what is happening
// (going, ends tonight ⏳, frozen ❄, or ended — bring it back for a few days), the friendship numbers, and
// "Share to story" (a ready 9:16 card opens in the story editor). Freeze / restore are paid with ✦ Nex.
final class NexStreakSheet {

    private NexStreakSheet() {
    }

    static void show(BaseFragment fragment, long dialogId) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final int account = fragment.getCurrentAccount();
        final NexStreaks streaks = NexStreaks.getInstance(account);
        final NexStreaks.Streak s = streaks.get(dialogId);
        final int count = s != null && s.visible() ? s.count : 0;
        final int color = NexStreaks.levelColor(Math.max(count, 3));
        final BottomSheet[] sheet = new BottomSheet[1];

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(20), AndroidUtilities.dp(22), AndroidUtilities.dp(12));

        final TextView symbol = text(context, count > 0 ? NexStreaks.symbol(count) : "✦", 56, count > 0 ? color : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), true);
        symbol.setGravity(Gravity.CENTER);
        column.addView(symbol, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final TextView big = text(context, count > 0 ? LocaleController.formatString(R.string.NexStreakDays, count) : LocaleController.getString(R.string.NexStreakNone), count > 0 ? 26 : 18, Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), true);
        big.setGravity(Gravity.CENTER);
        column.addView(big, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        final String status;
        if (count > 0 && streaks.atRisk(s)) {
            status = LocaleController.getString(R.string.NexStreakRisk);
        } else if (count > 0 && streaks.frozen(s)) {
            status = LocaleController.formatString(R.string.NexStreakFrozen, date(NexStreaks.dayStart(s.frozenUntil) * 1000L));
        } else if (count > 0) {
            status = LocaleController.getString(R.string.NexStreakGoing);
        } else if (streaks.canRestore(s)) {
            status = LocaleController.formatString(R.string.NexStreakEnded, s.lostCount);
        } else {
            status = LocaleController.getString(R.string.NexStreakHow);
        }
        final TextView statusView = text(context, status, 14, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), false);
        statusView.setGravity(Gravity.CENTER);
        column.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 14));

        if (s != null) {
            if (s.firstDay > 0) {
                column.addView(row(context, LocaleController.getString(R.string.NexStreakSince), date(NexStreaks.dayStart(s.firstDay) * 1000L)));
            }
            column.addView(row(context, LocaleController.getString(R.string.NexStreakTotal), LocaleController.formatString(R.string.NexStreakDaysShort, s.total)));
            if (s.messages > 0) {
                column.addView(row(context, LocaleController.getString(R.string.NexStreakMessages), LocaleController.formatNumber(s.messages, ' ')));
            }
            column.addView(row(context, LocaleController.getString(R.string.NexStreakRecord), LocaleController.formatString(R.string.NexStreakDaysShort, Math.max(s.record, count))));
        }

        final NexWallet.Wallet wallet = NexWallet.cached(NexWalletUi.me(fragment));
        final int restore = wallet != null ? wallet.restore : 50;
        final int freezeDay = wallet != null ? wallet.freezeDay : 15;
        if (count > 0) {
            column.addView(button(context, LocaleController.getString(R.string.NexStreakShare), true, () -> {
                sheet[0].dismiss();
                shareToStory(fragment, dialogId, count);
            }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 0, 0));
            column.addView(button(context, LocaleController.formatString(R.string.NexStreakFreeze, NexWalletUi.format(freezeDay)), false, () -> {
                sheet[0].dismiss();
                pickFreeze(fragment, dialogId, freezeDay);
            }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 8, 0, 0));
        } else if (streaks.canRestore(s)) {
            column.addView(button(context, LocaleController.formatString(R.string.NexStreakRestore, NexWalletUi.format(restore)), true, () -> {
                sheet[0].dismiss();
                NexWalletUi.spend(fragment, LocaleController.getString(R.string.NexStreakRestoreWhat), "streak_restore", 0, null, restore, () -> {
                    streaks.restore(dialogId);
                    show(fragment, dialogId);
                });
            }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 0, 0));
        }
        final TextView note = text(context, LocaleController.getString(R.string.NexStreakPrivacy), 12, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), false);
        note.setGravity(Gravity.CENTER);
        column.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        sheet[0] = new BottomSheet.Builder(context).setCustomView(column).create();
        fragment.showDialog(sheet[0]);
    }

    private static void pickFreeze(BaseFragment fragment, long dialogId, int perDay) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final int[] days = {1, 3, 7};
        final CharSequence[] items = new CharSequence[days.length];
        for (int i = 0; i < days.length; i++) {
            items[i] = LocaleController.formatString(R.string.NexStreakFreezeDays, days[i], NexWalletUi.format(perDay * days[i]));
        }
        final BottomSheet.Builder builder = new BottomSheet.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.NexStreakFreezeTitle));
        builder.setItems(items, (d, which) -> NexWalletUi.spend(fragment, LocaleController.formatString(R.string.NexStreakFreezeWhat, days[which]),
            "streak_freeze", days[which], null, perDay * days[which], () -> {
                NexStreaks.getInstance(fragment.getCurrentAccount()).freeze(dialogId, days[which]);
                show(fragment, dialogId);
            }));
        fragment.showDialog(builder.create());
    }

    // ---- the story card ----

    static void shareToStory(BaseFragment fragment, long dialogId, int count) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        try {
            final File file = new File(ApplicationLoader.applicationContext.getCacheDir(), "nex_streak_" + Math.abs(dialogId) + ".jpg");
            final Bitmap card = drawCard(fragment.getCurrentAccount(), dialogId, count);
            try (FileOutputStream out = new FileOutputStream(file)) {
                card.compress(Bitmap.CompressFormat.JPEG, 92, out);
            }
            card.recycle();
            StoryRecorder.getInstance(fragment.getParentActivity(), fragment.getCurrentAccount())
                .openEdit(null, StoryEntry.fromPhotoShoot(file, 0), System.currentTimeMillis(), true);
        } catch (Throwable e) {
            FileLog.e(e);
            NexWalletUi.failed(fragment);
        }
    }

    private static Bitmap drawCard(int account, long dialogId, int count) {
        final int w = 1080, h = 1920;
        final Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final int color = NexStreaks.levelColor(count);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, 0, h, new int[]{0xFF140A2E, 0xFF2D1366, 0xFF07070B}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(new RadialGradient(w / 2f, 760, 620, (color & 0x00FFFFFF) | 0x77000000, 0, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // the two of them (or the group)
        final UserConfig uc = UserConfig.getInstance(account);
        final TLRPC.User me = uc.getCurrentUser();
        final String theirName;
        final AvatarDrawable them = new AvatarDrawable();
        if (dialogId > 0) {
            final TLRPC.User u = org.telegram.messenger.MessagesController.getInstance(account).getUser(dialogId);
            them.setInfo(account, u);
            theirName = UserObject.getFirstName(u);
        } else {
            final TLRPC.Chat c = org.telegram.messenger.MessagesController.getInstance(account).getChat(-dialogId);
            them.setInfo(account, c);
            theirName = c != null ? c.title : "";
        }
        final int size = 250;
        if (dialogId > 0) {
            final AvatarDrawable mine = new AvatarDrawable();
            mine.setInfo(account, me);
            drawAvatar(canvas, mine, w / 2 - size + 40, 360, size);
            drawAvatar(canvas, them, w / 2 - 40, 360, size);
        } else {
            drawAvatar(canvas, them, (w - size) / 2, 360, size);
        }

        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(color);
        text.setTextSize(300);
        text.setShadowLayer(60, 0, 0, color);
        canvas.drawText(NexStreaks.symbol(count), w / 2f, 1010, text);
        text.clearShadowLayer();
        text.setColor(0xFFFFFFFF);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextSize(240);
        canvas.drawText(String.valueOf(count), w / 2f, 1270, text);
        text.setTypeface(Typeface.DEFAULT);
        text.setTextSize(64);
        text.setColor(0xE6FFFFFF);
        canvas.drawText(LocaleController.getString(R.string.NexStoryDaysTogether), w / 2f, 1370, text);
        text.setTextSize(52);
        text.setColor(0xB3FFFFFF);
        final String names = dialogId > 0 ? UserObject.getFirstName(me) + "  ✦  " + theirName : theirName;
        canvas.drawText(names, w / 2f, 1470, text);
        text.setTextSize(40);
        text.setColor(0x80FFFFFF);
        canvas.drawText("✦ NexGramm", w / 2f, h - 140, text);
        return bitmap;
    }

    private static void drawAvatar(Canvas canvas, AvatarDrawable avatar, int x, int y, int size) {
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setColor(0xFF1A0F33);
        canvas.drawCircle(x + size / 2f, y + size / 2f, size / 2f + 10, ring);
        avatar.setBounds(x, y, x + size, y + size);
        avatar.draw(canvas);
    }

    // ---- small views ----

    private static TextView text(Context context, String value, int sp, int color, boolean bold) {
        final TextView t = new TextView(context);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(AndroidUtilities.bold());
        }
        return t;
    }

    private static LinearLayout row(Context context, String label, String value) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        row.addView(text(context, label, 15, Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), false), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        row.addView(text(context, value, 15, Theme.getColor(Theme.key_windowBackgroundWhiteBlueText), true), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        return row;
    }

    private static TextView button(Context context, String label, boolean primary, Runnable click) {
        final TextView b = text(context, label, 15, primary ? 0xFFFFFFFF : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14),
            primary ? 0xFF7C3AED : Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.07f),
            primary ? 0xFF6A2BD9 : Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.14f)));
        b.setOnClickListener(v -> click.run());
        return b;
    }

    private static String date(long ms) {
        return new SimpleDateFormat("d MMMM yyyy", LocaleController.getInstance().getCurrentLocale() != null
            ? LocaleController.getInstance().getCurrentLocale() : Locale.getDefault()).format(new Date(ms));
    }
}
