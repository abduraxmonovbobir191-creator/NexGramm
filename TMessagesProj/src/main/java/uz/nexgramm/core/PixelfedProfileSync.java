package uz.nexgramm.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashSet;

// NexGramm: copies the Telegram name and profile photo to the Pixelfed profile ONCE per Telegram user
// (POST /api/v1/accounts/update_credentials). Each part is written only until it succeeded, so later
// changes made in the Lenta are never overwritten. A photo that is not in Telegram's cache yet is tried
// again on a later Lenta open. Names, files and tokens are never logged.
public final class PixelfedProfileSync {

    private static final String PREFS = "nexgramm_lenta";
    private static final int AVATAR_SIDE = 400;
    private static final HashSet<Long> running = new HashSet<>();

    private PixelfedProfileSync() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // Call on the UI thread after the Lenta is connected; the network part runs on CoreClient.EXECUTOR
    public static void syncOnce(int account) {
        final TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        if (user == null) {
            return;
        }
        final long tgUserId = user.id;
        final String nameKey = "name_synced_" + tgUserId;
        final String avatarKey = "avatar_synced_" + tgUserId;
        final boolean nameDone = prefs().getBoolean(nameKey, false);
        final boolean avatarDone = prefs().getBoolean(avatarKey, false);
        if (nameDone && avatarDone) {
            return;
        }
        synchronized (running) {
            if (!running.add(tgUserId)) {
                return;
            }
        }
        final String name = nameDone ? null : ContactsController.formatName(user.first_name, user.last_name).trim();
        final boolean noPhoto = user.photo == null || user.photo instanceof TLRPC.TL_userProfilePhotoEmpty;
        File photo = null;
        if (!avatarDone && !noPhoto) {
            final FileLoader loader = FileLoader.getInstance(account);
            photo = loader.getPathToAttach(user.photo.photo_big, true);
            if (photo == null || !photo.exists()) {
                photo = loader.getPathToAttach(user.photo.photo_small, true);
            }
            if (photo != null && !photo.exists()) {
                photo = null;
            }
        }
        final File source = photo;
        CoreClient.EXECUTOR.execute(() -> {
            File jpeg = null;
            try {
                if (PixelfedClient.getTokens(tgUserId) == null) {
                    return;
                }
                if (source != null) {
                    jpeg = toJpeg(source);
                }
                final String sendName = name == null || name.isEmpty() ? null : name;
                if (sendName != null || jpeg != null) {
                    PixelfedClient.updateCredentialsBlocking(tgUserId, sendName, jpeg);
                }
                final SharedPreferences.Editor editor = prefs().edit();
                if (!nameDone) {
                    editor.putBoolean(nameKey, true);
                }
                if (!avatarDone && (jpeg != null || noPhoto)) {
                    editor.putBoolean(avatarKey, true);
                }
                editor.apply();
            } catch (Throwable ignore) {
                // tried again on the next Lenta open
            } finally {
                if (jpeg != null) {
                    //noinspection ResultOfMethodCallIgnored
                    jpeg.delete();
                }
                synchronized (running) {
                    running.remove(tgUserId);
                }
            }
        });
    }

    // ≤ 400 px JPEG well under Pixelfed's avatar limit (2 MB by default)
    private static File toJpeg(File source) {
        try {
            final Bitmap bitmap = ImageLoader.loadBitmap(source.getAbsolutePath(), null, AVATAR_SIDE, AVATAR_SIDE, true);
            if (bitmap == null) {
                return null;
            }
            final File out = new File(ApplicationLoader.applicationContext.getCacheDir(), "lenta_avatar_" + System.nanoTime() + ".jpg");
            try (OutputStream os = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, os);
            } finally {
                bitmap.recycle();
            }
            return out;
        } catch (Throwable e) {
            return null;
        }
    }
}
