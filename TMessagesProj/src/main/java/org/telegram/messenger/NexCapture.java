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
final class NexCapture {

    private NexCapture() {
    }

    // dialogId: -channelId for a channel / supergroup, 0 for users and basic groups (ids unique per account)
    static void deleted(MessagesStorage storage, int account, long dialogId, ArrayList<Integer> mids) {
        if (mids == null || mids.isEmpty() || !NexSaved.keepDeleted() || !premium(account)) {
            return;
        }
        final ArrayList<Integer> ids = new ArrayList<>(mids);
        final long savedAt = System.currentTimeMillis();
        storage.getStorageQueue().postRunnable(() -> {
            SQLiteCursor cursor = null;
            try {
                final String in = TextUtils.join(",", ids);
                cursor = storage.getDatabase().queryFinalized(dialogId != 0
                    ? String.format(Locale.US, "SELECT uid, data, mid FROM messages_v2 WHERE mid IN(%s) AND uid = %d", in, dialogId)
                    : String.format(Locale.US, "SELECT uid, data, mid FROM messages_v2 WHERE mid IN(%s) AND is_channel = 0", in));
                final long self = UserConfig.getInstance(account).getClientUserId();
                while (cursor.next()) {
                    final long uid = cursor.longValue(0);
                    final TLRPC.Message m = read(cursor);
                    if (m == null || m.out || uid == self || m instanceof TLRPC.TL_messageService || selfDestructs(m)) {
                        continue;
                    }
                    final String text = describe(m);
                    if (!text.isEmpty()) {
                        NexSaved.add(new NexSaved.Entry(NexSaved.DELETED, account, uid, cursor.intValue(2), from(m, uid), m.date, savedAt, text, null));
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
