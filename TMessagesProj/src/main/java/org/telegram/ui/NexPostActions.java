package org.telegram.ui;

import android.content.Context;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.ShareAlert;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.PixelfedClient;
import uz.nexgramm.core.PixelfedStatus;

// NexGramm: actions on a Lenta post shared by the feed, the comments screen and the profile:
// "⋯" menu (copy link; own post: delete; others: report), Telegram share sheet, optimistic bookmark.
final class NexPostActions {

    interface Listener {
        // the post was deleted on the server
        void onDeleted(PixelfedStatus status);

        // a counter or flag changed: redraw the cell(s) of this post
        void onChanged(PixelfedStatus status);

        // "Not interested": the For-you Lenta learns it and drops the post (only where it is offered)
        default void onNotInterested(PixelfedStatus status) {
        }

        default boolean canSayNotInterested() {
            return false;
        }
    }

    // Report reasons shown to the user, same order as PixelfedClient.REPORT_TYPES
    private static final int[] REPORT_LABELS = {
        R.string.NexReportSpam, R.string.NexReportSensitive, R.string.NexReportAbusive, R.string.NexReportUnderage,
        R.string.NexReportViolence, R.string.NexReportCopyright, R.string.NexReportImpersonation, R.string.NexReportScam,
        R.string.NexReportTerrorism
    };

    private NexPostActions() {
    }

    static boolean isOwn(long tgUserId, PixelfedStatus status) {
        final String own = PixelfedClient.ownUsername(tgUserId);
        return own != null && own.equalsIgnoreCase(status.authorUsername);
    }

    static void showMenu(BaseFragment fragment, long tgUserId, PixelfedStatus status, Listener listener) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final boolean own = isOwn(tgUserId, status);
        final boolean notInterested = !own && listener.canSayNotInterested();
        // 0 copy link, 1 delete, 2 ✦ Nex reward, 3 not interested, 4 report
        final java.util.ArrayList<Integer> actions = new java.util.ArrayList<>();
        actions.add(0);
        if (own) {
            actions.add(1);
        } else {
            actions.add(2);
            if (notInterested) {
                actions.add(3);
            }
            actions.add(4);
        }
        final int[] labels = {R.string.NexPostCopyLink, R.string.NexPostDelete, R.string.NexTipMenu, R.string.NexPostNotInterested, R.string.NexPostReport};
        final int[] drawables = {R.drawable.msg_link, R.drawable.msg_delete, R.drawable.nex_ic_sparkle, R.drawable.msg_archive_hide, R.drawable.msg_report};
        final CharSequence[] items = new CharSequence[actions.size()];
        final int[] icons = new int[actions.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = LocaleController.getString(labels[actions.get(i)]);
            icons[i] = drawables[actions.get(i)];
        }
        final BottomSheet.Builder builder = new BottomSheet.Builder(context);
        builder.setItems(items, icons, (dialog, which) -> {
            switch (actions.get(which)) {
                case 0:
                    AndroidUtilities.addToClipboard(status.shareUrl());
                    Toast.makeText(context, LocaleController.getString(R.string.NexPostLinkCopied), Toast.LENGTH_SHORT).show();
                    break;
                case 1:
                    confirmDelete(fragment, tgUserId, status, listener);
                    break;
                case 2:
                    NexWalletUi.gift(fragment, 0, status.authorUsername, true);
                    break;
                case 3:
                    listener.onNotInterested(status);
                    Toast.makeText(context, LocaleController.getString(R.string.NexPostNotInterestedDone), Toast.LENGTH_SHORT).show();
                    break;
                default:
                    showReport(fragment, tgUserId, "post", status.statusId);
                    break;
            }
        });
        final BottomSheet sheet = builder.create();
        fragment.showDialog(sheet);
        if (own) {
            sheet.setItemColor(1, NexLentaColors.ERROR, NexLentaColors.ERROR);
        }
    }

    private static void confirmDelete(BaseFragment fragment, long tgUserId, PixelfedStatus status, Listener listener) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(context)
            .setTitle(LocaleController.getString(R.string.NexPostDeleteTitle))
            .setMessage(LocaleController.getString(R.string.NexPostDeleteText))
            .setPositiveButton(LocaleController.getString(R.string.NexPostDelete), (d, w) -> CoreClient.EXECUTOR.execute(() -> {
                boolean ok = false;
                try {
                    PixelfedClient.deleteStatusBlocking(tgUserId, status.statusId);
                    ok = true;
                } catch (Exception ignore) {
                }
                final boolean success = ok;
                AndroidUtilities.runOnUIThread(() -> {
                    if (success) {
                        listener.onDeleted(status);
                    }
                    Toast.makeText(context, LocaleController.getString(success ? R.string.NexPostDeleted : R.string.NexActionFailed), Toast.LENGTH_SHORT).show();
                });
            }))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create();
        fragment.showDialog(dialog);
        final android.view.View button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
        if (button instanceof android.widget.TextView) {
            ((android.widget.TextView) button).setTextColor(NexLentaColors.ERROR);
        }
    }

    // objectType "post" or "user"
    static void showReport(BaseFragment fragment, long tgUserId, String objectType, String objectId) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final CharSequence[] items = new CharSequence[REPORT_LABELS.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = LocaleController.getString(REPORT_LABELS[i]);
        }
        final BottomSheet.Builder builder = new BottomSheet.Builder(context);
        builder.setTitle(LocaleController.getString(R.string.NexReportTitle));
        builder.setItems(items, (dialog, which) -> CoreClient.EXECUTOR.execute(() -> {
            boolean ok = false;
            try {
                PixelfedClient.reportBlocking(tgUserId, objectType, objectId, PixelfedClient.REPORT_TYPES[which]);
                ok = true;
            } catch (Exception ignore) {
            }
            final boolean success = ok;
            AndroidUtilities.runOnUIThread(() -> Toast.makeText(context,
                LocaleController.getString(success ? R.string.NexReportSent : R.string.NexActionFailed), Toast.LENGTH_SHORT).show());
        }));
        fragment.showDialog(builder.create());
    }

    // Telegram's own share sheet (search, chats, comment, multi-select) with the post link
    // Send to Telegram chats: the post's photos / video themselves (an album when several), with the author and the
    // link as the caption; a post without media goes as its link
    static void share(BaseFragment fragment, PixelfedStatus status) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final String link = status.shareUrl();
        if (status.media.isEmpty()) {
            fragment.showDialog(ShareAlert.createShareAlert(context, null, link, false, link, false));
            return;
        }
        final android.os.Bundle args = new android.os.Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        final DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((dialogs, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty()) {
                return false;
            }
            final java.util.ArrayList<Long> targets = new java.util.ArrayList<>();
            for (org.telegram.messenger.MessagesStorage.TopicKey key : dids) {
                targets.add(key.dialogId);
            }
            NexLentaShare.send(fragment.getCurrentAccount(), status, targets);
            dialogs.finishFragment();
            return true;
        });
        fragment.presentFragment(picker);
    }

    // Optimistic save (bookmark): UI at once, rolled back on error
    static void toggleBookmark(Context context, long tgUserId, PixelfedStatus status, Listener listener) {
        if (status.bookmarkPending) {
            return;
        }
        final boolean bookmark = !status.bookmarked;
        status.bookmarkPending = true;
        status.bookmarked = bookmark;
        listener.onChanged(status);
        CoreClient.EXECUTOR.execute(() -> {
            boolean ok = false;
            try {
                PixelfedClient.setBookmarkBlocking(tgUserId, status.statusId, bookmark);
                ok = true;
            } catch (Exception ignore) {
            }
            final boolean success = ok;
            AndroidUtilities.runOnUIThread(() -> {
                status.bookmarkPending = false;
                if (!success) {
                    status.bookmarked = !bookmark;
                    listener.onChanged(status);
                    if (context != null) {
                        Toast.makeText(context, LocaleController.getString(R.string.NexActionFailed), Toast.LENGTH_SHORT).show();
                    }
                } else if (context != null && bookmark) {
                    Toast.makeText(context, LocaleController.getString(R.string.NexPostSaved), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // Repost to your followers (optimistic, rolled back on error)
    static void toggleRepost(Context context, long tgUserId, PixelfedStatus status, Listener listener) {
        if (status.reblogPending) {
            return;
        }
        final boolean reblog = !status.reblogged;
        status.reblogPending = true;
        status.reblogged = reblog;
        status.reblogsCount = Math.max(0, status.reblogsCount + (reblog ? 1 : -1));
        listener.onChanged(status);
        CoreClient.EXECUTOR.execute(() -> {
            boolean ok = false;
            try {
                PixelfedClient.setReblogBlocking(tgUserId, status.statusId, reblog);
                ok = true;
            } catch (Exception ignore) {
            }
            final boolean success = ok;
            AndroidUtilities.runOnUIThread(() -> {
                status.reblogPending = false;
                if (!success) {
                    status.reblogged = !reblog;
                    status.reblogsCount = Math.max(0, status.reblogsCount + (reblog ? -1 : 1));
                    listener.onChanged(status);
                    if (context != null) {
                        Toast.makeText(context, LocaleController.getString(R.string.NexActionFailed), Toast.LENGTH_SHORT).show();
                    }
                } else if (context != null && reblog) {
                    Toast.makeText(context, LocaleController.getString(R.string.NexPostReposted), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // ✦ under a post: NEX explains / translates the caption (same sheet as for a chat message)
    static void askAi(BaseFragment fragment, PixelfedStatus status) {
        if (fragment == null || status == null) {
            return;
        }
        final String caption = NexCommentsActivity.htmlToText(status.contentHtml).trim();
        if (caption.isEmpty()) {
            Toast.makeText(fragment.getParentActivity(), LocaleController.getString(R.string.NexAiNoCaption), Toast.LENGTH_SHORT).show();
            return;
        }
        NexAiUi.withConsent(fragment, () -> NexAiSheet.forMessage(fragment, caption, null));
    }
}
