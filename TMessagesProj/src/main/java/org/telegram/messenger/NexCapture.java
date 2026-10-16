package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Locale;

import uz.nexgramm.core.NexSaved;

// NexGramm: before Telegram applies a deletion or an edit from the server, the cached copy of the message is
// read (on the storage queue, so before the cache changes) and its text kept in NexSaved. Own messages,
// service messages and Saved Messages are skipped. Nothing is logged.
// With "keep deleted" on, a message someone else deletes is not removed from this phone at all: it stays in
// the chat with a 🗑 mark next to its time. An edited message shows its first text under the new one.
final class NexCapture {

    private NexCapture() {
    }

    static boolean keepInChat(int account) {
        return NexSaved.keepDeleted() && premium(account);
    }

    // Instead of Telegram's deletion: the messages of others that are in the cache stay (marked deleted), the
    // rest (own, service, self-destructing, not cached) are deleted as usual. key: as Telegram's deletedMessages
    // (0 for users and basic groups, -channelId for channels)
    static void deletedInChat(MessagesController controller, long key, ArrayList<Integer> mids) {
        if (mids == null || mids.isEmpty()) {
            return;
        }
        final int account = controller.currentAccount;
        final MessagesStorage storage = controller.getMessagesStorage();
        final ArrayList<Integer> ids = new ArrayList<>(mids);
        final long savedAt = System.currentTimeMillis();
        storage.getStorageQueue().postRunnable(() -> {
            final java.util.HashSet<Integer> kept = new java.util.HashSet<>();
            final androidx.collection.LongSparseArray<ArrayList<Integer>> keptByDialog = new androidx.collection.LongSparseArray<>();
            SQLiteCursor cursor = null;
            try {
                final String in = TextUtils.join(",", ids);
                cursor = storage.getDatabase().queryFinalized(key != 0
                    ? String.format(Locale.US, "SELECT uid, data, mid FROM messages_v2 WHERE mid IN(%s) AND uid = %d", in, key)
                    : String.format(Locale.US, "SELECT uid, data, mid FROM messages_v2 WHERE mid IN(%s) AND is_channel = 0", in));
                final long self = UserConfig.getInstance(account).getClientUserId();
                while (cursor.next()) {
                    final long uid = cursor.longValue(0);
                    final int mid = cursor.intValue(2);
                    final TLRPC.Message m = read(cursor);
                    if (m == null || m.out || uid == self || m instanceof TLRPC.TL_messageService || selfDestructs(m)) {
                        continue;
                    }
                    kept.add(mid);
                    ArrayList<Integer> list = keptByDialog.get(uid);
                    if (list == null) {
                        keptByDialog.put(uid, list = new ArrayList<>());
                    }
                    list.add(mid);
                    NexSaved.add(new NexSaved.Entry(NexSaved.DELETED, account, uid, mid, from(m, uid), m.date, savedAt, describe(m), null));
                }
            } catch (Throwable e) {
                FileLog.e(e);
                kept.clear(); // something went wrong: Telegram's own deletion
                keptByDialog.clear();
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
            final ArrayList<Integer> drop = new ArrayList<>();
            for (Integer id : ids) {
                if (!kept.contains(id)) {
                    drop.add(id);
                }
            }
            if (!drop.isEmpty()) {
                final ArrayList<Long> dialogIds = storage.markMessagesAsDeleted(key, drop, false, true, 0, 0);
                storage.updateDialogsWithDeletedMessages(key, -key, drop, dialogIds);
            }
            AndroidUtilities.runOnUIThread(() -> {
                controller.nexApplyDeleted(key, drop, ids);
                for (int i = 0; i < keptByDialog.size(); i++) {
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.nexMessagesKept, keptByDialog.keyAt(i), keptByDialog.valueAt(i), false);
                }
            });
        });
    }

    static void edited(MessagesStorage storage, int account, long dialogId, int mid, String newText) {
        if (dialogId == 0 || mid <= 0 || !NexSaved.keepEdits() || !premium(account)) {
            return;
        }
        final long savedAt = System.currentTimeMillis();
        storage.getStorageQueue().postRunnable(() -> {
            SQLiteCursor cursor = null;
            try {
                cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                    "SELECT uid, data, mid FROM messages_v2 WHERE mid = %d AND uid = %d", mid, dialogId));
                if (cursor.next()) {
                    final TLRPC.Message m = read(cursor);
                    if (m == null || m.out || dialogId == UserConfig.getInstance(account).getClientUserId()) {
                        return;
                    }
                    final String old = m.message == null ? "" : m.message;
                    final String now = newText == null ? "" : newText;
                    if (!old.isEmpty() && !old.equals(now)) { // reactions / views also come as edits: text unchanged
                        NexSaved.add(new NexSaved.Entry(NexSaved.EDITED, account, dialogId, mid, from(m, dialogId), m.date, savedAt, old, now));
                        final ArrayList<Integer> ids = new ArrayList<>();
                        ids.add(mid);
                        // the open chat shows the first text under the new one
                        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.nexMessagesKept, dialogId, ids, true));
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.dispose();
                }
            }
        });
    }

    // NexGramm Premium (keeping copies is a Premium feature)
    private static boolean premium(int account) {
        return uz.nexgramm.core.NexPremium.isActive(UserConfig.getInstance(account).getClientUserId());
    }

    // messages meant to disappear (auto-delete timer, view-once media) are never kept
    private static boolean selfDestructs(TLRPC.Message m) {
        return m.ttl_period != 0 || m.ttl != 0 || m.media != null && m.media.ttl_seconds != 0;
    }

    private static TLRPC.Message read(SQLiteCursor cursor) throws Exception {
        final NativeByteBuffer data = cursor.byteBufferValue(1);
        if (data == null) {
            return null;
        }
        final TLRPC.Message m = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
        data.reuse();
        return m;
    }

    private static long from(TLRPC.Message m, long dialogId) {
        return m.from_id != null ? DialogObject.getPeerDialogId(m.from_id) : dialogId;
    }

    // MessageObject: the text of an incoming message, with its first text under it when the sender edited it
    static CharSequence withOriginal(MessageObject object, CharSequence text) {
        if (text == null || object.messageOwner == null || object.messageOwner.out || object.scheduled || object.getId() <= 0
            || !(object.messageOwner.media == null || object.messageOwner.media instanceof TLRPC.TL_messageMediaEmpty || object.messageOwner.media instanceof TLRPC.TL_messageMediaWebPage)) {
            return text; // text messages only (a caption is laid out elsewhere)
        }
        final String original = NexSaved.originalText(object.currentAccount, object.getDialogId(), object.getId());
        if (original == null || original.isEmpty() || TextUtils.equals(original, object.messageOwner.message)) {
            return text;
        }
        final android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder(text);
        out.append("\n\n");
        final int start = out.length();
        out.append(LocaleController.getString(R.string.NexEditedBefore)).append("\n").append(original);
        out.setSpan(new android.text.style.ForegroundColorSpan(org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_chat_inTimeText)),
            start, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.ITALIC), start, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new android.text.style.RelativeSizeSpan(0.88f), start, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    // the text, with a short label for media: "📷 caption", "🎤", "📎 file.pdf"
    static String describe(TLRPC.Message m) {
        String label = "";
        final TLRPC.MessageMedia media = m.media;
        if (media instanceof TLRPC.TL_messageMediaPhoto) {
            label = "📷";
        } else if (media instanceof TLRPC.TL_messageMediaDocument && media.document != null) {
            final TLRPC.Document d = media.document;
            if (MessageObject.isVoiceDocument(d)) {
                label = "🎤";
            } else if (MessageObject.isRoundVideoDocument(d)) {
                label = "⭕";
            } else if (MessageObject.isStickerDocument(d)) {
                label = "🖼";
            } else if (MessageObject.isGifDocument(d)) {
                label = "GIF";
            } else if (MessageObject.isVideoDocument(d)) {
                label = "🎬";
            } else if (MessageObject.isMusicDocument(d)) {
                label = "🎵";
            } else {
                label = "📎 " + FileLoader.getDocumentFileName(d);
            }
        } else if (media instanceof TLRPC.TL_messageMediaGeo || media instanceof TLRPC.TL_messageMediaVenue || media instanceof TLRPC.TL_messageMediaGeoLive) {
            label = "📍";
        } else if (media instanceof TLRPC.TL_messageMediaContact) {
            label = "👤";
        } else if (media instanceof TLRPC.TL_messageMediaPoll) {
            label = "📊";
        }
        final String text = m.message == null ? "" : m.message;
        return (label + " " + text).trim();
    }
}
