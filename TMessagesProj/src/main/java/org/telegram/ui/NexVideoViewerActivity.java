package org.telegram.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.VideoPlayer;

import uz.nexgramm.core.PixelfedFormat;
import uz.nexgramm.core.PixelfedStatus;

// NexGramm: a Lenta video full screen, as in X: black, the whole video (no crop), sound on, tap = pause /
// play, a thin progress bar you can drag; author, caption and ♥ / 💬 / share at the bottom.
public class NexVideoViewerActivity extends BaseFragment {

    private final PixelfedStatus status;
    private final int page;

    private FrameLayout videoFrame;
    private TextureView textureView;
    private VideoPlayer player;
    private ImageView playIcon;
    private ImageView muteButton;
    private SeekBar seekBar;
    private ImageView likeIcon;
    private TextView likeCount;
    private TextView commentCount;
    private boolean muted;
    private boolean userSeeking;
    private int videoW;
    private int videoH;

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (player != null && seekBar != null && !userSeeking) {
                final long duration = player.getDuration();
                if (duration > 0) {
                    seekBar.setProgress((int) (1000 * player.getCurrentPosition() / duration));
                }
            }
            AndroidUtilities.runOnUIThread(this, 100);
        }
    };

    public NexVideoViewerActivity(PixelfedStatus status, int page) {
        this.status = status;
        this.page = page;
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    private long tgUserId() {
        return getUserConfig().getClientUserId();
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0xFF000000);

        videoFrame = new FrameLayout(context) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                if (changed) {
                    fitTexture();
                }
            }
        };
        videoFrame.setOnClickListener(v -> togglePlay());
        root.addView(videoFrame, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final PixelfedStatus.Media media = page < status.media.size() ? status.media.get(page) : null;
        if (media != null && media.previewUrl != null) {
            final BackupImageView preview = new BackupImageView(context);
            preview.getImageReceiver().setAspectFit(true);
            preview.setImage(media.previewUrl, null, null);
            videoFrame.addView(preview, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }

        playIcon = new ImageView(context);
        playIcon.setScaleType(ImageView.ScaleType.CENTER);
        playIcon.setImageResource(R.drawable.ic_player);
        playIcon.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(72), 0x66000000));
        playIcon.setAlpha(0f);
        root.addView(playIcon, LayoutHelper.createFrame(72, 72, Gravity.CENTER));

        // top: back, sound
        final float top = AndroidUtilities.statusBarHeight / AndroidUtilities.density + 6;
        final ImageView back = roundIcon(context, R.drawable.ic_ab_back);
        back.setOnClickListener(v -> finishFragment());
        root.addView(back, LayoutHelper.createFrame(44, 44, Gravity.TOP | Gravity.LEFT, 10, top, 0, 0));
        muteButton = roundIcon(context, R.drawable.video_mute);
        muteButton.setOnClickListener(v -> setMuted(!muted));
        root.addView(muteButton, LayoutHelper.createFrame(44, 44, Gravity.TOP | Gravity.RIGHT, 0, top, 10, 0));

        // bottom: author, caption, actions, progress
        final LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{0xCC000000, 0x00000000}));
        bottom.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(40), AndroidUtilities.dp(16), AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(12));

        final LinearLayout author = new LinearLayout(context);
        author.setOrientation(LinearLayout.HORIZONTAL);
        author.setGravity(Gravity.CENTER_VERTICAL);
        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(AndroidUtilities.dp(16));
        NexPostCell.setAvatar(avatar, status.avatarUrl, status.authorName, "32_32");
        author.addView(avatar, LayoutHelper.createLinear(32, 32));
        final TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(0xFFFFFFFF);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText(status.authorUsername.isEmpty() ? status.authorName : status.authorUsername);
        author.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 0, 0));
        author.setOnClickListener(v -> {
            if (!status.authorId.isEmpty()) {
                presentFragment(new NexProfileActivity(status.authorId));
            }
        });
        bottom.addView(author, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40));

        final String caption = NexCommentsActivity.htmlToText(status.contentHtml);
        if (!caption.isEmpty()) {
            final TextView captionView = new TextView(context);
            captionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            captionView.setTextColor(0xEEFFFFFF);
            captionView.setMaxLines(3);
            captionView.setEllipsize(TextUtils.TruncateAt.END);
            captionView.setText(caption);
            captionView.setOnClickListener(v -> captionView.setMaxLines(captionView.getMaxLines() == 3 ? Integer.MAX_VALUE : 3));
            bottom.addView(captionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        }

        final LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout like = action(context);
        likeIcon = new ImageView(context);
        likeIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        like.addView(likeIcon, LayoutHelper.createLinear(24, 24));
        likeCount = count(context);
        like.addView(likeCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 6, 0, 0, 0));
        like.setOnClickListener(v -> NexNativeFeedView.toggleLike(getParentActivity(), tgUserId(), status, this::updateCounts, () -> {
        }));
        actions.addView(like, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44));
        final LinearLayout comment = action(context);
        final ImageView commentIcon = new ImageView(context);
        commentIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        commentIcon.setImageResource(R.drawable.msg_msgbubble3);
        commentIcon.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        comment.addView(commentIcon, LayoutHelper.createLinear(22, 22));
        commentCount = count(context);
        comment.addView(commentCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 6, 0, 0, 0));
        comment.setOnClickListener(v -> presentFragment(new NexCommentsActivity(status)));
        actions.addView(comment, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44, 8, 0, 0, 0));
        final ImageView share = roundIcon(context, R.drawable.msg_send);
        share.setBackground(null);
        share.setOnClickListener(v -> NexPostActions.share(this, status));
        actions.addView(share, LayoutHelper.createLinear(44, 44, 8, 0, 0, 0));
        bottom.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, -8, 4, 0, 0));

        seekBar = new SeekBar(context);
        seekBar.setMax(1000);
        seekBar.getProgressDrawable().setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        seekBar.getThumb().setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && player != null && player.getDuration() > 0) {
                    player.seekTo(player.getDuration() * progress / 1000);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
            }
        });
        bottom.addView(seekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 32, -12, 4, -12, 0));
        root.addView(bottom, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        updateCounts();
        fragmentView = root;
        if (media != null && media.video) {
            startPlayer(context, media.url);
        }
        return fragmentView;
    }

    private static ImageView roundIcon(Context context, int icon) {
        final ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setImageResource(icon);
        view.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        view.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(44), 0x55000000));
        ScaleStateListAnimator.apply(view, 0.1f, 1.6f);
        return view;
    }

    private static LinearLayout action(Context context) {
        final LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setGravity(Gravity.CENTER_VERTICAL);
        group.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(10), 0);
        group.setBackground(Theme.createSelectorDrawable(0x33FFFFFF, Theme.RIPPLE_MASK_ALL));
        return group;
    }

    private static TextView count(Context context) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(0xFFFFFFFF);
        return view;
    }

    private void updateCounts() {
        if (likeIcon == null) {
            return;
        }
        likeIcon.setImageResource(status.favourited ? R.drawable.media_like_active : R.drawable.media_like);
        likeIcon.setColorFilter(new PorterDuffColorFilter(status.favourited ? NexLentaColors.LIKED : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        likeCount.setText(PixelfedFormat.count(Math.max(0, status.favouritesCount)));
        commentCount.setText(PixelfedFormat.count(Math.max(0, status.repliesCount)));
    }

    private void startPlayer(Context context, String url) {
        textureView = new TextureView(context);
        videoFrame.addView(textureView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
        player = new VideoPlayer();
        player.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
            @Override
            public void onStateChanged(boolean playWhenReady, int playbackState) {
            }

            @Override
            public void onError(VideoPlayer p, Exception e) {
                // the preview stays; nothing about the URL is logged
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
        player.setLooping(true);
        setMuted(false); // sound on, as when a video is opened full screen
        player.play();
        AndroidUtilities.runOnUIThread(progressTick, 100);
    }

    // The whole video, letterboxed (no crop)
    private void fitTexture() {
        if (textureView == null || videoW <= 0 || videoH <= 0 || videoFrame.getWidth() <= 0) {
            return;
        }
        final float scale = Math.min(videoFrame.getWidth() / (float) videoW, videoFrame.getHeight() / (float) videoH);
        final ViewGroup.LayoutParams lp = textureView.getLayoutParams();
        lp.width = (int) (videoW * scale);
        lp.height = (int) (videoH * scale);
        textureView.setLayoutParams(lp);
    }

    private void togglePlay() {
        if (player == null) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            // paused: the play sign stays in the middle
            playIcon.animate().cancel();
            playIcon.setScaleX(0.8f);
            playIcon.setScaleY(0.8f);
            playIcon.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start();
        } else {
            player.play();
            playIcon.animate().cancel();
            playIcon.animate().alpha(0f).setDuration(160).start();
        }
    }

    private void setMuted(boolean mute) {
        muted = mute;
        if (player != null) {
            player.setMute(mute);
        }
        if (muteButton != null) {
            muteButton.setImageResource(mute ? R.drawable.video_muted : R.drawable.video_mute);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (player != null) {
            player.pause();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (player != null) {
            player.play();
        }
        updateCounts();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        AndroidUtilities.cancelRunOnUIThread(progressTick);
        if (player != null) {
            player.releasePlayer(true);
            player = null;
        }
    }
}
