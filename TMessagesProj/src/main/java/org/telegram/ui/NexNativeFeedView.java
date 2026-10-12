package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
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

// NexGramm: native Lenta (phase 1) on the Pixelfed Mastodon API with the app's own token.
// Home timeline (max_id paging); empty home → local public timeline with a notice. Likes are
// optimistic and roll back on error. One video at a time plays: the most visible one, muted.
@SuppressLint("ViewConstructor")
public class NexNativeFeedView extends FrameLayout implements NexPostCell.Delegate {

    public interface Delegate {
        // The Pixelfed token is gone or rejected: connect again (OAuth)
        void onFeedNotConnected();
    }

    private static final int TYPE_POST = 0;
    private static final int TYPE_NOTICE = 1;
    private static final int TYPE_SKELETON = 2;
    private static final int TYPE_FOOTER = 3;
    private static final int SKELETON_COUNT = 3;

    private final long tgUserId;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Delegate delegate;
    private final RecyclerView listView;
    private final LinearLayoutManager layoutManager;
    private final Adapter adapter = new Adapter();
    private final LinearLayout errorCard;
    private final TextView errorReasonView;

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

    public NexNativeFeedView(Context context, long tgUserId, Theme.ResourcesProvider resourcesProvider, Delegate delegate) {
        super(context);
        this.tgUserId = tgUserId;
        this.resourcesProvider = resourcesProvider;
        this.delegate = delegate;
        setBackgroundColor(NexLentaView.BACKGROUND);

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

        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        errorCard = new LinearLayout(context);
        errorCard.setOrientation(LinearLayout.VERTICAL);
        errorCard.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(20));
        errorCard.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24), Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)));
        final TextView errorTitle = new TextView(context);
        errorTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        errorTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
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
        retryButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
        retryButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(24), textColor, Theme.multAlpha(textColor, 0.8f)));
        retryButton.setText(LocaleController.getString(R.string.NexFeedLoginRetry));
        retryButton.setOnClickListener(v -> reload());
        errorCard.addView(retryButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 20, 0, 0));
        errorCard.setVisibility(View.GONE);
        addView(errorCard, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 20, 0, 20, 0));
    }

    public void setInsets(int top, int bottom) {
        listView.setPadding(0, top + AndroidUtilities.dp(6), 0, bottom + AndroidUtilities.dp(6));
        errorCard.setTranslationY((top - bottom) / 2f);
    }

    // First page again (also the "Qayta urinish" action)
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
        loadNext();
    }

    public boolean isLoaded() {
        return firstLoaded || loading;
    }

    private void checkLoadMore() {
        if (!firstLoaded || loading || !hasMore || loadMoreFailed) {
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
        loading = true;
        loadMoreFailed = false;
        final int gen = generation;
        final boolean isPublic = publicMode;
        final String fromId = maxId;
        CoreClient.EXECUTOR.execute(() -> {
            ArrayList<PixelfedStatus> page = null;
            String error = null;
            boolean notConnected = false;
            try {
                page = PixelfedClient.timelineBlocking(tgUserId, isPublic, fromId);
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
            AndroidUtilities.runOnUIThread(() -> onPageLoaded(gen, isPublic, fromId, result, reason, finalNotConnected));
        });
    }

    private void onPageLoaded(int gen, boolean isPublic, String fromId, ArrayList<PixelfedStatus> page, String reason, boolean notConnected) {
        if (gen != generation) {
            return;
        }
        loading = false;
        if (notConnected) {
            delegate.onFeedNotConnected();
            return;
        }
        if (page == null) {
            if (!firstLoaded) {
                firstFailed = true;
                errorReasonView.setText(reason);
                errorReasonView.setVisibility(reason == null ? View.GONE : View.VISIBLE);
                errorCard.setVisibility(View.VISIBLE);
                listView.setVisibility(View.GONE);
            } else {
                loadMoreFailed = true;
            }
            adapter.notifyDataSetChanged();
            return;
        }
        if (!isPublic && fromId == null && page.isEmpty()) {
            // Nobody followed yet: show the local public timeline under a notice
            publicMode = true;
            loadNext();
            return;
        }
        final boolean wasFirst = !firstLoaded;
        firstLoaded = true;
        hasMore = !page.isEmpty();
        if (!page.isEmpty()) {
            maxId = page.get(page.size() - 1).id;
            items.addAll(page);
        }
        adapter.notifyDataSetChanged();
        if (wasFirst) {
            listView.scrollToPosition(0);
        }
        listView.post(this::updateActiveVideo);
    }

    // ---- likes ----

    @Override
    public void onLikeClick(NexPostCell cell, PixelfedStatus status) {
        if (status.likePending) {
            return;
        }
        final boolean favourite = !status.favourited;
        status.likePending = true;
        status.favourited = favourite;
        status.favouritesCount += favourite ? 1 : -1;
        cell.updateLike();
        final int gen = generation;
        CoreClient.EXECUTOR.execute(() -> {
            boolean ok = false;
            boolean notConnected = false;
            try {
                PixelfedClient.setFavouriteBlocking(tgUserId, status.statusId, favourite);
                ok = true;
            } catch (PixelfedClient.NotConnectedException e) {
                notConnected = true;
            } catch (Exception ignore) {
            }
            final boolean success = ok;
            final boolean finalNotConnected = notConnected;
            AndroidUtilities.runOnUIThread(() -> {
                status.likePending = false;
                if (!success) {
                    status.favourited = !favourite;
                    status.favouritesCount += favourite ? -1 : 1;
                    updateLikeOf(status);
                    if (gen == generation && !finalNotConnected) {
                        Toast.makeText(getContext(), LocaleController.getString(R.string.NexFeedLikeError), Toast.LENGTH_SHORT).show();
                    }
                }
                if (finalNotConnected && gen == generation) {
                    delegate.onFeedNotConnected();
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
    }

    // ---- list ----

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private boolean showNotice() {
            return firstLoaded && publicMode;
        }

        private boolean showFooter() {
            return firstLoaded && (loadMoreFailed || (hasMore && !items.isEmpty()));
        }

        @Override
        public int getItemCount() {
            if (!firstLoaded) {
                return firstFailed ? 0 : SKELETON_COUNT;
            }
            return (showNotice() ? 1 : 0) + items.size() + (showFooter() ? 1 : 0);
        }

        @Override
        public int getItemViewType(int position) {
            if (!firstLoaded) {
                return TYPE_SKELETON;
            }
            if (showNotice()) {
                if (position == 0) {
                    return TYPE_NOTICE;
                }
                position--;
            }
            return position < items.size() ? TYPE_POST : TYPE_FOOTER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            final View view;
            switch (viewType) {
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
                ((NexPostCell) view).setStatus(items.get(position - (showNotice() ? 1 : 0)));
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

    private View createNotice(Context context) {
        final FrameLayout frame = new FrameLayout(context);
        final TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        text.setGravity(Gravity.CENTER);
        text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        text.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(16), AndroidUtilities.dp(14));
        text.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(20), Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)));
        text.setText(LocaleController.getString(R.string.NexFeedEmptyHome));
        frame.addView(text, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 6, 12, 6));
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
            progressView.setProgressColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            addView(progressView, LayoutHelper.createFrame(32, 32, Gravity.CENTER, 0, 12, 0, 12));
            retryView = new TextView(context);
            retryView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            retryView.setTypeface(AndroidUtilities.bold());
            retryView.setGravity(Gravity.CENTER);
            retryView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
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
            cardPaint.setColor(0xFFFFFFFF);
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
