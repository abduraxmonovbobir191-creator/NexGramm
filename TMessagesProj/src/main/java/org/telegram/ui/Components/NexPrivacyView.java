package org.telegram.ui.Components;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;

// NexGramm: privacy view, the software cousin of Samsung's Privacy Display, for hidden / locked chats and
// hidden accounts. The conversation sits in near darkness; only a soft reading strip is lit — where the
// finger last touched the list (by default over the newest messages) — so a glance from the side catches
// one or two lines, never the chat. The window's own brightness is lowered too (never raised), which makes
// the screen far harder to read at an angle. A quick two-finger tap on the list switches it off and on.
// No camera, no sensors: it costs one gradient draw per frame, and frames only while the strip moves.
public class NexPrivacyView extends View {

    private static final int DARK = 0xF0000000;
    private static final float WINDOW_BRIGHTNESS = 0.3f;

    private final Paint paint = new Paint();
    private final Matrix matrix = new Matrix();
    private LinearGradient gradient;
    private int gradientHeight;
    private final int[] location = new int[2];
    private final int[] limitLocation = new int[2];

    private View topLimit;
    private View bottomLimit;
    private Runnable onToggle;

    private boolean enabled;
    private float shown;      // 0..1 fade of the whole effect
    private float stripY = -1; // center of the lit strip, in this view's coordinates
    private float targetY = -1;
    private long lastFrame;

    private Window window;
    private float savedBrightness = Float.NaN;

    // two-finger tap
    private long twoDownAt;
    private float twoDownX, twoDownY;

    public NexPrivacyView(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    // the area between the bottom of topView and the top of bottomView is darkened (either may be null)
    public void setLimits(View topView, View bottomView) {
        topLimit = topView;
        bottomLimit = bottomView;
    }

    public void setOnToggle(Runnable onToggle) {
        this.onToggle = onToggle;
    }

    // follows the finger on this list without taking a single touch from it
    public void watch(RecyclerView list) {
        list.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent e) {
                onListTouch(rv, e);
                return false;
            }
        });
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (enabled) {
                    invalidate(); // panels above / below may have moved
                }
            }
        });
    }

    public boolean isOn() {
        return enabled;
    }

    public void setOn(boolean on, boolean animated) {
        if (enabled == on) {
            return;
        }
        enabled = on;
        if (!animated) {
            shown = on ? 1f : 0f;
        }
        lastFrame = 0;
        applyBrightness();
        invalidate();
    }

    // the fragment is on screen / left it: brightness is the window's, so it follows the fragment
    public void onResume(Activity activity) {
        window = activity != null ? activity.getWindow() : null;
        applyBrightness();
    }

    public void onPause() {
        restoreBrightness();
    }

    private void applyBrightness() {
        if (window == null) {
            return;
        }
        if (!enabled) {
            restoreBrightness();
            return;
        }
        try {
            final WindowManager.LayoutParams lp = window.getAttributes();
            if (Float.isNaN(savedBrightness)) {
                savedBrightness = lp.screenBrightness;
            }
            lp.screenBrightness = Math.min(currentBrightness(), WINDOW_BRIGHTNESS);
            window.setAttributes(lp);
        } catch (Exception ignore) {
        }
    }

    private void restoreBrightness() {
        if (window == null || Float.isNaN(savedBrightness)) {
            return;
        }
        try {
            final WindowManager.LayoutParams lp = window.getAttributes();
            lp.screenBrightness = savedBrightness;
            window.setAttributes(lp);
        } catch (Exception ignore) {
        }
        savedBrightness = Float.NaN;
    }

    // the brightness the user has now (0..1), so the effect only ever dims
    private float currentBrightness() {
        if (!Float.isNaN(savedBrightness) && savedBrightness >= 0) {
            return savedBrightness;
        }
        try {
            final int value = Settings.System.getInt(getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
            return Math.max(0.05f, Math.min(1f, value / 255f));
        } catch (Exception e) {
            return 1f;
        }
    }

    private void onListTouch(View list, MotionEvent e) {
        final int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() == 2) {
            twoDownAt = e.getEventTime();
            twoDownX = (e.getX(0) + e.getX(1)) / 2f;
            twoDownY = (e.getY(0) + e.getY(1)) / 2f;
        } else if (action == MotionEvent.ACTION_POINTER_UP && twoDownAt != 0 && e.getPointerCount() == 2) {
            final float dx = (e.getX(0) + e.getX(1)) / 2f - twoDownX;
            final float dy = (e.getY(0) + e.getY(1)) / 2f - twoDownY;
            if (e.getEventTime() - twoDownAt < 300 && Math.hypot(dx, dy) < AndroidUtilities.dp(24) && onToggle != null) {
                onToggle.run();
            }
            twoDownAt = 0;
        } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            twoDownAt = 0;
        }
        if (!enabled || e.getPointerCount() != 1 || action == MotionEvent.ACTION_CANCEL) {
            return;
        }
        // the list's y in this view's coordinates
        getLocationInWindow(location);
        list.getLocationInWindow(limitLocation);
        targetY = e.getY() + limitLocation[1] - location[1];
        if (stripY < 0) {
            stripY = targetY;
        }
        lastFrame = 0;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        restoreBrightness();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final long now = android.os.SystemClock.elapsedRealtime();
        final float dt = lastFrame == 0 ? 16 : Math.min(48, now - lastFrame);
        lastFrame = now;
        boolean again = false;

        final float targetShown = enabled ? 1f : 0f;
        if (shown != targetShown) {
            shown += (targetShown - shown) * Math.min(1f, 0.18f * dt / 16f);
            if (Math.abs(targetShown - shown) < 0.01f) {
                shown = targetShown;
            } else {
                again = true;
            }
        }
        if (shown <= 0f) {
            return;
        }

        getLocationInWindow(location);
        float top = 0, bottom = getHeight();
        if (topLimit != null && topLimit.getVisibility() == VISIBLE) {
            topLimit.getLocationInWindow(limitLocation);
            top = Math.max(0, limitLocation[1] + topLimit.getHeight() - location[1]);
        }
        if (bottomLimit != null && bottomLimit.isShown()) {
            bottomLimit.getLocationInWindow(limitLocation);
            bottom = Math.min(getHeight(), limitLocation[1] - location[1]);
        }
        if (bottom <= top) {
            return;
        }

        // the strip glides to the finger; at first it rests over the newest messages
        if (targetY < 0) {
            targetY = top + (bottom - top) * 0.78f;
        }
        if (stripY < 0) {
            stripY = targetY;
        }
        if (Math.abs(targetY - stripY) > 0.5f) {
            stripY += (targetY - stripY) * Math.min(1f, 0.25f * dt / 16f);
            again = true;
        } else {
            stripY = targetY;
        }
        final float half = AndroidUtilities.dp(70);
        final float center = Math.max(top + half * 0.5f, Math.min(bottom - half * 0.5f, stripY));

        if (gradient == null || gradientHeight != (int) half) {
            gradientHeight = (int) half;
            final float feather = AndroidUtilities.dp(46);
            // dark above, a soft fade in, the lit strip, a soft fade out, dark below (CLAMP keeps it dark)
            gradient = new LinearGradient(0, -half - feather, 0, half + feather,
                new int[]{DARK, 0x00000000, 0x00000000, DARK},
                new float[]{0f, feather / (2 * (half + feather)), 1f - feather / (2 * (half + feather)), 1f},
                Shader.TileMode.CLAMP);
        }
        matrix.setTranslate(0, center);
        gradient.setLocalMatrix(matrix);
        paint.setShader(gradient);
        paint.setAlpha((int) (255 * shown));
        canvas.drawRect(0, top, getWidth(), bottom, paint);

        if (again) {
            postInvalidateOnAnimation();
        }
    }
}
