package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.VideoPlayer;

import java.util.ArrayList;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.PixelfedClient;
import uz.nexgramm.core.PixelfedStories;

// NexGramm: full-screen Lenta stories, as in Instagram: segments on top, the author and the time, tap on the
// right = next, on the left = back, hold = pause; after the last story of an author the next author starts.
// Photos show for their duration (5 s by default), videos play to their end with sound. Own stories have a
// delete button. Seen stories are reported with POST /api/v1.1/stories/seen.
public class NexStoryViewerActivity extends BaseFragment {

    private static final long PHOTO_MS = 5000;

    private final ArrayList<PixelfedStories.Group> groups;
    private int groupIndex;
    private int itemIndex;

    private FrameLayout mediaFrame;
    private BackupImageView imageView;
    private TextureView textureView;
    private VideoPlayer player;
    private int videoW;
    private int videoH;
    private SegmentsView segmentsView;
    private BackupImageView avatarView;
    private TextView nameView;
    private ImageView deleteButton;

    // timing of the current story
    private long shownAt;      // uptime when it started (or resumed) counting
    private long elapsedMs;    // counted before the last pause
    private boolean ready;     // the photo / first video frame is on screen
    private boolean held;
    private boolean paused;

    // a photo that never loads still moves on
    private final Runnable readyFallback = this::onReady;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (fragmentView == null) {
                return;
            }
            final float p = progress();
            segmentsView.setProgress(itemIndex, p);
            if (p >= 1f) {
                next();
                return;
            }
            AndroidUtilities.runOnUIThread(this, 32);
        }
    };

    public NexStoryViewerActivity(ArrayList<PixelfedStories.Group> groups, int groupIndex) {
        this.groups = groups;
        this.groupIndex = groupIndex;
    }

    private long tgUserId() {
        return getUserConfig().getClientUserId();
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0xFF000000);

        mediaFrame = new FrameLayout(context) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                if (changed) {
                    fitTexture();
                }
            }
        };
        root.addView(mediaFrame, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        imageView = new BackupImageView(context);
        imageView.getImageReceiver().setAspectFit(true);
        imageView.getImageReceiver().setDelegate(new ImageReceiver.ImageReceiverDelegate() {
            @Override
            public void didSetImage(ImageReceiver imageReceiver, boolean set, boolean thumb, boolean memCache) {
                if (set && !thumb && !currentItem().video) {
                    onReady();
                }
            }
        });
        mediaFrame.addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // tap left / right, hold to pause
        final View touch = new View(context) {
            private long downAt;

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downAt = SystemClock.uptimeMillis();
                        setHeld(true);
                        return true;
                    case MotionEvent.ACTION_UP:
                        setHeld(false);
                        if (SystemClock.uptimeMillis() - downAt < 250) {
                            if (e.getX() < getWidth() / 3f) {
                                previous();
                            } else {
                                next();
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        setHeld(false);
                        return true;
                }
                return true;
            }
        };
        root.addView(touch, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // top: a soft shade so the white segments and names read on light photos
        final View shade = new View(context);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x66000000, 0x00000000}));
        root.addView(shade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 140, Gravity.TOP));

        final LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(AndroidUtilities.dp(10), AndroidUtilities.statusBarHeight + AndroidUtilities.dp(8), AndroidUtilities.dp(6), 0);
        segmentsView = new SegmentsView(context);
        top.addView(segmentsView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 3));
        final LinearLayout author = new LinearLayout(context);
        author.setOrientation(LinearLayout.HORIZONTAL);
        author.setGravity(Gravity.CENTER_VERTICAL);
        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(AndroidUtilities.dp(16));
        author.addView(avatarView, LayoutHelper.createLinear(32, 32));
        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(0xFFFFFFFF);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameView.setShadowLayer(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(1), 0x66000000);
        author.addView(nameView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 6, 0));
        author.setOnClickListener(v -> {
            final PixelfedStories.Group g = currentGroup();
            if (!g.accountId.isEmpty()) {
                presentFragment(new NexProfileActivity(g.self ? null : g.accountId));
            }
        });
        deleteButton = roundIcon(context, R.drawable.msg_delete);
        deleteButton.setOnClickListener(v -> confirmDelete());
        author.addView(deleteButton, LayoutHelper.createLinear(40, 40));
        final ImageView close = roundIcon(context, R.drawable.ic_close_white);
        close.setOnClickListener(v -> finishFragment());
        author.addView(close, LayoutHelper.createLinear(40, 40));
        top.addView(author, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 8, 0, 0));
        root.addView(top, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        fragmentView = root;
        if (groupIndex < 0 || groupIndex >= groups.size()) {
            groupIndex = 0;
        }
        itemIndex = groups.isEmpty() ? 0 : groups.get(groupIndex).firstUnseen();
        if (groups.isEmpty()) {
            AndroidUtilities.runOnUIThread(this::finishFragment);
        } else {
            show();
        }
        return fragmentView;
    }

    private ImageView roundIcon(Context context, int icon) {
        final ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setImageResource(icon);
        view.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN));
        view.setBackground(org.telegram.ui.ActionBar.Theme.createSelectorDrawable(0x33FFFFFF, org.telegram.ui.ActionBar.Theme.RIPPLE_MASK_CIRCLE_20DP));
        return view;
    }

    private PixelfedStories.Group currentGroup() {
        return groups.get(groupIndex);
    }

    private PixelfedStories.Item currentItem() {
        return currentGroup().items.get(itemIndex);
    }

    private void show() {
        AndroidUtilities.cancelRunOnUIThread(tick);
        AndroidUtilities.cancelRunOnUIThread(readyFallback);
        releasePlayer();
        final PixelfedStories.Group g = currentGroup();
        final PixelfedStories.Item item = currentItem();
        if (g.avatarUrl == null || g.avatarUrl.contains("/avatars/default")) {
            avatarView.setImageDrawable(NexLentaUi.initialsAvatar(g.username, true));
        } else {
            avatarView.setImage(g.avatarUrl, "64_64", NexLentaUi.initialsAvatar(g.username, true));
        }
        final String time = NexLentaUi.timeAgo(item.createdAt);
        nameView.setText(g.self ? LocaleController.getString(R.string.NexStoryYours) + "  ·  " + time : g.username + "  ·  " + time);
        deleteButton.setVisibility(g.self ? View.VISIBLE : View.GONE);
        segmentsView.setCount(g.items.size(), itemIndex);

        ready = false;
        elapsedMs = 0;
        if (item.video) {
            imageView.setImageDrawable(null);
            startPlayer(item.src);
        } else {
            imageView.setImage(item.src, null, null);
            AndroidUtilities.runOnUIThread(readyFallback, 8000);
        }
        markSeen(g, item);
        AndroidUtilities.runOnUIThread(tick, 32);
    }

    private void onReady() {
        if (!ready) {
            ready = true;
            shownAt = SystemClock.uptimeMillis();
        }
    }

    // 0..1 of the current story
    private float progress() {
        if (!ready) {
            return 0f;
        }
        final PixelfedStories.Item item = currentItem();
        if (item.video && player != null) {
            final long duration = player.getDuration();
            if (duration <= 0) {
                return 0f;
            }
            return Math.min(1f, player.getCurrentPosition() / (float) duration);
        }
        final long total = item.durationS > 0 ? item.durationS * 1000L : PHOTO_MS;
        final long elapsed = elapsedMs + (held || paused ? 0 : SystemClock.uptimeMillis() - shownAt);
        return Math.min(1f, elapsed / (float) Math.max(1000, Math.min(total, 30_000)));
    }

    private void setHeld(boolean hold) {
        if (held == hold) {
            return;
        }
        if (hold) {
            if (ready && !paused) {
                elapsedMs += SystemClock.uptimeMillis() - shownAt;
            }
            held = true;
            if (player != null) {
                player.pause();
            }
        } else {
            held = false;
            shownAt = SystemClock.uptimeMillis();
            if (player != null && !paused) {
                player.play();
            }
        }
    }

    private void next() {
        final PixelfedStories.Group g = currentGroup();
        if (itemIndex + 1 < g.items.size()) {
            itemIndex++;
            show();
        } else if (groupIndex + 1 < groups.size()) {
            groupIndex++;
            itemIndex = currentGroup().firstUnseen();
            show();
        } else {
            finishFragment();
        }
    }

    private void previous() {
        if (itemIndex > 0) {
            itemIndex--;
            show();
        } else if (groupIndex > 0) {
            groupIndex--;
            itemIndex = 0;
            show();
        } else {
            show(); // the first story starts again
        }
    }

    private void markSeen(PixelfedStories.Group g, PixelfedStories.Item item) {
        if (item.seen) {
            return;
        }
        item.seen = true;
        if (g.self || uz.nexgramm.core.NexGhost.hideStoryViews()) {
            return; // own story, or ghost mode: seen on this phone only
        }
        final long userId = tgUserId();
        final String id = item.id;
        CoreClient.EXECUTOR.execute(() -> {
            try {
                PixelfedClient.storySeenBlocking(userId, id);
            } catch (Exception ignore) {
            }
        });
    }

    private void confirmDelete() {
        if (getParentActivity() == null) {
            return;
        }
        setHeld(true);
        final AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
            .setTitle(LocaleController.getString(R.string.NexStoryDeleteTitle))
            .setMessage(LocaleController.getString(R.string.NexStoryDeleteText))
            .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> deleteCurrent())
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create();
        dialog.setOnDismissListener(d -> setHeld(false));
        showDialog(dialog);
        dialog.redPositive();
    }

    private void deleteCurrent() {
        final PixelfedStories.Group g = currentGroup();
        final PixelfedStories.Item item = currentItem();
        final long userId = tgUserId();
        CoreClient.EXECUTOR.execute(() -> {
            boolean ok = false;
            try {
                PixelfedClient.deleteStoryBlocking(userId, item.id);
                ok = true;
            } catch (Exception ignore) {
            }
            final boolean done = ok;
            AndroidUtilities.runOnUIThread(() -> {
                if (fragmentView == null) {
                    return;
                }
                if (!done) {
                    Toast.makeText(getParentActivity(), LocaleController.getString(R.string.NexActionFailed), Toast.LENGTH_SHORT).show();
                    return;
                }
                g.items.remove(item);
                if (g.items.isEmpty()) {
                    groups.remove(g);
                    if (groupIndex >= groups.size()) {
                        finishFragment();
                        return;
                    }
                    itemIndex = 0;
                } else if (itemIndex >= g.items.size()) {
                    itemIndex = g.items.size() - 1;
                }
                show();
            });
        });
    }

    // ---- video ----

    private void startPlayer(String url) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        textureView = new TextureView(context);
        mediaFrame.addView(textureView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
        videoW = 0;
        videoH = 0;
        player = new VideoPlayer();
        player.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
            @Override
            public void onStateChanged(boolean playWhenReady, int playbackState) {
            }

            @Override
            public void onError(VideoPlayer p, Exception e) {
                // nothing about the URL is logged; go on after a moment
                AndroidUtilities.runOnUIThread(() -> {
                    if (player == p) {
                        next();
                    }
                }, 1500);
            }

            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                final boolean turned = unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270;
                videoW = (int) ((turned ? height : width) * pixelWidthHeightRatio);
                videoH = turned ? width : height;
                fitTexture();
            }

            @Override
            public void onRenderedFirstFrame() {
                onReady();
            }

            @Override
            public boolean onSurfaceDestroyed(SurfaceTexture surfaceTexture) {
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
            }
        });
        player.setTextureView(textureView);
        player.preparePlayer(Uri.parse(url), "other");
        player.setLooping(false);
        if (!paused && !held) {
            player.play();
        }
    }

    private void fitTexture() {
        if (textureView == null || videoW <= 0 || videoH <= 0 || mediaFrame.getWidth() <= 0) {
            return;
        }
        final float scale = Math.min(mediaFrame.getWidth() / (float) videoW, mediaFrame.getHeight() / (float) videoH);
        final ViewGroup.LayoutParams lp = textureView.getLayoutParams();
        lp.width = (int) (videoW * scale);
        lp.height = (int) (videoH * scale);
        textureView.setLayoutParams(lp);
    }

    private void releasePlayer() {
        if (player != null) {
            player.releasePlayer(true);
            player = null;
        }
        if (textureView != null) {
            mediaFrame.removeView(textureView);
            textureView = null;
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (!paused && ready && !held) {
            elapsedMs += SystemClock.uptimeMillis() - shownAt;
        }
        paused = true;
        if (player != null) {
            player.pause();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (paused) {
            paused = false;
            shownAt = SystemClock.uptimeMillis();
            if (player != null && !held) {
                player.play();
            }
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        AndroidUtilities.cancelRunOnUIThread(tick);
        AndroidUtilities.cancelRunOnUIThread(readyFallback);
        releasePlayer();
    }

    // thin white segments, one per story of the author
    private static final class SegmentsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int count = 1;
        private int current;
        private float progress;

        SegmentsView(Context context) {
            super(context);
        }

        void setCount(int count, int current) {
            this.count = Math.max(1, count);
            this.current = current;
            this.progress = 0;
            invalidate();
        }

        void setProgress(int current, float progress) {
            this.current = current;
            this.progress = progress;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float gap = AndroidUtilities.dp(4);
            final float w = (getWidth() - gap * (count - 1)) / count;
            final float h = getHeight();
            final float r = h / 2f;
            for (int i = 0; i < count; i++) {
                final float x = i * (w + gap);
                rect.set(x, 0, x + w, h);
                paint.setColor(0x59FFFFFF);
                canvas.drawRoundRect(rect, r, r, paint);
                final float fill = i < current ? 1f : i == current ? progress : 0f;
                if (fill > 0) {
                    rect.set(x, 0, x + w * fill, h);
                    paint.setColor(0xFFFFFFFF);
                    canvas.drawRoundRect(rect, r, r, paint);
                }
            }
        }
    }
}
