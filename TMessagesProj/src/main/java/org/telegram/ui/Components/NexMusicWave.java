package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;

// NexGramm: the music player's progress line, One UI style. Above the played part a soft glowing light rises
// and breathes with the song itself: the player's FFT (VideoPlayer's audio visualizer, every ~64 ms, in sync with what is heard)
// gives a loudness and six frequency bands; loudness sets how tall the wave is and how fast it runs, the
// bands shape it along the line (bass at the start, treble at the end). Silence or pause lets it settle.
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

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private android.graphics.LinearGradient glowShader;
    private int glowColor;
    private float glowTop, glowBottom;

    // draws the played part from left to right around centerY, One UI style: the straight progress line with a
    // soft glowing "aurora" rising above it that breathes with the music; true while it still moves
    public boolean draw(Canvas canvas, float left, float right, float centerY, float lineWidth, int color, boolean active) {
        final long now = SystemClock.elapsedRealtime();
        final float dt = lastFrame == 0 ? 16 : Math.min(48, now - lastFrame);
        lastFrame = now;

        // ease toward the music: quick to rise, slower to fall, so beats pop and the light never jitters
        final float target = active && playing ? level : 0;
        shownLevel += (target - shownLevel) * (target > shownLevel ? 0.3f : 0.08f) * (dt / 16f);
        for (int i = 0; i < shownBands.length; i++) {
            final float t = active && playing ? bands[i] : 0;
            shownBands[i] += (t - shownBands[i]) * (t > shownBands[i] ? 0.25f : 0.07f) * (dt / 16f);
        }
        if (active && playing) {
            phase += dt * (0.0025f + 0.004f * shownLevel);
        }

        final float width = right - left;
        final float maxHeight = AndroidUtilities.dp(4) + AndroidUtilities.dp(13) * shownLevel;
        if (width > AndroidUtilities.dp(8) && shownLevel > 0.01f) {
            final float step = AndroidUtilities.dp(3);
            path.rewind();
            path.moveTo(left, centerY);
            for (float x = 0; x <= width; x += step) {
                final float pos = x / width;
                final float f = pos * (shownBands.length - 1);
                final int i = (int) f;
                final float band = i >= shownBands.length - 1 ? shownBands[shownBands.length - 1]
                    : shownBands[i] + (shownBands[i + 1] - shownBands[i]) * (f - i);
                // two slow swells drifting at different speeds: a liquid, never-repeating hill line
                final double a = Math.sin(x / AndroidUtilities.dp(70) * Math.PI - phase);
                final double b = Math.sin(x / AndroidUtilities.dp(31) * Math.PI + phase * 1.7);
                final float shape = (float) (0.55 + 0.3 * a + 0.15 * b);
                final float taper = Math.min(1f, Math.min(x, width - x) / AndroidUtilities.dp(18));
                final float h = maxHeight * Math.max(0f, shape) * (0.45f + 0.55f * band) * taper;
                path.lineTo(left + x, centerY - h);
            }
            path.lineTo(right, centerY);
            path.close();
            final float top = centerY - AndroidUtilities.dp(17);
            if (glowShader == null || glowColor != color || glowTop != top || glowBottom != centerY) {
                glowColor = color;
                glowTop = top;
                glowBottom = centerY;
                glowShader = new android.graphics.LinearGradient(0, top, 0, centerY,
                    (color & 0x00FFFFFF) | 0x10000000, (color & 0x00FFFFFF) | 0xA0000000, android.graphics.Shader.TileMode.CLAMP);
            }
            glowPaint.setShader(glowShader);
            canvas.drawPath(path, glowPaint);
        }

        paint.setColor(color);
        paint.setStrokeWidth(lineWidth);
        if (right > left) {
            canvas.drawLine(left, centerY, right, centerY, paint);
        }
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
