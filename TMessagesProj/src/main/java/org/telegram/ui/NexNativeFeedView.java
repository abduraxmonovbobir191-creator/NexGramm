package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.util.ArrayList;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.PixelfedClient;
import uz.nexgramm.core.PixelfedStatus;

// NexGramm: native Lenta on the Pixelfed Mastodon API with the app's own token.
// Home mode: home timeline (max_id paging); empty home → local public timeline under a soft notice;
// "+" opens the composer. Account mode (profile screen): that account's posts under a header view.
// Pull down at the top to refresh. Likes are optimistic and roll back on error. One video at a time
// plays: the most visible one, muted. Always the app's light palette (NexLentaColors).
@SuppressLint("ViewConstructor")
public class NexNativeFeedView extends FrameLayout implements NexPostCell.Delegate {

    public interface Delegate {
        // The Pixelfed token is gone or rejected: connect again (OAuth)
        void onFeedNotConnected();

        default void onComposeClick() {
        }

        default void onOpenComments(PixelfedStatus status) {
        }

        default void onOpenProfile(String accountId) {
        }
    }

    private static final int TYPE_POST = 0;
    private static final int TYPE_NOTICE = 1;
    private static final int TYPE_SKELETON = 2;
    private static final int TYPE_FOOTER = 3;
    private static final int TYPE_HEADER = 4;
    private static final int SKELETON_COUNT = 3;

    private final long tgUserId;
    private final String accountId; // null = home timeline
    private final Theme.ResourcesProvider resourcesProvider;
    private final Delegate delegate;
    private final RecyclerView listView;
    private final LinearLayoutManager layoutManager;
    private final Adapter adapter = new Adapter();
    private final LinearLayout errorCard;
    private final TextView errorReasonView;
    private final FrameLayout refreshIndicator;
    private final ImageView composeButton;
    private View headerView;

    private final ArrayList<PixelfedStatus> items = new ArrayList<>();
    private boolean publicMode;     // home was empty: local public timeline
    private boolean firstLoaded;
    private boolean firstFailed;
    private boolean loading;
    private boolean hasMore = true;
    private boolean loadMoreFailed;
    private String maxId;
    private int generation;
    private boolean paused = true;
    // A post just made here: kept on top until the timeline itself returns it
    private PixelfedStatus pendingTop;

    // pull to refresh
    private final int touchSlop;
    private int topInset;
    private float downX;
    private float downY;
    private boolean pulling;
    private boolean refreshing;
    private float pullDistance;

    public NexNativeFeedView(Context context, long tgUserId, String accountId, Theme.ResourcesProvider resourcesProvider, Delegate delegate) {
        super(context);
        this.tgUserId = tgUserId;
        this.accountId = accountId;
        this.resourcesProvider = resourcesProvider;
        this.delegate = delegate;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setBackgroundColor(NexLentaColors.BACKGROUND);

        listView = new RecyclerView(context);
        layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
        listView.setLayoutManager(layoutManager);
        listView.setItemAnimator(null);
        listView.setClipToPadding(false);
        listView.setAdapter(adapter);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                checkLoadMore();
                updateActiveVideo();
            }
        });
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final int textColor = NexLentaColors.TEXT;
        errorCard = new LinearLayout(context);
        errorCard.setOrientation(LinearLayout.VERTICAL);
        errorCard.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(20));
        errorCard.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24), NexLentaColors.CARD));
        final TextView errorTitle = new TextView(context);
        errorTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        errorTitle.setTextColor(NexLentaColors.SECONDARY_TEXT);
        errorTitle.setText(LocaleController.getString(R.string.NexFeedLoadError));
        errorCard.addView(errorTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        errorReasonView = new TextView(context);
        errorReasonView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        errorReasonView.setTypeface(AndroidUtilities.bold());
        errorReasonView.setTextColor(textColor);
        errorCard.addView(errorReasonView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));
        final TextView retryButton = new TextView(context);
        retryButton.setGravity(Gravity.CENTER);
        retryButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        retryButton.setTypeface(AndroidUtilities.bold());
        retryButton.setTextColor(NexLentaColors.CARD);
        retryButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(24), textColor, Theme.multAlpha(textColor, 0.8f)));
        retryButton.setText(LocaleController.getString(R.string.NexFeedLoginRetry));
        retryButton.setOnClickListener(v -> reload());
        errorCard.addView(retryButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 20, 0, 0));
        errorCard.setVisibility(View.GONE);
        addView(errorCard, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 20, 0, 20, 0));

        // Pull-to-refresh spinner: a white circle that follows the finger
        refreshIndicator = new FrameLayout(context);
        refreshIndicator.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(40), NexLentaColors.CARD));
        refreshIndicator.setElevation(AndroidUtilities.dp(3));
        final RadialProgressView refreshProgress = new RadialProgressView(context);
        refreshProgress.setSize(AndroidUtilities.dp(20));
        refreshProgress.setProgressColor(NexLentaColors.ACCENT);
        refreshIndicator.addView(refreshProgress, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        refreshIndicator.setAlpha(0f);
        refreshIndicator.setVisibility(View.GONE);
        addView(refreshIndicator, LayoutHelper.createFrame(40, 40, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        // "+" (home only): new post
        composeButton = new ImageView(context);
        composeButton.setScaleType(ImageView.ScaleType.CENTER);
        composeButton.setImageResource(R.drawable.msg_add);
        composeButton.setColorFilter(new PorterDuffColorFilter(NexLentaColors.CARD, PorterDuff.Mode.SRC_IN));
        composeButton.setBackground(Theme.createSimpleSelectorCircleDrawable(AndroidUtilities.dp(56), NexLentaColors.ACCENT, Theme.multAlpha(NexLentaColors.ACCENT, 0.85f)));
        composeButton.setElevation(AndroidUtilities.dp(4));
        composeButton.setContentDescription(LocaleController.getString(R.string.NexComposeTitle));
        composeButton.setOnClickListener(v -> delegate.onComposeClick());
        composeButton.setVisibility(accountId == null ? View.VISIBLE : View.GONE);
        addView(composeButton, LayoutHelper.createFrame(56, 56, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 16, 16));
    }

    public void setInsets(int top, int bottom) {
        topInset = top;
        listView.setPadding(0, top + AndroidUtilities.dp(6), 0, bottom + AndroidUtilities.dp(accountId == null ? 80 : 6));
        errorCard.setTranslationY((top - bottom) / 2f);
        composeButton.setTranslationY(-bottom);
        if (!refreshing && !pulling) {
            refreshIndicator.setTranslationY(top - AndroidUtilities.dp(48));
        }
    }

    // Profile header (account mode): shown above the posts
    public void setHeaderView(View view) {
        headerView = view;
        adapter.notifyDataSetChanged();
    }

    // First page again from scratch (also the "Qayta urinish" action)
    public void reload() {
        generation++;
        items.clear();
        publicMode = false;
        firstLoaded = false;
        firstFailed = false;
        loading = false;
        hasMore = true;
        loadMoreFailed = false;
        maxId = null;
        errorCard.setVisibility(View.GONE);
        listView.setVisibility(View.VISIBLE);
        adapter.notifyDataSetChanged();
        loadPage(false, false, null);
    }

    // First page again while the current posts stay on screen (pull to refresh, after a new post)
    public void refresh() {
        if (!firstLoaded) {
            reload();
            return;
        }
        generation++;
        loading = false;
        showRefreshing();
        loadPage(true, false, null);
    }

    // A post made in the composer: on top at once, then the timeline is refreshed
    public void addPostOnTop(PixelfedStatus status) {
        pendingTop = status;
        if (firstLoaded) {
            items.add(0, status);
            adapter.notifyDataSetChanged();
        }
        listView.scrollToPosition(0);
        refresh();
    }

    public boolean isLoaded() {
        return firstLoaded || loading;
    }

    // Counters may have changed on another screen (comments)
    public void updateVisibleCounts() {
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof NexPostCell) {
                ((NexPostCell) child).updateCounts();
            }
        }
    }

    private void checkLoadMore() {
        if (!firstLoaded || loading || !hasMore || loadMoreFailed || refreshing) {
            return;
        }
        final int last = layoutManager.findLastVisibleItemPosition();
        if (last >= adapter.getItemCount() - 4) {
            loadNext();
        }
    }

    private void loadNext() {
        if (loading || !hasMore) {
            return;
        }
        loadPage(false, publicMode, maxId);
    }

    private void loadPage(boolean replace, boolean isPublic, String fromId) {
        loading = true;
        loadMoreFailed = false;
        final int gen = generation;
        CoreClient.EXECUTOR.execute(() -> {
            ArrayList<PixelfedStatus> page = null;
            String error = null;
            boolean notConnected = false;
            try {
                page = accountId != null
                    ? PixelfedClient.accountStatusesBlocking(tgUserId, accountId, fromId)
                    : PixelfedClient.timelineBlocking(tgUserId, isPublic, fromId);
            } catch (PixelfedClient.NotConnectedException e) {
                notConnected = true;
            } catch (PixelfedClient.HttpException e) {
                error = "HTTP " + e.code;
            } catch (Exception e) {
                error = e.getClass().getSimpleName();
            }
            final ArrayList<PixelfedStatus> result = page;
            final String reason = error;
            final boolean finalNotConnected = notConnected;
            AndroidUtilities.runOnUIThread(() -> onPageLoaded(gen, replace, isPublic, fromId, result, reason, finalNotConnected));
        });
    }

    private void onPageLoaded(int gen, boolean replace, boolean isPublic, String fromId, ArrayList<PixelfedStatus> page, String reason, boolean notConnected) {
        if (gen != generation) {
            return;
        }
        loading = false;
        if (notConnected) {
            hideRefreshing();
            delegate.onFeedNotConnected();
            return;
        }
        if (page == null) {
            hideRefreshing();
            if (replace) {
                Toast.makeText(getContext(), LocaleController.getString(R.string.NexFeedLoadError), Toast.LENGTH_SHORT).show();
            } else if (!firstLoaded) {
                firstFailed = true;
                errorReasonView.setText(reason);
                errorReasonView.setVisibility(reason == null ? View.GONE : View.VISIBLE);
                errorCard.setVisibility(View.VISIBLE);
                listView.setVisibility(headerView != null ? View.VISIBLE : View.GONE);
            } else {
                loadMoreFailed = true;
            }
            adapter.notifyDataSetChanged();
            return;
        }
        if (accountId == null && !isPublic && fromId == null && page.isEmpty()) {
            // Nobody followed yet: the local public timeline under a soft notice
            loadPage(replace, true, null);
            return;
        }
        hideRefreshing();
        final boolean wasFirst = !firstLoaded;
        if (replace) {
            items.clear();
            loadMoreFailed = false;
        }
        if (fromId == null) {
            publicMode = isPublic;
        }
        firstLoaded = true;
        firstFailed = false;
        errorCard.setVisibility(View.GONE);
        listView.setVisibility(View.VISIBLE);
        hasMore = !page.isEmpty();
        if (!page.isEmpty()) {
            maxId = page.get(page.size() - 1).id;
            items.addAll(page);
        }
        if (pendingTop != null) {
            boolean found = false;
            for (PixelfedStatus s : items) {
                if (s != pendingTop && pendingTop.statusId.equals(s.statusId)) {
                    found = true;
                    break;
                }
            }
            if (found) {
                items.remove(pendingTop);
                pendingTop = null;
            } else if (!items.contains(pendingTop)) {
                items.add(0, pendingTop);
            }
        }
        adapter.notifyDataSetChanged();
        if (wasFirst || replace) {
            listView.scrollToPosition(0);
        }
        listView.post(this::updateActiveVideo);
    }

    // ---- pull to refresh ----

    private void showRefreshing() {
        refreshing = true;
        refreshIndicator.animate().cancel();
        refreshIndicator.setVisibility(View.VISIBLE);
        refreshIndicator.animate().alpha(1f).translationY(topInset + AndroidUtilities.dp(12)).setDuration(150).start();
    }

    private void hideRefreshing() {
        if (!refreshing) {
            return;
        }
        refreshing = false;
        refreshIndicator.animate().cancel();
        refreshIndicator.animate().alpha(0f).translationY(topInset - AndroidUtilities.dp(48)).setDuration(200)
            .withEndAction(() -> {
                if (!refreshing && !pulling) {
                    refreshIndicator.setVisibility(View.GONE);
                }
            }).start();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!firstLoaded || refreshing || listView.getVisibility() != View.VISIBLE) {
            return super.onInterceptTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                pulling = false;
                break;
            case MotionEvent.ACTION_MOVE: {
                final float dy = ev.getY() - downY;
                final float dx = Math.abs(ev.getX() - downX);
                if (dy > touchSlop && dy > dx * 1.5f && !listView.canScrollVertically(-1)) {
                    pulling = true;
                    pullDistance = 0;
                    refreshIndicator.animate().cancel();
                    refreshIndicator.setVisibility(View.VISIBLE);
                    return true;
                }
                break;
            }
        }
        return super.onInterceptTouchEvent(ev);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!pulling) {
            return super.onTouchEvent(ev);
        }
        final float threshold = AndroidUtilities.dp(72);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                pullDistance = Math.max(0, Math.min((ev.getY() - downY - touchSlop) * 0.5f, threshold * 1.4f));
                refreshIndicator.setTranslationY(topInset - AndroidUtilities.dp(48) + pullDistance);
                refreshIndicator.setAlpha(Math.min(1f, pullDistance / threshold));
                refreshIndicator.setRotation(pullDistance * 2);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pulling = false;
                refreshIndicator.setRotation(0);
                if (pullDistance >= threshold && ev.getActionMasked() == MotionEvent.ACTION_UP) {
                    refresh();
                } else {
                    refreshing = true; // lets hideRefreshing() run its animation
                    hideRefreshing();
                }
                return true;
        }
        return true;
    }

    // ---- post actions ----

    @Override
    public void onCommentsClick(NexPostCell cell, PixelfedStatus status) {
        delegate.onOpenComments(status);
    }

    @Override
    public void onAuthorClick(PixelfedStatus status) {
        if (status.authorId != null && !status.authorId.isEmpty() && !status.authorId.equals(accountId)) {
            delegate.onOpenProfile(status.authorId);
        }
    }

    @Override
    public void onLikeClick(NexPostCell cell, PixelfedStatus status) {
        toggleLike(getContext(), tgUserId, status, () -> updateLikeOf(status), delegate::onFeedNotConnected);
    }

    // Optimistic like: UI at once, rolled back with a toast when the server refuses
    static void toggleLike(Context context, long tgUserId, PixelfedStatus status, Runnable update, Runnable notConnected) {
        if (status.likePending) {
            return;
        }
        final boolean favourite = !status.favourited;
        status.likePending = true;
        status.favourited = favourite;
        status.favouritesCount += favourite ? 1 : -1;
        update.run();
        CoreClient.EXECUTOR.execute(() -> {
            boolean ok = false;
            boolean finalNotConnected = false;
            try {
                PixelfedClient.setFavouriteBlocking(tgUserId, status.statusId, favourite);
                ok = true;
            } catch (PixelfedClient.NotConnectedException e) {
                finalNotConnected = true;
            } catch (Exception ignore) {
            }
            final boolean success = ok;
            final boolean nc = finalNotConnected;
            AndroidUtilities.runOnUIThread(() -> {
                status.likePending = false;
                if (!success) {
                    status.favourited = !favourite;
                    status.favouritesCount += favourite ? -1 : 1;
                    update.run();
                    if (nc) {
                        notConnected.run();
                    } else {
                        Toast.makeText(context, LocaleController.getString(R.string.NexFeedLikeError), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        });
    }

    private void updateLikeOf(PixelfedStatus status) {
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof NexPostCell && ((NexPostCell) child).getStatus() == status) {
                ((NexPostCell) child).updateLike();
            }
        }
    }

    // ---- video ----

    @Override
    public void onMediaPageChanged(NexPostCell cell) {
        updateActiveVideo();
    }

    // The video of the most visible post (≥ 60 % of its media on screen) plays; all others stop
    private void updateActiveVideo() {
        NexPostCell best = null;
        float bestFraction = 0.6f;
        if (!paused && getVisibility() == View.VISIBLE) {
            for (int i = 0; i < listView.getChildCount(); i++) {
                final View child = listView.getChildAt(i);
                if (!(child instanceof NexPostCell)) {
                    continue;
                }
                final NexPostCell cell = (NexPostCell) child;
                if (!cell.hasVideoOnCurrentPage()) {
                    continue;
                }
                final float fraction = cell.getMediaVisibleFraction(listView);
                if (fraction >= bestFraction) {
                    best = cell;
                    bestFraction = fraction;
                }
            }
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof NexPostCell) {
                ((NexPostCell) child).setVideoActive(child == best);
            }
        }
    }

    public void onResume() {
        paused = false;
        updateVisibleCounts();
        listView.post(this::updateActiveVideo);
    }

    public void onPause() {
        paused = true;
        updateActiveVideo();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (listView == null) {
            return;
        }
        if (visibility != View.VISIBLE) {
            stopVideos();
        } else {
            listView.post(this::updateActiveVideo);
        }
    }

    private void stopVideos() {
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof NexPostCell) {
                ((NexPostCell) child).releaseVideo();
            }
        }
    }

    // After logout or account change nothing of this feed may stay
    public void clear() {
        generation++;
        stopVideos();
        items.clear();
        pendingTop = null;
        publicMode = false;
        firstLoaded = false;
        firstFailed = false;
        loading = false;
        hasMore = true;
        loadMoreFailed = false;
        maxId = null;
        refreshing = false;
        pulling = false;
        refreshIndicator.setVisibility(View.GONE);
        errorCard.setVisibility(View.GONE);
        listView.setVisibility(View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    // ---- list ----

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private int headerCount() {
            return headerView != null ? 1 : 0;
        }

        private boolean showNotice() {
            return firstLoaded && publicMode && accountId == null;
        }

        private boolean showFooter() {
            return firstLoaded && (loadMoreFailed || (hasMore && !items.isEmpty()));
        }

        private int postsStart() {
            return headerCount() + (showNotice() ? 1 : 0);
        }

        @Override
        public int getItemCount() {
            if (!firstLoaded) {
                return headerCount() + (firstFailed ? 0 : SKELETON_COUNT);
            }
            return postsStart() + items.size() + (showFooter() ? 1 : 0);
        }

        @Override
        public int getItemViewType(int position) {
            if (position < headerCount()) {
                return TYPE_HEADER;
            }
            if (!firstLoaded) {
                return TYPE_SKELETON;
            }
            if (showNotice() && position == headerCount()) {
                return TYPE_NOTICE;
            }
            return position - postsStart() < items.size() ? TYPE_POST : TYPE_FOOTER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            final View view;
            switch (viewType) {
                case TYPE_HEADER:
                    view = headerView != null ? headerView : new View(context);
                    if (view.getParent() instanceof ViewGroup) {
                        ((ViewGroup) view.getParent()).removeView(view);
                    }
                    break;
                case TYPE_POST:
                    view = new NexPostCell(context, resourcesProvider, NexNativeFeedView.this);
                    break;
                case TYPE_NOTICE:
                    view = createNotice(context);
                    break;
                case TYPE_SKELETON:
                    view = new SkeletonCell(context);
                    break;
                case TYPE_FOOTER:
                default:
                    view = new FooterCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final View view = holder.itemView;
            if (view instanceof NexPostCell) {
                ((NexPostCell) view).setStatus(items.get(position - postsStart()));
            } else if (view instanceof FooterCell) {
                ((FooterCell) view).setFailed(loadMoreFailed);
            }
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            if (holder.itemView instanceof NexPostCell) {
                ((NexPostCell) holder.itemView).releaseVideo();
            }
        }
    }

    // Soft notice in the app's light style (no banner): nobody followed yet
    private View createNotice(Context context) {
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(16), AndroidUtilities.dp(18), AndroidUtilities.dp(16));
        card.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(20), NexLentaColors.CARD));
        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(NexLentaColors.TEXT);
        title.setText(LocaleController.getString(R.string.NexFeedEmptyTitle));
        card.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        text.setLineSpacing(AndroidUtilities.dp(2), 1f);
        text.setTextColor(NexLentaColors.SECONDARY_TEXT);
        text.setText(LocaleController.getString(R.string.NexFeedEmptyHome));
        card.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        final FrameLayout frame = new FrameLayout(context);
        frame.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 6, 12, 6));
        return frame;
    }

    // "Load more" spinner, or "Qayta urinish" after a failed page
    private class FooterCell extends FrameLayout {
        private final RadialProgressView progressView;
        private final TextView retryView;

        FooterCell(Context context) {
            super(context);
            progressView = new RadialProgressView(context);
            progressView.setSize(AndroidUtilities.dp(24));
            progressView.setProgressColor(NexLentaColors.ACCENT);
            addView(progressView, LayoutHelper.createFrame(32, 32, Gravity.CENTER, 0, 12, 0, 12));
            retryView = new TextView(context);
            retryView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            retryView.setTypeface(AndroidUtilities.bold());
            retryView.setGravity(Gravity.CENTER);
            retryView.setTextColor(NexLentaColors.TEXT);
            retryView.setText(LocaleController.getString(R.string.NexFeedLoginRetry));
            retryView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
            retryView.setOnClickListener(v -> {
                loadMoreFailed = false;
                adapter.notifyDataSetChanged();
                loadNext();
            });
            addView(retryView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        }

        void setFailed(boolean failed) {
            progressView.setVisibility(failed ? View.GONE : View.VISIBLE);
            retryView.setVisibility(failed ? View.VISIBLE : View.GONE);
        }
    }

    // Grey placeholder of a post while the first page loads (pulsing)
    private static class SkeletonCell extends View {
        private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        SkeletonCell(Context context) {
            super(context);
            cardPaint.setColor(NexLentaColors.CARD);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            setMeasuredDimension(width, AndroidUtilities.dp(12 + 62 + 40 + 12) + (width - AndroidUtilities.dp(24)));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float phase = (SystemClock.uptimeMillis() % 1200) / 1200f;
            final float alpha = 0.55f + 0.45f * (float) Math.abs(Math.sin(Math.PI * phase));
            shapePaint.setColor(Theme.multAlpha(0xFFE3E3E8, alpha));
            final float d = AndroidUtilities.density;
            final float left = 12 * d;
            final float right = getWidth() - 12 * d;
            rect.set(left, 6 * d, right, getHeight() - 6 * d);
            canvas.drawRoundRect(rect, 20 * d, 20 * d, cardPaint);
            canvas.drawCircle(left + 14 * d + 19 * d, 6 * d + 12 * d + 19 * d, 19 * d, shapePaint);
            rect.set(left + 62 * d, 6 * d + 18 * d, left + 190 * d, 6 * d + 30 * d);
            canvas.drawRoundRect(rect, 6 * d, 6 * d, shapePaint);
            rect.set(left + 62 * d, 6 * d + 38 * d, left + 140 * d, 6 * d + 48 * d);
            canvas.drawRoundRect(rect, 5 * d, 5 * d, shapePaint);
            final float mediaTop = 6 * d + 62 * d;
            rect.set(left, mediaTop, right, mediaTop + (right - left));
            canvas.drawRect(rect, shapePaint);
            rect.set(left + 14 * d, rect.bottom + 14 * d, left + 120 * d, rect.bottom + 26 * d);
            canvas.drawRoundRect(rect, 6 * d, 6 * d, shapePaint);
            postInvalidateOnAnimation();
        }
    }
}
