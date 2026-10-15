package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
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
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.NexForYou;
import uz.nexgramm.core.PixelfedClient;
import uz.nexgramm.core.PixelfedStatus;
import uz.nexgramm.core.PixelfedStories;

// NexGramm: the native Lenta (design screenshot "Lenta"): header with "Siz uchun" / "Kuzatayotganlar"
// (public local vs home timeline), serif "Lenta", "+" (new post) and ♡; then the posts (NexPostCell),
// max_id paging, pull to refresh, skeletons, empty / error / offline cards. Likes, saves and follows are
// optimistic. Video: the most visible post plays (muted), the next one is buffered, the rest hold no player.
@SuppressLint("ViewConstructor")
public class NexNativeFeedView extends FrameLayout implements NexPostCell.Delegate {

    public interface Delegate {
        // The Pixelfed token is gone or rejected: connect again (OAuth)
        void onFeedNotConnected();

        // for dialogs and new screens
        BaseFragment getFragment();

        default void onComposeClick() {
        }

        default void onOpenComments(PixelfedStatus status) {
        }

        default void onOpenProfile(String accountId) {
        }
    }

    private static final int TYPE_POST = 0;
    private static final int TYPE_EMPTY = 1;
    private static final int TYPE_SKELETON = 2;
    private static final int TYPE_FOOTER = 3;
    private static final int TYPE_HEADER = 4;
    private static final int TYPE_AD = 5;
    private static final int AD_EVERY = 4; // NexGramm: an ad after every 4 posts (no Premium)
    private static final int SKELETON_COUNT = 2;
    private static final String PREFS = "nexgramm_lenta";

    private final long tgUserId;
    private final Theme.ResourcesProvider resourcesProvider;
    private final Delegate delegate;
    private final RecyclerView listView;
    private final LinearLayoutManager layoutManager;
    private final Adapter adapter = new Adapter();
    private final FrameLayout refreshIndicator;
    private final View headerView;
    private final NexStoriesBar storiesBar;

    // "For you": the on-device interest model (NexForYou), one per account, and when each post came into view
    private static final long SESSION_SEED = System.nanoTime();
    private static final HashMap<Long, NexForYou> forYouModels = new HashMap<>();
    private final HashMap<PixelfedStatus, Long> visibleSince = new HashMap<>();
    private final Runnable saveForYou = this::saveForYouNow;
    private long storiesLoadedAt;
    private final TextView modeView;

    private final ArrayList<PixelfedStatus> items = new ArrayList<>();
    private boolean adsOn; // set right before the list is redrawn, so counts never change under it
    private int enteredUpTo; // rows up to here have had their entrance
    private boolean followingMode; // false = "Siz uchun" (public local), true = "Kuzatayotganlar" (home)
    private boolean firstLoaded;
    private boolean firstFailed;
    private boolean offline;
    private String errorReason;
    private boolean loading;
    private boolean hasMore = true;
    private boolean loadMoreFailed;
    private String maxId;
    private int generation;
    private boolean paused = true;
    private PixelfedStatus pendingTop; // a post just made here, on top until the timeline returns it
    private final HashMap<String, Boolean> follows = new HashMap<>(); // author id → followed
    private final HashSet<String> followPending = new HashSet<>();

    // pull to refresh
    private final int touchSlop;
    private int topInset;
    private float downX;
    private float downY;
    private boolean pulling;
    private RadialProgressView refreshProgress;
    private boolean refreshing;
    private float pullDistance;

    public NexNativeFeedView(Context context, long tgUserId, String unusedAccountId, Theme.ResourcesProvider resourcesProvider, Delegate delegate) {
        super(context);
        NexLentaColors.apply();
        this.tgUserId = tgUserId;
        this.resourcesProvider = resourcesProvider;
        this.delegate = delegate;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        followingMode = prefs().getBoolean("feed_following_" + tgUserId, false);
        // a soft glow of the accent at the top, fading into the page
        final int glow = uz.nexgramm.core.NexPalette.blend(NexLentaColors.ACCENT, NexLentaColors.BACKGROUND, NexLentaColors.dark ? 0.8f : 0.86f);
        setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{glow, NexLentaColors.BACKGROUND, NexLentaColors.BACKGROUND, NexLentaColors.BACKGROUND}));

        listView = new RecyclerView(context);
        // one extra screen laid out ahead: the next post's photo loads and its video can buffer
        layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
            @Override
            protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extraLayoutSpace) {
                extraLayoutSpace[0] = 0;
                extraLayoutSpace[1] = AndroidUtilities.displaySize.y;
            }
        };
        listView.setLayoutManager(layoutManager);
        listView.setItemAnimator(null);
        listView.setHasFixedSize(true);
        listView.setItemViewCacheSize(4);
        listView.setClipToPadding(false);
        listView.setAdapter(adapter);
        NexMotion.bounce(listView, false); // the top edge is the pull to refresh
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                checkLoadMore();
                // during a fling no video players are made and dropped for posts flying past (that made it
                // stutter); the one to play is picked when it slows down
                if (recyclerView.getScrollState() != RecyclerView.SCROLL_STATE_SETTLING || Math.abs(dy) < AndroidUtilities.dp(12)) {
                    updateActiveVideo();
                }
                trackDwell(false);
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    updateActiveVideo();
                }
            }
        });
        addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // ---- header (scrolls with the posts) ----
        final FrameLayout header = new FrameLayout(context);
        final LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        modeView = new TextView(context);
        modeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        modeView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        modeView.setPadding(0, AndroidUtilities.dp(2), AndroidUtilities.dp(12), 0);
        modeView.setOnClickListener(v -> switchMode());
        titles.addView(modeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        // "Lenta" in the NexGramm serif, the same word in every language, small and neat
        final TextView title = NexLentaUi.serifTitle(context, 30);
        title.setText("Lenta");
        title.setOnClickListener(v -> switchMode());
        titles.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        header.addView(titles, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.BOTTOM, 0, 0, 0, 0));
        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        // search: Lenta people by name or @username
        final ImageView searchButton = NexLentaUi.circleButton(context, R.drawable.msg_search, NexLentaUi.PILL_CARD, false);
        searchButton.setContentDescription(LocaleController.getString(R.string.Search));
        searchButton.setOnClickListener(v -> {
            final BaseFragment fragment = delegate.getFragment();
            if (fragment != null) {
                fragment.presentFragment(new NexLentaSearchActivity());
            }
        });
        buttons.addView(searchButton, LayoutHelper.createLinear(36, 36));
        final ImageView composeButton = NexLentaUi.circleButton(context, R.drawable.msg_add, NexLentaUi.PILL_PRIMARY, false);
        composeButton.setContentDescription(LocaleController.getString(R.string.NexComposeTitle));
        composeButton.setOnClickListener(v -> delegate.onComposeClick());
        buttons.addView(composeButton, LayoutHelper.createLinear(36, 36, 8, 0, 0, 0));
        final ImageView heartButton = NexLentaUi.circleButton(context, R.drawable.media_like, NexLentaUi.PILL_CARD, false);
        heartButton.setContentDescription(LocaleController.getString(R.string.NexFeedNotifications));
        heartButton.setOnClickListener(v -> {
            final BaseFragment fragment = delegate.getFragment();
            if (fragment != null) {
                fragment.presentFragment(new NexNotificationsActivity());
            }
        });
        buttons.addView(heartButton, LayoutHelper.createLinear(36, 36, 8, 0, 0, 0));
        // own Lenta profile: my posts, liked and saved posts
        final ImageView meButton = NexLentaUi.circleButton(context, R.drawable.msg_openprofile, NexLentaUi.PILL_CARD, false);
        meButton.setContentDescription(LocaleController.getString(R.string.NexFeedMyProfile));
        meButton.setOnClickListener(v -> {
            final BaseFragment fragment = delegate.getFragment();
            if (fragment != null) {
                fragment.presentFragment(new NexProfileActivity(null));
            }
        });
        buttons.addView(meButton, LayoutHelper.createLinear(36, 36, 8, 0, 0, 0));
        for (int i = 0; i < buttons.getChildCount(); i++) {
            // small round buttons: the icon a little smaller too
            final ImageView button = (ImageView) buttons.getChildAt(i);
            button.setScaleType(ImageView.ScaleType.FIT_CENTER);
            button.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        }
        header.addView(buttons, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 0, 0));
        header.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(6), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        // stories under the title, as in Instagram
        final LinearLayout headerColumn = new LinearLayout(context);
        headerColumn.setOrientation(LinearLayout.VERTICAL);
        headerColumn.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        storiesBar = new NexStoriesBar(context, tgUserId, new NexStoriesBar.Delegate() {
            @Override
            public void onOpenStories(ArrayList<PixelfedStories.Group> groups, int index, View from) {
                final BaseFragment fragment = delegate.getFragment();
                if (fragment != null) {
                    final NexStoryViewerActivity viewer = new NexStoryViewerActivity(groups, index);
                    viewer.setOrigin(from); // grows out of the tapped circle
                    fragment.presentFragment(viewer);
                }
            }

            @Override
            public void onAddStory() {
                final BaseFragment fragment = delegate.getFragment();
                if (fragment != null) {
                    fragment.presentFragment(new NexStoryComposeActivity(() -> loadStories(true)));
                }
            }
        });
        headerColumn.addView(storiesBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        headerView = headerColumn;
        updateModeText();

        // Pull-to-refresh spinner: a card circle that follows the finger
        refreshIndicator = new FrameLayout(context);
        refreshIndicator.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(40), NexLentaColors.CARD));
        refreshIndicator.setElevation(AndroidUtilities.dp(3));
        final RadialProgressView refreshProgress = new RadialProgressView(context);
        this.refreshProgress = refreshProgress;
        refreshProgress.setSize(AndroidUtilities.dp(20));
        refreshProgress.setProgressColor(NexLentaColors.TEXT);
        refreshIndicator.addView(refreshProgress, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        refreshIndicator.setAlpha(0f);
        refreshIndicator.setVisibility(View.GONE);
        addView(refreshIndicator, LayoutHelper.createFrame(40, 40, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void setInsets(int top, int bottom) {
        topInset = top;
        listView.setPadding(0, top, 0, bottom + AndroidUtilities.dp(12));
        if (!refreshing && !pulling) {
            refreshIndicator.setTranslationY(top - AndroidUtilities.dp(48));
        }
    }

    private void updateModeText() {
        modeView.setText(LocaleController.getString(followingMode ? R.string.NexFeedModeFollowing : R.string.NexFeedModeForYou) + "  ⌄");
    }

    private void switchMode() {
        followingMode = !followingMode;
        prefs().edit().putBoolean("feed_following_" + tgUserId, followingMode).apply();
        updateModeText();
        reload();
    }

    // First page again from scratch (also the "Qayta urinish" action)
    public void reload() {
        generation++;
        stopVideos();
        items.clear();
        firstLoaded = false;
        firstFailed = false;
        offline = false;
        loading = false;
        hasMore = true;
        loadMoreFailed = false;
        maxId = null;
        adsOn = NexAds.enabled(tgUserId);
        enteredUpTo = 0;
        adapter.notifyDataSetChanged();
        loadPage(false, null);
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
        loadStories(true);
        loadPage(true, null);
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

    // Counters may have changed on another screen (comments, profile)
    public void updateVisibleCounts() {
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof NexPostCell) {
                ((NexPostCell) child).updateCounts();
                ((NexPostCell) child).updateFollow();
            }
        }
    }

    private void checkLoadMore() {
        if (!firstLoaded || loading || !hasMore || loadMoreFailed || refreshing) {
            return;
        }
        final int last = layoutManager.findLastVisibleItemPosition();
        if (last >= adapter.getItemCount() - 3) {
            loadPage(false, maxId);
        }
    }

    static boolean isOfflineError(Throwable e) {
        return e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException
            || e instanceof java.net.SocketTimeoutException || e instanceof java.net.NoRouteToHostException
            || e instanceof javax.net.ssl.SSLException && e.getCause() instanceof java.net.SocketException;
    }

    private void loadPage(boolean replace, String fromId) {
        loading = true;
        loadMoreFailed = false;
        final int gen = generation;
        final boolean isPublic = !followingMode;
        // "For you" ranks against what is already on screen and who is followed
        final HashSet<String> shown = new HashSet<>();
        if (!replace) {
            for (PixelfedStatus s : items) {
                shown.add(s.statusId);
            }
        }
        final HashMap<String, Boolean> followed = new HashMap<>(follows);
        final NexForYou model = forYou();
        CoreClient.EXECUTOR.execute(() -> {
            ArrayList<PixelfedStatus> page = null;
            String nextMaxId = null;
            String error = null;
            boolean notConnected = false;
            boolean noNetwork = false;
            try {
                page = PixelfedClient.timelineBlocking(tgUserId, isPublic, fromId);
                nextMaxId = page.isEmpty() ? null : page.get(page.size() - 1).id;
                if (isPublic) {
                    page = rankForYou(model, page, fromId == null, shown, followed);
                }
            } catch (PixelfedClient.NotConnectedException e) {
                notConnected = true;
            } catch (PixelfedClient.HttpException e) {
                error = "HTTP " + e.code;
            } catch (IOException e) {
                noNetwork = isOfflineError(e);
                error = e.getClass().getSimpleName();
            } catch (Exception e) {
                error = e.getClass().getSimpleName();
            }
            final ArrayList<PixelfedStatus> result = page;
            final String nextMax = nextMaxId;
            final String reason = error;
            final boolean finalNotConnected = notConnected;
            final boolean finalOffline = noNetwork;
            AndroidUtilities.runOnUIThread(() -> onPageLoaded(gen, replace, fromId, result, nextMax, reason, finalNotConnected, finalOffline));
        });
    }

    // "For you" page (worker thread): this page of the local public timeline, plus on the first page the home
    // timeline and the month's most liked posts; without duplicates or posts already shown, ranked by NexForYou
    private ArrayList<PixelfedStatus> rankForYou(NexForYou model, ArrayList<PixelfedStatus> page, boolean first,
                                                 HashSet<String> shown, HashMap<String, Boolean> followed) {
        final ArrayList<PixelfedStatus> pool = new ArrayList<>(page);
        if (first) {
            // the home timeline and the trending posts at the same time: a refresh waits for one round trip, not two
            final java.util.concurrent.Future<ArrayList<PixelfedStatus>> home = CoreClient.EXECUTOR.submit(() -> PixelfedClient.timelineBlocking(tgUserId, false, null));
            final java.util.concurrent.Future<ArrayList<PixelfedStatus>> trending = CoreClient.EXECUTOR.submit(() -> PixelfedClient.trendingBlocking(tgUserId));
            // at most a few seconds each: a slow extra source must not hold the refresh (the spinner) for long
            try {
                pool.addAll(home.get(4, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Exception ignore) {
                home.cancel(true);
            }
            try {
                pool.addAll(trending.get(4, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Exception ignore) {
                trending.cancel(true);
            }
        }
        final HashMap<String, PixelfedStatus> byId = new HashMap<>();
        final ArrayList<NexForYou.Candidate> candidates = new ArrayList<>();
        for (PixelfedStatus s : pool) {
            if (shown.contains(s.statusId) || byId.containsKey(s.statusId)) {
                continue; // the timeline copy (with this user's like / save state) comes first
            }
            byId.put(s.statusId, s);
            candidates.add(new NexForYou.Candidate(s.statusId, s.authorId, NexForYou.hashtags(s.contentHtml), s.hasVideo(),
                s.favouritesCount, s.repliesCount, s.createdAt, Boolean.TRUE.equals(followed.get(s.authorId)),
                NexPostActions.isOwn(tgUserId, s)));
        }
        final ArrayList<PixelfedStatus> ranked = new ArrayList<>(candidates.size());
        for (NexForYou.Candidate c : model.rank(candidates, System.currentTimeMillis(), SESSION_SEED)) {
            ranked.add(byId.get(c.id));
        }
        return ranked;
    }

    private NexForYou forYou() {
        NexForYou model = forYouModels.get(tgUserId);
        if (model == null) {
            model = NexForYou.fromJson(prefs().getString("foryou_" + tgUserId, null));
            forYouModels.put(tgUserId, model);
        }
        return model;
    }

    // A signal for the "For you" model; saved a few seconds later
    private void learn(PixelfedStatus status, float[] weights) {
        if (status == null) {
            return;
        }
        forYou().record(status.authorId, NexForYou.hashtags(status.contentHtml), status.hasVideo(), weights, System.currentTimeMillis());
        AndroidUtilities.cancelRunOnUIThread(saveForYou);
        AndroidUtilities.runOnUIThread(saveForYou, 3000);
    }

    private void saveForYouNow() {
        AndroidUtilities.cancelRunOnUIThread(saveForYou);
        final NexForYou model = forYouModels.get(tgUserId);
        if (model == null) {
            return;
        }
        final String key = "foryou_" + tgUserId;
        CoreClient.EXECUTOR.execute(() -> prefs().edit().putString(key, model.toJson()).apply());
    }

    // How long each post stayed on screen (≥ half of it visible): long looks teach the model, very quick
    // skips count a little against; looked-at posts go down in "For you" next time
    private void trackDwell(boolean leaveAll) {
        final long now = android.os.SystemClock.elapsedRealtime();
        final HashSet<PixelfedStatus> visible = new HashSet<>();
        if (!leaveAll && !paused && getVisibility() == View.VISIBLE) {
            final int top = listView.getPaddingTop();
            final int bottom = listView.getHeight() - listView.getPaddingBottom();
            for (int i = 0; i < listView.getChildCount(); i++) {
                final View child = listView.getChildAt(i);
                if (!(child instanceof NexPostCell) || child.getHeight() <= 0) {
                    continue;
                }
                final int shownPx = Math.min(bottom, child.getBottom()) - Math.max(top, child.getTop());
                final PixelfedStatus status = ((NexPostCell) child).getStatus();
                if (status != null && shownPx >= Math.min(child.getHeight(), bottom - top) / 2) {
                    visible.add(status);
                    if (!visibleSince.containsKey(status)) {
                        visibleSince.put(status, now);
                    }
                }
            }
        }
        final java.util.Iterator<java.util.Map.Entry<PixelfedStatus, Long>> it = visibleSince.entrySet().iterator();
        while (it.hasNext()) {
            final java.util.Map.Entry<PixelfedStatus, Long> e = it.next();
            if (visible.contains(e.getKey())) {
                continue;
            }
            final long dwell = now - e.getValue();
            it.remove();
            final PixelfedStatus status = e.getKey();
            if (dwell >= 8000) {
                learn(status, NexForYou.VERY_LONG_LOOK);
            } else if (dwell >= 3000) {
                learn(status, NexForYou.LONG_LOOK);
            } else if (dwell < 700 && !leaveAll) {
                learn(status, NexForYou.QUICK_SKIP);
            }
            if (dwell >= 1000) {
                forYou().markSeen(status.statusId, System.currentTimeMillis());
            }
        }
    }

    private void onPageLoaded(int gen, boolean replace, String fromId, ArrayList<PixelfedStatus> page, String nextMaxId, String reason, boolean notConnected, boolean noNetwork) {
        if (gen != generation) {
            return;
        }
        loading = false;
        hideRefreshing();
        if (notConnected) {
            delegate.onFeedNotConnected();
            return;
        }
        if (page == null) {
            if (replace) {
                Toast.makeText(getContext(), LocaleController.getString(noNetwork ? R.string.NexFeedOffline : R.string.NexFeedLoadError), Toast.LENGTH_SHORT).show();
            } else if (!firstLoaded) {
                firstFailed = true;
                offline = noNetwork;
                errorReason = reason;
            } else {
                loadMoreFailed = true;
            }
            adapter.notifyDataSetChanged();
            return;
        }
        final boolean wasFirst = !firstLoaded;
        if (replace) {
            items.clear();
            loadMoreFailed = false;
        }
        firstLoaded = true;
        firstFailed = false;
        offline = false;
        hasMore = nextMaxId != null;
        if (nextMaxId != null) {
            maxId = nextMaxId;
        }
        items.addAll(page);
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
        adsOn = NexAds.enabled(tgUserId);
        adapter.notifyDataSetChanged();
        if (wasFirst || replace) {
            listView.scrollToPosition(0);
        }
        loadRelationships(page);
        listView.post(this::updateActiveVideo);
    }

    // Which authors this user follows (for the "Obuna" button); one request per page
    private void loadRelationships(ArrayList<PixelfedStatus> page) {
        final HashSet<String> ids = new HashSet<>();
        for (PixelfedStatus s : page) {
            if (!s.authorId.isEmpty() && !follows.containsKey(s.authorId) && !NexPostActions.isOwn(tgUserId, s)) {
                ids.add(s.authorId);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        final int gen = generation;
        CoreClient.EXECUTOR.execute(() -> {
            HashMap<String, Boolean> result = null;
            try {
                result = PixelfedClient.relationshipsBlocking(tgUserId, ids);
            } catch (Exception ignore) {
            }
            final HashMap<String, Boolean> finalResult = result;
            AndroidUtilities.runOnUIThread(() -> {
                if (finalResult == null || gen != generation) {
                    return;
                }
                follows.putAll(finalResult);
                updateVisibleCounts();
            });
        });
    }

    // ---- pull to refresh ----

    private void showRefreshing() {
        refreshing = true;
        refreshProgress.setNoProgress(true); // spins until the posts are in
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
        if (!firstLoaded || refreshing) {
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
                    listView.animate().cancel();
                    refreshIndicator.animate().cancel();
                    refreshIndicator.setVisibility(View.VISIBLE);
                    refreshProgress.setNoProgress(false); // the ring fills with the pull
                    refreshProgress.setProgress(0f);
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
                refreshProgress.setProgress(Math.min(1f, pullDistance / threshold));
                listView.setTranslationY(pullDistance); // the posts follow the finger
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pulling = false;
                refreshIndicator.animate().rotation(0).setDuration(200).start();
                listView.animate().translationY(0).setStartDelay(0).setDuration(480).setInterpolator(NexMotion.EASE).start();
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

    private final NexPostActions.Listener postListener = new NexPostActions.Listener() {
        @Override
        public void onDeleted(PixelfedStatus status) {
            items.remove(status);
            if (pendingTop == status) {
                pendingTop = null;
            }
            adapter.notifyDataSetChanged();
        }

        @Override
        public void onChanged(PixelfedStatus status) {
            updateCellsOf(status);
        }
    };

    @Override
    public void onVideoClick(NexPostCell cell, PixelfedStatus status, int page) {
        final BaseFragment fragment = delegate.getFragment();
        if (fragment != null) {
            fragment.presentFragment(new NexVideoViewerActivity(status, page));
        }
        learn(status, NexForYou.VIDEO_OPEN);
    }

    @Override
    public void onCommentsClick(NexPostCell cell, PixelfedStatus status) {
        learn(status, NexForYou.COMMENTS);
        delegate.onOpenComments(status);
    }

    @Override
    public void onAuthorClick(PixelfedStatus status) {
        if (status.authorId != null && !status.authorId.isEmpty()) {
            learn(status, NexForYou.PROFILE_OPEN);
            delegate.onOpenProfile(status.authorId);
        }
    }

    @Override
    public void onLikeClick(NexPostCell cell, PixelfedStatus status) {
        if (!status.likePending) {
            learn(status, status.favourited ? NexForYou.UNLIKE : NexForYou.LIKE);
        }
        toggleLike(getContext(), tgUserId, status, () -> updateCellsOf(status), delegate::onFeedNotConnected);
    }

    @Override
    public void onShareClick(PixelfedStatus status) {
        final BaseFragment fragment = delegate.getFragment();
        if (fragment != null) {
            NexPostActions.share(fragment, status);
        }
        learn(status, NexForYou.SHARE);
    }

    @Override
    public void onBookmarkClick(NexPostCell cell, PixelfedStatus status) {
        if (!status.bookmarked && !status.bookmarkPending) {
            learn(status, NexForYou.SAVE);
        }
        NexPostActions.toggleBookmark(getContext(), tgUserId, status, postListener);
    }

    @Override
    public void onRepostClick(NexPostCell cell, PixelfedStatus status) {
        if (!status.reblogged && !status.reblogPending) {
            learn(status, NexForYou.SHARE);
        }
        NexPostActions.toggleRepost(getContext(), tgUserId, status, postListener);
    }

    @Override
    public void onMenuClick(NexPostCell cell, PixelfedStatus status) {
        final BaseFragment fragment = delegate.getFragment();
        if (fragment != null) {
            NexPostActions.showMenu(fragment, tgUserId, status, postListener);
        }
    }

    @Override
    public void onAiClick(PixelfedStatus status) {
        NexPostActions.askAi(delegate.getFragment(), status);
    }

    @Override
    public Boolean isFollowing(PixelfedStatus status) {
        if (status.authorId.isEmpty() || NexPostActions.isOwn(tgUserId, status)) {
            return null;
        }
        return follows.get(status.authorId);
    }

    // "Obuna": optimistic follow, rolled back on error
    @Override
    public void onFollowClick(NexPostCell cell, PixelfedStatus status) {
        final String authorId = status.authorId;
        if (!authorId.isEmpty() && !followPending.contains(authorId) && !Boolean.TRUE.equals(follows.get(authorId))) {
            learn(status, NexForYou.FOLLOW);
        }
        if (authorId.isEmpty() || !followPending.add(authorId)) {
            return;
        }
        follows.put(authorId, true);
        updateVisibleCounts();
        CoreClient.EXECUTOR.execute(() -> {
            boolean ok = false;
            try {
                PixelfedClient.setFollowBlocking(tgUserId, authorId, true);
                ok = true;
            } catch (Exception ignore) {
            }
            final boolean success = ok;
            AndroidUtilities.runOnUIThread(() -> {
                followPending.remove(authorId);
                if (!success) {
                    follows.put(authorId, false);
                    updateVisibleCounts();
                    Toast.makeText(getContext(), LocaleController.getString(R.string.NexActionFailed), Toast.LENGTH_SHORT).show();
                }
            });
        });
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

    private void updateCellsOf(PixelfedStatus status) {
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof NexPostCell && ((NexPostCell) child).getStatus() == status) {
                ((NexPostCell) child).updateCounts();
            }
        }
    }

    // ---- video ----

    @Override
    public void onMediaPageChanged(NexPostCell cell) {
        updateActiveVideo();
    }

    // The most visible post with a video (≥ 60 % of its media on screen) plays; the next post below with a
    // video is buffered (paused); every other cell releases its player
    private void updateActiveVideo() {
        NexPostCell best = null;
        float bestFraction = 0.45f; // starts a little earlier while scrolling
        if (!paused && getVisibility() == View.VISIBLE) {
            for (int i = 0; i < listView.getChildCount(); i++) {
                final View child = listView.getChildAt(i);
                if (!(child instanceof NexPostCell) || !((NexPostCell) child).hasVideoOnCurrentPage()) {
                    continue;
                }
                final float fraction = ((NexPostCell) child).getMediaVisibleFraction(listView);
                if (fraction >= bestFraction) {
                    best = (NexPostCell) child;
                    bestFraction = fraction;
                }
            }
        }
        NexPostCell next = null;
        if (!paused && getVisibility() == View.VISIBLE) {
            final int fromTop = best != null ? best.getTop() : listView.getPaddingTop() - 1;
            for (int i = 0; i < listView.getChildCount(); i++) {
                final View child = listView.getChildAt(i);
                if (child instanceof NexPostCell && child != best && child.getTop() > fromTop && ((NexPostCell) child).hasVideoOnCurrentPage()
                    && (next == null || child.getTop() < next.getTop())) {
                    next = (NexPostCell) child;
                }
            }
        }
        for (int i = 0; i < listView.getChildCount(); i++) {
            final View child = listView.getChildAt(i);
            if (child instanceof NexPostCell) {
                ((NexPostCell) child).setVideoState(child == best ? NexPostCell.VIDEO_PLAY : child == next ? NexPostCell.VIDEO_PRELOAD : NexPostCell.VIDEO_OFF);
            }
        }
    }

    // Stories again when coming back (seen rings, new stories), at most every 30 s unless forced
    private void loadStories(boolean force) {
        final long now = android.os.SystemClock.elapsedRealtime();
        if (force || storiesLoadedAt == 0 || now - storiesLoadedAt > 30_000) {
            storiesLoadedAt = now;
            storiesBar.load();
        } else {
            storiesBar.refreshRings();
        }
    }

    public void onResume() {
        paused = false;
        loadStories(false);
        updateVisibleCounts();
        listView.post(this::updateActiveVideo);
    }

    public void onPause() {
        paused = true;
        trackDwell(true);
        saveForYouNow();
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
        follows.clear();
        pendingTop = null;
        firstLoaded = false;
        firstFailed = false;
        offline = false;
        loading = false;
        hasMore = true;
        loadMoreFailed = false;
        maxId = null;
        refreshing = false;
        pulling = false;
        enteredUpTo = 0;
        listView.setTranslationY(0);
        refreshIndicator.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    // ---- list ----

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private boolean showEmpty() {
            return firstFailed || (firstLoaded && items.isEmpty());
        }

        private boolean showFooter() {
            return firstLoaded && (loadMoreFailed || (hasMore && !items.isEmpty()));
        }

        @Override
        public int getItemCount() {
            if (!firstLoaded && !firstFailed) {
                return 1 + SKELETON_COUNT;
            }
            if (showEmpty()) {
                return 2;
            }
            return 1 + rows() + (showFooter() ? 1 : 0);
        }

        // posts plus, without Premium, an ad slot after every AD_EVERY posts
        private int rows() {
            return items.size() + (adsOn ? items.size() / AD_EVERY : 0);
        }

        private boolean isAd(int row) {
            return adsOn && row % (AD_EVERY + 1) == AD_EVERY;
        }

        private int postIndex(int row) {
            return adsOn ? row / (AD_EVERY + 1) * AD_EVERY + row % (AD_EVERY + 1) : row;
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return TYPE_HEADER;
            }
            if (!firstLoaded && !firstFailed) {
                return TYPE_SKELETON;
            }
            if (showEmpty()) {
                return TYPE_EMPTY;
            }
            if (position - 1 < rows()) {
                return isAd(position - 1) ? TYPE_AD : TYPE_POST;
            }
            return TYPE_FOOTER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            final View view;
            switch (viewType) {
                case TYPE_HEADER:
                    view = headerView;
                    if (view.getParent() instanceof ViewGroup) {
                        ((ViewGroup) view.getParent()).removeView(view);
                    }
                    break;
                case TYPE_POST:
                    view = new NexPostCell(context, resourcesProvider, NexNativeFeedView.this);
                    break;
                case TYPE_AD:
                    view = new NexAds.Slot(context);
                    break;
                case TYPE_EMPTY:
                    view = new StateCard(context);
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
            if (view instanceof NexPostCell || view instanceof NexAds.Slot) {
                // a post seen for the first time rises in (the first ones one after another)
                if (position > enteredUpTo) {
                    enteredUpTo = position;
                    NexMotion.enter(view, position <= 3 ? position - 1 : 0);
                } else {
                    NexMotion.settle(view);
                }
            }
            if (view instanceof NexPostCell) {
                ((NexPostCell) view).setStatus(items.get(postIndex(position - 1)));
            } else if (view instanceof NexAds.Slot) {
                ((NexAds.Slot) view).fill();
            } else if (view instanceof FooterCell) {
                ((FooterCell) view).setFailed(loadMoreFailed);
            } else if (view instanceof StateCard) {
                ((StateCard) view).update();
            }
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            if (holder.itemView instanceof NexPostCell) {
                ((NexPostCell) holder.itemView).releaseVideo();
            }
        }
    }

    // Empty, error or offline state: a soft card with a title, a line of text and one button
    private class StateCard extends FrameLayout {
        private final TextView titleView;
        private final TextView textView;
        private final TextView button;

        StateCard(Context context) {
            super(context);
            final LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(22), AndroidUtilities.dp(22), AndroidUtilities.dp(20));
            card.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 28));
            titleView = NexLentaUi.serifTitle(context, 28);
            card.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            textView.setLineSpacing(AndroidUtilities.dp(2), 1f);
            textView.setTextColor(NexLentaColors.SECONDARY_TEXT);
            card.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
            button = NexLentaUi.pillButton(context, "", NexLentaUi.PILL_PRIMARY);
            card.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, 18, 0, 0));
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 16, 8, 16, 8));
        }

        void update() {
            if (firstFailed) {
                titleView.setText(LocaleController.getString(offline ? R.string.NexFeedOfflineTitle : R.string.NexFeedLoadError));
                textView.setText(offline ? LocaleController.getString(R.string.NexFeedOffline) : errorReason);
                textView.setVisibility(offline || errorReason != null ? View.VISIBLE : View.GONE);
                button.setText(LocaleController.getString(R.string.NexFeedLoginRetry));
                button.setOnClickListener(v -> reload());
            } else if (followingMode) {
                titleView.setText(LocaleController.getString(R.string.NexFeedEmptyFollowingTitle));
                textView.setText(LocaleController.getString(R.string.NexFeedEmptyFollowing));
                textView.setVisibility(View.VISIBLE);
                button.setText(LocaleController.getString(R.string.NexFeedShowForYou));
                button.setOnClickListener(v -> switchMode());
            } else {
                titleView.setText(LocaleController.getString(R.string.NexFeedEmptyTitle));
                textView.setText(LocaleController.getString(R.string.NexFeedEmptyForYou));
                textView.setVisibility(View.VISIBLE);
                button.setText(LocaleController.getString(R.string.NexComposeTitle));
                button.setOnClickListener(v -> delegate.onComposeClick());
            }
        }
    }

    // "Load more" spinner, or "Qayta urinish" after a failed page
    private class FooterCell extends FrameLayout {
        private final RadialProgressView progressView;
        private final TextView retryView;

        FooterCell(Context context) {
            super(context);
            progressView = new RadialProgressView(context);
            progressView.setSize(AndroidUtilities.dp(24));
            progressView.setProgressColor(NexLentaColors.TEXT);
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
                loadPage(false, maxId);
            });
            addView(retryView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        }

        void setFailed(boolean failed) {
            progressView.setVisibility(failed ? View.GONE : View.VISIBLE);
            retryView.setVisibility(failed ? View.VISIBLE : View.GONE);
        }
    }

    // Placeholder of a post (design shapes) while the first page loads, pulsing
    private static class SkeletonCell extends View {
        private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        SkeletonCell(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            final int mediaWidth = width - AndroidUtilities.dp(32);
            setMeasuredDimension(width, (int) (mediaWidth * 1.25f) + AndroidUtilities.dp(4 + 14 + 52 + 12 + 40 + 26));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float phase = (SystemClock.uptimeMillis() % 1200) / 1200f;
            final float alpha = 0.55f + 0.45f * (float) Math.abs(Math.sin(Math.PI * phase));
            shapePaint.setColor(Theme.multAlpha(NexLentaColors.PLACEHOLDER, alpha));
            final float d = AndroidUtilities.density;
            final float left = 16 * d;
            final float right = getWidth() - 16 * d;
            final float mediaBottom = 4 * d + (right - left) * 1.25f;
            rect.set(left, 4 * d, right, mediaBottom);
            canvas.drawRoundRect(rect, 28 * d, 28 * d, shapePaint);
            final float rowTop = mediaBottom + 14 * d;
            rect.set(left, rowTop, left + 100 * d, rowTop + 52 * d);
            canvas.drawRoundRect(rect, 26 * d, 26 * d, shapePaint);
            rect.set(left + 110 * d, rowTop, left + 190 * d, rowTop + 52 * d);
            canvas.drawRoundRect(rect, 26 * d, 26 * d, shapePaint);
            canvas.drawCircle(right - 26 * d, rowTop + 26 * d, 26 * d, shapePaint);
            final float textTop = rowTop + 52 * d + 14 * d;
            rect.set(left, textTop, right - 60 * d, textTop + 14 * d);
            canvas.drawRoundRect(rect, 7 * d, 7 * d, shapePaint);
            postInvalidateOnAnimation();
        }
    }
}
