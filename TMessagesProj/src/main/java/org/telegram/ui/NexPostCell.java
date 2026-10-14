package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
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
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.VideoPlayer;

import java.util.ArrayList;

import uz.nexgramm.core.PixelfedFormat;
import uz.nexgramm.core.PixelfedStatus;

// NexGramm: one Lenta post as in the design: rounded media (4:5 … 1:1, centre-cropped, album pages swipe,
// "1 / 4"), a frosted panel over the photo (avatar, name, place · time, "Obuna"), then likes, comments,
// send to Telegram, ✦ and save, and the caption with "ko'proq". Double tap on the photo likes it.
// Videos: the feed marks one cell PLAY (muted autoplay, tap = sound) and the next one PRELOAD (buffered,
// paused); every other cell holds no player.
@SuppressLint("ViewConstructor")
public class NexPostCell extends FrameLayout {

    public interface Delegate {
        void onLikeClick(NexPostCell cell, PixelfedStatus status);

        void onMediaPageChanged(NexPostCell cell);

        default void onCommentsClick(NexPostCell cell, PixelfedStatus status) {
        }

        default void onAuthorClick(PixelfedStatus status) {
        }

        default void onShareClick(PixelfedStatus status) {
        }

        default void onBookmarkClick(NexPostCell cell, PixelfedStatus status) {
        }

        default void onMenuClick(NexPostCell cell, PixelfedStatus status) {
        }

        default void onFollowClick(NexPostCell cell, PixelfedStatus status) {
        }

        default void onAiClick(PixelfedStatus status) {
        }

        // A video tapped: the full-screen player (X style); without one, the comments
        default void onVideoClick(NexPostCell cell, PixelfedStatus status, int page) {
            onCommentsClick(cell, status);
        }

        // Is the author followed? null = unknown or own post (no "Obuna" button)
        default Boolean isFollowing(PixelfedStatus status) {
            return null;
        }
    }

    public static final int VIDEO_OFF = 0;
    public static final int VIDEO_PRELOAD = 1;
    public static final int VIDEO_PLAY = 2;

    private static final float MIN_RATIO = 1f;    // 1:1
    private static final float MAX_RATIO = 1.25f; // 4:5

    private final Delegate delegate;
    private final FrameLayout mediaFrame;
    private final RecyclerView pagerView;
    private final LinearLayoutManager pagerLayout;
    private final MediaAdapter mediaAdapter = new MediaAdapter();
    private final TextView pageIndicator;
    private final ImageView menuButton;
    private final ImageView heartPop;
    private final BackupImageView avatarView;
    private final TextView nameView;
    private final TextView subtitleView;
    private final TextView followButton;
    private final ImageView likeIcon;
    private final TextView likeCount;
    private final TextView commentCount;
    private final ImageView bookmarkButton;
    private final TextView captionView;
    private final TextView moreView;
    private final TextView commentsLink;
    private float mediaRatio = MAX_RATIO;
    private boolean noMedia;
    private PixelfedStatus status;
    private int currentPage;
    private int videoState = VIDEO_OFF;

    public NexPostCell(Context context, Theme.ResourcesProvider resourcesProvider, Delegate delegate) {
        super(context);
        this.delegate = delegate;
        // each post is a soft rounded card (the photo clipped to its corners)
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 24));
        root.setClipToOutline(true);
        root.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(12));
        addView(root, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 10, 0, 10, 14));

        // ---- header (Instagram): photo, name + place · time, Follow, ⋯ ----
        final LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(4), 0);
        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(AndroidUtilities.dp(18));
        header.addView(avatarView, LayoutHelper.createLinear(36, 36));
        final LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(NexLentaColors.TEXT);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        header.addView(names, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 6, 0));
        followButton = new TextView(context);
        followButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        followButton.setTypeface(AndroidUtilities.bold());
        followButton.setTextColor(NexLentaColors.ACCENT);
        followButton.setGravity(Gravity.CENTER);
        followButton.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        followButton.setText(LocaleController.getString(R.string.NexPostFollow));
        followButton.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(NexLentaColors.TEXT, 0.08f), Theme.RIPPLE_MASK_ALL));
        followButton.setOnClickListener(v -> {
            if (status != null) {
                delegate.onFollowClick(this, status);
            }
        });
        header.addView(followButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36));
        menuButton = plainIcon(context, R.drawable.ic_ab_other, NexLentaColors.TEXT);
        menuButton.setRotation(90);
        menuButton.setContentDescription(LocaleController.getString(R.string.NexPostMenu));
        menuButton.setOnClickListener(v -> {
            if (status != null) {
                delegate.onMenuClick(this, status);
            }
        });
        header.addView(menuButton, LayoutHelper.createLinear(40, 40));
        final OnClickListener openAuthor = v -> {
            if (status != null) {
                delegate.onAuthorClick(status);
            }
        };
        avatarView.setOnClickListener(openAuthor);
        names.setOnClickListener(openAuthor);
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58));

        // ---- media: edge to edge ----
        mediaFrame = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                final int width = MeasureSpec.getSize(widthMeasureSpec);
                final int height = noMedia ? 0 : (int) (width * mediaRatio);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
        };
        mediaFrame.setBackgroundColor(NexLentaColors.PLACEHOLDER);
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

        pageIndicator = glassPill(context);
        mediaFrame.addView(pageIndicator, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 28, Gravity.TOP | Gravity.RIGHT, 0, 12, 12, 0));

        heartPop = new ImageView(context);
        heartPop.setImageResource(R.drawable.media_like_active);
        heartPop.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        heartPop.setScaleType(ImageView.ScaleType.FIT_CENTER);
        heartPop.setAlpha(0f);
        mediaFrame.addView(heartPop, LayoutHelper.createFrame(96, 96, Gravity.CENTER));
        root.addView(mediaFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ---- actions (X): ♥ count, 💬 count, share, ✦ ... bookmark ----
        final LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6), 0);

        final LinearLayout likeGroup = iconCount(context);
        likeIcon = new ImageView(context);
        likeIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        likeGroup.addView(likeIcon, LayoutHelper.createLinear(26, 26));
        likeCount = countText(context);
        likeGroup.addView(likeCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 6, 0, 0, 0));
        likeGroup.setOnClickListener(v -> {
            if (status != null) {
                final boolean pending = status.likePending;
                final boolean liking = !status.favourited;
                delegate.onLikeClick(this, status);
                if (!pending) {
                    NexMotion.likeBounce(likeIcon, liking);
                }
            }
        });
        actions.addView(likeGroup, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 46));

        final LinearLayout commentGroup = iconCount(context);
        final ImageView commentIcon = new ImageView(context);
        commentIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        commentIcon.setImageResource(R.drawable.msg_msgbubble3);
        commentIcon.setColorFilter(new PorterDuffColorFilter(NexLentaColors.TEXT, PorterDuff.Mode.SRC_IN));
        commentGroup.addView(commentIcon, LayoutHelper.createLinear(24, 24));
        commentCount = countText(context);
        commentGroup.addView(commentCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 6, 0, 0, 0));
        commentGroup.setOnClickListener(v -> openComments());
        actions.addView(commentGroup, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 46, 4, 0, 0, 0));

        final ImageView sendButton = plainIcon(context, R.drawable.msg_send, NexLentaColors.TEXT);
        sendButton.setContentDescription(LocaleController.getString(R.string.NexPostShare));
        sendButton.setOnClickListener(v -> {
            if (status != null) {
                delegate.onShareClick(status);
            }
        });
        actions.addView(sendButton, LayoutHelper.createLinear(44, 44, 4, 0, 0, 0));

        actions.addView(new View(context), LayoutHelper.createLinear(0, 1, 1f));

        final TextView aiButton = new TextView(context);
        aiButton.setGravity(Gravity.CENTER);
        aiButton.setText("✦");
        aiButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        aiButton.setTextColor(0xFF1F1F24);
        final GradientDrawable aiBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFC9D8F4, 0xFFE4D3F6, 0xFFF6E3D8});
        aiBg.setShape(GradientDrawable.OVAL);
        aiButton.setBackground(aiBg);
        aiButton.setOnClickListener(v -> {
            if (status != null) {
                delegate.onAiClick(status);
            }
        });
        ScaleStateListAnimator.apply(aiButton, 0.1f, 1.6f);
        actions.addView(aiButton, LayoutHelper.createLinear(32, 32, 0, 0, 6, 0));

        bookmarkButton = plainIcon(context, R.drawable.msg_saved, NexLentaColors.TEXT);
        bookmarkButton.setContentDescription(LocaleController.getString(R.string.NexPostSave));
        bookmarkButton.setOnClickListener(v -> {
            if (status != null) {
                delegate.onBookmarkClick(this, status);
            }
        });
        actions.addView(bookmarkButton, LayoutHelper.createLinear(44, 44));
        root.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, 2, 0, 0));

        // ---- caption: "name text", more…, then "View all N comments" ----
        captionView = new TextView(context);
        captionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        captionView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        captionView.setTextColor(NexLentaColors.TEXT);
        captionView.setMaxLines(2);
        captionView.setEllipsize(TextUtils.TruncateAt.END);
        captionView.setOnClickListener(v -> expandCaption());
        root.addView(captionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 0, 14, 0));
        moreView = new TextView(context);
        moreView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        moreView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        moreView.setText(LocaleController.getString(R.string.NexPostMore));
        moreView.setVisibility(View.GONE);
        moreView.setOnClickListener(v -> expandCaption());
        root.addView(moreView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 14, 2, 14, 0));
        commentsLink = new TextView(context);
        commentsLink.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        commentsLink.setTextColor(NexLentaColors.SECONDARY_TEXT);
        commentsLink.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(2));
        commentsLink.setOnClickListener(v -> openComments());
        root.addView(commentsLink, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 14, 0, 14, 0));
    }

    // Icon without a background (Instagram / X), soft press
    private static ImageView plainIcon(Context context, int icon, int color) {
        final ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setImageResource(icon);
        view.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        view.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(NexLentaColors.TEXT, 0.08f), Theme.RIPPLE_MASK_CIRCLE_20DP));
        ScaleStateListAnimator.apply(view, 0.1f, 1.6f);
        return view;
    }

    private static LinearLayout iconCount(Context context) {
        final LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setGravity(Gravity.CENTER_VERTICAL);
        group.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(10), 0);
        group.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(NexLentaColors.TEXT, 0.08f), Theme.RIPPLE_MASK_ALL));
        return group;
    }

    private static TextView countText(Context context) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(NexLentaColors.TEXT);
        return view;
    }

    static TextView glassPill(Context context) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(NexLentaColors.TEXT);
        view.setGravity(Gravity.CENTER);
        view.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        view.setBackground(NexLentaUi.glass(15));
        return view;
    }

    // small dark pill (composer thumbnails)
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

        nameView.setText(status.authorUsername.isEmpty() ? status.authorName : status.authorUsername);
        final String time = NexLentaUi.timeAgo(status.createdAt);
        subtitleView.setText(status.placeName != null ? status.placeName + " · " + time : time);
        setAvatar(avatarView, status.avatarUrl, status.authorName, "36_36");

        noMedia = status.media.isEmpty();
        if (noMedia) {
            mediaAdapter.setItems(new ArrayList<>(), 1f);
        } else {
            final PixelfedStatus.Media first = status.media.get(0);
            final float ratio = first.width > 0 && first.height > 0 ? first.height / (float) first.width : MAX_RATIO;
            mediaRatio = Math.max(MIN_RATIO, Math.min(MAX_RATIO, ratio));
            mediaAdapter.setItems(status.media, mediaRatio);
            pagerView.scrollToPosition(0);
        }
        pagerView.setVisibility(noMedia ? View.GONE : View.VISIBLE);
        mediaFrame.setVisibility(noMedia ? View.GONE : View.VISIBLE);
        updatePageIndicator();
        mediaFrame.requestLayout();

        bindCaption();
        updateCounts();
        updateFollow();
    }

    // Photo or pastel initials (Pixelfed's default avatar counts as none)
    static void setAvatar(BackupImageView view, String url, String name, String filter) {
        final android.graphics.drawable.Drawable initials = NexLentaUi.initialsAvatar(name, false);
        if (url == null || url.contains("/avatars/default")) {
            view.setImageDrawable(initials);
        } else {
            view.setImage(url, filter, initials);
        }
    }

    private void bindCaption() {
        final String text = NexCommentsActivity.htmlToText(status.contentHtml);
        if (text.isEmpty()) {
            captionView.setVisibility(View.GONE);
            moreView.setVisibility(View.GONE);
            return;
        }
        final SpannableStringBuilder sb = new SpannableStringBuilder();
        final String author = status.authorUsername.isEmpty() ? status.authorName : status.authorUsername;
        sb.append(author);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, author.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(' ').append(text);
        captionView.setVisibility(View.VISIBLE);
        captionView.setText(sb);
        captionView.setMaxLines(status.captionExpanded ? Integer.MAX_VALUE : 2);
        moreView.setVisibility(View.GONE);
        if (!status.captionExpanded) {
            final PixelfedStatus bound = status;
            captionView.post(() -> {
                if (status != bound || captionView.getLayout() == null) {
                    return;
                }
                final int lines = captionView.getLayout().getLineCount();
                final boolean cut = lines > 0 && captionView.getLayout().getEllipsisCount(lines - 1) > 0;
                moreView.setVisibility(cut ? View.VISIBLE : View.GONE);
            });
        }
    }

    private void expandCaption() {
        if (status != null && !status.captionExpanded) {
            status.captionExpanded = true;
            captionView.setMaxLines(Integer.MAX_VALUE);
            moreView.setVisibility(View.GONE);
        } else {
            openComments();
        }
    }

    public void updateLike() {
        if (status == null) {
            return;
        }
        likeIcon.setImageResource(status.favourited ? R.drawable.media_like_active : R.drawable.media_like);
        likeIcon.setColorFilter(new PorterDuffColorFilter(status.favourited ? NexLentaColors.LIKED : NexLentaColors.TEXT, PorterDuff.Mode.SRC_IN));
        likeCount.setText(PixelfedFormat.count(Math.max(0, status.favouritesCount)));
    }

    public void updateBookmark() {
        if (status == null) {
            return;
        }
        bookmarkButton.setColorFilter(new PorterDuffColorFilter(status.bookmarked ? NexLentaColors.ACCENT : NexLentaColors.TEXT, PorterDuff.Mode.SRC_IN));
    }

    public void updateFollow() {
        final Boolean following = status == null ? null : delegate.isFollowing(status);
        followButton.setVisibility(Boolean.FALSE.equals(following) ? View.VISIBLE : View.GONE);
    }

    // Counters may have changed on another screen (comments)
    public void updateCounts() {
        if (status != null) {
            commentCount.setText(PixelfedFormat.count(Math.max(0, status.repliesCount)));
            commentsLink.setVisibility(status.repliesCount > 0 ? View.VISIBLE : View.GONE);
            commentsLink.setText(LocaleController.formatString(R.string.NexPostViewComments, PixelfedFormat.count(status.repliesCount)));
            updateLike();
            updateBookmark();
        }
    }

    private void updatePageIndicator() {
        final int count = status == null ? 0 : status.media.size();
        pageIndicator.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
        pageIndicator.setText((currentPage + 1) + " / " + count);
    }

    private void openComments() {
        if (status != null) {
            delegate.onCommentsClick(this, status);
        }
    }

    private void openVideo() {
        if (status != null) {
            releaseVideo();
            delegate.onVideoClick(this, status, currentPage);
        }
    }

    private void onDoubleTapLike() {
        if (status == null) {
            return;
        }
        NexMotion.heartPop(heartPop);
        if (!status.favourited) {
            delegate.onLikeClick(this, status);
            NexMotion.likeBounce(likeIcon, true);
        }
    }

    // ---- video ----

    public boolean hasVideoOnCurrentPage() {
        return status != null && currentPage < status.media.size() && status.media.get(currentPage).video;
    }

    // Share of the media area inside the list's visible area (0..1)
    public float getMediaVisibleFraction(RecyclerView list) {
        if (mediaFrame.getHeight() <= 0) {
            return 0f;
        }
        final View root = (View) mediaFrame.getParent();
        final int top = getTop() + root.getTop() + mediaFrame.getTop();
        final int bottom = top + mediaFrame.getHeight();
        final int visible = Math.min(bottom, list.getHeight() - list.getPaddingBottom()) - Math.max(top, list.getPaddingTop());
        return Math.max(0, visible) / (float) mediaFrame.getHeight();
    }

    public void setVideoState(int state) {
        videoState = state;
        for (int i = 0; i < pagerView.getChildCount(); i++) {
            final View child = pagerView.getChildAt(i);
            if (!(child instanceof MediaPageView)) {
                continue;
            }
            final MediaPageView page = (MediaPageView) child;
            if (state != VIDEO_OFF && pagerLayout.getPosition(page) == currentPage) {
                page.prepare(state == VIDEO_PLAY);
            } else {
                page.stop();
            }
        }
    }

    // kept for callers of the phase 1 API
    public void setVideoActive(boolean active) {
        setVideoState(active ? VIDEO_PLAY : VIDEO_OFF);
    }

    public void releaseVideo() {
        setVideoState(VIDEO_OFF);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        releaseVideo();
    }

    private class MediaAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final ArrayList<PixelfedStatus.Media> items = new ArrayList<>();
        private String filter = "400_500";

        void setItems(ArrayList<PixelfedStatus.Media> media, float ratio) {
            items.clear();
            items.addAll(media);
            final int widthDp = (int) (AndroidUtilities.displaySize.x / AndroidUtilities.density) - 32;
            filter = widthDp + "_" + (int) (widthDp * ratio);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final MediaPageView page = new MediaPageView(parent.getContext(), NexPostCell.this::openComments, NexPostCell.this::openVideo, NexPostCell.this::onDoubleTapLike);
            page.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new RecyclerView.ViewHolder(page) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final MediaPageView page = (MediaPageView) holder.itemView;
            page.bind(items.get(position), filter);
            if (videoState != VIDEO_OFF && position == currentPage) {
                page.prepare(videoState == VIDEO_PLAY);
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

    // One album page: centre-cropped photo, or video preview + player (Telegram's VideoPlayer, ExoPlayer inside)
    private static class MediaPageView extends FrameLayout {
        private final BackupImageView imageView;
        private final TextView muteView;
        private final GestureDetector gestures;
        private TextureView textureView;
        private VideoPlayer player;
        private PixelfedStatus.Media media;
        private boolean muted = true;

        MediaPageView(Context context, Runnable onTap, Runnable onVideoTap, Runnable onDoubleTap) {
            super(context);
            imageView = new BackupImageView(context);
            addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            muteView = glassPill(context);
            muteView.setOnClickListener(v -> toggleMute());
            addView(muteView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 30, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 12, 12));
            gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (media != null && media.video) {
                        onVideoTap.run();
                    } else {
                        onTap.run();
                    }
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    onDoubleTap.run();
                    return true;
                }
            });
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            gestures.onTouchEvent(event);
            return true;
        }

        void bind(PixelfedStatus.Media media, String filter) {
            stop();
            this.media = media;
            final String image = media.video ? media.previewUrl : media.url;
            imageView.setImage(image, filter, new ColorDrawable(NexLentaColors.PLACEHOLDER));
            muteView.setVisibility(media.video ? View.VISIBLE : View.GONE);
            muteView.setText("🔇");
        }

        // play = muted autoplay (looping); otherwise buffer only (the next video). The preview stays until
        // the first frame is drawn.
        void prepare(boolean play) {
            if (media == null || !media.video) {
                return;
            }
            if (player == null) {
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
                videoPlayer.nexCached = true; // seen once = plays at once next time
                videoPlayer.preparePlayer(Uri.parse(media.url), "other");
                videoPlayer.setLooping(true);
                muted = true;
                videoPlayer.setMute(true);
                muteView.setText("🔇");
            }
            if (play) {
                player.play();
            } else {
                player.pause();
            }
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

        // Centre-crop the video inside the page (no black bars)
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
