package org.telegram.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MediaActivity;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.voip.VoIPHelper;

import java.util.ArrayList;

// NexGramm: the profile of another person, a group or a channel in the NexGramm style — a soft banner in the
// avatar's colors, the big photo, name and status, round actions (message, call, video, sound / leave), one
// card with the details (tap copies) and the photos and videos of the chat as a grid. Telegram's full
// profile stays one tap away in the ⋮ menu for everything else (notifications, admin tools, gifts…).
public class NexPeerProfileActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int COLUMNS = 3;
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_MEDIA = 1;
    private static final int TYPE_EMPTY = 2;
    private static final int BAR_DP = 56;
    private static final int AVATAR_DP = 108;

    private long userId;
    private long chatId;
    private long dialogId;
    private TLRPC.User user;
    private TLRPC.Chat chat;
    private TLRPC.UserFull userFull;
    private TLRPC.ChatFull chatFull;

    private RecyclerView listView;
    private GridLayoutManager layoutManager;
    private final Adapter adapter = new Adapter();
    private LinearLayout headerView;
    private View topBarBackground;
    private TextView barTitle;
    private final ArrayList<ImageView> barIcons = new ArrayList<>();
    private View banner;
    private BackupImageView avatarView;
    private TextView nameView;
    private TextView statusView;
    private LinearLayout actionsRow;
    private LinearLayout infoCard;

    private final ArrayList<MessageObject> media = new ArrayList<>();
    private boolean mediaLoading;
    private boolean mediaEnd;
    private boolean mediaLoaded;

    public NexPeerProfileActivity(Bundle args) {
        super(args);
    }

    // ---- which profiles open here ----

    // Telegram opens a ProfileActivity; a plain profile of someone else, a group or a channel opens here instead
    public static BaseFragment replace(BaseFragment fragment) {
        if (!(fragment instanceof ProfileActivity) || fragment.getArguments() == null) {
            return fragment;
        }
        final Bundle a = fragment.getArguments();
        if (a.getBoolean("nex_classic", false)) {
            return fragment;
        }
        // special openings (saved messages, gifts, reports, vCards, own profile, topics, secret chats…)
        final String[] special = {"saved", "similar", "ban_chat_id", "report_reaction_message_id", "vcard_phone", "reportSpam",
            "my_profile", "open_gifts", "open_gifts_upgradable", "open_gifts_collection", "open_common", "open_story_album_id",
            "hasMainTabs", "nearby_distance", "topic_id"};
        for (String key : special) {
            final Object value = a.get(key);
            if (value instanceof Boolean && (Boolean) value || value instanceof Number && ((Number) value).longValue() > 0
                || value instanceof String && !((String) value).isEmpty()) {
                return fragment;
            }
        }
        final long dialog = a.getLong("dialog_id", 0);
        if (dialog != 0 && DialogObject.isEncryptedDialog(dialog)) {
            return fragment;
        }
        final long uid = a.getLong("user_id", 0);
        final long cid = a.getLong("chat_id", 0);
        if (uid == 0 && cid == 0 || uid != 0 && uid == UserConfig.getInstance(fragment.getCurrentAccount()).getClientUserId()) {
            return fragment;
        }
        final NexPeerProfileActivity profile = new NexPeerProfileActivity(a);
        profile.setCurrentAccount(fragment.getCurrentAccount());
        return profile;
    }

    private boolean isUser() {
        return userId != 0;
    }

    private boolean isChannel() {
        return chat != null && ChatObject.isChannelAndNotMegaGroup(chat);
    }

    @Override
    public boolean onFragmentCreate() {
        userId = getArguments().getLong("user_id", 0);
        chatId = getArguments().getLong("chat_id", 0);
        if (userId != 0) {
            user = getMessagesController().getUser(userId);
            if (user == null) {
                return false;
            }
            dialogId = userId;
            userFull = getMessagesController().getUserFull(userId);
            getMessagesController().loadFullUser(user, classGuid, true);
        } else {
            chat = getMessagesController().getChat(chatId);
            if (chat == null) {
                return false;
            }
            dialogId = -chatId;
            chatFull = getMessagesController().getChatFull(chatId);
            getMessagesController().loadFullChat(chatId, classGuid, true);
        }
        getNotificationCenter().addObserver(this, NotificationCenter.userInfoDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.chatInfoDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.mediaDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().addObserver(this, NotificationCenter.notificationsSettingsUpdated);
        loadMedia();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.userInfoDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.chatInfoDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.mediaDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().removeObserver(this, NotificationCenter.notificationsSettingsUpdated);
        super.onFragmentDestroy();
    }

    @Override
    public boolean isLightStatusBar() {
        return !NexLentaColors.dark;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.userInfoDidLoad) {
            if ((Long) args[0] == userId) {
                userFull = (TLRPC.UserFull) args[1];
                bind();
            }
        } else if (id == NotificationCenter.chatInfoDidLoad) {
            final TLRPC.ChatFull full = (TLRPC.ChatFull) args[0];
            if (full != null && full.id == chatId) {
                chatFull = full;
                bind();
            }
        } else if (id == NotificationCenter.mediaDidLoad) {
            if ((Long) args[0] != dialogId || (Integer) args[3] != classGuid) {
                return;
            }
            mediaLoading = false;
            mediaLoaded = true;
            @SuppressWarnings("unchecked") final ArrayList<MessageObject> loaded = (ArrayList<MessageObject>) args[2];
            for (MessageObject m : loaded) {
                if ((m.isPhoto() || m.isVideo()) && !containsMessage(m.getId())) {
                    media.add(m);
                }
            }
            mediaEnd = (Boolean) args[5] || loaded.isEmpty();
            adapter.notifyDataSetChanged();
        } else if (id == NotificationCenter.updateInterfaces || id == NotificationCenter.notificationsSettingsUpdated) {
            if (isUser()) {
                final TLRPC.User fresh = getMessagesController().getUser(userId);
                if (fresh != null) {
                    user = fresh;
                }
            } else {
                final TLRPC.Chat fresh = getMessagesController().getChat(chatId);
                if (fresh != null) {
                    chat = fresh;
                }
            }
            bind();
        }
    }

    private boolean containsMessage(int messageId) {
        for (MessageObject m : media) {
            if (m.getId() == messageId) {
                return true;
            }
        }
        return false;
    }

    private void loadMedia() {
        if (mediaLoading || mediaEnd) {
            return;
        }
        mediaLoading = true;
        int maxId = 0;
        for (MessageObject m : media) {
            if (maxId == 0 || m.getId() < maxId) {
                maxId = m.getId();
            }
        }
        getMediaDataController().loadMedia(dialogId, 50, maxId, 0, MediaDataController.MEDIA_PHOTOVIDEO, 0, 1, classGuid, 0, null, null);
    }

    // ---- views ----

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
                return adapter.getItemViewType(position) == TYPE_MEDIA ? 1 : COLUMNS;
            }
        });
        listView.setLayoutManager(layoutManager);
        listView.setItemAnimator(null);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(24));
        listView.setAdapter(adapter);
        NexMotion.bounce(listView);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (!mediaEnd && layoutManager.findLastVisibleItemPosition() >= adapter.getItemCount() - 9) {
                    loadMedia();
                }
                updateTopBar();
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(createTopBar(context), new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            AndroidUtilities.statusBarHeight + AndroidUtilities.dp(BAR_DP), Gravity.TOP));
        headerView = createHeader(context);
        fragmentView = root;
        bind();
        return fragmentView;
    }

    private View createTopBar(Context context) {
        final FrameLayout bar = new FrameLayout(context);
        bar.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        topBarBackground = new View(context);
        topBarBackground.setBackgroundColor(NexLentaColors.BACKGROUND);
        topBarBackground.setAlpha(0f);
        bar.addView(topBarBackground, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // the bar's background also covers the status bar
        topBarBackground.setTranslationY(-AndroidUtilities.statusBarHeight);
        ((FrameLayout.LayoutParams) topBarBackground.getLayoutParams()).height = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(BAR_DP);

        final ImageView back = NexLentaUi.circleButton(context, R.drawable.ic_ab_back, NexLentaUi.PILL_CARD, true);
        back.setOnClickListener(v -> finishFragment());
        bar.addView(back, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 6, 0, 6));

        barTitle = new TextView(context);
        barTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        barTitle.setTypeface(AndroidUtilities.bold());
        barTitle.setTextColor(NexLentaColors.TEXT);
        barTitle.setSingleLine(true);
        barTitle.setEllipsize(TextUtils.TruncateAt.END);
        barTitle.setGravity(Gravity.CENTER);
        barTitle.setAlpha(0f);
        bar.addView(barTitle, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 68, 0, 68, 0));

        final ImageView more = NexLentaUi.circleButton(context, R.drawable.ic_ab_other, NexLentaUi.PILL_CARD, true);
        more.setRotation(90);
        more.setOnClickListener(this::showMenu);
        bar.addView(more, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 6, 12, 6));
        return bar;
    }

    private void updateTopBar() {
        if (topBarBackground == null || layoutManager == null) {
            return;
        }
        final View first = layoutManager.findViewByPosition(0);
        final int offset = first == null ? AndroidUtilities.dp(10000) : -first.getTop();
        final float solid = Math.max(0f, Math.min(1f, (offset - AndroidUtilities.dp(80)) / (float) AndroidUtilities.dp(60)));
        topBarBackground.setAlpha(solid);
        barTitle.setAlpha(offset > AndroidUtilities.dp(220) ? 1f : 0f);
    }

    private LinearLayout createHeader(Context context) {
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);

        // banner in the avatar's colors, the photo half over its lower edge
        final FrameLayout top = new FrameLayout(context);
        final int bannerHeight = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(BAR_DP + 96);
        banner = new View(context);
        top.addView(banner, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, bannerHeight));
        final FrameLayout avatarFrame = new FrameLayout(context);
        final GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(NexLentaColors.BACKGROUND);
        avatarFrame.setBackground(ring);
        avatarFrame.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));
        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(AndroidUtilities.dp(AVATAR_DP / 2f));
        avatarView.setOnClickListener(v -> openPhoto());
        avatarFrame.addView(avatarView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        final FrameLayout.LayoutParams avatarParams = new FrameLayout.LayoutParams(AndroidUtilities.dp(AVATAR_DP), AndroidUtilities.dp(AVATAR_DP),
            Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        avatarParams.topMargin = bannerHeight - AndroidUtilities.dp(AVATAR_DP / 2f);
        top.addView(avatarFrame, avatarParams);
        column.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(NexLentaColors.TEXT);
        nameView.setGravity(Gravity.CENTER);
        nameView.setMaxLines(2);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 12, 24, 0));
        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        statusView.setGravity(Gravity.CENTER);
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 0));

        actionsRow = new LinearLayout(context);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        column.addView(actionsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 20, 16, 0));

        infoCard = new LinearLayout(context);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 22));
        infoCard.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        column.addView(infoCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 16, 16, 0));

        // "Media" and "All media and files" (Telegram's shared media screen: files, links, voice, music…)
        final FrameLayout mediaHeader = new FrameLayout(context);
        final TextView mediaTitle = new TextView(context);
        mediaTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        mediaTitle.setTypeface(AndroidUtilities.bold());
        mediaTitle.setTextColor(NexLentaColors.TEXT);
        mediaTitle.setText(LocaleController.getString(R.string.NexPeerMedia));
        mediaHeader.addView(mediaTitle, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        final TextView all = new TextView(context);
        all.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        all.setTextColor(NexLentaColors.ACCENT);
        all.setText(LocaleController.getString(R.string.NexPeerAllMedia) + " ›");
        all.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        all.setOnClickListener(v -> openAllMedia());
        mediaHeader.addView(all, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        column.addView(mediaHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 18, 24, 16, 10));

        NexMotion.enter(nameView, 0);
        NexMotion.enter(statusView, 1);
        NexMotion.enter(actionsRow, 2);
        NexMotion.enter(infoCard, 3);
        return column;
    }

    // ---- data on screen ----

    private void bind() {
        if (nameView == null) {
            return;
        }
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        final String name;
        if (isUser()) {
            avatarDrawable.setInfo(currentAccount, user);
            avatarView.setForUserOrChat(user, avatarDrawable);
            name = UserObject.getUserName(user);
            final boolean online = user.status != null && user.status.expires > getConnectionsManager().getCurrentTime()
                || getMessagesController().onlinePrivacy.containsKey(user.id);
            statusView.setText(UserObject.isBot(user) ? LocaleController.getString(R.string.Bot) : LocaleController.formatUserStatus(currentAccount, user));
            statusView.setTextColor(online ? NexLentaColors.ACCENT : NexLentaColors.SECONDARY_TEXT);
        } else {
            avatarDrawable.setInfo(currentAccount, chat);
            avatarView.setForUserOrChat(chat, avatarDrawable);
            name = chat.title;
            final int count = chatFull != null && chatFull.participants_count > 0 ? chatFull.participants_count : chat.participants_count;
            statusView.setText(count > 0 ? LocaleController.formatPluralString(isChannel() ? "Subscribers" : "Members", count)
                : LocaleController.getString(isChannel() ? R.string.ChannelPublic : R.string.MegaPublic));
            statusView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        }
        nameView.setText(name);
        barTitle.setText(name);
        // a soft banner from the avatar's two colors
        final int c1 = avatarDrawable.getColor();
        final int c2 = avatarDrawable.getColor2();
        final GradientDrawable bannerBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{
            uz.nexgramm.core.NexPalette.blend(c1, NexLentaColors.BACKGROUND, 0.15f),
            uz.nexgramm.core.NexPalette.blend(c2 != 0 ? c2 : c1, NexLentaColors.BACKGROUND, 0.45f)});
        banner.setBackground(bannerBg);
        bindActions();
        bindInfo();
    }

    private void bindActions() {
        final Context context = actionsRow.getContext();
        actionsRow.removeAllViews();
        final boolean muted = getMessagesController().isDialogMuted(dialogId, 0);
        if (isUser()) {
            addAction(context, R.drawable.msg_message, R.string.NexPeerMessage, this::openChat);
            if (!UserObject.isBot(user) && !UserObject.isDeleted(user)) {
                addAction(context, R.drawable.msg_calls, R.string.NexPeerCall, () -> call(false));
                addAction(context, R.drawable.msg_videocall, R.string.NexPeerVideo, () -> call(true));
            }
        } else if (ChatObject.isNotInChat(chat)) {
            addAction(context, R.drawable.msg_add, R.string.NexPeerJoin, this::join);
            addAction(context, R.drawable.msg_message, R.string.NexPeerMessage, this::openChat);
        } else {
            addAction(context, R.drawable.msg_message, R.string.NexPeerMessage, this::openChat);
        }
        if (isUser() || !ChatObject.isNotInChat(chat)) {
            addAction(context, muted ? R.drawable.msg_unmute : R.drawable.msg_mute, muted ? R.string.NexPeerUnmute : R.string.NexPeerMute,
                () -> getNotificationsController().muteDialog(dialogId, 0, !muted));
        }
        if (!isUser() && !ChatObject.isNotInChat(chat)) {
            addAction(context, R.drawable.msg_leave, R.string.NexPeerLeave, this::confirmLeave);
        }
    }

    // a rounded card: the icon in the accent over a short label
    private void addAction(Context context, int icon, int label, Runnable action) {
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(18), NexLentaColors.CARD,
            Theme.multAlpha(NexLentaColors.TEXT, 0.08f)));
        card.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(9));
        final ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER);
        image.setImageResource(icon);
        image.setColorFilter(new PorterDuffColorFilter(NexLentaColors.ACCENT, PorterDuff.Mode.SRC_IN));
        card.addView(image, LayoutHelper.createLinear(28, 28));
        final TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        text.setTextColor(NexLentaColors.TEXT);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setGravity(Gravity.CENTER);
        text.setText(LocaleController.getString(label));
        card.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 5, 4, 0));
        card.setOnClickListener(v -> action.run());
        ScaleStateListAnimator.apply(card, 0.05f, 1.6f);
        actionsRow.addView(card, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, actionsRow.getChildCount() == 0 ? 0 : 8, 0, 0, 0));
    }

    private void bindInfo() {
        infoCard.removeAllViews();
        if (isUser()) {
            final String username = UserObject.getPublicUsername(user);
            if (username != null) {
                addInfo(R.string.NexPeerUsername, "@" + username, "@" + username);
            }
            if (!TextUtils.isEmpty(user.phone)) {
                final String phone = PhoneFormat.getInstance().format("+" + user.phone);
                addInfo(R.string.NexPeerPhone, phone, phone);
            }
            if (userFull != null && !TextUtils.isEmpty(userFull.about)) {
                addInfo(R.string.NexPeerBio, userFull.about, userFull.about);
            }
            if (userFull != null && userFull.birthday != null) {
                final String birthday = UserInfoActivity.birthdayString(userFull.birthday);
                addInfo(R.string.NexPeerBirthday, birthday, birthday);
            }
        } else {
            final String username = ChatObject.getPublicUsername(chat);
            final String link = username != null ? "https://t.me/" + username
                : chatFull != null && chatFull.exported_invite != null ? chatFull.exported_invite.link : null;
            if (!TextUtils.isEmpty(link)) {
                addInfo(R.string.NexPeerLink, link.replace("https://", ""), link);
            }
            if (chatFull != null && !TextUtils.isEmpty(chatFull.about)) {
                addInfo(R.string.NexPeerAbout, chatFull.about, chatFull.about);
            }
            if (!isChannel() || ChatObject.hasAdminRights(chat)) {
                final int count = chatFull != null && chatFull.participants_count > 0 ? chatFull.participants_count : chat.participants_count;
                addRow(R.string.NexPeerMembers, count > 0 ? String.valueOf(count) : "", this::openMembers);
            }
        }
        infoCard.setVisibility(infoCard.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    // a detail: small grey label over the value; a tap copies it
    private void addInfo(int label, String value, String copy) {
        final LinearLayout row = infoRow();
        final TextView labelView = new TextView(row.getContext());
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        labelView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        labelView.setText(LocaleController.getString(label));
        row.addView(labelView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final TextView valueView = new TextView(row.getContext());
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        valueView.setTextColor(NexLentaColors.TEXT);
        valueView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        valueView.setText(value);
        row.addView(valueView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(copy);
            if (getParentActivity() != null) {
                Toast.makeText(getParentActivity(), LocaleController.getString(R.string.NexMeCopied), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // a row that opens something: the label, the value on the right and ›
    private void addRow(int label, String value, Runnable action) {
        final LinearLayout row = infoRow();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final TextView labelView = new TextView(row.getContext());
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        labelView.setTextColor(NexLentaColors.TEXT);
        labelView.setText(LocaleController.getString(label));
        row.addView(labelView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        final TextView valueView = new TextView(row.getContext());
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        valueView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        valueView.setText(value + "  ›");
        row.addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        row.setOnClickListener(v -> action.run());
    }

    private LinearLayout infoRow() {
        final Context context = infoCard.getContext();
        if (infoCard.getChildCount() > 0) {
            final View divider = new View(context);
            divider.setBackgroundColor(NexLentaColors.DIVIDER);
            infoCard.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
        }
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, AndroidUtilities.dp(11), 0, AndroidUtilities.dp(11));
        row.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(NexLentaColors.TEXT, 0.06f), Theme.RIPPLE_MASK_ALL));
        infoCard.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return row;
    }

    // ---- actions ----

    private void openChat() {
        // back to the chat when the profile was opened from it
        final ArrayList<BaseFragment> stack = getParentLayout() == null ? null : new ArrayList<>(getParentLayout().getFragmentStack());
        if (stack != null && stack.size() >= 2) {
            final BaseFragment below = stack.get(stack.size() - 2);
            if (below instanceof ChatActivity && ((ChatActivity) below).getDialogId() == dialogId) {
                finishFragment();
                return;
            }
        }
        final Bundle args = new Bundle();
        if (isUser()) {
            args.putLong("user_id", userId);
        } else {
            args.putLong("chat_id", chatId);
        }
        presentFragment(new ChatActivity(args));
    }

    private void call(boolean video) {
        if (getParentActivity() == null) {
            return;
        }
        VoIPHelper.startCall(user, video, userFull != null && userFull.video_calls_available, getParentActivity(), userFull, getAccountInstance());
    }

    private void join() {
        getMessagesController().addUserToChat(chatId, getUserConfig().getCurrentUser(), 0, null, this, () -> {
            chat = getMessagesController().getChat(chatId);
            bind();
        });
    }

    private void confirmLeave() {
        if (getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity())
            .setTitle(chat.title)
            .setMessage(AndroidUtilities.replaceTags(LocaleController.formatString(R.string.ChannelLeaveAlertWithName, chat.title)))
            .setPositiveButton(LocaleController.getString(R.string.NexPeerLeave), (d, w) -> {
                getMessagesController().deleteParticipantFromChat(chatId, getUserConfig().getCurrentUser());
                finishFragment();
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show();
    }

    private void openMembers() {
        final Bundle args = new Bundle();
        args.putLong("chat_id", chatId);
        args.putInt("type", ChatUsersActivity.TYPE_USERS);
        presentFragment(new ChatUsersActivity(args));
    }

    private void openAllMedia() {
        final Bundle args = new Bundle();
        args.putLong("dialog_id", dialogId);
        args.putInt("type", MediaActivity.TYPE_MEDIA);
        presentFragment(new MediaActivity(args, null));
    }

    // Telegram's full profile, for everything not shown here
    private void openClassic() {
        final Bundle args = new Bundle(getArguments());
        args.putBoolean("nex_classic", true);
        presentFragment(new ProfileActivity(args));
    }

    private void openPhoto() {
        if (getParentActivity() == null) {
            return;
        }
        final TLRPC.FileLocation big;
        if (isUser()) {
            big = user.photo != null && !(user.photo instanceof TLRPC.TL_userProfilePhotoEmpty) ? user.photo.photo_big : null;
            if (big != null && user.photo.dc_id != 0) {
                big.dc_id = user.photo.dc_id;
            }
        } else {
            big = chat.photo != null && !(chat.photo instanceof TLRPC.TL_chatPhotoEmpty) ? chat.photo.photo_big : null;
            if (big != null && chat.photo.dc_id != 0) {
                big.dc_id = chat.photo.dc_id;
            }
        }
        if (big == null) {
            return;
        }
        PhotoViewer.getInstance().setParentActivity(this);
        PhotoViewer.getInstance().openPhoto(big, new PhotoViewer.EmptyPhotoViewerProvider());
    }

    private void openMedia(int index) {
        if (getParentActivity() == null || index < 0 || index >= media.size()) {
            return;
        }
        PhotoViewer.getInstance().setParentActivity(this);
        PhotoViewer.getInstance().openPhoto(media, index, dialogId, 0, 0, new PhotoViewer.EmptyPhotoViewerProvider());
    }

    private void showMenu(View anchor) {
        final ItemOptions options = ItemOptions.makeOptions(this, anchor);
        if (isUser()) {
            final String username = UserObject.getPublicUsername(user);
            if (username != null) {
                options.add(R.drawable.msg_share, LocaleController.getString(R.string.ShareLink), () -> share("https://t.me/" + username));
            }
            if (!user.contact && !UserObject.isBot(user) && !TextUtils.isEmpty(user.phone)) {
                options.add(R.drawable.msg_addcontact, LocaleController.getString(R.string.AddContact), () -> {
                    final Bundle args = new Bundle();
                    args.putLong("user_id", userId);
                    presentFragment(new ContactAddActivity(args));
                });
            }
            final boolean blocked = getMessagesController().blockePeers.indexOfKey(userId) >= 0;
            options.add(blocked ? R.drawable.msg_block2 : R.drawable.msg_block, LocaleController.getString(blocked ? R.string.Unblock : R.string.BlockContact), () -> {
                if (blocked) {
                    getMessagesController().unblockPeer(userId);
                } else {
                    getMessagesController().blockPeer(userId);
                }
            });
        } else {
            final String username = ChatObject.getPublicUsername(chat);
            if (username != null) {
                options.add(R.drawable.msg_share, LocaleController.getString(R.string.ShareLink), () -> share("https://t.me/" + username));
            }
            if (ChatObject.canChangeChatInfo(chat)) {
                options.add(R.drawable.msg_edit, LocaleController.getString(R.string.Edit), () -> {
                    final Bundle args = new Bundle();
                    args.putLong("chat_id", chatId);
                    presentFragment(new ChatEditActivity(args));
                });
            }
        }
        options.add(R.drawable.msg_info, LocaleController.getString(R.string.NexPeerFullProfile), this::openClassic);
        options.show();
    }

    private void share(String link) {
        if (getParentActivity() == null) {
            return;
        }
        showDialog(new org.telegram.ui.Components.ShareAlert(getParentActivity(), null, link, false, link, false));
    }

    // ---- list: the header, then the photos and videos ----

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemCount() {
            return 1 + (media.isEmpty() ? (mediaLoaded && mediaEnd ? 1 : 0) : media.size());
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return TYPE_HEADER;
            }
            return media.isEmpty() ? TYPE_EMPTY : TYPE_MEDIA;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View view;
            if (viewType == TYPE_HEADER) {
                AndroidUtilities.removeFromParent(headerView);
                view = headerView;
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else if (viewType == TYPE_EMPTY) {
                final TextView empty = new TextView(parent.getContext());
                empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                empty.setTextColor(NexLentaColors.SECONDARY_TEXT);
                empty.setGravity(Gravity.CENTER);
                empty.setText(LocaleController.getString(R.string.NexPeerNoMedia));
                empty.setPadding(0, AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32));
                empty.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                view = empty;
            } else {
                view = new MediaCell(parent.getContext());
                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.itemView instanceof MediaCell) {
                final int index = position - 1;
                ((MediaCell) holder.itemView).set(media.get(index), index);
            }
        }
    }

    // A square photo / video thumbnail (videos show their length)
    private class MediaCell extends FrameLayout {
        private final BackupImageView image;
        private final TextView duration;
        private int index;

        MediaCell(Context context) {
            super(context);
            setPadding(AndroidUtilities.dp(1), AndroidUtilities.dp(1), AndroidUtilities.dp(1), AndroidUtilities.dp(1));
            image = new BackupImageView(context);
            image.getImageReceiver().setRoundRadius(AndroidUtilities.dp(4));
            addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            duration = new TextView(context);
            duration.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            duration.setTextColor(0xFFFFFFFF);
            duration.setTypeface(AndroidUtilities.bold());
            duration.setShadowLayer(AndroidUtilities.dp(3), 0, AndroidUtilities.dp(1), 0x99000000);
            addView(duration, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 6, 4));
            setOnClickListener(v -> openMedia(index));
            ScaleStateListAnimator.apply(this, 0.04f, 1.5f);
        }

        void set(MessageObject m, int index) {
            this.index = index;
            final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(m.photoThumbs, 320);
            final TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(m.photoThumbs, 50);
            image.setImage(ImageLocation.getForObject(size, m.photoThumbsObject), "160_160",
                ImageLocation.getForObject(small, m.photoThumbsObject), "b", 0, m);
            if (m.isVideo()) {
                duration.setVisibility(VISIBLE);
                duration.setText(AndroidUtilities.formatShortDuration((int) m.getDuration()));
            } else {
                duration.setVisibility(GONE);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec); // square
        }
    }
}
