package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.URLSpan;
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
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
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
import org.telegram.ui.Components.ImageUpdater;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.Stories.StoriesController;
import org.telegram.ui.Stories.StoriesUtilities;
import org.telegram.ui.Stories.StoryViewer;
import org.telegram.ui.Stories.recorder.StoryRecorder;

import java.io.File;
import java.util.ArrayList;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.NexCover;
import uz.nexgramm.core.PixelfedFormat;

// NexGramm: the Profile tab — the one profile of the app: the user's Telegram profile in a wide, clean,
// TikTok-like layout: a background (NexGramm cover: photo, video or color), the photo (+ = new photo or
// video, or remove it), name, @username, stories and gifts counters, bio, phone / birthday, Edit and
// Background; below, the stories in a 4-column grid: kept on the profile, or the archive.
// Lenta posts, likes and saved posts live in the Lenta (own Lenta profile), not here.
public class NexMeActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate, NotificationCenter.NotificationCenterDelegate, ImageUpdater.ImageUpdaterDelegate {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_TABS = 1;
    private static final int TYPE_STORY = 2;
    private static final int TYPE_STATE = 3;
    private static final int TAB_PROFILE = 0;
    private static final int TAB_ARCHIVE = 1;
    private static final int AVATAR_DP = 92;
    private static final int COLUMNS = 4;
    private static final int BAR_DP = 60;

    private RecyclerView listView;
    private GridLayoutManager layoutManager;
    private final Adapter adapter = new Adapter();
    private View headerView;
    private LinearLayout tabsView;
    private NexMotion.Tabs tabsPill;
    private boolean tabsAnimate;

    private TextView barTitle;
    private View topBar;
    private View topBarBackground;
    // texts and bar icons that turn white with a soft shadow over a background (photo, video, color)
    private final ArrayList<TextView> coverTexts = new ArrayList<>();
    private final ArrayList<ImageView> barIcons = new ArrayList<>();
    private NexCoverView coverView;
    private View coverHolder;
    private LinearLayout headerColumn;
    private BackupImageView avatarView;
    private ImageUpdater imageUpdater;
    private TLRPC.FileLocation uploadingSmall;
    private TLRPC.FileLocation uploadingBig;
    private int coverGeneration;
    private TextView nameView;
    private TextView usernameView;
    private TextView storiesCount;
    private TextView giftsCount;
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
        if (profileStories != null && profileLink >= 0) {
            profileStories.unlink(profileLink);
        }
        if (archiveStories != null && archiveLink >= 0) {
            archiveStories.unlink(archiveLink);
        }
        coverGeneration++;
        if (imageUpdater != null) {
            imageUpdater.clear();
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
        layoutManager = new GridLayoutManager(context, COLUMNS);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                final int type = adapter.getItemViewType(position);
                return type == TYPE_STORY || type == TYPE_ADD ? 1 : COLUMNS;
            }
        });
        listView.setLayoutManager(layoutManager);
        listView.setItemAnimator(null);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(104));
        listView.setAdapter(adapter);
        NexMotion.bounce(listView);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                final StoriesController.StoriesList list = currentList();
                if (list != null && !list.isLoading() && !list.isFull()
                    && layoutManager.findLastVisibleItemPosition() >= adapter.getItemCount() - 6) {
                    list.load(false, 30);
                }
                updateTopBar();
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(createTopBar(context), new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.statusBarHeight + AndroidUtilities.dp(BAR_DP), Gravity.TOP));

        headerView = createHeader(context);
        tabsView = new LinearLayout(context);
        tabsView.setOrientation(LinearLayout.HORIZONTAL);
        tabsPill = null; // a new view tree: a new pill
        rebuildTabs();

        imageUpdater = new ImageUpdater(true, ImageUpdater.FOR_TYPE_USER, true);
        imageUpdater.setOpenWithFrontfaceCamera(true);
        imageUpdater.parentFragment = this;
        imageUpdater.setDelegate(this);

        fragmentView = root;
        bind();
        loadCover();
        return fragmentView;
    }

    // ---- top bar: see-through over the background, the page color once the page scrolled under it ----

    private void updateTopBar() {
        if (topBar == null || layoutManager == null) {
            return;
        }
        final View first = layoutManager.findViewByPosition(0);
        final int offset = first == null ? AndroidUtilities.dp(10000) : -first.getTop();
        float solid = 1f;
        if (coverHolder != null && coverHolder.getVisibility() == View.VISIBLE) {
            final int fadeFrom = Math.max(0, coverHolder.getHeight() - topBar.getHeight() - AndroidUtilities.dp(48));
            solid = Math.max(0f, Math.min(1f, (offset - fadeFrom) / (float) AndroidUtilities.dp(48)));
        }
        topBarBackground.setAlpha(solid);
        if (coverHolder != null && coverHolder.getVisibility() == View.VISIBLE) {
            // white over the background, the page's text color once the bar turns solid
            final int iconColor = uz.nexgramm.core.NexPalette.blend(0xFFFFFFFF, NexLentaColors.TEXT, solid);
            for (ImageView icon : barIcons) {
                icon.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
            }
        }
        // the @username shows in the bar once the name scrolled away
        barTitle.setAlpha(offset > AndroidUtilities.dp(110) ? 1f : 0f);
    }

    private View createTopBar(Context context) {
        final FrameLayout bar = new FrameLayout(context);
        topBarBackground = new View(context);
        topBarBackground.setBackgroundColor(NexLentaColors.BACKGROUND);
        bar.addView(topBarBackground, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        bar.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        topBar = bar;

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
        barIcons.add(button);
        return button;
    }

    // ---- header ----

    private View createHeader(Context context) {
        final LinearLayout outer = new LinearLayout(context);
        outer.setOrientation(LinearLayout.VERTICAL);
        final FrameLayout header = new FrameLayout(context);
        outer.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // the background (NexGramm cover: photo, muted video, color / emoji) across the top, fading into the
        // page; hidden until the user sets one, so the page stays clean
        final FrameLayout holder = new FrameLayout(context);
        coverView = new NexCoverView(context, true);
        holder.addView(coverView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        holder.setVisibility(View.GONE);
        coverHolder = holder;
        // a clear banner on top; the name and the rest sit below it, never over the picture
        header.addView(holder, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, bannerHeight()));

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(AndroidUtilities.dp(16), AndroidUtilities.statusBarHeight + AndroidUtilities.dp(BAR_DP + 4), AndroidUtilities.dp(16), AndroidUtilities.dp(30));
        headerColumn = column;
        header.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // name + @username (left), photo with + (right)
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
        usernameView.setTextColor(Theme.multAlpha(NexLentaColors.TEXT, 0.75f));
        usernameView.setSingleLine(true);
        usernameView.setEllipsize(TextUtils.TruncateAt.END);
        usernameView.setOnClickListener(v -> copyUsername());
        names.addView(usernameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        identity.addView(names, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 16, 0));

        final FrameLayout avatarHolder = new FrameLayout(context);
        final FrameLayout avatarFrame = new FrameLayout(context);
        final GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(NexLentaColors.BACKGROUND);
        avatarFrame.setBackground(ring);
        avatarFrame.setPadding(AndroidUtilities.dp(3), AndroidUtilities.dp(3), AndroidUtilities.dp(3), AndroidUtilities.dp(3));
        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(AndroidUtilities.dp(AVATAR_DP / 2f));
        avatarView.setOnClickListener(v -> openPhoto());
        avatarView.setOnLongClickListener(v -> {
            openPhotoMenu();
            return true;
        });
        avatarFrame.addView(avatarView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        avatarHolder.addView(avatarFrame, LayoutHelper.createFrame(AVATAR_DP, AVATAR_DP));
        // + : a new photo or video for the profile (Telegram's menu, remove included)
        final ImageView plus = new ImageView(context);
        plus.setScaleType(ImageView.ScaleType.CENTER);
        plus.setImageResource(R.drawable.msg_add);
        plus.setColorFilter(new PorterDuffColorFilter(NexLentaColors.ON_ACCENT, PorterDuff.Mode.SRC_IN));
        final GradientDrawable plusBg = new GradientDrawable();
        plusBg.setShape(GradientDrawable.OVAL);
        plusBg.setColor(NexLentaColors.ACCENT);
        plusBg.setStroke(AndroidUtilities.dp(3), NexLentaColors.BACKGROUND);
        plus.setBackground(plusBg);
        plus.setContentDescription(LocaleController.getString(R.string.NexMeSetPhoto));
        plus.setOnClickListener(v -> openPhotoMenu());
        ScaleStateListAnimator.apply(plus, 0.1f, 1.6f);
        avatarHolder.addView(plus, LayoutHelper.createFrame(32, 32, Gravity.RIGHT | Gravity.BOTTOM));
        identity.addView(avatarHolder, LayoutHelper.createLinear(AVATAR_DP, AVATAR_DP));
        column.addView(identity, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // stories · gifts
        final LinearLayout stats = new LinearLayout(context);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        storiesCount = addStat(context, stats, R.string.NexMeStatStories, () -> {
            selectTab(TAB_PROFILE);
            layoutManager.scrollToPositionWithOffset(1, 0);
        });
        giftsCount = addStat(context, stats, R.string.NexMeStatGifts, () -> presentFragment(new NexGiftsActivity()));
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

        // Edit (Telegram name, bio, username, birthday) · Background (photo, video or color behind the header)
        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        final TextView edit = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexProfileEdit), NexLentaUi.PILL_CARD);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        edit.setOnClickListener(v -> presentFragment(new UserInfoActivity()));
        buttons.addView(edit, LayoutHelper.createLinear(0, 46, 1f));
        final TextView background = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexMeBackground), NexLentaUi.PILL_CARD);
        background.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        final android.graphics.drawable.Drawable palette = context.getResources().getDrawable(R.drawable.msg_photoeditor).mutate();
        palette.setBounds(0, 0, AndroidUtilities.dp(20), AndroidUtilities.dp(20));
        palette.setColorFilter(new PorterDuffColorFilter(NexLentaColors.TEXT, PorterDuff.Mode.SRC_IN));
        background.setCompoundDrawablesRelative(palette, null, null, null);
        background.setCompoundDrawablePadding(AndroidUtilities.dp(8));
        background.setOnClickListener(v -> openCoverEditor());
        buttons.addView(background, LayoutHelper.createLinear(0, 46, 1f, 10, 0, 0, 0));
        column.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));
        coverTexts.add(nameView);
        coverTexts.add(usernameView);
        coverTexts.add(bioView);
        return outer;
    }

    // The background shows only when the user has one
    private void showCover(NexCover cover) {
        if (coverView == null) {
            return;
        }
        coverView.setCover(cover);
        coverHolder.setVisibility(cover == null ? View.GONE : View.VISIBLE);
        headerColumn.setPadding(headerColumn.getPaddingLeft(),
            cover == null ? AndroidUtilities.statusBarHeight + AndroidUtilities.dp(BAR_DP + 4) : bannerHeight() + AndroidUtilities.dp(16),
            headerColumn.getPaddingRight(), headerColumn.getPaddingBottom());
        coverHolder.post(this::updateTopBar);
        applyCoverStyle(false); // the texts are on the page now; the bar icons follow updateTopBar
    }

    private static int bannerHeight() {
        return AndroidUtilities.statusBarHeight + AndroidUtilities.dp(BAR_DP + 150);
    }

    // Over a background the texts and bar icons are white with a soft shadow, so the background keeps its
    // own bright colors (no dark veil) and everything stays readable; without one, the page colors
    private void applyCoverStyle(boolean onCover) {
        for (TextView text : coverTexts) {
            if (!(text.getTag() instanceof Integer)) {
                text.setTag(text.getCurrentTextColor()); // the page color, to go back to
            }
            final int pageColor = (Integer) text.getTag();
            final boolean secondary = text != nameView && Color.alpha(pageColor) < 0xF0;
            text.setTextColor(onCover ? (secondary ? 0xE6FFFFFF : 0xFFFFFFFF) : pageColor);
            if (text == bioView) {
                text.setLinkTextColor(onCover ? 0xFFFFFFFF : NexLentaColors.TEXT);
            }
            if (onCover) {
                text.setShadowLayer(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(1), 0x99000000);
            } else {
                text.setShadowLayer(0, 0, 0, 0);
            }
        }
        for (ImageView icon : barIcons) {
            icon.setColorFilter(new PorterDuffColorFilter(onCover ? 0xFFFFFFFF : NexLentaColors.TEXT, PorterDuff.Mode.SRC_IN));
        }
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
        label.setTextColor(Theme.multAlpha(NexLentaColors.TEXT, 0.75f));
        label.setText(LocaleController.getString(labelRes));
        box.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        coverTexts.add(number);
        coverTexts.add(label);
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
        final android.graphics.drawable.Drawable iconDrawable = context.getResources().getDrawable(icon).mutate();
        iconDrawable.setBounds(0, 0, AndroidUtilities.dp(18), AndroidUtilities.dp(18));
        iconDrawable.setColorFilter(new PorterDuffColorFilter(NexLentaColors.SECONDARY_TEXT, PorterDuff.Mode.SRC_IN));
        chip.setCompoundDrawablesRelative(iconDrawable, null, null, null);
        chip.setCompoundDrawablePadding(AndroidUtilities.dp(6));
        chip.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(14), 0);
        ScaleStateListAnimator.apply(chip, 0.05f, 1.6f);
        chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(16), NexLentaColors.CARD, Theme.multAlpha(NexLentaColors.CARD, 0.8f)));
        chip.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(text);
            Toast.makeText(context, LocaleController.getString(R.string.NexMeCopied), Toast.LENGTH_SHORT).show();
        });
        chipsRow.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 8, 0));
    }

    // Stories | Archive: one centered pill with both labels, the selected one tinted (as in Telegram)
    private void rebuildTabs() {
        final Context context = tabsView.getContext();
        tabsView.removeAllViews();
        tabsView.setGravity(Gravity.CENTER_HORIZONTAL);
        tabsView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        // one pill kept across rebuilds, so its highlight can slide from one label to the other
        if (tabsPill == null) {
            tabsPill = new NexMotion.Tabs(context, NexMotion.Tabs.PILL, Theme.multAlpha(NexLentaColors.ACCENT, 0.15f));
            tabsPill.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 22));
            tabsPill.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));
        }
        tabsPill.removeAllViews();
        final LinearLayout pill = tabsPill;
        final int[] names = {R.string.NexMeStatStories, R.string.ProfileStoriesArchiveTab};
        for (int i = 0; i < names.length; i++) {
            final int t = i;
            final boolean selected = t == tab;
            final TextView option = new TextView(context);
            option.setGravity(Gravity.CENTER);
            option.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            option.setTypeface(AndroidUtilities.bold());
            option.setSingleLine(true);
            option.setText(LocaleController.getString(names[i]));
            option.setTextColor(selected ? NexLentaColors.ACCENT : NexLentaColors.SECONDARY_TEXT);
            option.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
            option.setOnClickListener(v -> selectTab(t));
            ScaleStateListAnimator.apply(option, 0.04f, 1.6f);
            pill.addView(option, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 38));
        }
        AndroidUtilities.removeFromParent(pill);
        tabsView.addView(pill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        tabsPill.setIndicatorColor(Theme.multAlpha(NexLentaColors.ACCENT, 0.15f));
        tabsPill.select(tab, tabsAnimate);
    }

    private void selectTab(int t) {
        if (tab == t) {
            return;
        }
        final int direction = t > tab ? 1 : -1;
        tab = t;
        tabsAnimate = true;
        rebuildTabs();
        tabsAnimate = false;
        final StoriesController.StoriesList list = currentList();
        if (list != null && list.messageObjects.isEmpty() && !list.isLoading()) {
            list.load(false, 30);
        }
        adapter.notifyDataSetChanged();
        NexMotion.slideRows(listView, 2, direction);
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
        if (uploadingSmall == null) {
            avatarView.setForUserOrChat(user, new AvatarDrawable(user));
        }


        final TLRPC.UserFull full = getMessagesController().getUserFull(user.id);
        final String about = full != null && full.about != null ? full.about.trim() : "";
        final SpannableString bio = new SpannableString(about);
        Linkify.addLinks(bio, Linkify.WEB_URLS);
        for (URLSpan span : bio.getSpans(0, bio.length(), URLSpan.class)) {
            final int start = bio.getSpanStart(span);
            final int end = bio.getSpanEnd(span);
            bio.removeSpan(span);
            bio.setSpan(new URLSpanNoUnderline(span.getURL(), true), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        bioView.setText(bio);
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

    // The profile photos full screen, as in Telegram; no photo yet: the photo menu
    private void openPhoto() {
        final TLRPC.User user = getUserConfig().getCurrentUser();
        if (user == null || getParentActivity() == null) {
            return;
        }
        if (!hasPhoto(user)) {
            openPhotoMenu();
            return;
        }
        PhotoViewer.getInstance().setParentActivity(this);
        if (user.photo.dc_id != 0) {
            user.photo.photo_big.dc_id = user.photo.dc_id;
        }
        PhotoViewer.getInstance().openPhoto(user.photo.photo_big, new PhotoViewer.EmptyPhotoViewerProvider());
    }

    private static boolean hasPhoto(TLRPC.User user) {
        return user.photo != null && user.photo.photo_big != null && !(user.photo instanceof TLRPC.TL_userProfilePhotoEmpty);
    }

    // Telegram's own photo menu: camera, gallery, video, emoji avatar, remove
    private void openPhotoMenu() {
        final TLRPC.User user = getUserConfig().getCurrentUser();
        if (user == null || imageUpdater == null || getParentActivity() == null) {
            return;
        }
        imageUpdater.openMenu(hasPhoto(user), () -> getMessagesController().deleteUserPhoto(null), dialog -> {
        }, 0);
    }

    @Override
    public void didUploadPhoto(TLRPC.InputFile photo, TLRPC.InputFile video, double videoStartTimestamp, String videoPath,
                               TLRPC.PhotoSize bigSize, TLRPC.PhotoSize smallSize, boolean isVideo, TLRPC.VideoSize emojiMarkup) {
        AndroidUtilities.runOnUIThread(() -> {
            if (photo == null && video == null && emojiMarkup == null) {
                // picked: show it at once while it uploads
                uploadingSmall = smallSize.location;
                uploadingBig = bigSize.location;
                avatarView.setImage(ImageLocation.getForLocal(uploadingSmall), "92_92", new AvatarDrawable(getUserConfig().getCurrentUser()), null);
                return;
            }
            final TLRPC.TL_photos_uploadProfilePhoto req = new TLRPC.TL_photos_uploadProfilePhoto();
            if (photo != null) {
                req.file = photo;
                req.flags |= 1;
            }
            if (video != null) {
                req.video = video;
                req.flags |= 2;
                req.video_start_ts = videoStartTimestamp;
                req.flags |= 4;
            }
            if (emojiMarkup != null) {
                req.video_emoji_markup = emojiMarkup;
                req.flags |= 16;
            }
            getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (error == null && response instanceof TLRPC.TL_photos_photo) {
                    TLRPC.User user = getMessagesController().getUser(getUserConfig().getClientUserId());
                    if (user == null) {
                        user = getUserConfig().getCurrentUser();
                        if (user == null) {
                            return;
                        }
                        getMessagesController().putUser(user, false);
                    } else {
                        getUserConfig().setCurrentUser(user);
                    }
                    final TLRPC.TL_photos_photo photosPhoto = (TLRPC.TL_photos_photo) response;
                    final ArrayList<TLRPC.PhotoSize> sizes = photosPhoto.photo.sizes;
                    final TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(sizes, 150);
                    final TLRPC.PhotoSize big = FileLoader.getClosestPhotoSizeWithSize(sizes, 800);
                    final TLRPC.VideoSize videoSize = photosPhoto.photo.video_sizes.isEmpty() ? null : FileLoader.getClosestVideoSizeWithSize(photosPhoto.photo.video_sizes, 1000);
                    user.photo = new TLRPC.TL_userProfilePhoto();
                    user.photo.photo_id = photosPhoto.photo.id;
                    if (small != null) {
                        user.photo.photo_small = small.location;
                    }
                    if (big != null) {
                        user.photo.photo_big = big.location;
                    }
                    // the picked files become the cache of the new photo: no download again
                    if (small != null && uploadingSmall != null) {
                        final File dest = FileLoader.getInstance(currentAccount).getPathToAttach(small, true);
                        final File src = FileLoader.getInstance(currentAccount).getPathToAttach(uploadingSmall, true);
                        src.renameTo(dest);
                        final String oldKey = uploadingSmall.volume_id + "_" + uploadingSmall.local_id + "@90_90";
                        final String newKey = small.location.volume_id + "_" + small.location.local_id + "@90_90";
                        ImageLoader.getInstance().replaceImageInCache(oldKey, newKey, ImageLocation.getForUserOrChat(currentAccount, user, ImageLocation.TYPE_SMALL), false);
                    }
                    if (videoSize != null && videoPath != null) {
                        new File(videoPath).renameTo(FileLoader.getInstance(currentAccount).getPathToAttach(videoSize, "mp4", true));
                    } else if (big != null && uploadingBig != null) {
                        FileLoader.getInstance(currentAccount).getPathToAttach(uploadingBig, true)
                            .renameTo(FileLoader.getInstance(currentAccount).getPathToAttach(big, true));
                    }
                    getMessagesController().getDialogPhotos(user.id).addPhotoAtStart(photosPhoto.photo);
                    final ArrayList<TLRPC.User> users = new ArrayList<>();
                    users.add(user);
                    getMessagesStorage().putUsersAndChats(users, null, false, true);
                    final TLRPC.UserFull userFull = getMessagesController().getUserFull(getUserConfig().getClientUserId());
                    if (userFull != null) {
                        userFull.profile_photo = photosPhoto.photo;
                        getMessagesStorage().updateUserInfo(userFull, false);
                    }
                } else if (getParentActivity() != null) {
                    Toast.makeText(getParentActivity(), LocaleController.getString(R.string.NexActionFailed), Toast.LENGTH_SHORT).show();
                }
                uploadingSmall = null;
                uploadingBig = null;
                getNotificationCenter().postNotificationName(NotificationCenter.updateInterfaces, org.telegram.messenger.MessagesController.UPDATE_MASK_ALL);
                getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
                getUserConfig().saveConfig(true);
            }));
        });
    }

    @Override
    public void didUploadFailed() {
        uploadingSmall = null;
        uploadingBig = null;
        bind();
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (imageUpdater != null) {
            imageUpdater.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (imageUpdater != null) {
            imageUpdater.onRequestPermissionsResultFragment(requestCode, permissions, grantResults);
        }
    }

    // ---- background: the NexGramm cover (needs the silent NexGramm sign-in, not the Lenta) ----

    private void loadCover() {
        final String id = NexCover.ownId(tgUserId());
        if (id == null || coverView == null) {
            return;
        }
        final String key = NexCover.idKey(id);
        if (NexCover.isCached(key)) {
            showCover(NexCover.cached(key));
        }
        final int gen = coverGeneration;
        CoreClient.EXECUTOR.execute(() -> {
            final NexCover cover;
            try {
                cover = NexCover.loadByIdBlocking(id);
            } catch (Exception e) {
                return; // offline: keep what is shown
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (gen == coverGeneration && coverView != null) {
                    showCover(cover);
                }
            });
        });
    }

    private void openCoverEditor() {
        final long userId = tgUserId();
        NexAiUi.withCoreLogin(currentAccount, userId, () -> {
            final String id = NexCover.ownId(userId);
            final TLRPC.User user = getUserConfig().getCurrentUser();
            if (id == null || user == null || fragmentView == null) {
                return;
            }
            final String key = NexCover.idKey(id);
            presentFragment(new NexCoverEditActivity(user, key, NexCover.cached(key), cover -> {
                showCover(cover);
            }));
        }, () -> {
            if (getParentActivity() != null) {
                Toast.makeText(getParentActivity(), LocaleController.getString(R.string.NexAiLogin), Toast.LENGTH_SHORT).show();
            }
        });
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
            loadCover();
            adapter.notifyDataSetChanged();
        }
        if (coverView != null) {
            coverView.resume();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (coverView != null) {
            coverView.pause();
        }
    }

    // ---- list: header, tabs, stories grid (4 columns, rounded tiles; "+" first on the profile tab) ----

    private static final int TYPE_ADD = 4;

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private int storyCount() {
            final StoriesController.StoriesList list = currentList();
            return list == null ? 0 : list.messageObjects.size();
        }

        private int addCount() {
            return tab == TAB_PROFILE ? 1 : 0;
        }

        private boolean showState() {
            final StoriesController.StoriesList list = currentList();
            return storyCount() == 0 || list != null && list.isLoading();
        }

        @Override
        public int getItemCount() {
            return 2 + addCount() + storyCount() + (showState() ? 1 : 0);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return TYPE_HEADER;
            }
            if (position == 1) {
                return TYPE_TABS;
            }
            final int index = position - 2;
            if (index < addCount()) {
                return TYPE_ADD;
            }
            return index - addCount() < storyCount() ? TYPE_STORY : TYPE_STATE;
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
            } else if (viewType == TYPE_STORY || viewType == TYPE_ADD) {
                view = new StoryTile(parent.getContext(), viewType == TYPE_ADD);
            } else {
                view = new StateView(parent.getContext());
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final int index = position - 2;
            if (holder.itemView instanceof StoryTile) {
                final StoryTile tile = (StoryTile) holder.itemView;
                if (tile.add) {
                    tile.set(null, 0);
                } else {
                    final MessageObject object = currentList().messageObjects.get(index - addCount());
                    tile.set(object.storyItem, index % COLUMNS);
                }
            } else if (holder.itemView instanceof StateView) {
                ((StateView) holder.itemView).update();
            }
        }
    }

    // 3:4 story tile with rounded corners and the view count; the "+" tile posts a new story
    private class StoryTile extends FrameLayout {
        private final boolean add;
        private final BackupImageView imageView;
        private final TextView viewsView;
        private TL_stories.StoryItem story;

        StoryTile(Context context, boolean add) {
            super(context);
            this.add = add;
            final FrameLayout inner = new FrameLayout(context);
            inner.setBackground(NexLentaUi.roundRect(add ? NexLentaColors.CARD : NexLentaColors.PLACEHOLDER, 12));
            imageView = new BackupImageView(context);
            imageView.setRoundRadius(AndroidUtilities.dp(12));
            inner.addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            viewsView = new TextView(context);
            viewsView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            viewsView.setTypeface(AndroidUtilities.bold());
            viewsView.setTextColor(0xFFFFFFFF);
            viewsView.setShadowLayer(AndroidUtilities.dp(2), 0, 0, 0x99000000);
            final android.graphics.drawable.Drawable eye = context.getResources().getDrawable(R.drawable.msg_views).mutate();
            eye.setBounds(0, 0, AndroidUtilities.dp(14), AndroidUtilities.dp(14));
            eye.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            viewsView.setCompoundDrawables(eye, null, null, null);
            viewsView.setCompoundDrawablePadding(AndroidUtilities.dp(3));
            inner.addView(viewsView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.BOTTOM, 6, 0, 0, 5));
            if (add) {
                final ImageView plus = new ImageView(context);
                plus.setScaleType(ImageView.ScaleType.CENTER);
                plus.setImageResource(R.drawable.msg_add);
                plus.setColorFilter(new PorterDuffColorFilter(NexLentaColors.TEXT, PorterDuff.Mode.SRC_IN));
                inner.addView(plus, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
                setContentDescription(LocaleController.getString(R.string.NexMeNewStory));
            }
            addView(inner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            ScaleStateListAnimator.apply(this, 0.04f, 1.6f);
            setOnClickListener(v -> {
                if (add) {
                    openStoryRecorder();
                } else if (story != null) {
                    openStory(story);
                }
            });
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            final int inner = width - getPaddingLeft() - getPaddingRight();
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(inner * 4 / 3 + getPaddingBottom(), MeasureSpec.EXACTLY));
        }

        // the same 6 dp at the edges and between tiles, so every tile has the same size
        void set(TL_stories.StoryItem story, int column) {
            this.story = story;
            final int spacing = AndroidUtilities.dp(6);
            setPadding(spacing - column * spacing / COLUMNS, 0, (column + 1) * spacing / COLUMNS, spacing);
            if (add) {
                viewsView.setVisibility(View.GONE);
                return;
            }
            imageView.getImageReceiver().clearImage();
            if (story != null) {
                StoriesUtilities.setImage(imageView.getImageReceiver(), story, "120_160");
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
