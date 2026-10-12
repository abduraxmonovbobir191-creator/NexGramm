package org.telegram.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.ShareAlert;

import java.util.ArrayList;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.NexCover;
import uz.nexgramm.core.PixelfedAccount;
import uz.nexgramm.core.PixelfedClient;
import uz.nexgramm.core.PixelfedFormat;
import uz.nexgramm.core.PixelfedStatus;

// NexGramm: Lenta profile, wide and minimal (TikTok-like): a soft cover band in the app colors, name and
// @username with the avatar on the right, following / followers / posts, bio and link, one main button
// ("Obuna bo'lish" / "Tahrirlash") and share, icon tabs (posts + videos; own: posts, liked, saved) and an
// edge-to-edge 3-column grid of 3:4 tiles with like counts. A bar with the username fades in on scroll.
// accountId null = the user's own profile (GET /api/v1/accounts/verify_credentials).
public class NexProfileActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_TABS = 1;
    private static final int TYPE_TILE = 2;
    private static final int TYPE_STATE = 3;
    private static final int TAB_POSTS = 0;
    private static final int TAB_VIDEOS = 1;
    private static final int TAB_SAVED = 2;
    private static final int TAB_LIKED = 3;

    private static final int COVER_DP = 168;
    private static final int AVATAR_DP = 92;

    private String accountId;
    private PixelfedAccount account;
    private boolean own;
    private boolean following;
    private boolean followPending;
    private int generation;

    private final ArrayList<PixelfedStatus> posts = new ArrayList<>();
    private ArrayList<PixelfedStatus> saved;
    private ArrayList<PixelfedStatus> liked;
    private boolean postsLoading;
    private boolean postsFailed;
    private boolean hasMore = true;
    private String maxId;
    private int tab = TAB_POSTS;

    private RecyclerView listView;
    private GridLayoutManager layoutManager;
    private final Adapter adapter = new Adapter();
    private View headerView;
    private BackupImageView avatarView;
    private TextView nameView;
    private TextView usernameView;
    private TextView followingCountView;
    private TextView followersCountView;
    private TextView postsCountView;
    private TextView bioView;
    private TextView websiteView;
    private TextView mainButton;
    private TextView coverButton;
    private NexCoverView coverView;
    private LinearLayout tabsView;
    private View topBarBackground;
    private TextView topBarTitle;

    public NexProfileActivity(String accountId) {
        this.accountId = accountId;
    }

    private long tgUserId() {
        return getUserConfig().getClientUserId();
    }

    @Override
    public boolean isLightStatusBar() {
        return !NexLentaColors.dark;
    }

    @Override
    public View createView(Context context) {
        NexLentaColors.apply();
        actionBar.setAddToContainer(false);

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(NexLentaColors.BACKGROUND);

        listView = new RecyclerView(context);
        layoutManager = new GridLayoutManager(context, 3);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.getItemViewType(position) == TYPE_TILE ? 1 : 3;
            }
        });
        listView.setLayoutManager(layoutManager);
        listView.setItemAnimator(null);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(96));
        listView.setAdapter(adapter);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if ((tab == TAB_POSTS || tab == TAB_VIDEOS) && !postsLoading && hasMore && !postsFailed
                    && layoutManager.findLastVisibleItemPosition() >= adapter.getItemCount() - 6) {
                    loadPosts();
                }
                updateTopBar();
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        headerView = createHeader(context);
        tabsView = new LinearLayout(context);
        tabsView.setOrientation(LinearLayout.HORIZONTAL);

        root.addView(createTopBar(context), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        fragmentView = root;
        loadAccount();
        return fragmentView;
    }

    // ---- top bar: back, username (fades in), share, menu ----

    private View createTopBar(Context context) {
        final FrameLayout bar = new FrameLayout(context);
        bar.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        topBarBackground = new View(context);
        topBarBackground.setBackgroundColor(NexLentaColors.BACKGROUND);
        topBarBackground.setAlpha(0f);
        bar.addView(topBarBackground, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final ImageView back = NexLentaUi.circleButton(context, R.drawable.ic_ab_back, NexLentaUi.PILL_CARD, true);
        back.setOnClickListener(v -> finishFragment());
        bar.addView(back, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 6, 0, 6));

        topBarTitle = new TextView(context);
        topBarTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        topBarTitle.setTypeface(AndroidUtilities.bold());
        topBarTitle.setTextColor(NexLentaColors.TEXT);
        topBarTitle.setSingleLine(true);
        topBarTitle.setEllipsize(TextUtils.TruncateAt.END);
        topBarTitle.setGravity(Gravity.CENTER);
        topBarTitle.setAlpha(0f);
        bar.addView(topBarTitle, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 72, 0, 120, 0));

        final ImageView share = NexLentaUi.circleButton(context, R.drawable.msg_send, NexLentaUi.PILL_CARD, true);
        share.setOnClickListener(v -> shareProfile());
        bar.addView(share, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 6, 64, 6));
        final ImageView more = NexLentaUi.circleButton(context, R.drawable.ic_ab_other, NexLentaUi.PILL_CARD, true);
        more.setRotation(90);
        more.setOnClickListener(v -> showMenu());
        bar.addView(more, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 6, 12, 6));
        return bar;
    }

    private void updateTopBar() {
        if (topBarBackground == null) {
            return;
        }
        float progress = 1f;
        if (layoutManager.findFirstVisibleItemPosition() == 0) {
            final View first = layoutManager.findViewByPosition(0);
            if (first != null) {
                final float range = AndroidUtilities.dp(COVER_DP) - AndroidUtilities.statusBarHeight - AndroidUtilities.dp(56);
                progress = Math.max(0f, Math.min(1f, -first.getTop() / Math.max(1f, range)));
            }
        }
        topBarBackground.setAlpha(progress);
        topBarTitle.setAlpha(Math.max(0f, progress * 2f - 1f));
    }

    // ---- header ----

    private View createHeader(Context context) {
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        // the user's cover (photo, muted video, color / emoji), else a soft band in the app colors
        coverView = new NexCoverView(context, true);
        column.addView(coverView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, COVER_DP));

        // name + @username (left), avatar (right) overlapping the band
        final FrameLayout identity = new FrameLayout(context);
        final FrameLayout avatarFrame = new FrameLayout(context);
        final GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(NexLentaColors.BACKGROUND);
        avatarFrame.setBackground(ring);
        avatarFrame.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));
        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(AndroidUtilities.dp(AVATAR_DP / 2f));
        avatarFrame.addView(avatarView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        identity.addView(avatarFrame, LayoutHelper.createFrame(AVATAR_DP, AVATAR_DP, Gravity.RIGHT | Gravity.TOP, 0, 0, 16, 0));

        final LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(NexLentaColors.TEXT);
        nameView.setMaxLines(2);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        usernameView = new TextView(context);
        usernameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        usernameView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        usernameView.setSingleLine(true);
        usernameView.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(usernameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        identity.addView(names, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 16, AVATAR_DP / 2f + 6, AVATAR_DP + 28, 0));
        column.addView(identity, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, -AVATAR_DP / 2f, 0, 0));

        // following / followers / posts
        final LinearLayout stats = new LinearLayout(context);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        followingCountView = addStat(context, stats, R.string.NexProfileFollowingLabel);
        followersCountView = addStat(context, stats, R.string.NexProfileFollowersLabel);
        postsCountView = addStat(context, stats, R.string.NexProfilePostsLabel);
        column.addView(stats, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        bioView = new TextView(context);
        bioView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        bioView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        bioView.setTextColor(NexLentaColors.TEXT);
        bioView.setMaxLines(4);
        bioView.setEllipsize(TextUtils.TruncateAt.END);
        bioView.setVisibility(View.GONE);
        column.addView(bioView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 0));

        websiteView = new TextView(context);
        websiteView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        websiteView.setTypeface(AndroidUtilities.bold());
        websiteView.setTextColor(NexLentaColors.TEXT);
        websiteView.setSingleLine(true);
        websiteView.setEllipsize(TextUtils.TruncateAt.END);
        websiteView.setGravity(Gravity.CENTER_VERTICAL);
        websiteView.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.msg_link, 0, 0, 0);
        websiteView.setCompoundDrawablePadding(AndroidUtilities.dp(6));
        for (android.graphics.drawable.Drawable d : websiteView.getCompoundDrawablesRelative()) {
            if (d != null) {
                d.setColorFilter(new PorterDuffColorFilter(NexLentaColors.TEXT, PorterDuff.Mode.SRC_IN));
            }
        }
        websiteView.setVisibility(View.GONE);
        websiteView.setOnClickListener(v -> openWebsite());
        column.addView(websiteView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, Gravity.LEFT, 16, 6, 16, 0));

        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        mainButton = NexLentaUi.pillButton(context, "", NexLentaUi.PILL_PRIMARY);
        mainButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        mainButton.setVisibility(View.INVISIBLE);
        mainButton.setOnClickListener(v -> {
            if (own) {
                openEditor();
            } else {
                toggleFollow();
            }
        });
        buttons.addView(mainButton, LayoutHelper.createLinear(0, 46, 1f));
        coverButton = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexCoverButton), NexLentaUi.PILL_CARD);
        coverButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        coverButton.setVisibility(View.GONE);
        coverButton.setOnClickListener(v -> openCoverEditor());
        buttons.addView(coverButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 46, 10, 0, 0, 0));
        final ImageView shareButton = NexLentaUi.circleButton(context, R.drawable.msg_share, NexLentaUi.PILL_CARD, false);
        shareButton.setOnClickListener(v -> shareProfile());
        buttons.addView(shareButton, LayoutHelper.createLinear(46, 46, 10, 0, 0, 0));
        column.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 18, 16, 16));
        return column;
    }

    private TextView addStat(Context context, LinearLayout stats, int labelRes) {
        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView number = new TextView(context);
        number.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
        number.setTypeface(AndroidUtilities.bold());
        number.setTextColor(NexLentaColors.TEXT);
        number.setText("—");
        box.addView(number, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        final TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        label.setTextColor(NexLentaColors.SECONDARY_TEXT);
        label.setText(LocaleController.getString(labelRes));
        box.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        stats.addView(box, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 28, 0));
        return number;
    }

    private void rebuildTabs() {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        tabsView.removeAllViews();
        final int[] tabs = own ? new int[]{TAB_POSTS, TAB_LIKED, TAB_SAVED} : new int[]{TAB_POSTS, TAB_VIDEOS};
        final int[] icons = own
            ? new int[]{R.drawable.nex_ic_grid, R.drawable.nex_ic_heart, R.drawable.nex_ic_bookmark}
            : new int[]{R.drawable.nex_ic_grid, R.drawable.nex_ic_video};
        for (int i = 0; i < tabs.length; i++) {
            final int t = tabs[i];
            final FrameLayout cell = new FrameLayout(context);
            final ImageView icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setImageResource(icons[i]);
            icon.setColorFilter(new PorterDuffColorFilter(t == tab ? NexLentaColors.TEXT : NexLentaColors.SECONDARY_TEXT, PorterDuff.Mode.SRC_IN));
            cell.addView(icon, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            final View divider = new View(context);
            divider.setBackgroundColor(NexLentaColors.DIVIDER);
            cell.addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1, Gravity.BOTTOM));
            if (t == tab) {
                final View underline = new View(context);
                underline.setBackground(NexLentaUi.roundRect(NexLentaColors.TEXT, 2));
                cell.addView(underline, LayoutHelper.createFrame(36, 2.5f, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
            }
            cell.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(NexLentaColors.TEXT, 0.06f), Theme.RIPPLE_MASK_ALL));
            cell.setOnClickListener(v -> selectTab(t));
            tabsView.addView(cell, LayoutHelper.createLinear(0, 48, 1f));
        }
    }

    private void selectTab(int t) {
        if (tab == t) {
            return;
        }
        tab = t;
        rebuildTabs();
        if (tab == TAB_SAVED && saved == null) {
            loadList(true);
        } else if (tab == TAB_LIKED && liked == null) {
            loadList(false);
        }
        adapter.notifyDataSetChanged();
    }

    private boolean isListTab() {
        return tab == TAB_SAVED || tab == TAB_LIKED;
    }

    private ArrayList<PixelfedStatus> visibleTiles() {
        if (tab == TAB_SAVED) {
            return saved == null ? new ArrayList<>() : saved;
        }
        if (tab == TAB_LIKED) {
            return liked == null ? new ArrayList<>() : liked;
        }
        if (tab == TAB_VIDEOS) {
            final ArrayList<PixelfedStatus> videos = new ArrayList<>();
            for (PixelfedStatus s : posts) {
                if (s.hasVideo()) {
                    videos.add(s);
                }
            }
            return videos;
        }
        return posts;
    }

    // ---- data ----

    private void loadAccount() {
        final int gen = ++generation;
        final long userId = tgUserId();
        final String requestedId = accountId;
        CoreClient.EXECUTOR.execute(() -> {
            PixelfedAccount result = null;
            boolean isFollowing = false;
            try {
                result = requestedId == null ? PixelfedClient.ownAccountBlocking(userId) : PixelfedClient.accountBlocking(userId, requestedId);
                final String ownName = PixelfedClient.ownUsername(userId);
                if (requestedId != null && (ownName == null || !ownName.equalsIgnoreCase(result.username))) {
                    final Boolean f = PixelfedClient.relationshipsBlocking(userId, java.util.Collections.singleton(requestedId)).get(requestedId);
                    isFollowing = Boolean.TRUE.equals(f);
                }
            } catch (Exception ignore) {
            }
            final PixelfedAccount finalResult = result;
            final boolean finalFollowing = isFollowing;
            final String ownName = PixelfedClient.ownUsername(userId);
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || fragmentView == null) {
                    return;
                }
                if (finalResult == null) {
                    nameView.setText(LocaleController.getString(R.string.NexFeedLoadError));
                    postsFailed = true;
                    adapter.notifyDataSetChanged();
                    return;
                }
                account = finalResult;
                accountId = finalResult.id;
                own = ownName != null && ownName.equalsIgnoreCase(finalResult.username);
                following = finalFollowing;
                bindAccount();
                rebuildTabs();
                adapter.notifyDataSetChanged();
                loadPosts();
            });
        });
    }

    private void bindAccount() {
        if (account.avatarUrl == null || account.avatarUrl.contains("/avatars/default")) {
            avatarView.setImageDrawable(NexLentaUi.initialsAvatar(account.displayName, true));
        } else {
            avatarView.setImage(account.avatarUrl, "140_140", NexLentaUi.initialsAvatar(account.displayName, true));
        }
        nameView.setText(account.displayName);
        usernameView.setText("@" + account.username);
        topBarTitle.setText(account.displayName);
        updateCounts();
        final String bio = NexCommentsActivity.htmlToText(account.noteHtml);
        bioView.setText(bio);
        bioView.setVisibility(bio.isEmpty() ? View.GONE : View.VISIBLE);
        if (account.website != null) {
            websiteView.setText(account.website.replaceFirst("^https?://", ""));
            websiteView.setVisibility(View.VISIBLE);
        } else {
            websiteView.setVisibility(View.GONE);
        }
        mainButton.setVisibility(View.VISIBLE);
        coverButton.setVisibility(own ? View.VISIBLE : View.GONE);
        updateMainButton();
        loadCover();
    }

    // Cached cover at once, then the fresh one from the core (GET /v1/covers/lenta/{username})
    private void loadCover() {
        final String username = account.username;
        if (NexCover.isCached(username)) {
            coverView.setCover(NexCover.cached(username));
        }
        final int gen = generation;
        CoreClient.EXECUTOR.execute(() -> {
            NexCover cover;
            try {
                cover = NexCover.loadBlocking(username);
            } catch (Exception e) {
                return; // core not reachable: keep what is shown
            }
            final NexCover result = cover;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen == generation && fragmentView != null) {
                    coverView.setCover(result);
                }
            });
        });
    }

    private void openCoverEditor() {
        if (account == null) {
            return;
        }
        presentFragment(new NexCoverEditActivity(account, NexCover.cached(account.username), cover -> {
            if (fragmentView != null) {
                coverView.setCover(cover);
            }
        }));
    }

    private void updateCounts() {
        followingCountView.setText(PixelfedFormat.count(account.followingCount));
        followersCountView.setText(PixelfedFormat.count(account.followersCount));
        postsCountView.setText(PixelfedFormat.count(account.statusesCount));
    }

    private void updateMainButton() {
        if (own) {
            mainButton.setText(LocaleController.getString(R.string.NexProfileEdit));
            NexLentaUi.stylePill(mainButton, NexLentaUi.PILL_CARD);
        } else {
            mainButton.setText(LocaleController.getString(following ? R.string.NexProfileFollowingState : R.string.NexProfileFollow));
            NexLentaUi.stylePill(mainButton, following ? NexLentaUi.PILL_CARD : NexLentaUi.PILL_PRIMARY);
        }
        mainButton.setAlpha(followPending ? 0.6f : 1f);
    }

    private void openEditor() {
        if (account == null) {
            return;
        }
        presentFragment(new NexProfileEditActivity(account, updated -> {
            account = updated;
            if (fragmentView != null) {
                bindAccount();
            }
        }));
    }

    private void loadPosts() {
        if (accountId == null || postsLoading || !hasMore) {
            return;
        }
        postsLoading = true;
        postsFailed = false;
        final int gen = generation;
        final long userId = tgUserId();
        final String fromId = maxId;
        final String id = accountId;
        CoreClient.EXECUTOR.execute(() -> {
            ArrayList<PixelfedStatus> page = null;
            try {
                page = PixelfedClient.accountMediaBlocking(userId, id, fromId);
            } catch (Exception ignore) {
            }
            final ArrayList<PixelfedStatus> result = page;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || fragmentView == null) {
                    return;
                }
                postsLoading = false;
                if (result == null) {
                    postsFailed = true;
                } else {
                    hasMore = !result.isEmpty();
                    if (!result.isEmpty()) {
                        maxId = result.get(result.size() - 1).id;
                        posts.addAll(result);
                    }
                }
                adapter.notifyDataSetChanged();
            });
        });
    }

    // Saved (bookmarks) or liked (favourites) posts of the own profile, one page
    private void loadList(boolean savedList) {
        final int gen = generation;
        final long userId = tgUserId();
        CoreClient.EXECUTOR.execute(() -> {
            ArrayList<PixelfedStatus> result = null;
            try {
                result = savedList ? PixelfedClient.bookmarksBlocking(userId) : PixelfedClient.favouritesBlocking(userId);
            } catch (Exception ignore) {
            }
            final ArrayList<PixelfedStatus> finalResult = result;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || fragmentView == null) {
                    return;
                }
                final ArrayList<PixelfedStatus> list = new ArrayList<>();
                if (finalResult != null) {
                    for (PixelfedStatus s : finalResult) {
                        if (!s.media.isEmpty()) {
                            list.add(s);
                        }
                    }
                }
                if (savedList) {
                    saved = list;
                } else {
                    liked = list;
                }
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void toggleFollow() {
        if (account == null || followPending) {
            return;
        }
        final boolean follow = !following;
        followPending = true;
        following = follow;
        account.followersCount += follow ? 1 : -1;
        updateCounts();
        updateMainButton();
        final long userId = tgUserId();
        final String id = accountId;
        CoreClient.EXECUTOR.execute(() -> {
            boolean ok = false;
            try {
                PixelfedClient.setFollowBlocking(userId, id, follow);
                ok = true;
            } catch (Exception ignore) {
            }
            final boolean success = ok;
            AndroidUtilities.runOnUIThread(() -> {
                followPending = false;
                if (!success) {
                    following = !follow;
                    account.followersCount += follow ? -1 : 1;
                    if (getParentActivity() != null) {
                        Toast.makeText(getParentActivity(), LocaleController.getString(R.string.NexActionFailed), Toast.LENGTH_SHORT).show();
                    }
                }
                if (fragmentView != null) {
                    updateCounts();
                    updateMainButton();
                }
            });
        });
    }

    private void shareProfile() {
        if (account == null || getParentActivity() == null) {
            return;
        }
        showDialog(ShareAlert.createShareAlert(getParentActivity(), null, account.url, false, account.url, false));
    }

    private void openWebsite() {
        if (account == null || account.website == null) {
            return;
        }
        final String url = account.website.startsWith("http") ? account.website : "https://" + account.website;
        org.telegram.messenger.browser.Browser.openUrl(getParentActivity(), Uri.parse(url));
    }

    private void showMenu() {
        if (account == null || getParentActivity() == null) {
            return;
        }
        final Context context = getParentActivity();
        final BottomSheet.Builder builder = new BottomSheet.Builder(context);
        final CharSequence[] items = own
            ? new CharSequence[]{LocaleController.getString(R.string.NexPostCopyLink), LocaleController.getString(R.string.NexProfileEdit), LocaleController.getString(R.string.NexCoverChange)}
            : new CharSequence[]{LocaleController.getString(R.string.NexPostCopyLink), LocaleController.getString(R.string.NexPostReport)};
        final int[] icons = own ? new int[]{R.drawable.msg_link, R.drawable.msg_edit, R.drawable.msg_photoeditor} : new int[]{R.drawable.msg_link, R.drawable.msg_report};
        builder.setItems(items, icons, (dialog, which) -> {
            if (which == 0) {
                AndroidUtilities.addToClipboard(account.url);
                Toast.makeText(context, LocaleController.getString(R.string.NexPostLinkCopied), Toast.LENGTH_SHORT).show();
            } else if (own && which == 1) {
                openEditor();
            } else if (own) {
                openCoverEditor();
            } else {
                NexPostActions.showReport(this, tgUserId(), "user", account.id);
            }
        });
        showDialog(builder.create());
    }

    private void openPost(PixelfedStatus status) {
        presentFragment(new NexCommentsActivity(status));
    }

    @Override
    public void onPause() {
        super.onPause();
        if (coverView != null) {
            coverView.pause();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (coverView != null) {
            coverView.resume();
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        generation++;
        if (coverView != null) {
            coverView.release();
        }
    }

    // ---- list ----

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private boolean showState() {
            final ArrayList<PixelfedStatus> tiles = visibleTiles();
            return tiles.isEmpty() || (!isListTab() && (postsLoading || postsFailed || hasMore));
        }

        @Override
        public int getItemCount() {
            if (account == null) {
                return postsFailed ? 2 : 1;
            }
            return 2 + visibleTiles().size() + (showState() ? 1 : 0);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return TYPE_HEADER;
            }
            if (account == null) {
                return TYPE_STATE;
            }
            if (position == 1) {
                return TYPE_TABS;
            }
            return position - 2 < visibleTiles().size() ? TYPE_TILE : TYPE_STATE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            View view;
            if (viewType == TYPE_HEADER || viewType == TYPE_TABS) {
                view = viewType == TYPE_HEADER ? headerView : tabsView;
                if (view.getParent() instanceof ViewGroup) {
                    ((ViewGroup) view.getParent()).removeView(view);
                }
            } else if (viewType == TYPE_TILE) {
                view = new TileView(context);
            } else {
                view = new StateView(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.itemView instanceof TileView) {
                ((TileView) holder.itemView).set(visibleTiles().get(position - 2), (position - 2) % 3);
            } else if (holder.itemView instanceof StateView) {
                ((StateView) holder.itemView).update();
            }
        }
    }

    // 3:4 edge-to-edge tile: centre-cropped preview, ♥ count at the bottom, ▶ for videos, album mark
    private class TileView extends FrameLayout {
        private final BackupImageView imageView;
        private final ImageView typeMark;
        private final TextView likesView;
        private PixelfedStatus status;

        TileView(Context context) {
            super(context);
            imageView = new BackupImageView(context);
            imageView.setBackgroundColor(NexLentaColors.PLACEHOLDER);
            addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            final View shade = new View(context);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x66000000, 0x00000000}));
            addView(shade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.BOTTOM));
            likesView = new TextView(context);
            likesView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            likesView.setTypeface(AndroidUtilities.bold());
            likesView.setTextColor(0xFFFFFFFF);
            likesView.setShadowLayer(AndroidUtilities.dp(2), 0, 0, 0x66000000);
            likesView.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.nex_ic_heart, 0, 0, 0);
            likesView.setCompoundDrawablePadding(AndroidUtilities.dp(3));
            for (android.graphics.drawable.Drawable d : likesView.getCompoundDrawablesRelative()) {
                if (d != null) {
                    d.setBounds(0, 0, AndroidUtilities.dp(15), AndroidUtilities.dp(15));
                }
            }
            likesView.setCompoundDrawablesRelative(likesView.getCompoundDrawablesRelative()[0], null, null, null);
            addView(likesView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 8, 0, 0, 7));
            typeMark = new ImageView(context);
            typeMark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            typeMark.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            addView(typeMark, LayoutHelper.createFrame(20, 20, Gravity.TOP | Gravity.RIGHT, 0, 8, 8, 0));
            setOnClickListener(v -> {
                if (status != null) {
                    openPost(status);
                }
            });
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(width * 4 / 3, MeasureSpec.EXACTLY));
        }

        // column 0..2: 1 px gaps between tiles only
        void set(PixelfedStatus status, int column) {
            this.status = status;
            final int gap = Math.max(1, AndroidUtilities.dp(1));
            setPadding(column == 0 ? 0 : gap / 2 + gap % 2, 0, column == 2 ? 0 : gap / 2, gap);
            final PixelfedStatus.Media first = status.media.isEmpty() ? null : status.media.get(0);
            final String image = first == null ? null : first.video ? first.previewUrl : first.url;
            final int w = (int) (AndroidUtilities.displaySize.x / AndroidUtilities.density / 3);
            imageView.setImage(image, w + "_" + (w * 4 / 3), null);
            likesView.setText(PixelfedFormat.count(status.favouritesCount));
            if (status.hasVideo()) {
                typeMark.setImageResource(R.drawable.nex_ic_play);
                typeMark.setVisibility(View.VISIBLE);
            } else if (status.media.size() > 1) {
                typeMark.setImageResource(R.drawable.msg_copy);
                typeMark.setVisibility(View.VISIBLE);
            } else {
                typeMark.setVisibility(View.GONE);
            }
        }
    }

    // Loading, error (tap to retry) or empty
    private class StateView extends FrameLayout {
        private final RadialProgressView progressView;
        private final TextView textView;

        StateView(Context context) {
            super(context);
            progressView = new RadialProgressView(context);
            progressView.setSize(AndroidUtilities.dp(24));
            progressView.setProgressColor(NexLentaColors.TEXT);
            addView(progressView, LayoutHelper.createFrame(32, 32, Gravity.CENTER, 0, 24, 0, 24));
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            textView.setGravity(Gravity.CENTER);
            textView.setTextColor(NexLentaColors.SECONDARY_TEXT);
            textView.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(40), AndroidUtilities.dp(24), AndroidUtilities.dp(40));
            textView.setOnClickListener(v -> {
                if (postsFailed) {
                    postsFailed = false;
                    if (account == null) {
                        loadAccount();
                    } else {
                        loadPosts();
                    }
                    adapter.notifyDataSetChanged();
                }
            });
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        }

        void update() {
            final boolean loadingNow = !postsFailed && (account == null
                || (tab == TAB_SAVED ? saved == null : tab == TAB_LIKED ? liked == null : (postsLoading || hasMore)));
            progressView.setVisibility(loadingNow ? View.VISIBLE : View.GONE);
            textView.setVisibility(loadingNow ? View.GONE : View.VISIBLE);
            final int empty = tab == TAB_SAVED ? R.string.NexProfileNoSaved : tab == TAB_LIKED ? R.string.NexProfileNoLiked : R.string.NexProfileNoPosts;
            textView.setText(postsFailed
                ? LocaleController.getString(R.string.NexFeedLoadError) + "\n" + LocaleController.getString(R.string.NexFeedLoginRetry)
                : LocaleController.getString(empty));
        }
    }
}
