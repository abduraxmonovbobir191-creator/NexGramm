package org.telegram.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EdgeEffect;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.CubicBezierInterpolator;

// NexGramm: the motion of the NexGramm screens, iOS-like: soft ease-out curves, small springs, rubber-band
// edges. Small helpers so each screen only says what moves.
final class NexMotion {

    // fast start, long soft landing (as UIKit's default curve)
    static final Interpolator EASE = CubicBezierInterpolator.EASE_OUT_QUINT;

    private NexMotion() {
    }

    // ---- appear ----

    // fades in and rises a little; order staggers a group (0 = at once)
    static void enter(View view, int order) {
        view.animate().cancel();
        view.setAlpha(0f);
        view.setTranslationY(AndroidUtilities.dp(26));
        view.animate().alpha(1f).translationY(0).setStartDelay(Math.min(order, 6) * 55L).setDuration(460)
            .setInterpolator(EASE).setListener(null).start();
    }

    // back to rest at once (a recycled view may still be mid-animation)
    static void settle(View view) {
        view.animate().cancel();
        view.setAlpha(1f);
        view.setTranslationX(0);
        view.setTranslationY(0);
    }

    // after the next layout, the rows from firstPosition on slide in from the side (a tab change)
    static void slideRows(RecyclerView list, int firstPosition, int direction) {
        list.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                list.getViewTreeObserver().removeOnPreDrawListener(this);
                for (int i = 0; i < list.getChildCount(); i++) {
                    final View child = list.getChildAt(i);
                    if (list.getChildAdapterPosition(child) >= firstPosition) {
                        child.animate().cancel();
                        child.setAlpha(0f);
                        child.setTranslationX(AndroidUtilities.dp(32) * direction);
                        child.animate().alpha(1f).translationX(0).setStartDelay(0).setDuration(340)
                            .setInterpolator(EASE).setListener(null).start();
                    }
                }
                return true;
            }
        });
    }

    // ---- feedback ----

    // a short beat: grows a little, springs back
    static void pulse(View view) {
        view.animate().cancel();
        view.animate().scaleX(1.06f).scaleY(1.06f).setStartDelay(0).setDuration(120).setInterpolator(EASE).setListener(null)
            .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(new OvershootInterpolator(2.6f)).start())
            .start();
    }

    // "no": a quick side shake
    static void shake(View view) {
        final float d = AndroidUtilities.dp(9);
        final ObjectAnimator a = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0, d, -d, d * 0.7f, -d * 0.7f, d * 0.35f, -d * 0.35f, 0);
        a.setDuration(460);
        a.start();
    }

    // like icon: a springy pop when liked, a small dip when unliked
    static void likeBounce(View icon, boolean liked) {
        icon.animate().cancel();
        if (liked) {
            icon.setScaleX(0.55f);
            icon.setScaleY(0.55f);
            icon.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(520).setInterpolator(new OvershootInterpolator(3.2f)).setListener(null).start();
        } else {
            icon.setScaleX(0.82f);
            icon.setScaleY(0.82f);
            icon.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(300).setInterpolator(EASE).setListener(null).start();
        }
    }

    // a counter rolls to its new value: the old one slides up and fades, the new one comes up from below
    static void rollText(android.widget.TextView view, CharSequence text, boolean up) {
        if (TextUtils.equals(view.getText(), text)) {
            return;
        }
        if (!view.isAttachedToWindow() || view.getText().length() == 0) {
            view.setText(text);
            return;
        }
        final float d = AndroidUtilities.dp(8) * (up ? 1 : -1);
        view.animate().cancel();
        view.animate().translationY(-d).alpha(0f).setStartDelay(0).setDuration(110).setInterpolator(EASE).setListener(null)
            .withEndAction(() -> {
                view.setText(text);
                view.setTranslationY(d);
                view.animate().translationY(0).alpha(1f).setDuration(220).setInterpolator(EASE).start();
            })
            .start();
    }

    // repost: a half turn with a spring
    static void repostSpin(View icon, boolean on) {
        icon.animate().cancel();
        icon.setRotation(0);
        icon.setScaleX(on ? 0.7f : 0.9f);
        icon.setScaleY(on ? 0.7f : 0.9f);
        icon.animate().rotation(on ? 180f : -180f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(480)
            .setInterpolator(new OvershootInterpolator(1.6f)).setListener(null)
            .withEndAction(() -> icon.setRotation(0)).start();
    }

    // the big heart over a photo: pops with a tilt, then floats up and fades
    static void heartPop(View heart) {
        heart.animate().cancel();
        heart.setAlpha(1f);
        heart.setScaleX(0.2f);
        heart.setScaleY(0.2f);
        heart.setTranslationY(0);
        heart.setRotation((float) (Math.random() * 24 - 12));
        heart.animate().scaleX(1f).scaleY(1f).rotation(0).setStartDelay(0).setDuration(420)
            .setInterpolator(new OvershootInterpolator(2.4f)).setListener(null)
            .withEndAction(() -> heart.animate().scaleX(1.3f).scaleY(1.3f).translationY(-AndroidUtilities.dp(40)).alpha(0f)
                .setStartDelay(200).setDuration(340).setInterpolator(CubicBezierInterpolator.EASE_IN).start())
            .start();
    }

    // a slow glow loop (Premium star); the caller cancels it
    static ValueAnimator breathe(View view, float scale, float minAlpha, float maxAlpha) {
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(1700);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
        a.addUpdateListener(animation -> {
            final float v = (float) animation.getAnimatedValue();
            view.setScaleX(1f + (scale - 1f) * v);
            view.setScaleY(1f + (scale - 1f) * v);
            if (maxAlpha > 0) {
                view.setAlpha(minAlpha + (maxAlpha - minAlpha) * v);
            }
        });
        a.start();
        return a;
    }

    // ---- rubber-band edges ----

    // the list stretches past its ends and springs back, as on iOS
    static void bounce(RecyclerView list) {
        bounce(list, true);
    }

    // top = false where the top edge belongs to pull to refresh (both moving the list would fight)
    static void bounce(RecyclerView list, boolean top) {
        list.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        list.setEdgeEffectFactory(new RecyclerView.EdgeEffectFactory() {
            @NonNull
            @Override
            protected EdgeEffect createEdgeEffect(@NonNull RecyclerView view, int direction) {
                if (direction == DIRECTION_TOP && !top) {
                    return new EdgeEffect(view.getContext()) {
                        @Override
                        public boolean draw(Canvas canvas) {
                            return false; // the pull to refresh shows the edge
                        }
                    };
                }
                if (direction == DIRECTION_TOP || direction == DIRECTION_BOTTOM) {
                    return new BounceEdge(view, direction == DIRECTION_TOP ? 1f : -1f);
                }
                return super.createEdgeEffect(view, direction);
            }
        });
    }

    private static float rubber(float current, float delta, float limit) {
        return current + delta * 0.55f * (1f - Math.min(1f, Math.abs(current) / limit));
    }

    private static ValueAnimator springBack(View view, float from) {
        final ValueAnimator a = ValueAnimator.ofFloat(from, 0f);
        a.setDuration(480);
        a.setInterpolator(EASE);
        a.addUpdateListener(animation -> view.setTranslationY((float) animation.getAnimatedValue()));
        a.start();
        return a;
    }

    private static final class BounceEdge extends EdgeEffect {
        private final RecyclerView list;
        private final float sign; // +1 top edge (content moves down), -1 bottom edge
        private ValueAnimator animator;

        BounceEdge(RecyclerView list, float sign) {
            super(list.getContext());
            this.list = list;
            this.sign = sign;
        }

        private void stop() {
            if (animator != null) {
                animator.removeAllListeners();
                animator.cancel();
                animator = null;
            }
        }

        @Override
        public void onPull(float deltaDistance) {
            onPull(deltaDistance, 0.5f);
        }

        @Override
        public void onPull(float deltaDistance, float displacement) {
            stop();
            final float limit = list.getHeight() * 0.25f;
            final float current = list.getTranslationY() * sign;
            final float next = Math.max(0f, rubber(current, list.getHeight() * deltaDistance, limit));
            list.setTranslationY(next * sign);
        }

        @Override
        public void onRelease() {
            if (animator == null && list.getTranslationY() != 0) {
                animator = springBack(list, list.getTranslationY());
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        animator = null;
                    }
                });
            }
        }

        @Override
        public void onAbsorb(int velocity) {
            stop();
            final float kick = Math.min(AndroidUtilities.dp(44), Math.abs(velocity) * 0.012f) * sign;
            final ValueAnimator out = ValueAnimator.ofFloat(list.getTranslationY(), kick);
            out.setDuration(130);
            out.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            out.addUpdateListener(animation -> list.setTranslationY((float) animation.getAnimatedValue()));
            out.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animator == out) {
                        animator = springBack(list, list.getTranslationY());
                        animator.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                animator = null;
                            }
                        });
                    }
                }
            });
            animator = out;
            out.start();
        }

        @Override
        public boolean isFinished() {
            return animator == null && list.getTranslationY() == 0;
        }

        @Override
        public boolean draw(Canvas canvas) {
            return false; // the list itself moves; nothing to paint
        }
    }

    // A ScrollView whose content stretches past the ends and springs back
    static class BounceScrollView extends ScrollView {
        private float lastY;
        private float shift;      // finger movement spent on easing the stretch, hidden from ScrollView
        private boolean swallowed; // content that cannot scroll: the children got a cancel
        private ValueAnimator back;

        BounceScrollView(Context context) {
            super(context);
            setOverScrollMode(OVER_SCROLL_NEVER);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            final View child = getChildCount() > 0 ? getChildAt(0) : null;
            if (child == null) {
                return super.dispatchTouchEvent(ev);
            }
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastY = ev.getY();
                    shift = 0;
                    swallowed = false;
                    if (back != null) {
                        back.cancel();
                        back = null;
                    }
                    break;
                case MotionEvent.ACTION_MOVE: {
                    final float dy = ev.getY() - lastY;
                    lastY = ev.getY();
                    final float t = child.getTranslationY();
                    if (t != 0 || (dy > 0 && !canScrollVertically(-1)) || (dy < 0 && !canScrollVertically(1))) {
                        float next = rubber(t, dy, getHeight() * 0.3f);
                        if (t > 0 && next < 0 || t < 0 && next > 0) {
                            next = 0;
                        }
                        child.setTranslationY(next);
                        if (t != 0 && Math.signum(dy) == -Math.signum(t)) {
                            shift += dy; // going back to rest: the content must not scroll as well
                        }
                        if (next != 0 && !canScrollVertically(-1) && !canScrollVertically(1)) {
                            if (!swallowed) {
                                swallowed = true;
                                final MotionEvent cancel = MotionEvent.obtain(ev);
                                cancel.setAction(MotionEvent.ACTION_CANCEL);
                                super.dispatchTouchEvent(cancel);
                                cancel.recycle();
                            }
                            return true;
                        }
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (child.getTranslationY() != 0) {
                        back = springBack(child, child.getTranslationY());
                    }
                    if (swallowed) {
                        return true;
                    }
                    break;
            }
            if (swallowed) {
                return true;
            }
            ev.offsetLocation(0, -shift);
            final boolean handled = super.dispatchTouchEvent(ev);
            ev.offsetLocation(0, shift);
            return handled;
        }
    }

    // ---- tabs ----

    // A row of tabs with one indicator that slides between them: an underline or a soft pill behind the label
    static final class Tabs extends LinearLayout {
        static final int UNDERLINE = 0;
        static final int PILL = 1;

        private final int style;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float position = -1;
        private ValueAnimator animator;

        Tabs(Context context, int style, int color) {
            super(context);
            this.style = style;
            setOrientation(HORIZONTAL);
            setWillNotDraw(false);
            paint.setColor(color);
        }

        void setIndicatorColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        void select(int index, boolean animated) {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            if (!animated || position < 0) {
                position = index;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(position, index);
            animator.setDuration(360);
            animator.setInterpolator(EASE);
            animator.addUpdateListener(a -> {
                position = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (style == PILL) {
                drawIndicator(canvas);
                super.dispatchDraw(canvas);
            } else {
                super.dispatchDraw(canvas);
                drawIndicator(canvas);
            }
        }

        private void drawIndicator(Canvas canvas) {
            final int count = getChildCount();
            if (position < 0 || count == 0) {
                return;
            }
            final int i = Math.max(0, Math.min(count - 1, (int) Math.floor(position)));
            final float f = Math.max(0f, Math.min(1f, position - i));
            final View a = getChildAt(i);
            final View b = getChildAt(Math.min(count - 1, i + 1));
            final float left = a.getLeft() + (b.getLeft() - a.getLeft()) * f;
            final float right = a.getRight() + (b.getRight() - a.getRight()) * f;
            if (style == PILL) {
                rect.set(left, a.getTop(), right, a.getBottom());
                final float r = rect.height() / 2f;
                canvas.drawRoundRect(rect, r, r, paint);
            } else {
                final float cx = (left + right) / 2f;
                final float w = AndroidUtilities.dp(36);
                rect.set(cx - w / 2f, getHeight() - AndroidUtilities.dp(2.5f), cx + w / 2f, getHeight());
                canvas.drawRoundRect(rect, AndroidUtilities.dp(1.25f), AndroidUtilities.dp(1.25f), paint);
            }
        }
    }
}
