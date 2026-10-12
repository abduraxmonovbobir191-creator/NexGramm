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
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.ShareAlert;

import java.util.ArrayList;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.PixelfedAccount;
import uz.nexgramm.core.PixelfedClient;
import uz.nexgramm.core.PixelfedFormat;
import uz.nexgramm.core.PixelfedStatus;

// NexGramm: Lenta profile as in the design screenshots: cover (the latest photo, else a soft gradient),
// big serif name, avatar with a ring, following / followers / posts, bio, website chip, "Obuna bo'lish"
// (others) or "Tahrirlash" (own), tabs and a 3-column grid (3:4 tiles). A tile opens the post.
// accountId null = the user's own profile (GET /api/v1/accounts/verify_credentials).
public class NexProfileActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_TABS = 1;
    private static final int TYPE_TILE = 2;
    private static final int TYPE_STATE = 3;
    private static final int TAB_POSTS = 0;
    private static final int TAB_VIDEOS = 1;
    private static final int TAB_SAVED = 2;

    private String accountId;
    private PixelfedAccount account;
    private boolean own;
    private boolean following;
    private boolean followPending;
    private int generation;

    private final ArrayList<PixelfedStatus> posts = new ArrayList<>();
    private ArrayList<PixelfedStatus> saved;
    private boolean postsLoading;
    private boolean postsFailed;
    private boolean hasMore = true;
    private String maxId;
    private int tab = TAB_POSTS;

    private RecyclerView listView;
    private final Adapter adapter = new Adapter();
    private View headerView;
    private BackupImageView coverView;
    private BackupImageView avatarView;
    private TextView nameView;
    private TextView usernameView;
    private TextView followingCountView;
    private TextView followersCountView;
    private TextView postsCountView;
    private TextView bioView;
    private TextView websiteView;
    private TextView mainButton;
    private LinearLayout tabsView;

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
        final GridLayoutManager layoutManager = new GridLayoutManager(context, 3);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.getItemViewType(position) == TYPE_TILE ? 1 : 3;
            }
        });
        listView.setLayoutManager(layoutManager);
        listView.setItemAnimator(null);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(24));
        listView.setAdapter(adapter);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (tab != TAB_SAVED && !postsLoading && hasMore && !postsFailed
                    && layoutManager.findLastVisibleItemPosition() >= adapter.getItemCount() - 6) {
                    loadPosts();
                }
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        headerView = createHeader(context);
        tabsView = new LinearLayout(context);
        tabsView.setOrientation(LinearLayout.HORIZONTAL);

        // floating top buttons over the cover
        final int top = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(10);
        final ImageView back = NexLentaUi.circleButton(context, R.drawable.ic_ab_back, NexLentaUi.PILL_CARD, true);
        back.setOnClickListener(v -> finishFragment());
        root.addView(back, LayoutHelper.createFrame(52, 52, Gravity.TOP | Gravity.LEFT, 18, 0, 0, 0));
        back.setTranslationY(top);
        final ImageView share = NexLentaUi.circleButton(context, R.drawable.msg_send, NexLentaUi.PILL_CARD, true);
        share.setOnClickListener(v -> shareProfile());
        root.addView(share, LayoutHelper.createFrame(52, 52, Gravity.TOP | Gravity.RIGHT, 0, 0, 82, 0));
        share.setTranslationY(top);
        final ImageView more = NexLentaUi.circleButton(context, R.drawable.ic_ab_other, NexLentaUi.PILL_CARD, true);
        more.setRotation(90);
        more.setOnClickListener(v -> showMenu());
        root.addView(more, LayoutHelper.createFrame(52, 52, Gravity.TOP | Gravity.RIGHT, 0, 0, 18, 0));
        more.setTranslationY(top);

        fragmentView = root;
        loadAccount();
        return fragmentView;
    }

    // ---- header ----

    private View createHeader(Context context) {
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        final FrameLayout cover = new FrameLayout(context);
        final GradientDrawable coverGradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            NexLentaColors.dark ? new int[]{0xFF2B2838, 0xFF2E2A2A} : new int[]{0xFFCCD2E8, 0xFFF0DCD3});
        cover.setBackground(coverGradient);
        coverView = new BackupImageView(context);
        cover.addView(coverView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        final View fade = new View(context);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000 | (NexLentaColors.BACKGROUND & 0x00FFFFFF), NexLentaColors.BACKGROUND}));
        cover.addView(fade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 140, Gravity.BOTTOM));
        column.addView(cover, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 300));

        // name (left) and avatar (right) overlapping the cover's bottom
        final FrameLayout identity = new FrameLayout(context);
        final FrameLayout avatarRing = new FrameLayout(context);
        final GradientDrawable ring = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFC9D8F4, 0xFFE4D3F6, 0xFFF6E3D8});
        ring.setShape(GradientDrawable.OVAL);
        avatarRing.setBackground(ring);
        avatarRing.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));
        final FrameLayout avatarInner = new FrameLayout(context);
        final GradientDrawable inner = new GradientDrawable();
        inner.setShape(GradientDrawable.OVAL);
        inner.setColor(NexLentaColors.CARD);
        avatarInner.setBackground(inner);
        avatarInner.setPadding(AndroidUtilities.dp(5), AndroidUtilities.dp(5), AndroidUtilities.dp(5), AndroidUtilities.dp(5));
        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(AndroidUtilities.dp(70));
        avatarInner.addView(avatarView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        avatarRing.addView(avatarInner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        identity.addView(avatarRing, LayoutHelper.createFrame(156, 156, Gravity.RIGHT | Gravity.TOP, 0, 0, 20, 0));

        final LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        nameView = NexLentaUi.serifTitle(context, 50);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        usernameView = new TextView(context);
        usernameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        usernameView.setTypeface(AndroidUtilities.bold());
        usernameView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        usernameView.setSingleLine(true);
        usernameView.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(usernameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        identity.addView(names, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.BOTTOM, 24, 0, 186, 6));
        column.addView(identity, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 156, 0, -104, 0, 0));

        // following / followers / posts (serif numbers)
        final LinearLayout stats = new LinearLayout(context);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        followingCountView = addStat(context, stats, R.string.NexProfileFollowingLabel);
        followersCountView = addStat(context, stats, R.string.NexProfileFollowersLabel);
        postsCountView = addStat(context, stats, R.string.NexProfilePostsLabel);
        column.addView(stats, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 14, 24, 0));

        bioView = new TextView(context);
        bioView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        bioView.setLineSpacing(AndroidUtilities.dp(3), 1f);
        bioView.setTextColor(NexLentaColors.TEXT);
        bioView.setVisibility(View.GONE);
        column.addView(bioView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 16, 24, 0));

        websiteView = NexLentaUi.pillButton(context, "", NexLentaUi.PILL_CARD);
        websiteView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        websiteView.setEllipsize(TextUtils.TruncateAt.END);
        websiteView.setMaxWidth(AndroidUtilities.dp(260));
        websiteView.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.msg_link, 0, 0, 0);
        websiteView.setCompoundDrawablePadding(AndroidUtilities.dp(6));
        NexLentaUi.stylePill(websiteView, NexLentaUi.PILL_CARD);
        websiteView.setVisibility(View.GONE);
        websiteView.setOnClickListener(v -> openWebsite());
        column.addView(websiteView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44, Gravity.LEFT, 24, 14, 24, 0));

        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        mainButton = NexLentaUi.pillButton(context, "", NexLentaUi.PILL_PRIMARY);
        mainButton.setVisibility(View.INVISIBLE);
        mainButton.setOnClickListener(v -> {
            if (own) {
                NexPostActions.aiSoon(getParentActivity());
            } else {
                toggleFollow();
            }
        });
        buttons.addView(mainButton, LayoutHelper.createLinear(0, 56, 1f));
        final ImageView shareButton = NexLentaUi.circleButton(context, R.drawable.msg_share, NexLentaUi.PILL_CARD, false);
        shareButton.setOnClickListener(v -> shareProfile());
        buttons.addView(shareButton, LayoutHelper.createLinear(56, 56, 12, 0, 0, 0));
        column.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 18, 24, 22));
        return column;
    }

    private TextView addStat(Context context, LinearLayout stats, int labelRes) {
        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView number = NexLentaUi.serifTitle(context, 34);
        number.setText("—");
        box.addView(number, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        final TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        label.setTextColor(NexLentaColors.SECONDARY_TEXT);
        label.setText(LocaleController.getString(labelRes));
        box.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        stats.addView(box, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 36, 0));
        return number;
    }

    private void rebuildTabs() {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        tabsView.removeAllViews();
        final int[] tabs = own ? new int[]{TAB_POSTS, TAB_SAVED} : new int[]{TAB_POSTS, TAB_VIDEOS};
        final int[] icons = own ? new int[]{R.drawable.msg_media, R.drawable.msg_saved} : new int[]{R.drawable.msg_media, R.drawable.msg_video};
        for (int i = 0; i < tabs.length; i++) {
            final int t = tabs[i];
            final FrameLayout cell = new FrameLayout(context);
            final ImageView icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setImageResource(icons[i]);
            icon.setColorFilter(new PorterDuffColorFilter(t == tab ? NexLentaColors.TEXT : NexLentaColors.SECONDARY_TEXT, PorterDuff.Mode.SRC_IN));
            cell.addView(icon, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            final View underline = new View(context);
            underline.setBackgroundColor(t == tab ? NexLentaColors.TEXT : NexLentaColors.DIVIDER);
            cell.addView(underline, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, t == tab ? 2 : 1, Gravity.BOTTOM));
            cell.setOnClickListener(v -> selectTab(t));
            tabsView.addView(cell, LayoutHelper.createLinear(0, 52, 1f));
        }
    }

    private void selectTab(int t) {
        if (tab == t) {
            return;
        }
        tab = t;
        rebuildTabs();
        if (tab == TAB_SAVED && saved == null) {
            loadSaved();
        }
        adapter.notifyDataSetChanged();
    }

    private ArrayList<PixelfedStatus> visibleTiles() {
        if (tab == TAB_SAVED) {
            return saved == null ? new ArrayList<>() : saved;
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
        updateCounts();
        final String bio = NexCommentsActivity.htmlToText(account.noteHtml);
        bioView.setText(bio);
        bioView.setVisibility(bio.isEmpty() ? View.GONE : View.VISIBLE);
        if (account.website != null) {
            websiteView.setText(account.website.replaceFirst("^https?://", ""));
            websiteView.setVisibility(View.VISIBLE);
        }
        mainButton.setVisibility(View.VISIBLE);
        updateMainButton();
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
                    if (fromId == null) {
                        bindCover();
                    }
                }
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void loadSaved() {
        final int gen = generation;
        final long userId = tgUserId();
        CoreClient.EXECUTOR.execute(() -> {
            ArrayList<PixelfedStatus> result = null;
            try {
                result = PixelfedClient.bookmarksBlocking(userId);
            } catch (Exception ignore) {
            }
            final ArrayList<PixelfedStatus> finalResult = result;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || fragmentView == null) {
                    return;
                }
                saved = finalResult != null ? finalResult : new ArrayList<>();
                adapter.notifyDataSetChanged();
            });
        });
    }

    // The latest photo as the cover (design: the profile's own picture), else the soft gradient
    private void bindCover() {
        for (PixelfedStatus s : posts) {
            if (!s.media.isEmpty()) {
                final PixelfedStatus.Media m = s.media.get(0);
                final String image = m.video ? m.previewUrl : m.url;
                if (image != null) {
                    final int w = (int) (AndroidUtilities.displaySize.x / AndroidUtilities.density);
                    coverView.setImage(image, w + "_300", null);
                    return;
                }
            }
        }
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
            ? new CharSequence[]{LocaleController.getString(R.string.NexPostCopyLink)}
            : new CharSequence[]{LocaleController.getString(R.string.NexPostCopyLink), LocaleController.getString(R.string.NexPostReport)};
        final int[] icons = own ? new int[]{R.drawable.msg_link} : new int[]{R.drawable.msg_link, R.drawable.msg_report};
        builder.setItems(items, icons, (dialog, which) -> {
            if (which == 0) {
                AndroidUtilities.addToClipboard(account.url);
                Toast.makeText(context, LocaleController.getString(R.string.NexPostLinkCopied), Toast.LENGTH_SHORT).show();
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
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        generation++;
    }

    // ---- list ----

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private boolean showState() {
            final ArrayList<PixelfedStatus> tiles = visibleTiles();
            return tiles.isEmpty() || (tab != TAB_SAVED && (postsLoading || postsFailed || hasMore));
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
                ((TileView) holder.itemView).set(visibleTiles().get(position - 2));
            } else if (holder.itemView instanceof StateView) {
                ((StateView) holder.itemView).update();
            }
        }
    }

    // 3:4 tile: centre-cropped preview, ▷ for videos, album mark for several photos
    private class TileView extends FrameLayout {
        private final BackupImageView imageView;
        private final ImageView videoMark;
        private final ImageView albumMark;
        private PixelfedStatus status;

        TileView(Context context) {
            super(context);
            setPadding(AndroidUtilities.dp(1), AndroidUtilities.dp(1), AndroidUtilities.dp(1), AndroidUtilities.dp(1));
            imageView = new BackupImageView(context);
            imageView.setBackgroundColor(NexLentaColors.PLACEHOLDER);
            addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            videoMark = new ImageView(context);
            videoMark.setImageResource(R.drawable.play_mini_video);
            videoMark.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            addView(videoMark, LayoutHelper.createFrame(18, 18, Gravity.BOTTOM | Gravity.LEFT, 8, 0, 0, 8));
            albumMark = new ImageView(context);
            albumMark.setScaleType(ImageView.ScaleType.CENTER);
            albumMark.setImageResource(R.drawable.msg_copy);
            albumMark.setColorFilter(new PorterDuffColorFilter(NexLentaColors.TEXT, PorterDuff.Mode.SRC_IN));
            albumMark.setBackground(NexLentaUi.glass(8));
            albumMark.setScaleX(0.8f);
            albumMark.setScaleY(0.8f);
            addView(albumMark, LayoutHelper.createFrame(30, 30, Gravity.TOP | Gravity.RIGHT, 0, 6, 6, 0));
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

        void set(PixelfedStatus status) {
            this.status = status;
            final PixelfedStatus.Media first = status.media.isEmpty() ? null : status.media.get(0);
            final String image = first == null ? null : first.video ? first.previewUrl : first.url;
            final int w = (int) (AndroidUtilities.displaySize.x / AndroidUtilities.density / 3);
            imageView.setImage(image, w + "_" + (w * 4 / 3), null);
            videoMark.setVisibility(status.hasVideo() ? View.VISIBLE : View.GONE);
            albumMark.setVisibility(status.media.size() > 1 ? View.VISIBLE : View.GONE);
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
            textView.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(32), AndroidUtilities.dp(24), AndroidUtilities.dp(32));
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
            final boolean loadingNow = !postsFailed && (account == null || (tab == TAB_SAVED ? saved == null : (postsLoading || hasMore)));
            progressView.setVisibility(loadingNow ? View.VISIBLE : View.GONE);
            textView.setVisibility(loadingNow ? View.GONE : View.VISIBLE);
            textView.setText(postsFailed
                ? LocaleController.getString(R.string.NexFeedLoadError) + "\n" + LocaleController.getString(R.string.NexFeedLoginRetry)
                : LocaleController.getString(tab == TAB_SAVED ? R.string.NexProfileNoSaved : R.string.NexProfileNoPosts));
        }
    }
}
