package org.telegram.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MediaActivity;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.Stories.StoriesController;
import org.telegram.ui.Stories.StoriesUtilities;
import org.telegram.ui.Stories.StoryViewer;
import org.telegram.ui.Stories.recorder.StoryRecorder;

import java.util.HashSet;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.NexCover;
import uz.nexgramm.core.PixelfedAccount;
import uz.nexgramm.core.PixelfedClient;

// NexGramm: the Profile tab — the user's Telegram profile in the app's minimal style: cover, photo, name,
// bio, phone / username / birthday, stories, saved messages. Lenta posts, likes and saved posts live in the
// Lenta (own Lenta profile), not here.
public class NexMeActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate, NotificationCenter.NotificationCenterDelegate {

    private static final int COVER_DP = 190;
    private static final int AVATAR_DP = 96;

    private ScrollView scrollView;
    private NexCoverView coverView;
    private BackupImageView avatarView;
    private TextView nameView;
    private TextView usernameView;
    private TextView bioView;
    private LinearLayout infoCard;
    private LinearLayout storiesRow;
    private TextView storiesEmpty;
    private StoriesController.StoriesList profileStories;
    private int storiesLink = -1;
    private PixelfedAccount lentaAccount;
    private boolean coverLoading;
    private int generation;

    public NexMeActivity() {
        super(mainTabArgs());
    }

    private static Bundle mainTabArgs() {
        final Bundle args = new Bundle();
        args.putBoolean("hasMainTabs", true);
        return args;
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.userInfoDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().addObserver(this, NotificationCenter.storiesUpdated);
        getNotificationCenter().addObserver(this, NotificationCenter.storiesListUpdated);
        final TLRPC.User user = getUserConfig().getCurrentUser();
        if (user != null) {
            getMessagesController().loadFullUser(user, classGuid, true);
        }
        // stories kept on the profile
        profileStories = getMessagesController().getStoriesController().getStoriesList(tgUserId(), StoriesController.StoriesList.TYPE_PINNED);
        storiesLink = profileStories.link();
        profileStories.load(false, 30);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        generation++;
        getNotificationCenter().removeObserver(this, NotificationCenter.userInfoDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().removeObserver(this, NotificationCenter.storiesUpdated);
        getNotificationCenter().removeObserver(this, NotificationCenter.storiesListUpdated);
        if (profileStories != null && storiesLink >= 0) {
            profileStories.unlink(storiesLink);
        }
        if (coverView != null) {
            coverView.release();
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (fragmentView == null) {
            return;
        }
        if (id == NotificationCenter.updateInterfaces
            || id == NotificationCenter.userInfoDidLoad && args.length > 0 && args[0] instanceof Long && (Long) args[0] == tgUserId()) {
            bind();
        }
        if (id == NotificationCenter.storiesUpdated || id == NotificationCenter.updateInterfaces
            || id == NotificationCenter.storiesListUpdated && args.length > 0 && args[0] == profileStories) {
            bindStories();
        }
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }

    @Override
    public void onParentScrollToTop() {
        if (scrollView != null) {
            scrollView.smoothScrollTo(0, 0);
        }
    }

    @Override
    public boolean isLightStatusBar() {
        return !NexLentaColors.dark;
    }

    private long tgUserId() {
        return getUserConfig().getClientUserId();
    }

    @Override
    public View createView(Context context) {
        NexLentaColors.apply();
        actionBar.setAddToContainer(false);

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(NexLentaColors.BACKGROUND);

        scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(104));
        scrollView.addView(createContent(context));
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = root;
        bind();
        bindStories();
        loadCover();
        return fragmentView;
    }

    private View createContent(Context context) {
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        // the user's cover (photo, muted video, color / emoji), else a soft band in the app colors
        final FrameLayout coverFrame = new FrameLayout(context);
        coverView = new NexCoverView(context, true);
        coverFrame.addView(coverView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        // QR (left) and settings (right) sit on the cover and scroll away with it
        final float top = AndroidUtilities.statusBarHeight / AndroidUtilities.density + 10;
        final ImageView qr = NexLentaUi.circleButton(context, R.drawable.msg_qrcode, NexLentaUi.PILL_CARD, true);
        qr.setContentDescription(LocaleController.getString(R.string.QrCode));
        qr.setOnClickListener(v -> {
            final Bundle args = new Bundle();
            args.putLong("user_id", tgUserId());
            presentFragment(new QrActivity(args));
        });
        coverFrame.addView(qr, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.TOP, 16, top, 0, 0));
        final ImageView settings = NexLentaUi.circleButton(context, R.drawable.msg_settings, NexLentaUi.PILL_CARD, true);
        settings.setContentDescription(LocaleController.getString(R.string.Settings));
        settings.setOnClickListener(v -> presentFragment(new SettingsActivity()));
        coverFrame.addView(settings, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.TOP, 0, top, 16, 0));
        column.addView(coverFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, COVER_DP));

        // photo (right) overlapping the band; name + @username (left)
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
        avatarFrame.setOnClickListener(v -> openPhoto());
        identity.addView(avatarFrame, LayoutHelper.createFrame(AVATAR_DP, AVATAR_DP, Gravity.RIGHT | Gravity.TOP, 0, 0, 16, 0));

        final LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        nameView = NexLentaUi.serifTitle(context, 34);
        nameView.setMaxLines(2);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        usernameView = new TextView(context);
        usernameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        usernameView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        usernameView.setSingleLine(true);
        usernameView.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(usernameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        identity.addView(names, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 20, AVATAR_DP / 2f + 8, AVATAR_DP + 28, 0));
        column.addView(identity, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, -AVATAR_DP / 2f, 0, 0));

        bioView = new TextView(context);
        bioView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        bioView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        bioView.setTextColor(NexLentaColors.TEXT);
        bioView.setVisibility(View.GONE);
        column.addView(bioView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 14, 20, 0));

        // Edit (Telegram name, bio, birthday), cover, share
        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        final TextView edit = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexProfileEdit), NexLentaUi.PILL_CARD);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        edit.setOnClickListener(v -> presentFragment(new UserInfoActivity()));
        buttons.addView(edit, LayoutHelper.createLinear(0, 46, 1f));
        final ImageView cover = NexLentaUi.circleButton(context, R.drawable.msg_photoeditor, NexLentaUi.PILL_CARD, false);
        cover.setContentDescription(LocaleController.getString(R.string.NexCoverButton));
        cover.setOnClickListener(v -> openCoverEditor());
        buttons.addView(cover, LayoutHelper.createLinear(46, 46, 10, 0, 0, 0));
        final ImageView share = NexLentaUi.circleButton(context, R.drawable.msg_share, NexLentaUi.PILL_CARD, false);
        share.setContentDescription(LocaleController.getString(R.string.ShareLink));
        share.setOnClickListener(v -> shareProfile());
        buttons.addView(share, LayoutHelper.createLinear(46, 46, 10, 0, 0, 0));
        column.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 20, 20, 18));

        column.addView(createStories(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 14));

        // phone, @username, birthday: tap copies
        infoCard = card(context);
        column.addView(infoCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 14));

        // Telegram places
        final LinearLayout places = card(context);
        addPlace(places, R.drawable.msg_saved, LocaleController.getString(R.string.SavedMessages), () -> {
            final Bundle args = new Bundle();
            args.putLong("user_id", tgUserId());
            presentFragment(new ChatActivity(args));
        });
        addPlace(places, R.drawable.msg_openprofile, LocaleController.getString(R.string.NexMeFullProfile), this::openTelegramProfile);
        column.addView(places, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));
        return column;
    }

    // ---- stories: active ones and the ones kept on the profile, right on the page ----

    private View createStories(Context context) {
        final LinearLayout section = card(context);
        section.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(14));
        final FrameLayout header = new FrameLayout(context);
        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(NexLentaColors.TEXT);
        title.setText(LocaleController.getString(R.string.ProfileMyStories));
        header.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));
        final TextView all = new TextView(context);
        all.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        all.setTextColor(NexLentaColors.SECONDARY_TEXT);
        all.setText(LocaleController.getString(R.string.NexMeAllStories));
        all.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(6), AndroidUtilities.dp(16), AndroidUtilities.dp(6));
        all.setOnClickListener(v -> openAllStories());
        header.addView(all, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        section.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36));

        final HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        storiesRow = new LinearLayout(context);
        storiesRow.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(storiesRow);
        section.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        storiesEmpty = new TextView(context);
        storiesEmpty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        storiesEmpty.setTextColor(NexLentaColors.SECONDARY_TEXT);
        storiesEmpty.setText(LocaleController.getString(R.string.NexMeNoStories));
        section.addView(storiesEmpty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 10, 16, 0));
        return section;
    }

    private void bindStories() {
        if (storiesRow == null) {
            return;
        }
        final Context context = storiesRow.getContext();
        storiesRow.removeAllViews();
        // "+": a new story
        final FrameLayout add = new FrameLayout(context);
        add.setBackground(NexLentaUi.roundRect(NexLentaColors.BACKGROUND, 16));
        final ImageView plus = new ImageView(context);
        plus.setScaleType(ImageView.ScaleType.CENTER);
        plus.setImageResource(R.drawable.msg_add);
        plus.setColorFilter(new PorterDuffColorFilter(NexLentaColors.TEXT, PorterDuff.Mode.SRC_IN));
        add.addView(plus, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        add.setContentDescription(LocaleController.getString(R.string.NexMeNewStory));
        add.setOnClickListener(v -> openStoryRecorder());
        storiesRow.addView(add, LayoutHelper.createLinear(76, 108, 4, 0, 4, 0));

        final long selfId = tgUserId();
        final StoriesController controller = getMessagesController().getStoriesController();
        final HashSet<Integer> shown = new HashSet<>();
        final TL_stories.PeerStories active = controller.getStories(selfId);
        if (active != null && active.stories != null) {
            for (TL_stories.StoryItem story : active.stories) {
                if (story.media != null && shown.add(story.id)) {
                    addStoryTile(story, true, () -> getOrCreateStoryViewer().open(getContext(), selfId, noPlace()));
                }
            }
        }
        if (profileStories != null) {
            for (MessageObject object : profileStories.messageObjects) {
                final TL_stories.StoryItem story = object.storyItem;
                if (story != null && story.media != null && shown.add(story.id)) {
                    addStoryTile(story, false, () -> getOrCreateStoryViewer().open(getContext(), story.id, profileStories, noPlace()));
                }
            }
        }
        storiesEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void addStoryTile(TL_stories.StoryItem story, boolean active, Runnable open) {
        final Context context = storiesRow.getContext();
        final FrameLayout tile = new FrameLayout(context);
        if (active) {
            // a ring in the accent marks the stories of the last 24 hours
            final GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(AndroidUtilities.dp(18));
            ring.setStroke(AndroidUtilities.dp(2), NexLentaColors.ACCENT);
            tile.setBackground(ring);
            tile.setPadding(AndroidUtilities.dp(3), AndroidUtilities.dp(3), AndroidUtilities.dp(3), AndroidUtilities.dp(3));
        }
        final BackupImageView image = new BackupImageView(context);
        image.setRoundRadius(AndroidUtilities.dp(active ? 14 : 16));
        image.setBackground(NexLentaUi.roundRect(NexLentaColors.PLACEHOLDER, 16));
        StoriesUtilities.setImage(image.getImageReceiver(), story, "76_108");
        tile.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        tile.setOnClickListener(v -> open.run());
        storiesRow.addView(tile, LayoutHelper.createLinear(76, 108, 4, 0, 4, 0));
    }

    private static StoryViewer.PlaceProvider noPlace() {
        return (dialogId, messageId, storyId, type, holder) -> false;
    }

    private void openAllStories() {
        final Bundle args = new Bundle();
        args.putInt("type", MediaActivity.TYPE_STORIES);
        args.putLong("dialog_id", tgUserId());
        presentFragment(new MediaActivity(args, null));
    }

    private void openStoryRecorder() {
        if (getParentActivity() == null) {
            return;
        }
        if (!getMessagesController().storiesEnabled()) {
            presentFragment(new PremiumPreviewFragment("stories"));
            return;
        }
        final StoriesController.StoryLimit limit = getMessagesController().getStoriesController().checkStoryLimit();
        if (limit != null && limit.active(currentAccount, 1)) {
            showDialog(new org.telegram.ui.Components.Premium.LimitReachedBottomSheet(this, getParentActivity(), limit.getLimitReachedType(), currentAccount, null));
            return;
        }
        StoryRecorder.getInstance(getParentActivity(), currentAccount).open(null, true);
    }

    // The profile photo full screen, as in Telegram; no photo yet: the Telegram profile, where it is set
    private void openPhoto() {
        final TLRPC.User user = getUserConfig().getCurrentUser();
        if (user == null || user.photo == null || user.photo.photo_big == null || getParentActivity() == null) {
            openTelegramProfile();
            return;
        }
        PhotoViewer.getInstance().setParentActivity(this);
        if (user.photo.dc_id != 0) {
            user.photo.photo_big.dc_id = user.photo.dc_id;
        }
        PhotoViewer.getInstance().openPhoto(user.photo.photo_big, new PhotoViewer.EmptyPhotoViewerProvider());
    }

    private static LinearLayout card(Context context) {
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 22));
        card.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        return card;
    }

    private void addPlace(LinearLayout card, int icon, String title, Runnable action) {
        final Context context = card.getContext();
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        row.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(NexLentaColors.TEXT, 0.06f), Theme.RIPPLE_MASK_ALL));
        final ImageView iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER);
        iconView.setImageResource(icon);
        iconView.setColorFilter(new PorterDuffColorFilter(NexLentaColors.TEXT, PorterDuff.Mode.SRC_IN));
        iconView.setBackground(NexLentaUi.roundRect(NexLentaColors.BACKGROUND, 12));
        row.addView(iconView, LayoutHelper.createLinear(36, 36));
        final TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTextColor(NexLentaColors.TEXT);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setText(title);
        row.addView(titleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 14, 0, 8, 0));
        final ImageView arrow = new ImageView(context);
        arrow.setScaleType(ImageView.ScaleType.CENTER);
        arrow.setImageResource(R.drawable.msg_arrowright);
        arrow.setColorFilter(new PorterDuffColorFilter(NexLentaColors.SECONDARY_TEXT, PorterDuff.Mode.SRC_IN));
        row.addView(arrow, LayoutHelper.createLinear(20, 20));
        row.setOnClickListener(v -> action.run());
        card.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
    }

    // ---- Telegram identity ----

    private void bind() {
        final TLRPC.User user = getUserConfig().getCurrentUser();
        if (user == null || nameView == null) {
            return;
        }
        nameView.setText(UserObject.getUserName(user));
        final String username = UserObject.getPublicUsername(user);
        usernameView.setText(username != null ? "@" + username : LocaleController.getString(R.string.NexMeNoUsername));
        avatarView.setForUserOrChat(user, new AvatarDrawable(user));

        final TLRPC.UserFull full = getMessagesController().getUserFull(user.id);
        final String about = full != null && full.about != null ? full.about.trim() : "";
        bioView.setText(about);
        bioView.setVisibility(about.isEmpty() ? View.GONE : View.VISIBLE);

        infoCard.removeAllViews();
        if (!TextUtils.isEmpty(user.phone)) {
            addInfoRow(org.telegram.PhoneFormat.PhoneFormat.getInstance().format("+" + user.phone), R.string.NexMePhone);
        }
        if (username != null) {
            addInfoRow("@" + username, R.string.NexMeUsername);
        }
        if (full != null && full.birthday != null) {
            addInfoRow(UserInfoActivity.birthdayString(full.birthday), R.string.NexMeBirthday);
        }
        infoCard.setVisibility(infoCard.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void addInfoRow(String value, int labelRes) {
        final Context context = infoCard.getContext();
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(10), AndroidUtilities.dp(18), AndroidUtilities.dp(10));
        row.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(NexLentaColors.TEXT, 0.06f), Theme.RIPPLE_MASK_ALL));
        final TextView valueView = new TextView(context);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        valueView.setTextColor(NexLentaColors.TEXT);
        valueView.setSingleLine(true);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        valueView.setText(value);
        row.addView(valueView);
        final TextView labelView = new TextView(context);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        labelView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        labelView.setText(LocaleController.getString(labelRes));
        row.addView(labelView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(value);
            if (getParentActivity() != null) {
                Toast.makeText(getParentActivity(), LocaleController.getString(R.string.NexMeCopied), Toast.LENGTH_SHORT).show();
            }
        });
        infoCard.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void shareProfile() {
        final String username = UserObject.getPublicUsername(getUserConfig().getCurrentUser());
        if (username == null || getParentActivity() == null) {
            Toast.makeText(getParentActivity(), LocaleController.getString(R.string.NexMeNoUsername), Toast.LENGTH_SHORT).show();
            return;
        }
        final String link = "https://t.me/" + username;
        showDialog(ShareAlert.createShareAlert(getParentActivity(), null, link, false, link, false));
    }

    // The classic Telegram profile: photos, stories, gifts, everything Telegram has
    private void openTelegramProfile() {
        final Bundle args = new Bundle();
        args.putLong("user_id", tgUserId());
        args.putBoolean("my_profile", true);
        presentFragment(new ProfileActivity(args));
    }

    // ---- cover (stored by the core for the Lenta username) ----

    private void loadCover() {
        final String username = PixelfedClient.ownUsername(tgUserId());
        if (username == null) {
            return;
        }
        if (NexCover.isCached(username)) {
            coverView.setCover(NexCover.cached(username));
        }
        final int gen = generation;
        CoreClient.EXECUTOR.execute(() -> {
            final NexCover cover;
            try {
                cover = NexCover.loadBlocking(username);
            } catch (Exception e) {
                return; // core not reachable: keep what is shown
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (gen == generation && fragmentView != null) {
                    coverView.setCover(cover);
                }
            });
        });
    }

    private void openCoverEditor() {
        final long userId = tgUserId();
        if (PixelfedClient.getTokens(userId) == null) {
            // the cover belongs to the NexGramm account, made when the Lenta is connected
            Toast.makeText(getParentActivity(), LocaleController.getString(R.string.NexMeConnectLenta), Toast.LENGTH_SHORT).show();
            openFeedTab();
            return;
        }
        if (lentaAccount != null) {
            presentCoverEditor();
            return;
        }
        if (coverLoading) {
            return;
        }
        coverLoading = true;
        final int gen = generation;
        CoreClient.EXECUTOR.execute(() -> {
            PixelfedAccount result = null;
            try {
                result = PixelfedClient.ownAccountBlocking(userId);
            } catch (Exception ignore) {
            }
            final PixelfedAccount account = result;
            AndroidUtilities.runOnUIThread(() -> {
                coverLoading = false;
                if (gen != generation || fragmentView == null) {
                    return;
                }
                if (account == null) {
                    Toast.makeText(getParentActivity(), LocaleController.getString(R.string.NexFeedLoadError), Toast.LENGTH_SHORT).show();
                    return;
                }
                lentaAccount = account;
                presentCoverEditor();
            });
        });
    }

    private void presentCoverEditor() {
        presentFragment(new NexCoverEditActivity(lentaAccount, NexCover.cached(lentaAccount.username), cover -> {
            if (fragmentView != null) {
                coverView.setCover(cover);
            }
        }));
    }

    private void openFeedTab() {
        final INavigationLayout layout = getParentLayout();
        if (layout == null) {
            return;
        }
        for (BaseFragment fragment : layout.getFragmentStack()) {
            if (fragment instanceof MainTabsActivity) {
                ((MainTabsActivity) fragment).openFeedTab();
                return;
            }
        }
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
        if (fragmentView != null) {
            bind();
            loadCover(); // the cover may have been set, or the Lenta connected, meanwhile
        }
        if (coverView != null) {
            coverView.resume();
        }
    }
}
