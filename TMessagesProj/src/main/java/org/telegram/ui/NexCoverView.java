package org.telegram.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.VideoPlayer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.NexCover;
import uz.nexgramm.core.NexPalette;

// NexGramm: a profile cover (CONTRACT "Covers"): default soft band in the app colors, a color, a color or
// band with an emoji pattern, a photo (centre-cropped) or a short muted looping video. Used by the
// Lenta profile and the cover editor's preview. Call release() when the screen goes away.
final class NexCoverView extends FrameLayout {

    // Pattern: x, y (0..1) and size (dp) of each glyph, fixed so a cover always looks the same
    private static final float[][] SPOTS = {
        {0.07f, 0.16f, 18}, {0.22f, 0.34f, 11}, {0.36f, 0.12f, 22}, {0.52f, 0.30f, 12}, {0.66f, 0.10f, 16},
        {0.82f, 0.26f, 20}, {0.93f, 0.08f, 12}, {0.12f, 0.58f, 13}, {0.30f, 0.66f, 20}, {0.47f, 0.52f, 15},
        {0.61f, 0.70f, 11}, {0.76f, 0.56f, 22}, {0.90f, 0.72f, 13}, {0.05f, 0.88f, 10}, {0.40f, 0.90f, 12},
        {0.70f, 0.92f, 10}
    };

    private final PatternView patternView;
    private final BackupImageView imageView;
    private TextureView textureView;
    private VideoPlayer player;
    private String videoUrl;
    private final boolean fadeBottom;

    NexCoverView(Context context, boolean fadeBottom) {
        super(context);
        this.fadeBottom = fadeBottom;
        patternView = new PatternView(context);
        addView(patternView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        imageView = new BackupImageView(context);
        imageView.getImageReceiver().setAspectFit(false);
        imageView.setVisibility(GONE);
        addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        if (fadeBottom) {
            final View fade = new View(context);
            // a long, soft fade: the background melts into the page instead of ending in a hard line
            final int bg = NexLentaColors.BACKGROUND & 0x00FFFFFF;
            fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{bg, bg, bg | 0x30000000, bg | 0x90000000, NexLentaColors.BACKGROUND}));
            addView(fade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        setClipChildren(true);
    }

    // Soft band of the default cover (lilac for "Standart", else the accent)
    static int[] defaultBand() {
        final NexPalette palette = Theme.getNexgrammPalette();
        final int tint = palette != null && !palette.standard ? palette.accent : 0xFFB48CF5;
        return new int[]{
            NexPalette.blend(NexLentaColors.BACKGROUND, tint, NexLentaColors.dark ? 0.30f : 0.26f),
            NexPalette.blend(NexLentaColors.BACKGROUND, tint, NexLentaColors.dark ? 0.14f : 0.12f),
            NexLentaColors.BACKGROUND
        };
    }

    // null = the default band
    void setCover(NexCover cover) {
        if (cover == null) {
            showPattern(null, null);
            return;
        }
        switch (cover.mode) {
            case NexCover.MODE_PHOTO:
                showPattern(null, null);
                stopVideo();
                imageView.setVisibility(VISIBLE);
                imageView.setImage(cover.url, null, null);
                break;
            case NexCover.MODE_VIDEO:
                showPattern(null, null);
                imageView.setVisibility(GONE);
                playVideo(cover.url);
                break;
            default:
                showPattern(cover.color, NexCover.MODE_COLOR.equals(cover.mode) ? null : cover.emojiGlyph);
                break;
        }
    }

    // Editor preview of a local, not yet uploaded file
    void setLocalImage(Bitmap bitmap) {
        showPattern(null, null);
        stopVideo();
        imageView.setVisibility(VISIBLE);
        imageView.setImageBitmap(bitmap);
    }

    void setLocalVideo(Uri uri) {
        showPattern(null, null);
        imageView.setVisibility(GONE);
        playVideo(uri.toString());
    }

    // color null = default band; glyph null = no pattern
    void showPattern(Integer color, String glyph) {
        stopVideo();
        imageView.setVisibility(GONE);
        imageView.setImageDrawable(null);
        patternView.set(color, glyph);
    }

    private void playVideo(String url) {
        if (url == null) {
            return;
        }
        if (player != null && url.equals(videoUrl)) {
            player.play();
            return;
        }
        stopVideo();
        videoUrl = url;
        if (url.startsWith("http")) {
            // played from the phone: downloaded once, then it loops smoothly with no traffic
            final File cached = cachedVideo(url);
            if (cached.length() > 0) {
                startPlayer(Uri.fromFile(cached));
                return;
            }
            CoreClient.EXECUTOR.execute(() -> {
                final boolean ok = download(url, cached);
                AndroidUtilities.runOnUIThread(() -> {
                    if (ok && url.equals(videoUrl) && player == null) {
                        startPlayer(Uri.fromFile(cached));
                    }
                });
            });
            return;
        }
        startPlayer(Uri.parse(url));
    }

    private static File cachedVideo(String url) {
        final File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "nex_covers");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length() + ".mp4");
    }

    // Background thread; keeps the 6 newest cover videos. Nothing about the URL is logged.
    private static boolean download(String url, File target) {
        final File part = new File(target.getPath() + ".part");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            if (connection.getResponseCode() != 200) {
                return false;
            }
            long total = 0;
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                final byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    total += read;
                    if (total > 8L * 1024 * 1024) {
                        return false;
                    }
                    out.write(buffer, 0, read);
                }
            }
            if (!part.renameTo(target)) {
                return false;
            }
            final File[] files = target.getParentFile() == null ? null : target.getParentFile().listFiles();
            if (files != null && files.length > 6) {
                Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (int i = 6; i < files.length; i++) {
                    //noinspection ResultOfMethodCallIgnored
                    files[i].delete();
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void startPlayer(Uri uri) {
        final TextureView texture = new TextureView(getContext());
        texture.setAlpha(0f);
        textureView = texture;
        addView(texture, 1, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
        final VideoPlayer videoPlayer = new VideoPlayer();
        player = videoPlayer;
        videoPlayer.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
            @Override
            public void onStateChanged(boolean playWhenReady, int playbackState) {
            }

            @Override
            public void onError(VideoPlayer p, Exception e) {
                // the band stays; nothing about the URL is logged
            }

            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                fitTexture(texture, width, height, unappliedRotationDegrees, pixelWidthHeightRatio);
            }

            @Override
            public void onRenderedFirstFrame() {
                texture.setAlpha(1f);
            }

            @Override
            public boolean onSurfaceDestroyed(SurfaceTexture surfaceTexture) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
            }
        });
        videoPlayer.setTextureView(texture);
        videoPlayer.preparePlayer(uri, "other");
        videoPlayer.setLooping(true);
        videoPlayer.setMute(true);
        videoPlayer.play();
    }

    void pause() {
        if (player != null) {
            player.pause();
        }
    }

    void resume() {
        if (player != null) {
            player.play();
        }
    }

    private void stopVideo() {
        if (player != null) {
            player.releasePlayer(true);
            player = null;
        }
        if (textureView != null) {
            removeView(textureView);
            textureView = null;
        }
        videoUrl = null;
    }

    void release() {
        stopVideo();
    }

    private void fitTexture(TextureView texture, int width, int height, int rotation, float pixelRatio) {
        if (width <= 0 || height <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        float videoW = width * pixelRatio;
        float videoH = height;
        if (rotation == 90 || rotation == 270) {
            final float t = videoW;
            videoW = videoH;
            videoH = t;
        }
        final float scale = Math.max(getWidth() / videoW, getHeight() / videoH);
        final ViewGroup.LayoutParams lp = texture.getLayoutParams();
        lp.width = (int) Math.ceil(videoW * scale);
        lp.height = (int) Math.ceil(videoH * scale);
        texture.setLayoutParams(lp);
    }

    // Background (color or the default band) and the glyph pattern in a deeper tone of it
    private final class PatternView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint glyphPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private Integer color;
        private String glyph;
        private GradientDrawable band;

        PatternView(Context context) {
            super(context);
            glyphPaint.setTextAlign(Paint.Align.CENTER);
        }

        void set(Integer color, String glyph) {
            this.color = color;
            this.glyph = glyph;
            band = color == null ? new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, defaultBand()) : null;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getWidth();
            final int h = getHeight();
            int base;
            if (color != null) {
                base = color;
                paint.setColor(base);
                canvas.drawRect(0, 0, w, h, paint);
            } else {
                if (band == null) {
                    band = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, defaultBand());
                }
                band.setBounds(0, 0, w, h);
                band.draw(canvas);
                base = defaultBand()[0];
            }
            if (glyph == null) {
                return;
            }
            // deeper tone of the background, or a light one on dark colors
            final boolean darkBase = NexPalette.onColor(base) == 0xFFFFFFFF;
            glyphPaint.setColor(darkBase ? NexPalette.blend(base, 0xFFFFFFFF, 0.28f) : NexPalette.blend(base, 0xFF4B3A8C, 0.35f));
            final float limit = fadeBottom ? 0.8f : 1f;
            for (float[] spot : SPOTS) {
                if (spot[1] > limit) {
                    continue;
                }
                glyphPaint.setTextSize(AndroidUtilities.dp(spot[2]));
                canvas.drawText(glyph, spot[0] * w, spot[1] * h + glyphPaint.getTextSize() / 3f, glyphPaint);
            }
        }
    }
}
