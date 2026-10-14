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
        final CharSequence[] items = own
            ? new CharSequence[]{LocaleController.getString(R.string.NexPostCopyLink), LocaleController.getString(R.string.NexPostDelete)}
            : new CharSequence[]{LocaleController.getString(R.string.NexPostCopyLink), LocaleController.getString(R.string.NexPostReport)};
        final int[] icons = own ? new int[]{R.drawable.msg_link, R.drawable.msg_delete} : new int[]{R.drawable.msg_link, R.drawable.msg_report};
        final BottomSheet.Builder builder = new BottomSheet.Builder(context);
        builder.setItems(items, icons, (dialog, which) -> {
            if (which == 0) {
                AndroidUtilities.addToClipboard(status.shareUrl());
                Toast.makeText(context, LocaleController.getString(R.string.NexPostLinkCopied), Toast.LENGTH_SHORT).show();
            } else if (own) {
                confirmDelete(fragment, tgUserId, status, listener);
            } else {
                showReport(fragment, tgUserId, "post", status.statusId);
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
    static void share(BaseFragment fragment, PixelfedStatus status) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final String link = status.shareUrl();
        fragment.showDialog(ShareAlert.createShareAlert(context, null, link, false, link, false));
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
