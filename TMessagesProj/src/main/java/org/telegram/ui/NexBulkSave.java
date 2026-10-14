package org.telegram.ui;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

// NexGramm: saves the selected photos and videos of a chat to the gallery in one go. Files already on the
// phone are saved at once; the others are downloaded first (normal priority) and saved when they arrive.
final class NexBulkSave implements NotificationCenter.NotificationCenterDelegate {

    interface Done {
        void onDone(int saved, int failed);
    }

    private final int account;
    private final Utilities.Callback<MessageObject> saver; // ChatActivity.saveMessageToGallery
    private final Done done;
    private final HashMap<String, MessageObject> waiting = new HashMap<>();
    private int saved;
    private int failed;

    NexBulkSave(int account, Utilities.Callback<MessageObject> saver, Done done) {
        this.account = account;
        this.saver = saver;
        this.done = done;
    }

    static boolean canSave(MessageObject m) {
        return m != null && (m.isPhoto() || m.isVideo()) && !m.isLivePhoto() && m.getId() > 0;
    }

    void start(ArrayList<MessageObject> messages) {
        final FileLoader loader = FileLoader.getInstance(account);
        for (MessageObject m : messages) {
            if (!canSave(m)) {
                continue;
            }
            final File f = loader.getPathToMessage(m.messageOwner);
            if (f != null && f.exists()) {
                saver.run(m);
                saved++;
                continue;
            }
            final String name = m.getFileName();
            if (name == null || name.isEmpty()) {
                failed++;
                continue;
            }
            waiting.put(name, m);
            if (m.isPhoto()) {
                final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(m.photoThumbs, AndroidUtilities.getPhotoSize());
                loader.loadFile(ImageLocation.getForObject(size, m.photoThumbsObject), m, null, FileLoader.PRIORITY_NORMAL, 0);
            } else {
                loader.loadFile(m.getDocument(), m, FileLoader.PRIORITY_NORMAL, 0);
            }
        }
        if (waiting.isEmpty()) {
            finish();
            return;
        }
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoaded);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.fileLoadFailed);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        final String name = (String) args[0];
        final MessageObject m = waiting.remove(name);
        if (m == null) {
            return;
        }
        if (id == NotificationCenter.fileLoaded) {
            saver.run(m);
            saved++;
        } else {
            failed++;
        }
        if (waiting.isEmpty()) {
            NotificationCenter.getInstance(this.account).removeObserver(this, NotificationCenter.fileLoaded);
            NotificationCenter.getInstance(this.account).removeObserver(this, NotificationCenter.fileLoadFailed);
            finish();
        }
    }

    private void finish() {
        AndroidUtilities.runOnUIThread(() -> done.onDone(saved, failed));
    }
}
