package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.Html;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.VideoPlayer;

import java.util.ArrayList;

import uz.nexgramm.core.PixelfedStatus;

// NexGramm: one post of the native Lenta: author, media (album pages swipe), text, likes, comments.
// Videos play only while the feed marks this cell active (muted; a tap toggles the sound).
@SuppressLint("ViewConstructor")
public class NexPostCell extends FrameLayout {

    public interface Delegate {
        void onLikeClick(NexPostCell cell, PixelfedStatus status);

        void onMediaPageChanged(NexPostCell cell);
    }

    private static final int LIKED_COLOR = 0xFFFF3B5C;
    private static final int PLACEHOLDER_COLOR = 0xFFE9E9EE;

    private final Delegate delegate;
    private final int textColor;
    private final int grayColor;
    private final LinearLayout card;
    private final BackupImageView avatarView;
    private final TextView nameView;
    private final TextView usernameView;
    private final FrameLayout mediaFrame;
    private final RecyclerView pagerView;
    private final LinearLayoutManager pagerLayout;
    private final MediaAdapter mediaAdapter = new MediaAdapter();
    private final TextView pageIndicator;
    private final TextView textView;
    private final ImageView likeIcon;
    private final TextView likeCount;
    private final TextView commentCount;
    private float mediaRatio = 1f; // height / width
    private PixelfedStatus status;
    private int currentPage;
    private boolean videoActive;

    public NexPostCell(Context context, Theme.ResourcesProvider resourcesProvider, Delegate delegate) {
        super(context);
        this.delegate = delegate;
        textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
        final int radius = AndroidUtilities.dp(20);

        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.createRoundRectDrawable(radius, Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)));
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);
        addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 6, 12, 6));

        // Author
        final LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));
        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(AndroidUtilities.dp(19));
        header.addView(avatarView, LayoutHelper.createLinear(38, 38));
        final LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(textColor);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        usernameView = new TextView(context);
        usernameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        usernameView.setTextColor(grayColor);
        usernameView.setSingleLine(true);
        usernameView.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(usernameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        header.addView(names, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 0, 0));
        card.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Media: height follows the first item's aspect ratio (clamped like Instagram, 1.91:1 … 4:5)
        mediaFrame = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                final int width = MeasureSpec.getSize(widthMeasureSpec);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((int) (width * mediaRatio), MeasureSpec.EXACTLY));
            }
        };
        mediaFrame.setBackgroundColor(PLACEHOLDER_COLOR);
        pagerView = new RecyclerView(context);
        pagerLayout = new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false);
        pagerView.setLayoutManager(pagerLayout);
        pagerView.setItemAnimator(null);
        pagerView.setAdapter(mediaAdapter);
        new PagerSnapHelper().attachToRecyclerView(pagerView);
        pagerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    return;
                }
                final int page = pagerLayout.findFirstCompletelyVisibleItemPosition();
                if (page != RecyclerView.NO_POSITION && page != currentPage) {
                    currentPage = page;
                    updatePageIndicator();
                    delegate.onMediaPageChanged(NexPostCell.this);
                }
            }
        });
        mediaFrame.addView(pagerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        pageIndicator = createPill(context);
        mediaFrame.addView(pageIndicator, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, 10, 10, 0));
        card.addView(mediaFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Text
        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        textView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        textView.setTextColor(textColor);
        textView.setMaxLines(8);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 12, 14, 0));

        // Likes and comments
        final LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(6), AndroidUtilities.dp(14), AndroidUtilities.dp(8));

        final LinearLayout likeButton = new LinearLayout(context);
        likeButton.setOrientation(LinearLayout.HORIZONTAL);
        likeButton.setGravity(Gravity.CENTER_VERTICAL);
        likeButton.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(6), AndroidUtilities.dp(8), AndroidUtilities.dp(6));
        likeButton.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(textColor, 0.08f), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        likeIcon = new ImageView(context);
        likeIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        likeButton.addView(likeIcon, LayoutHelper.createLinear(24, 24));
        likeCount = createCount(context);
        likeButton.addView(likeCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 6, 0, 0, 0));
        likeButton.setOnClickListener(v -> {
            if (status != null) {
                delegate.onLikeClick(this, status);
            }
        });
        footer.addView(likeButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        final ImageView commentIcon = new ImageView(context);
        commentIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        commentIcon.setImageResource(R.drawable.msg_msgbubble3);
        commentIcon.setColorFilter(new PorterDuffColorFilter(textColor, PorterDuff.Mode.SRC_IN));
        footer.addView(commentIcon, LayoutHelper.createLinear(24, 24, 12, 0, 0, 0));
        commentCount = createCount(context);
        footer.addView(commentCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 6, 0, 0, 0));
        card.addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private TextView createCount(Context context) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(textColor);
        return view;
    }

    static TextView createPill(Context context) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(0xFFFFFFFF);
        view.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(3), AndroidUtilities.dp(8), AndroidUtilities.dp(3));
        view.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), 0x80000000));
        return view;
    }

    public PixelfedStatus getStatus() {
        return status;
    }

    public void setStatus(PixelfedStatus status) {
        releaseVideo();
        this.status = status;
        currentPage = 0;
        videoActive = false;

        nameView.setText(status.authorName);
        usernameView.setText(TextUtils.isEmpty(status.authorUsername) ? null : "@" + status.authorUsername);
        avatarView.setImage(status.avatarUrl, "38_38", Theme.createCircleDrawable(AndroidUtilities.dp(38), PLACEHOLDER_COLOR));

        if (status.media.isEmpty()) {
            mediaFrame.setVisibility(View.GONE);
        } else {
            final PixelfedStatus.Media first = status.media.get(0);
            float ratio = first.width > 0 && first.height > 0 ? first.height / (float) first.width : 1f;
            mediaRatio = Math.max(1f / 1.91f, Math.min(1.25f, ratio));
            mediaFrame.setVisibility(View.VISIBLE);
            mediaAdapter.setItems(status.media, mediaRatio);
            pagerView.scrollToPosition(0);
            updatePageIndicator();
            mediaFrame.requestLayout();
        }

        final String text = htmlToText(status.contentHtml);
        textView.setText(text);
        textView.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        commentCount.setText(String.valueOf(status.repliesCount));
        updateLike();
    }

    public void updateLike() {
        if (status == null) {
            return;
        }
        likeIcon.setImageResource(status.favourited ? R.drawable.media_like_active : R.drawable.media_like);
        likeIcon.setColorFilter(new PorterDuffColorFilter(status.favourited ? LIKED_COLOR : textColor, PorterDuff.Mode.SRC_IN));
        likeCount.setText(String.valueOf(Math.max(0, status.favouritesCount)));
    }

    private void updatePageIndicator() {
        final int count = status == null ? 0 : status.media.size();
        pageIndicator.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
        pageIndicator.setText((currentPage + 1) + "/" + count);
    }

    @SuppressWarnings("deprecation")
    private static String htmlToText(String html) {
        if (TextUtils.isEmpty(html)) {
            return "";
        }
        final CharSequence text = Build.VERSION.SDK_INT >= 24 ? Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT) : Html.fromHtml(html);
        return text.toString().trim();
    }

    // ---- video ----

    public boolean hasVideoOnCurrentPage() {
        return status != null && currentPage < status.media.size() && status.media.get(currentPage).video;
    }

    // Share of the media area that is inside the list's visible area (0..1)
    public float getMediaVisibleFraction(RecyclerView list) {
        if (mediaFrame.getVisibility() != View.VISIBLE || mediaFrame.getHeight() <= 0) {
            return 0f;
        }
        final int top = getTop() + card.getTop() + mediaFrame.getTop();
        final int bottom = top + mediaFrame.getHeight();
        final int visible = Math.min(bottom, list.getHeight() - list.getPaddingBottom()) - Math.max(top, list.getPaddingTop());
        return Math.max(0, visible) / (float) mediaFrame.getHeight();
    }

    public void setVideoActive(boolean active) {
        videoActive = active;
        for (int i = 0; i < pagerView.getChildCount(); i++) {
            final View child = pagerView.getChildAt(i);
            if (!(child instanceof MediaPageView)) {
                continue;
            }
            final MediaPageView page = (MediaPageView) child;
            if (active && pagerLayout.getPosition(page) == currentPage) {
                page.play();
            } else {
                page.stop();
            }
        }
    }

    public void releaseVideo() {
        videoActive = false;
        for (int i = 0; i < pagerView.getChildCount(); i++) {
            final View child = pagerView.getChildAt(i);
            if (child instanceof MediaPageView) {
                ((MediaPageView) child).stop();
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        releaseVideo();
    }

    private class MediaAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final ArrayList<PixelfedStatus.Media> items = new ArrayList<>();
        private String filter = "400_400";

        void setItems(ArrayList<PixelfedStatus.Media> media, float ratio) {
            items.clear();
            items.addAll(media);
            final int widthDp = (int) (AndroidUtilities.displaySize.x / AndroidUtilities.density) - 24;
            filter = widthDp + "_" + (int) (widthDp * ratio);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final MediaPageView page = new MediaPageView(parent.getContext());
            page.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new RecyclerView.ViewHolder(page) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final MediaPageView page = (MediaPageView) holder.itemView;
            page.bind(items.get(position), filter);
            if (videoActive && position == currentPage) {
                page.play();
            }
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            ((MediaPageView) holder.itemView).stop();
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    // One album page: image, or video preview + player (Telegram's VideoPlayer, ExoPlayer inside)
    private static class MediaPageView extends FrameLayout {
        private final BackupImageView imageView;
        private final TextView muteView;
        private TextureView textureView;
        private VideoPlayer player;
        private PixelfedStatus.Media media;
        private boolean muted = true;

        MediaPageView(Context context) {
            super(context);
            imageView = new BackupImageView(context);
            addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            muteView = createPill(context);
            addView(muteView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 10, 10));
            setOnClickListener(v -> toggleMute());
        }

        void bind(PixelfedStatus.Media media, String filter) {
            stop();
            this.media = media;
            final String image = media.video ? media.previewUrl : media.url;
            imageView.setImage(image, filter, new ColorDrawable(PLACEHOLDER_COLOR));
            muteView.setVisibility(media.video ? View.VISIBLE : View.GONE);
            muteView.setText("🔇");
            setClickable(media.video);
        }

        // Muted autoplay, looping; the preview stays until the first frame is drawn
        void play() {
            if (media == null || !media.video || player != null) {
                return;
            }
            textureView = new TextureView(getContext());
            textureView.setAlpha(0f);
            addView(textureView, 1, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
            final TextureView texture = textureView;
            final VideoPlayer videoPlayer = new VideoPlayer();
            player = videoPlayer;
            videoPlayer.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                @Override
                public void onStateChanged(boolean playWhenReady, int playbackState) {
                }

                @Override
                public void onError(VideoPlayer p, Exception e) {
                    // the preview image stays; nothing about the URL is logged
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
            videoPlayer.preparePlayer(Uri.parse(media.url), "other");
            videoPlayer.setLooping(true);
            muted = true;
            videoPlayer.setMute(true);
            videoPlayer.play();
            muteView.setText("🔇");
        }

        void stop() {
            if (player != null) {
                player.releasePlayer(true);
                player = null;
            }
            if (textureView != null) {
                removeView(textureView);
                textureView = null;
            }
            muted = true;
            muteView.setText("🔇");
        }

        private void toggleMute() {
            if (player == null) {
                return;
            }
            muted = !muted;
            player.setMute(muted);
            muteView.setText(muted ? "🔇" : "🔊");
        }

        // Center-crop the video inside the page
        private void fitTexture(TextureView texture, int width, int height, int rotation, float pixelRatio) {
            if (width <= 0 || height <= 0) {
                return;
            }
            float videoW = width * pixelRatio;
            float videoH = height;
            if (rotation == 90 || rotation == 270) {
                final float t = videoW;
                videoW = videoH;
                videoH = t;
            }
            final int viewW = getWidth();
            final int viewH = getHeight();
            if (viewW <= 0 || viewH <= 0) {
                return;
            }
            final float scale = Math.max(viewW / videoW, viewH / videoH);
            final ViewGroup.LayoutParams lp = texture.getLayoutParams();
            lp.width = (int) Math.ceil(videoW * scale);
            lp.height = (int) Math.ceil(videoH * scale);
            texture.setLayoutParams(lp);
        }
    }
}
