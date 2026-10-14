package uz.nexgramm.core;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.messenger.video.MediaCodecVideoConvertor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

// NexGramm: media for a new Lenta post, prepared on the device before the upload (the server is small).
// Photos: at most 2048 px on the long side, JPEG. Videos: at most 60 s and 50 MB, else re-encoded with
// Telegram's own converter (MediaCodecVideoConvertor) to ≤ 720p and cut to the first 60 s. Files live in
// the app cache (lenta_upload/) and are deleted after the post. Paths and URIs are never logged.
// All methods except isVideo() do disk or codec work: call them off the UI thread.
public final class PixelfedMedia {

    public static final int MAX_IMAGES = 4;
    public static final long MAX_VIDEO_BYTES = 50L * 1024 * 1024;
    public static final long MAX_VIDEO_MS = 60_000;
    private static final int MAX_IMAGE_SIDE = 2048;
    private static final int VIDEO_LONG_SIDE = 1280;
    private static final int VIDEO_SHORT_SIDE = 720;
    private static final long VIDEO_BUDGET_BYTES = 45L * 1024 * 1024; // room for the audio track and the container
    private static final int VIDEO_MAX_BITRATE = 2_500_000;

    // Profile cover (CONTRACT covers)
    private static final int COVER_IMAGE_SIDE = 1600;
    // cover videos: the first 2..10 whole seconds (CONTRACT v1.6)
    public static final long COVER_LONG_MS = 10_000;
    public static final long COVER_SHORT_MS = 2_000;
    private static final int COVER_VIDEO_LONG_SIDE = 960;
    private static final int COVER_VIDEO_SHORT_SIDE = 540;
    private static final long COVER_VIDEO_MAX_BYTES = 6L * 1024 * 1024;
    private static final long COVER_VIDEO_BUDGET_BYTES = 5_600_000; // container overhead stays under 6 MB
    private static final int COVER_VIDEO_MAX_BITRATE = 3_000_000;

    // Stories (Pixelfed): up to 30 s, within Pixelfed's default 15 MB upload limit
    public static final long STORY_MAX_MS = 30_000;
    private static final long STORY_MAX_BYTES = 15L * 1000 * 1024;
    private static final long STORY_BUDGET_BYTES = 13L * 1024 * 1024;

    public static final String IMAGE_MIME = "image/jpeg";
    public static final String VIDEO_MIME = "video/mp4";

    // The video stays over 50 MB even after compression
    public static final class TooLargeException extends IOException {
        TooLargeException() {
            super("video_too_large");
        }
    }

    // The video could not be read or re-encoded on this device
    public static final class VideoException extends IOException {
        VideoException(String code) {
            super(code);
        }
    }

    public interface Progress {
        // 0..1, called on the worker thread
        void onProgress(float fraction);
    }

    private PixelfedMedia() {
    }

    public static boolean isVideo(Context context, Uri uri) {
        final String type = context.getContentResolver().getType(uri);
        return type != null && type.startsWith("video/");
    }

    private static File dir() throws IOException {
        final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "lenta_upload");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cache");
        }
        return dir;
    }

    private static File newFile(String ext) throws IOException {
        return new File(dir(), System.nanoTime() + "." + ext);
    }

    // Removes every prepared file (after a post or when the composer closes)
    public static void clearCache() {
        try {
            final File[] files = dir().listFiles();
            if (files != null) {
                for (File f : files) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Exception ignore) {
        }
    }

    private static File copyToCache(Uri uri, String ext) throws IOException {
        final File out = newFile(ext);
        final ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
        try (InputStream in = resolver.openInputStream(uri); OutputStream os = new FileOutputStream(out)) {
            if (in == null) {
                throw new IOException("open");
            }
            final byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                os.write(buffer, 0, n);
            }
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw e;
        }
        return out;
    }

    // Small preview for the composer (null when it cannot be decoded)
    public static Bitmap thumbnail(Uri uri, boolean video, int sizePx) {
        final Context context = ApplicationLoader.applicationContext;
        try {
            if (video) {
                final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(context, uri);
                    final Bitmap frame = retriever.getFrameAtTime(0);
                    return frame == null ? null : scaleDown(frame, sizePx);
                } finally {
                    try {
                        retriever.release();
                    } catch (Exception ignore) {
                    }
                }
            }
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) {
                sample *= 2;
            }
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, options);
            }
        } catch (Throwable e) {
            return null;
        }
    }

    private static Bitmap scaleDown(Bitmap bitmap, int sizePx) {
        final int max = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (max <= sizePx * 2) {
            return bitmap;
        }
        final float scale = sizePx * 2f / max;
        final Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, (int) (bitmap.getWidth() * scale)), Math.max(1, (int) (bitmap.getHeight() * scale)), true);
        if (scaled != bitmap) {
            bitmap.recycle();
        }
        return scaled;
    }

    // Photo → JPEG with the long side ≤ 2048 px, EXIF rotation applied (Telegram's ImageLoader.loadBitmap)
    public static File prepareImage(Uri uri) throws IOException {
        return prepareImage(uri, MAX_IMAGE_SIDE, 90);
    }

    // Profile cover photo (CONTRACT covers: ≤ 1600 px, ≤ 2 MB)
    public static File prepareCoverImage(Uri uri) throws IOException {
        return prepareImage(uri, COVER_IMAGE_SIDE, 82);
    }

    private static File prepareImage(Uri uri, int maxSide, int quality) throws IOException {
        final File copy = copyToCache(uri, "img");
        try {
            final Bitmap bitmap = ImageLoader.loadBitmap(copy.getAbsolutePath(), null, maxSide, maxSide, true);
            if (bitmap == null) {
                throw new IOException("image_decode");
            }
            final File out = newFile("jpg");
            try (OutputStream os = new FileOutputStream(out)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, os)) {
                    throw new IOException("image_encode");
                }
            } finally {
                bitmap.recycle();
            }
            return out;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
        }
    }

    private static int intMeta(MediaMetadataRetriever retriever, int key, int fallback) {
        try {
            final String value = retriever.extractMetadata(key);
            return value == null ? fallback : (int) Float.parseFloat(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static final class VideoMeta {
        long durationMs;
        int width;
        int height;
        int rotation;
        int framerate;
    }

    private static VideoMeta readMeta(File file) throws VideoException {
        final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        final VideoMeta meta = new VideoMeta();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            meta.durationMs = intMeta(retriever, MediaMetadataRetriever.METADATA_KEY_DURATION, 0);
            meta.width = intMeta(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, 0);
            meta.height = intMeta(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, 0);
            meta.rotation = intMeta(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, 0);
            meta.framerate = android.os.Build.VERSION.SDK_INT >= 23 ? intMeta(retriever, MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE, 0) : 0;
        } catch (Exception e) {
            throw new VideoException("video_read");
        } finally {
            try {
                retriever.release();
            } catch (Exception ignore) {
            }
        }
        if (meta.durationMs <= 0 || meta.width <= 0 || meta.height <= 0) {
            throw new VideoException("video_read");
        }
        return meta;
    }

    // Video → mp4 within 60 s / 50 MB. Small mp4 files are sent as they are.
    public static File prepareVideo(Uri uri, Progress progress) throws IOException {
        final File copy = copyToCache(uri, "vid");
        boolean keepCopy = false;
        try {
            final String mime = ApplicationLoader.applicationContext.getContentResolver().getType(uri);
            final VideoMeta meta = readMeta(copy);
            final boolean isMp4 = mime == null || VIDEO_MIME.equals(mime);
            if (meta.durationMs <= MAX_VIDEO_MS && copy.length() <= MAX_VIDEO_BYTES && isMp4) {
                keepCopy = true;
                return copy;
            }
            final File out = encode(copy, meta, Math.min(meta.durationMs, MAX_VIDEO_MS), VIDEO_LONG_SIDE, VIDEO_SHORT_SIDE,
                VIDEO_BUDGET_BYTES, VIDEO_MAX_BITRATE, false, progress);
            if (out.length() > MAX_VIDEO_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                throw new TooLargeException();
            }
            return out;
        } finally {
            if (!keepCopy) {
                //noinspection ResultOfMethodCallIgnored
                copy.delete();
            }
        }
    }

    // Profile cover video (CONTRACT covers): the first 6 s (4 s when the clip is 4–6 s long), muted,
    // ≤ 540p, ≤ 3 MB. Returns the file and its length in seconds (4 or 6) via durationOut[0].
    public static File prepareCoverVideo(Uri uri, int[] durationOut, Progress progress) throws IOException {
        final File copy = copyToCache(uri, "vid");
        try {
            final VideoMeta meta = readMeta(copy);
            if (meta.durationMs < COVER_SHORT_MS) {
                throw new VideoException("video_short");
            }
            final long keptMs = Math.min(COVER_LONG_MS, meta.durationMs / 1000 * 1000); // whole seconds
            final File out = encode(copy, meta, keptMs, COVER_VIDEO_LONG_SIDE, COVER_VIDEO_SHORT_SIDE,
                COVER_VIDEO_BUDGET_BYTES, COVER_VIDEO_MAX_BITRATE, true, progress);
            if (out.length() > COVER_VIDEO_MAX_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                throw new TooLargeException();
            }
            durationOut[0] = (int) (keptMs / 1000);
            return out;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
        }
    }

    // Story video (Pixelfed stories): the first 1..30 whole seconds, ≤ 720p, ≤ 15 MB (Pixelfed's default upload
    // limit). Short small mp4 files are sent as they are. Returns the length in seconds via durationOut[0].
    public static File prepareStoryVideo(Uri uri, int[] durationOut, Progress progress) throws IOException {
        final File copy = copyToCache(uri, "vid");
        boolean keepCopy = false;
        try {
            final String mime = ApplicationLoader.applicationContext.getContentResolver().getType(uri);
            final VideoMeta meta = readMeta(copy);
            final long keptMs = Math.max(1000, Math.min(STORY_MAX_MS, meta.durationMs / 1000 * 1000));
            durationOut[0] = (int) (keptMs / 1000);
            if (meta.durationMs <= STORY_MAX_MS && copy.length() <= STORY_MAX_BYTES && (mime == null || VIDEO_MIME.equals(mime))) {
                keepCopy = true;
                return copy;
            }
            final File out = encode(copy, meta, keptMs, VIDEO_LONG_SIDE, VIDEO_SHORT_SIDE, STORY_BUDGET_BYTES, VIDEO_MAX_BITRATE, false, progress);
            if (out.length() > STORY_MAX_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
                throw new TooLargeException();
            }
            return out;
        } finally {
            if (!keepCopy) {
                //noinspection ResultOfMethodCallIgnored
                copy.delete();
            }
        }
    }

    // Re-encodes the first keptMs of the clip with Telegram's MediaCodecVideoConvertor: size within
    // longSide x shortSide, bitrate within the byte budget, optionally without sound
    private static File encode(File copy, VideoMeta meta, long keptMs, int longSide, int shortSide, long budgetBytes,
                               int maxBitrate, boolean muted, Progress progress) throws IOException {
        final int width = meta.width;
        final int height = meta.height;
        final int rotation = meta.rotation;
        int framerate = meta.framerate;
        // in the original orientation; MediaController swaps for 90/270° before converting
        final float scale = Math.min(1f, Math.min(longSide / (float) Math.max(width, height), shortSide / (float) Math.min(width, height)));
        int resultWidth = Math.round(width * scale / 2) * 2;
        int resultHeight = Math.round(height * scale / 2) * 2;
        final int originalBitrate = MediaController.getVideoBitrate(copy.getAbsolutePath());
        int bitrate = MediaController.makeVideoBitrate(height, width, originalBitrate > 0 ? originalBitrate : maxBitrate, resultHeight, resultWidth);
        final int budget = (int) (budgetBytes * 8 / (keptMs / 1000.0)) - (muted ? 0 : 128_000);
        bitrate = Math.max(300_000, Math.min(Math.min(bitrate, budget), maxBitrate));
        if (framerate <= 0) {
            framerate = 30;
        } else if (framerate > 59) {
            framerate = 59;
        }
        if (rotation == 90 || rotation == 270) {
            final int t = resultWidth;
            resultWidth = resultHeight;
            resultHeight = t;
        }
        if (framerate > 40 && Math.min(resultWidth, resultHeight) <= 480) {
            framerate = 30;
        }

        final File out = newFile("mp4");
        final long endTimeUs = meta.durationMs > keptMs ? keptMs * 1000 : -1;
        final MediaController.VideoConvertorListener listener = new MediaController.VideoConvertorListener() {
            @Override
            public boolean checkConversionCanceled() {
                return false;
            }

            @Override
            public void didWriteData(long availableSize, float fraction) {
                if (progress != null && fraction >= 0) {
                    progress.onProgress(Math.min(1f, fraction > 1.5f ? fraction / 1000f : fraction));
                }
            }
        };
        final VideoEditedInfo info = new VideoEditedInfo();
        info.muted = muted;
        final MediaCodecVideoConvertor.ConvertVideoParams params = MediaCodecVideoConvertor.ConvertVideoParams.of(
            copy.getAbsolutePath(), out, 0,
            rotation, false,
            width, height,
            resultWidth, resultHeight,
            framerate, bitrate, originalBitrate,
            endTimeUs > 0 ? 0 : -1, endTimeUs, -1,
            true, keptMs,
            listener,
            info
        );
        final boolean error;
        try {
            error = new MediaCodecVideoConvertor().convertVideo(params);
        } catch (Throwable e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new VideoException("video_compress");
        }
        if (error || !out.exists() || out.length() == 0) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new VideoException("video_compress");
        }
        return out;
    }
}
