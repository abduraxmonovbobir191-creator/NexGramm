package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;

// NexGramm: the music player's progress line, One UI style. The played part is a wave that moves with the
// song itself: the player's FFT (VideoPlayer's audio visualizer, every ~64 ms, in sync with what is heard)
// gives a loudness and six frequency bands; loudness sets how tall the wave is and how fast it runs, the
// bands shape it along the line (bass at the start, treble at the end). Silence or pause lays it flat.
// accentFrom picks the cover's most vivid color, so the wave, the thumb and the play button match the song.
public class NexMusicWave {

    // ---- levels from the player, shared by every wave on screen ----

    private static final float[] bands = new float[6];
    private static volatile float level;
    private static volatile boolean playing;
    private static volatile int listeners;

    public static void onLevels(boolean isPlaying, float[] values) {
        playing = isPlaying;
        if (values == null || values.length < 7 || !isPlaying) {
            level = 0;
            for (int i = 0; i < bands.length; i++) {
                bands[i] = 0;
            }
            return;
        }
        level = Math.min(1f, values[6]);
        for (int i = 0; i < bands.length; i++) {
            bands[i] = Math.min(1f, values[i]);
        }
    }

    // the player computes the levels only while someone draws them
    public static boolean listening() {
        return listeners > 0;
    }

    // ---- one wave ----

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] shownBands = new float[6];
    private float shownLevel;
    private float phase;
    private long lastFrame;
    private boolean attached;

    public NexMusicWave() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void attach() {
        if (!attached) {
            attached = true;
            listeners++;
        }
    }

    public void detach() {
        if (attached) {
            attached = false;
            listeners = Math.max(0, listeners - 1);
        }
    }

    // draws the played part from left to right around centerY; true while it still moves (invalidate again)
    public boolean draw(Canvas canvas, float left, float right, float centerY, float lineWidth, int color, boolean active) {
        final long now = SystemClock.elapsedRealtime();
        final float dt = lastFrame == 0 ? 16 : Math.min(48, now - lastFrame);
        lastFrame = now;

        // ease toward the music: quick to rise, slower to fall, so beats pop and the wave never jitters
        final float target = active && playing ? level : 0;
        shownLevel += (target - shownLevel) * (target > shownLevel ? 0.35f : 0.12f) * (dt / 16f);
        for (int i = 0; i < shownBands.length; i++) {
            final float t = active && playing ? bands[i] : 0;
            shownBands[i] += (t - shownBands[i]) * (t > shownBands[i] ? 0.3f : 0.1f) * (dt / 16f);
        }
        // the wave keeps flowing while music plays, faster when it is loud
        if (active && playing) {
            phase += dt * (0.006f + 0.012f * shownLevel);
        }

        paint.setColor(color);
        paint.setStrokeWidth(lineWidth);
        final float width = right - left;
        final float base = AndroidUtilities.dp(1.2f) + AndroidUtilities.dp(4.5f) * shownLevel;
        if (width <= lineWidth || base < AndroidUtilities.dp(1.25f) && shownLevel < 0.02f) {
            canvas.drawLine(left, centerY, Math.max(left, right), centerY, paint);
            return active && playing;
        }

        final float wavelength = AndroidUtilities.dp(22);
        final float step = AndroidUtilities.dp(2);
        path.rewind();
        for (float x = 0; x <= width; x += step) {
            final float pos = x / width;
            // the bands spread along the line, blended between neighbours
            final float f = pos * (shownBands.length - 1);
            final int i = (int) f;
            final float band = i >= shownBands.length - 1 ? shownBands[shownBands.length - 1]
                : shownBands[i] + (shownBands[i + 1] - shownBands[i]) * (f - i);
            // the ends taper into the line, so the wave grows out of the start and into the thumb
            final float taper = Math.min(1f, Math.min(x, width - x) / AndroidUtilities.dp(10));
            final float amp = base * (0.55f + 0.45f * band) * taper;
            final float y = centerY + (float) Math.sin(x / wavelength * 2 * Math.PI - phase) * amp;
            if (x == 0) {
                path.moveTo(left, y);
            } else {
                path.lineTo(left + x, y);
            }
        }
        path.lineTo(right, centerY);
        canvas.drawPath(path, paint);
        return active && playing || shownLevel > 0.01f;
    }

    // the cover's most vivid color, made light enough for a dark background (or dark enough for a light one)
    public static int accentFrom(Bitmap bitmap, boolean dark, int fallback) {
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() < 2 || bitmap.getHeight() < 2) {
            return fallback;
        }
        final float[] hsv = new float[3];
        float bestScore = -1;
        int best = fallback;
        final int steps = 12;
        for (int i = 0; i < steps; i++) {
            for (int j = 0; j < steps; j++) {
                final int pixel = bitmap.getPixel((bitmap.getWidth() - 1) * i / (steps - 1), (bitmap.getHeight() - 1) * j / (steps - 1));
                if (Color.alpha(pixel) < 200) {
                    continue;
                }
                Color.colorToHSV(pixel, hsv);
                if (hsv[2] < 0.2f) {
                    continue; // near black says nothing about the song
                }
                final float score = hsv[1] * (float) Math.sqrt(hsv[2]);
                if (score > bestScore) {
                    bestScore = score;
                    best = pixel;
                }
            }
        }
        if (bestScore < 0) {
            return fallback;
        }
        Color.colorToHSV(best, hsv);
        if (hsv[1] < 0.12f) {
            hsv[1] = 0; // a grey cover gets a clean white / graphite line, not a muddy tint
            hsv[2] = dark ? 0.92f : 0.25f;
        } else {
            hsv[1] = Math.min(hsv[1], dark ? 0.7f : 0.85f);
            hsv[2] = dark ? Math.max(hsv[2], 0.88f) : Math.min(hsv[2], 0.6f);
        }
        return Color.HSVToColor(hsv);
    }
}
