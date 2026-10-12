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
    private float mediaRatio = MAX_RATIO;
    private boolean noMedia;
    private PixelfedStatus status;
    private int currentPage;
    private int videoState = VIDEO_OFF;

    public NexPostCell(Context context, Theme.ResourcesProvider resourcesProvider, Delegate delegate) {
        super(context);
        this.delegate = delegate;
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        addView(root, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 16, 4, 16, 26));

        // ---- media ----
        mediaFrame = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                final int width = MeasureSpec.getSize(widthMeasureSpec);
                final int height = noMedia ? AndroidUtilities.dp(96) : (int) (width * mediaRatio);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
        };
        mediaFrame.setBackgroundColor(NexLentaColors.PLACEHOLDER);
        final int radius = AndroidUtilities.dp(28);
        mediaFrame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        mediaFrame.setClipToOutline(true);
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
        mediaFrame.addView(pageIndicator, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 30, Gravity.TOP | Gravity.RIGHT, 0, 16, 16, 0));

        menuButton = NexLentaUi.circleButton(context, R.drawable.ic_ab_other, NexLentaUi.PILL_CARD, true);
        menuButton.setRotation(90);
        menuButton.setContentDescription(LocaleController.getString(R.string.NexPostMenu));
        menuButton.setOnClickListener(v -> {
            if (status != null) {
                delegate.onMenuClick(this, status);
            }
        });
        mediaFrame.addView(menuButton, LayoutHelper.createFrame(34, 34, Gravity.TOP | Gravity.LEFT, 16, 14, 0, 0));

        heartPop = new ImageView(context);
        heartPop.setImageResource(R.drawable.media_like_active);
        heartPop.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        heartPop.setScaleType(ImageView.ScaleType.FIT_CENTER);
        heartPop.setAlpha(0f);
        mediaFrame.addView(heartPop, LayoutHelper.createFrame(96, 96, Gravity.CENTER));

        // frosted author panel over the photo
        final LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setBackground(NexLentaUi.glass(24));
        panel.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(AndroidUtilities.dp(23));
        final GradientDrawable avatarRing = new GradientDrawable();
        avatarRing.setShape(GradientDrawable.OVAL);
        avatarRing.setColor(NexLentaColors.CARD);
        avatarView.setBackground(avatarRing);
        avatarView.setPadding(AndroidUtilities.dp(2), AndroidUtilities.dp(2), AndroidUtilities.dp(2), AndroidUtilities.dp(2));
        panel.addView(avatarView, LayoutHelper.createLinear(50, 50));
        final LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(NexLentaColors.TEXT);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitleView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        panel.addView(names, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 8, 0));
        followButton = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexPostFollow), NexLentaUi.PILL_PRIMARY);
        followButton.setOnClickListener(v -> {
            if (status != null) {
                delegate.onFollowClick(this, status);
            }
        });
        panel.addView(followButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 46));
        final OnClickListener openAuthor = v -> {
            if (status != null) {
                delegate.onAuthorClick(status);
            }
        };
        avatarView.setOnClickListener(openAuthor);
        names.setOnClickListener(openAuthor);
        mediaFrame.addView(panel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, 14, 0, 14, 14));
        root.addView(mediaFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // ---- actions ----
        final LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        final LinearLayout likePill = actionPill(context);
        likeIcon = new ImageView(context);
        likeIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        likePill.addView(likeIcon, LayoutHelper.createLinear(22, 22));
        likeCount = countText(context);
        likePill.addView(likeCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 0, 0, 0));
        likePill.setOnClickListener(v -> {
            if (status != null) {
                delegate.onLikeClick(this, status);
            }
        });
        actions.addView(likePill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 52));

        final LinearLayout commentPill = actionPill(context);
        final ImageView commentIcon = new ImageView(context);
        commentIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        commentIcon.setImageResource(R.drawable.msg_msgbubble3);
        commentIcon.setColorFilter(new PorterDuffColorFilter(NexLentaColors.TEXT, PorterDuff.Mode.SRC_IN));
        commentPill.addView(commentIcon, LayoutHelper.createLinear(22, 22));
        commentCount = countText(context);
        commentPill.addView(commentCount, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 0, 0, 0));
        commentPill.setOnClickListener(v -> openComments());
        actions.addView(commentPill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 52, 10, 0, 0, 0));

        final ImageView sendButton = NexLentaUi.circleButton(context, R.drawable.msg_send, NexLentaUi.PILL_PRIMARY, false);
        sendButton.setContentDescription(LocaleController.getString(R.string.NexPostShare));
        sendButton.setOnClickListener(v -> {
            if (status != null) {
                delegate.onShareClick(status);
            }
        });
        actions.addView(sendButton, LayoutHelper.createLinear(52, 52, 10, 0, 0, 0));

        actions.addView(new View(context), LayoutHelper.createLinear(0, 1, 1f));

        final TextView aiButton = new TextView(context);
        aiButton.setGravity(Gravity.CENTER);
        aiButton.setText("✦");
        aiButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        aiButton.setTextColor(0xFF1F1F24);
        final GradientDrawable aiBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFC9D8F4, 0xFFE4D3F6, 0xFFF6E3D8});
        aiBg.setShape(GradientDrawable.OVAL);
        aiBg.setStroke(Math.max(1, AndroidUtilities.dp(1)), 0x99FFFFFF);
        aiButton.setBackground(aiBg);
        aiButton.setOnClickListener(v -> {
            if (status != null) {
                delegate.onAiClick(status);
            }
        });
        actions.addView(aiButton, LayoutHelper.createLinear(52, 52));

        bookmarkButton = NexLentaUi.circleButton(context, R.drawable.msg_saved, NexLentaUi.PILL_CARD, false);
        bookmarkButton.setContentDescription(LocaleController.getString(R.string.NexPostSave));
        bookmarkButton.setOnClickListener(v -> {
            if (status != null) {
                delegate.onBookmarkClick(this, status);
            }
        });
        actions.addView(bookmarkButton, LayoutHelper.createLinear(52, 52, 10, 0, 0, 0));
        root.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        // ---- caption ----
        captionView = new TextView(context);
        captionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        captionView.setLineSpacing(AndroidUtilities.dp(3), 1f);
        captionView.setTextColor(NexLentaColors.TEXT);
        captionView.setMaxLines(2);
        captionView.setEllipsize(TextUtils.TruncateAt.END);
        captionView.setOnClickListener(v -> expandCaption());
        root.addView(captionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 12, 4, 0));
        moreView = new TextView(context);
        moreView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        moreView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        moreView.setText(LocaleController.getString(R.string.NexPostMore));
        moreView.setVisibility(View.GONE);
        moreView.setOnClickListener(v -> expandCaption());
        root.addView(moreView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 2, 4, 0));
    }

    private static LinearLayout actionPill(Context context) {
        final LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(20), 0);
        pill.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(26), NexLentaColors.CARD, Theme.multAlpha(NexLentaColors.CARD, 0.8f)));
        return pill;
    }

    private static TextView countText(Context context) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
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
        setAvatar(avatarView, status.avatarUrl, status.authorName, "46_46");

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
        mediaFrame.setBackgroundColor(noMedia ? NexLentaColors.CARD : NexLentaColors.PLACEHOLDER);
        menuButton.setVisibility(noMedia ? View.GONE : View.VISIBLE);
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
        final int bg = status.bookmarked ? NexLentaColors.ACCENT : NexLentaColors.CARD;
        bookmarkButton.setBackground(Theme.createSimpleSelectorCircleDrawable(AndroidUtilities.dp(52), bg, Theme.multAlpha(bg, 0.8f)));
        bookmarkButton.setColorFilter(new PorterDuffColorFilter(status.bookmarked ? NexLentaColors.ON_ACCENT : NexLentaColors.TEXT, PorterDuff.Mode.SRC_IN));
    }

    public void updateFollow() {
        final Boolean following = status == null ? null : delegate.isFollowing(status);
        followButton.setVisibility(Boolean.FALSE.equals(following) ? View.VISIBLE : View.GONE);
    }

    // Counters may have changed on another screen (comments)
    public void updateCounts() {
        if (status != null) {
            commentCount.setText(PixelfedFormat.count(Math.max(0, status.repliesCount)));
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

    private void onDoubleTapLike() {
        if (status == null) {
            return;
        }
        heartPop.animate().cancel();
        heartPop.setScaleX(0.6f);
        heartPop.setScaleY(0.6f);
        heartPop.setAlpha(1f);
        heartPop.animate().scaleX(1.1f).scaleY(1.1f).setDuration(180).withEndAction(() ->
            heartPop.animate().alpha(0f).setStartDelay(250).setDuration(220).start()).start();
        if (!status.favourited) {
            delegate.onLikeClick(this, status);
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
            final MediaPageView page = new MediaPageView(parent.getContext(), NexPostCell.this::openComments, NexPostCell.this::onDoubleTapLike);
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

        MediaPageView(Context context, Runnable onTap, Runnable onDoubleTap) {
            super(context);
            imageView = new BackupImageView(context);
            addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            muteView = glassPill(context);
            addView(muteView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 30, Gravity.TOP | Gravity.RIGHT, 0, 54, 16, 0));
            gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    if (media != null && media.video && player != null) {
                        toggleMute();
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
