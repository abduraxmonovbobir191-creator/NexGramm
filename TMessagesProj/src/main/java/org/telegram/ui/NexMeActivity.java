package org.telegram.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.Stories.StoriesController;
import org.telegram.ui.Stories.StoriesUtilities;
import org.telegram.ui.Stories.StoryViewer;
import org.telegram.ui.Stories.recorder.StoryRecorder;

import uz.nexgramm.core.PixelfedFormat;

// NexGramm: the Profile tab — the one profile of the app: the user's Telegram profile in a wide, clean,
// TikTok-like layout. Name, @username, photo (+ = new story), counters (stories, gifts, contacts), bio,
// phone / birthday, Edit; below, the stories as a grid: kept on the profile, or the archive.
// Lenta posts, likes and saved posts live in the Lenta (own Lenta profile), not here.
public class NexMeActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate, NotificationCenter.NotificationCenterDelegate {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_TABS = 1;
    private static final int TYPE_STORY = 2;
    private static final int TYPE_STATE = 3;
    private static final int TAB_PROFILE = 0;
    private static final int TAB_ARCHIVE = 1;
    private static final int AVATAR_DP = 96;
    private static final int BAR_DP = 60;

    private RecyclerView listView;
    private GridLayoutManager layoutManager;
    private final Adapter adapter = new Adapter();
    private View headerView;
    private LinearLayout tabsView;

    private TextView barTitle;
    private BackupImageView avatarView;
    private View avatarRing;
    private TextView nameView;
    private TextView usernameView;
    private TextView storiesCount;
    private TextView giftsCount;
    private TextView contactsCount;
    private TextView bioView;
    private LinearLayout chipsRow;

    private StoriesController.StoriesList profileStories;
    private StoriesController.StoriesList archiveStories;
    private int profileLink = -1;
    private int archiveLink = -1;
    private int tab = TAB_PROFILE;

    public NexMeActivity() {
        super(mainTabArgs());
    }

    private static Bundle mainTabArgs() {
        final Bundle args = new Bundle();
        args.putBoolean("hasMainTabs", true);
        return args;
    }

    private long tgUserId() {
        return getUserConfig().getClientUserId();
    }

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.userInfoDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().addObserver(this, NotificationCenter.storiesUpdated);
        getNotificationCenter().addObserver(this, NotificationCenter.storiesListUpdated);
        getNotificationCenter().addObserver(this, NotificationCenter.contactsDidLoad);
        final TLRPC.User user = getUserConfig().getCurrentUser();
        if (user != null) {
            getMessagesController().loadFullUser(user, classGuid, true);
        }
        final StoriesController controller = getMessagesController().getStoriesController();
        profileStories = controller.getStoriesList(tgUserId(), StoriesController.StoriesList.TYPE_PINNED);
        profileLink = profileStories.link();
        profileStories.load(false, 30);
        archiveStories = controller.getStoriesList(tgUserId(), StoriesController.StoriesList.TYPE_ARCHIVE);
        archiveLink = archiveStories.link();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        getNotificationCenter().removeObserver(this, NotificationCenter.userInfoDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().removeObserver(this, NotificationCenter.storiesUpdated);
        getNotificationCenter().removeObserver(this, NotificationCenter.storiesListUpdated);
        getNotificationCenter().removeObserver(this, NotificationCenter.contactsDidLoad);
        if (profileStories != null && profileLink >= 0) {
            profileStories.unlink(profileLink);
        }
        if (archiveStories != null && archiveLink >= 0) {
            archiveStories.unlink(archiveLink);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (fragmentView == null) {
            return;
        }
        if (id == NotificationCenter.storiesListUpdated) {
            if (args.length > 0 && (args[0] == profileStories || args[0] == archiveStories)) {
                bind();
                adapter.notifyDataSetChanged();
            }
        } else if (id == NotificationCenter.userInfoDidLoad) {
            if (args.length > 0 && args[0] instanceof Long && (Long) args[0] == tgUserId()) {
                bind();
            }
        } else {
            bind();
            if (id == NotificationCenter.storiesUpdated) {
                adapter.notifyDataSetChanged();
            }
        }
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }

    @Override
    public void onParentScrollToTop() {
        if (listView != null) {
            listView.smoothScrollToPosition(0);
        }
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
                return adapter.getItemViewType(position) == TYPE_STORY ? 1 : 3;
            }
        });
        listView.setLayoutManager(layoutManager);
        listView.setItemAnimator(null);
        listView.setClipToPadding(false);
        listView.setPadding(0, AndroidUtilities.statusBarHeight + AndroidUtilities.dp(BAR_DP), 0, AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(104));
        listView.setAdapter(adapter);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                final StoriesController.StoriesList list = currentList();
                if (list != null && !list.isLoading() && !list.isFull()
                    && layoutManager.findLastVisibleItemPosition() >= adapter.getItemCount() - 6) {
                    list.load(false, 30);
                }
                // the @username in the bar shows once the name scrolled away
                final View first = layoutManager.findViewByPosition(0);
                barTitle.setAlpha(first == null || first.getTop() < listView.getPaddingTop() - AndroidUtilities.dp(56) ? 1f : 0f);
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(createTopBar(context), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        headerView = createHeader(context);
        tabsView = new LinearLayout(context);
        tabsView.setOrientation(LinearLayout.HORIZONTAL);
        rebuildTabs();

        fragmentView = root;
        bind();
        return fragmentView;
    }

    // ---- top bar: always solid (the page color), so nothing scrolls under the status bar ----

    private View createTopBar(Context context) {
        final FrameLayout bar = new FrameLayout(context);
        bar.setBackgroundColor(NexLentaColors.BACKGROUND);
        bar.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        bar.setClickable(true);

        final ImageView qr = barButton(context, R.drawable.msg_qrcode, R.string.QrCode);
        qr.setOnClickListener(v -> {
            final Bundle args = new Bundle();
            args.putLong("user_id", tgUserId());
            presentFragment(new QrActivity(args));
        });
        bar.addView(qr, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 8, 0, 8));

        barTitle = new TextView(context);
        barTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        barTitle.setTypeface(AndroidUtilities.bold());
        barTitle.setTextColor(NexLentaColors.TEXT);
        barTitle.setSingleLine(true);
        barTitle.setEllipsize(TextUtils.TruncateAt.END);
        barTitle.setGravity(Gravity.CENTER);
        barTitle.setAlpha(0f);
        bar.addView(barTitle, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 68, 0, 116, 0));

        final ImageView share = barButton(context, R.drawable.msg_share, R.string.ShareLink);
        share.setOnClickListener(v -> shareProfile());
        bar.addView(share, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 8, 64, 8));
        final ImageView settings = barButton(context, R.drawable.msg_settings, R.string.Settings);
        settings.setOnClickListener(v -> presentFragment(new SettingsActivity()));
        bar.addView(settings, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 8, 12, 8));
        return bar;
    }

    private ImageView barButton(Context context, int icon, int description) {
        final ImageView button = new ImageView(context);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageResource(icon);
        button.setColorFilter(new PorterDuffColorFilter(NexLentaColors.TEXT, PorterDuff.Mode.SRC_IN));
        button.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(NexLentaColors.TEXT, 0.1f), Theme.RIPPLE_MASK_CIRCLE_20DP));
        button.setContentDescription(LocaleController.getString(description));
        return button;
    }

    // ---- header ----

    private View createHeader(Context context) {
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(4), AndroidUtilities.dp(20), AndroidUtilities.dp(16));

        // name + @username (left), photo with "+" (right)
        final LinearLayout identity = new LinearLayout(context);
        identity.setOrientation(LinearLayout.HORIZONTAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
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
        usernameView.setOnClickListener(v -> copyUsername());
        names.addView(usernameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        identity.addView(names, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 16, 0));

        final FrameLayout avatarFrame = new FrameLayout(context);
        avatarRing = new View(context);
        avatarFrame.addView(avatarRing, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(AndroidUtilities.dp((AVATAR_DP - 10) / 2f));
        avatarView.setOnClickListener(v -> openPhoto());
        avatarFrame.addView(avatarView, LayoutHelper.createFrame(AVATAR_DP - 10, AVATAR_DP - 10, Gravity.CENTER));
        final ImageView plus = new ImageView(context);
        plus.setScaleType(ImageView.ScaleType.CENTER);
        plus.setImageResource(R.drawable.msg_add);
        plus.setColorFilter(new PorterDuffColorFilter(NexLentaColors.ON_ACCENT, PorterDuff.Mode.SRC_IN));
        final GradientDrawable plusBg = new GradientDrawable();
        plusBg.setShape(GradientDrawable.OVAL);
        plusBg.setColor(NexLentaColors.ACCENT);
        plusBg.setStroke(AndroidUtilities.dp(3), NexLentaColors.BACKGROUND);
        plus.setBackground(plusBg);
        plus.setContentDescription(LocaleController.getString(R.string.NexMeNewStory));
        plus.setOnClickListener(v -> openStoryRecorder());
        avatarFrame.addView(plus, LayoutHelper.createFrame(32, 32, Gravity.RIGHT | Gravity.BOTTOM));
        identity.addView(avatarFrame, LayoutHelper.createLinear(AVATAR_DP, AVATAR_DP));
        column.addView(identity, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // stories · gifts · contacts
        final LinearLayout stats = new LinearLayout(context);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        storiesCount = addStat(context, stats, R.string.NexMeStatStories, () -> {
            selectTab(TAB_PROFILE);
            layoutManager.scrollToPositionWithOffset(1, 0);
        });
        giftsCount = addStat(context, stats, R.string.NexMeStatGifts, () -> presentFragment(new NexGiftsActivity()));
        contactsCount = addStat(context, stats, R.string.NexMeStatContacts, () -> presentFragment(new ContactsActivity(null)));
        column.addView(stats, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, -8, 14, 0, 0));

        bioView = new TextView(context);
        bioView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        bioView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        bioView.setTextColor(NexLentaColors.TEXT);
        bioView.setLinkTextColor(NexLentaColors.TEXT);
        bioView.setVisibility(View.GONE);
        column.addView(bioView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        // phone, birthday: small chips, tap copies
        final HorizontalScrollView chipsScroll = new HorizontalScrollView(context);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chipsRow = new LinearLayout(context);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsScroll.addView(chipsRow);
        column.addView(chipsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        final TextView edit = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexProfileEdit), NexLentaUi.PILL_CARD);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        edit.setOnClickListener(v -> presentFragment(new UserInfoActivity()));
        column.addView(edit, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 0, 16, 0, 0));
        return column;
    }

    private TextView addStat(Context context, LinearLayout stats, int labelRes, Runnable action) {
        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(4), AndroidUtilities.dp(8), AndroidUtilities.dp(4));
        box.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12), 0, Theme.multAlpha(NexLentaColors.TEXT, 0.08f)));
        final TextView number = new TextView(context);
        number.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
        number.setTypeface(AndroidUtilities.bold());
        number.setTextColor(NexLentaColors.TEXT);
        number.setText("0");
        box.addView(number, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        final TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        label.setTextColor(NexLentaColors.SECONDARY_TEXT);
        label.setText(LocaleController.getString(labelRes));
        box.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        box.setOnClickListener(v -> action.run());
        stats.addView(box, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 16, 0));
        return number;
    }

    private void addChip(int icon, String text) {
        final Context context = chipsRow.getContext();
        final TextView chip = new TextView(context);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setTextColor(NexLentaColors.TEXT);
        chip.setSingleLine(true);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setText(text);
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        chip.setCompoundDrawablePadding(AndroidUtilities.dp(6));
        for (android.graphics.drawable.Drawable d : chip.getCompoundDrawablesRelative()) {
            if (d != null) {
                d.setColorFilter(new PorterDuffColorFilter(NexLentaColors.SECONDARY_TEXT, PorterDuff.Mode.SRC_IN));
            }
        }
        chip.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(14), 0);
        chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16), NexLentaColors.CARD, Theme.multAlpha(NexLentaColors.CARD, 0.8f)));
        chip.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(text);
            Toast.makeText(context, LocaleController.getString(R.string.NexMeCopied), Toast.LENGTH_SHORT).show();
        });
        chipsRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 8, 0));
    }

    private void rebuildTabs() {
        final Context context = tabsView.getContext();
        tabsView.removeAllViews();
        final int[] icons = {R.drawable.nex_ic_grid, R.drawable.msg_stories_archive};
        final int[] names = {R.string.ProfileStories, R.string.ProfileStoriesArchive};
        for (int i = 0; i < icons.length; i++) {
            final int t = i;
            final FrameLayout cell = new FrameLayout(context);
            final ImageView icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setImageResource(icons[i]);
            icon.setContentDescription(LocaleController.getString(names[i]));
            icon.setColorFilter(new PorterDuffColorFilter(t == tab ? NexLentaColors.TEXT : NexLentaColors.SECONDARY_TEXT, PorterDuff.Mode.SRC_IN));
            cell.addView(icon, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            final View divider = new View(context);
            divider.setBackgroundColor(NexLentaColors.DIVIDER);
            cell.addView(divider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 1, Gravity.BOTTOM));
            if (t == tab) {
                final View underline = new View(context);
                underline.setBackground(NexLentaUi.roundRect(NexLentaColors.TEXT, 2));
                cell.addView(underline, LayoutHelper.createFrame(40, 2.5f, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
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
        final StoriesController.StoriesList list = currentList();
        if (list != null && list.messageObjects.isEmpty() && !list.isLoading()) {
            list.load(false, 30);
        }
        adapter.notifyDataSetChanged();
    }

    private StoriesController.StoriesList currentList() {
        return tab == TAB_ARCHIVE ? archiveStories : profileStories;
    }

    // ---- Telegram data ----

    private void bind() {
        final TLRPC.User user = getUserConfig().getCurrentUser();
        if (user == null || nameView == null) {
            return;
        }
        final String name = UserObject.getUserName(user);
        nameView.setText(name);
        final String username = UserObject.getPublicUsername(user);
        usernameView.setText(username != null ? "@" + username : LocaleController.getString(R.string.NexMeNoUsername));
        barTitle.setText(username != null ? "@" + username : name);
        avatarView.setForUserOrChat(user, new AvatarDrawable(user));

        // a ring in the accent while there are stories of the last 24 hours
        final boolean active = getMessagesController().getStoriesController().hasStories(user.id);
        final GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(AndroidUtilities.dp(2.5f), active ? NexLentaColors.ACCENT : 0);
        avatarRing.setBackground(ring);

        final TLRPC.UserFull full = getMessagesController().getUserFull(user.id);
        final String about = full != null && full.about != null ? full.about.trim() : "";
        bioView.setText(about);
        Linkify.addLinks(bioView, Linkify.WEB_URLS);
        bioView.setMovementMethod(LinkMovementMethod.getInstance());
        bioView.setVisibility(about.isEmpty() ? View.GONE : View.VISIBLE);

        chipsRow.removeAllViews();
        if (!TextUtils.isEmpty(user.phone)) {
            addChip(R.drawable.msg_calls, org.telegram.PhoneFormat.PhoneFormat.getInstance().format("+" + user.phone));
        }
        if (full != null && full.birthday != null) {
            addChip(R.drawable.filled_birthday, UserInfoActivity.birthdayString(full.birthday));
        }
        ((View) chipsRow.getParent()).setVisibility(chipsRow.getChildCount() == 0 ? View.GONE : View.VISIBLE);

        storiesCount.setText(PixelfedFormat.count(profileStories == null ? 0 : profileStories.getCount()));
        giftsCount.setText(PixelfedFormat.count(full == null ? 0 : full.stargifts_count));
        contactsCount.setText(PixelfedFormat.count(ContactsController.getInstance(currentAccount).contacts.size()));
    }

    private void copyUsername() {
        final String username = UserObject.getPublicUsername(getUserConfig().getCurrentUser());
        if (username != null && getParentActivity() != null) {
            AndroidUtilities.addToClipboard("@" + username);
            Toast.makeText(getParentActivity(), LocaleController.getString(R.string.NexMeCopied), Toast.LENGTH_SHORT).show();
        }
    }

    private void shareProfile() {
        final String username = UserObject.getPublicUsername(getUserConfig().getCurrentUser());
        if (getParentActivity() == null) {
            return;
        }
        if (username == null) {
            Toast.makeText(getParentActivity(), LocaleController.getString(R.string.NexMeNoUsername), Toast.LENGTH_SHORT).show();
            return;
        }
        final String link = "https://t.me/" + username;
        showDialog(ShareAlert.createShareAlert(getParentActivity(), null, link, false, link, false));
    }

    // The profile photos full screen, as in Telegram; no photo yet: Edit profile, where one is set
    private void openPhoto() {
        final TLRPC.User user = getUserConfig().getCurrentUser();
        if (user == null || getParentActivity() == null) {
            return;
        }
        if (user.photo == null || user.photo.photo_big == null) {
            presentFragment(new UserInfoActivity());
            return;
        }
        PhotoViewer.getInstance().setParentActivity(this);
        if (user.photo.dc_id != 0) {
            user.photo.photo_big.dc_id = user.photo.dc_id;
        }
        PhotoViewer.getInstance().openPhoto(user.photo.photo_big, new PhotoViewer.EmptyPhotoViewerProvider());
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
            showDialog(new LimitReachedBottomSheet(this, getParentActivity(), limit.getLimitReachedType(), currentAccount, null));
            return;
        }
        StoryRecorder.getInstance(getParentActivity(), currentAccount).open(null, true);
    }

    private void openStory(TL_stories.StoryItem story) {
        final StoriesController.StoriesList list = currentList();
        if (list == null || getParentActivity() == null) {
            return;
        }
        final StoryViewer.PlaceProvider place = (dialogId, messageId, storyId, type, holder) -> false;
        getOrCreateStoryViewer().open(getParentActivity(), story.id, list, place);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (fragmentView != null) {
            bind();
            adapter.notifyDataSetChanged();
        }
    }

    // ---- list: header, tabs, stories grid ----

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private int storyCount() {
            final StoriesController.StoriesList list = currentList();
            return list == null ? 0 : list.messageObjects.size();
        }

        private boolean showState() {
            final StoriesController.StoriesList list = currentList();
            return storyCount() == 0 || list != null && list.isLoading();
        }

        @Override
        public int getItemCount() {
            return 2 + storyCount() + (showState() ? 1 : 0);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return TYPE_HEADER;
            }
            if (position == 1) {
                return TYPE_TABS;
            }
            return position - 2 < storyCount() ? TYPE_STORY : TYPE_STATE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View view;
            if (viewType == TYPE_HEADER) {
                AndroidUtilities.removeFromParent(headerView);
                view = headerView;
            } else if (viewType == TYPE_TABS) {
                AndroidUtilities.removeFromParent(tabsView);
                view = tabsView;
            } else if (viewType == TYPE_STORY) {
                view = new StoryTile(parent.getContext());
            } else {
                view = new StateView(parent.getContext());
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.itemView instanceof StoryTile) {
                final MessageObject object = currentList().messageObjects.get(position - 2);
                ((StoryTile) holder.itemView).set(object.storyItem, (position - 2) % 3);
            } else if (holder.itemView instanceof StateView) {
                ((StateView) holder.itemView).update();
            }
        }
    }

    // 3:4 story tile with the view count, 1 px gaps
    private class StoryTile extends FrameLayout {
        private final BackupImageView imageView;
        private final TextView viewsView;
        private TL_stories.StoryItem story;

        StoryTile(Context context) {
            super(context);
            imageView = new BackupImageView(context);
            imageView.setBackgroundColor(NexLentaColors.PLACEHOLDER);
            addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            viewsView = new TextView(context);
            viewsView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            viewsView.setTypeface(AndroidUtilities.bold());
            viewsView.setTextColor(0xFFFFFFFF);
            viewsView.setShadowLayer(AndroidUtilities.dp(2), 0, 0, 0x99000000);
            viewsView.setCompoundDrawablesWithIntrinsicBounds(R.drawable.msg_views, 0, 0, 0);
            viewsView.setCompoundDrawablePadding(AndroidUtilities.dp(3));
            for (android.graphics.drawable.Drawable d : viewsView.getCompoundDrawables()) {
                if (d != null) {
                    d.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
                }
            }
            addView(viewsView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.BOTTOM, 6, 0, 0, 6));
            setOnClickListener(v -> {
                if (story != null) {
                    openStory(story);
                }
            });
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(width * 4 / 3, MeasureSpec.EXACTLY));
        }

        void set(TL_stories.StoryItem story, int column) {
            this.story = story;
            final int gap = Math.max(1, AndroidUtilities.dp(1));
            setPadding(column == 0 ? 0 : gap / 2 + gap % 2, 0, column == 2 ? 0 : gap / 2, gap);
            imageView.getImageReceiver().clearImage();
            if (story != null) {
                StoriesUtilities.setImage(imageView.getImageReceiver(), story, "150_200");
            }
            final int views = story != null && story.views != null ? story.views.views_count : 0;
            viewsView.setText(PixelfedFormat.count(views));
            viewsView.setVisibility(views > 0 ? View.VISIBLE : View.GONE);
        }
    }

    // Loading or empty
    private class StateView extends FrameLayout {
        private final RadialProgressView progressView;
        private final TextView textView;

        StateView(Context context) {
            super(context);
            progressView = new RadialProgressView(context);
            progressView.setSize(AndroidUtilities.dp(24));
            progressView.setProgressColor(NexLentaColors.TEXT);
            addView(progressView, LayoutHelper.createFrame(32, 32, Gravity.CENTER, 0, 32, 0, 32));
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            textView.setGravity(Gravity.CENTER);
            textView.setTextColor(NexLentaColors.SECONDARY_TEXT);
            textView.setPadding(AndroidUtilities.dp(32), AndroidUtilities.dp(40), AndroidUtilities.dp(32), AndroidUtilities.dp(40));
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        }

        void update() {
            final StoriesController.StoriesList list = currentList();
            final boolean loading = list != null && list.isLoading();
            progressView.setVisibility(loading ? View.VISIBLE : View.GONE);
            textView.setVisibility(loading ? View.GONE : View.VISIBLE);
            textView.setText(LocaleController.getString(tab == TAB_ARCHIVE ? R.string.NexMeNoArchive : R.string.NexMeNoStories));
            textView.setOnClickListener(tab == TAB_ARCHIVE ? null : v -> openStoryRecorder());
        }
    }
}
