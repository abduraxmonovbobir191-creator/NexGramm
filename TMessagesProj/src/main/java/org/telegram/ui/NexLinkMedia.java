package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.messenger.utils.tlutils.TLKeyboardHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_keyboard;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;

import uz.nexgramm.core.NexLinks;

// NexGramm: a message that is just an Instagram / TikTok / YouTube… link goes as the video (or photos) itself,
// with its music when the bot offers it. The link is sent to the SaveX bot in its own chat, the media it
// answers with are copied into the chat without the "forwarded" header, the music button is pressed for the
// song, and the helper messages in the bot chat are removed. If nothing comes back in time, the link is sent
// as usual. Turned off in NexGramm features → Comfort.
public final class NexLinkMedia implements NotificationCenter.NotificationCenterDelegate {

    public static final String BOT_USERNAME = "SaveXStorageBot";
    private static final long FIRST_MEDIA_TIMEOUT_MS = 60_000;
    private static final long MORE_MEDIA_MS = 2_500;   // an album comes as several messages
    private static final long MUSIC_TIMEOUT_MS = 25_000;
    private static final long CLEANUP_DELAY_MS = 20_000;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("nexgramm_extras", Context.MODE_PRIVATE);
    }

    public static boolean enabled() {
        return prefs().getBoolean("link_media", true);
    }

    public static void setEnabled(boolean value) {
        prefs().edit().putBoolean("link_media", value).apply();
    }

    // ChatActivityEnterView: should this outgoing text go as media instead?
    public static boolean shouldConvert(ChatActivity chat, CharSequence text) {
        if (chat == null || !enabled() || chat.getCurrentEncryptedChat() != null || !NexLinks.isVideoLink(text)) {
            return false;
        }
        final TLRPC.User user = chat.getCurrentUser();
        return user == null || !BOT_USERNAME.equalsIgnoreCase(org.telegram.messenger.UserObject.getPublicUsername(user));
    }

    public static void send(ChatActivity chat, long targetDialogId, String url, Runnable fallback) {
        new NexLinkMedia(chat, targetDialogId, url.trim(), fallback).start();
    }

    private final ChatActivity chat;
    private final int account;
    private final long target;
    private final String url;
    private final Runnable fallback;
    private final ArrayList<MessageObject> media = new ArrayList<>();
    private final ArrayList<Integer> cleanupIds = new ArrayList<>();
    private long botId;
    private boolean finished;
    private boolean waitingMusic;
    private MessageObject music;

    private final Runnable timeout = () -> finish();

    private NexLinkMedia(ChatActivity chat, long target, String url, Runnable fallback) {
        this.chat = chat;
        this.account = chat.getCurrentAccount();
        this.target = target;
        this.url = url;
        this.fallback = fallback;
    }

    private MessagesController controller() {
        return MessagesController.getInstance(account);
    }

    private void start() {
        BulletinFactory.of(chat).createSimpleBulletin(R.raw.forward, LocaleController.getString(R.string.NexLinkMediaPreparing)).show();
        controller().getUserNameResolver().resolve(BOT_USERNAME, peerId -> {
            final TLRPC.User bot = peerId != null && peerId > 0 ? controller().getUser(peerId) : null;
            if (bot == null || !bot.bot) {
                fail();
                return;
            }
            botId = bot.id;
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didReceiveNewMessages);
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messageReceivedByServer);
            final SendMessagesHelper helper = SendMessagesHelper.getInstance(account);
            if (controller().getDialog(botId) == null) {
                helper.sendMessage(SendMessagesHelper.SendMessageParams.of("/start", botId));
            }
            final SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(url, botId);
            params.searchLinks = false;
            helper.sendMessage(params);
            AndroidUtilities.runOnUIThread(timeout, FIRST_MEDIA_TIMEOUT_MS);
        });
    }

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (finished || acc != account) {
            return;
        }
        if (id == NotificationCenter.messageReceivedByServer) {
            // our own link / "/start" in the bot chat, now with its real id: removed at the end
            final Object msg = args.length > 2 ? args[2] : null;
            if (msg instanceof TLRPC.Message && ((TLRPC.Message) msg).dialog_id == botId && args[1] instanceof Integer) {
                cleanupIds.add((Integer) args[1]);
            }
            return;
        }
        if (id != NotificationCenter.didReceiveNewMessages || !(args[0] instanceof Long) || (Long) args[0] != botId) {
            return;
        }
        @SuppressWarnings("unchecked")
        final ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
        for (MessageObject m : messages) {
            if (m.isOut()) {
                continue;
            }
            cleanupIds.add(m.getId());
            if (!hasMedia(m)) {
                continue; // "please wait…", ads: not copied
            }
            if (waitingMusic && m.isMusic()) {
                music = m;
                AndroidUtilities.cancelRunOnUIThread(timeout);
                AndroidUtilities.runOnUIThread(timeout, 300);
                continue;
            }
            media.add(m);
            final byte[] button = musicButton(m);
            AndroidUtilities.cancelRunOnUIThread(timeout);
            if (button != null && !waitingMusic) {
                waitingMusic = true;
                pressButton(m, button);
                AndroidUtilities.runOnUIThread(timeout, MUSIC_TIMEOUT_MS);
            } else {
                AndroidUtilities.runOnUIThread(timeout, waitingMusic ? MUSIC_TIMEOUT_MS : MORE_MEDIA_MS);
            }
        }
    }

    private static boolean hasMedia(MessageObject m) {
        return m.isPhoto() || m.isVideo() || m.isGif() || m.isMusic() || m.isRoundVideo() || m.getDocument() != null;
    }

    // the callback data of the bot's music button ("Скачать песню", "♪ Musiqa"…), or null
    private static byte[] musicButton(MessageObject m) {
        if (!(m.messageOwner.reply_markup instanceof TLRPC.TL_replyInlineMarkup)) {
            return null;
        }
        for (TL_keyboard.KeyboardInlineButtonRow row : ((TLRPC.TL_replyInlineMarkup) m.messageOwner.reply_markup).rows) {
            for (TL_keyboard.KeyboardInlineButton b : row.buttons) {
                final TL_keyboard.TL_inlineButtonTypeCallback callback = TLKeyboardHelper.getType(b, TL_keyboard.TL_inlineButtonTypeCallback.class);
                if (callback != null && callback.data != null && !callback.requires_password && NexLinks.isMusicButton(b.getText())) {
                    return callback.data;
                }
            }
        }
        return null;
    }

    private void pressButton(MessageObject m, byte[] data) {
        final TLRPC.TL_messages_getBotCallbackAnswer req = new TLRPC.TL_messages_getBotCallbackAnswer();
        req.peer = controller().getInputPeer(botId);
        req.msg_id = m.getId();
        req.flags |= 1;
        req.data = data;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
        });
    }

    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        AndroidUtilities.cancelRunOnUIThread(timeout);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.messageReceivedByServer);
        if (media.isEmpty()) {
            fail();
            return;
        }
        final ArrayList<MessageObject> out = new ArrayList<>(media);
        if (music != null) {
            out.add(music);
        }
        // copied as our own messages (no "forwarded from"), the bot's caption stays
        SendMessagesHelper.getInstance(account).sendMessage(out, target, true, false, true, 0, 0);
        cleanupLater();
    }

    private void fail() {
        finished = true;
        AndroidUtilities.cancelRunOnUIThread(timeout);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.messageReceivedByServer);
        fallback.run();
        Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(R.string.NexLinkMediaFailed), Toast.LENGTH_SHORT).show();
        cleanupLater();
    }

    // the bot chat keeps nothing of this (after the copy has surely reached the server)
    private void cleanupLater() {
        if (botId == 0 || cleanupIds.isEmpty()) {
            return;
        }
        final ArrayList<Integer> ids = new ArrayList<>(cleanupIds);
        final long bot = botId;
        AndroidUtilities.runOnUIThread(() -> controller().deleteMessages(ids, null, null, bot, 0, true, 0), CLEANUP_DELAY_MS);
    }
}
