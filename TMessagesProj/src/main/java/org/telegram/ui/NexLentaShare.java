package org.telegram.ui;

import android.widget.Toast;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.PixelfedStatus;

// NexGramm: a Lenta post sent to Telegram chats as real media: its photos / video are downloaded once (https,
// at most 60 MB each) and sent like from the gallery, as an album when there are several, with "author ·
// link" as the caption. If a download fails the link is sent instead.
final class NexLentaShare {

    private static final long MAX_BYTES = 60L * 1024 * 1024;

    private NexLentaShare() {
    }

    static void send(int account, PixelfedStatus status, ArrayList<Long> targets) {
        Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(R.string.NexLentaShareSending), Toast.LENGTH_SHORT).show();
        final String caption = (status.authorUsername.isEmpty() ? status.authorName : "@" + status.authorUsername) + " · " + status.shareUrl();
        CoreClient.EXECUTOR.execute(() -> {
            final ArrayList<SendMessagesHelper.SendingMediaInfo> media = new ArrayList<>();
            for (PixelfedStatus.Media m : status.media) {
                final File file = download(m.url, m.video ? ".mp4" : ".jpg");
                if (file == null) {
                    media.clear();
                    break;
                }
                final SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
                info.path = file.getAbsolutePath();
                info.isVideo = m.video;
                media.add(info);
                if (media.size() == 10) {
                    break; // the most Telegram puts in one album
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                for (long did : targets) {
                    if (media.isEmpty()) {
                        SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(status.shareUrl(), did));
                        continue;
                    }
                    final ArrayList<SendMessagesHelper.SendingMediaInfo> copy = new ArrayList<>();
                    for (int i = 0; i < media.size(); i++) {
                        final SendMessagesHelper.SendingMediaInfo src = media.get(i);
                        final SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
                        info.path = src.path;
                        info.isVideo = src.isVideo;
                        info.caption = i == 0 ? caption : null;
                        copy.add(info);
                    }
                    SendMessagesHelper.prepareSendingMedia(AccountInstance.getInstance(account), copy, did, null, null, null, null,
                        false, copy.size() > 1, null, true, 0, 0, 0, false, null, null, 0, false, 0, 0, null);
                }
                if (media.isEmpty()) {
                    Toast.makeText(ApplicationLoader.applicationContext, LocaleController.getString(R.string.NexLentaShareLinkOnly), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // https only; the file name is the URL's hash so the same media is downloaded once
    private static File download(String url, String ext) {
        if (url == null || !url.startsWith("https://")) {
            return null;
        }
        final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "nex_share");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        final File file = new File(dir, Utilities.MD5(url) + ext);
        if (file.exists() && file.length() > 0) {
            return file;
        }
        final File part = new File(dir, file.getName() + ".part");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            if (connection.getResponseCode() != 200) {
                return null;
            }
            long total = 0;
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                final byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) {
                        return null;
                    }
                    out.write(buffer, 0, n);
                }
            }
            return total > 0 && part.renameTo(file) ? file : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (part.exists()) {
                part.delete();
            }
        }
    }
}
