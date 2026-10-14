package org.telegram.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.StoriesController;
import org.telegram.ui.Stories.StoryViewer;

// NexGramm: "Highlights" as on Instagram — the Telegram story albums of a profile as round covers in a row, in
// their own order. A tap plays the album; on your own profile "+" makes a new album (Telegram's album page
// then adds stories to it). Hidden when someone else has no albums.
final class NexHighlightsRow extends LinearLayout implements NotificationCenter.NotificationCenterDelegate {

    private final BaseFragment fragment;
    private final long dialogId;
    private final boolean own;
    private final LinearLayout row;
    private int pendingAlbum; // tapped while its stories were still loading

    NexHighlightsRow(Context context, BaseFragment fragment, long dialogId, boolean own) {
        super(context);
        this.fragment = fragment;
        this.dialogId = dialogId;
        this.own = own;
        setOrientation(VERTICAL);

        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(NexLentaColors.TEXT);
        title.setText(LocaleController.getString(R.string.NexHighlights));
        addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 10));

        final HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);
        row.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        scroll.addView(row);
        addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rebuild();
    }

    private StoriesController controller() {
        return fragment.getMessagesController().getStoriesController();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getInstance(fragment.getCurrentAccount()).addObserver(this, NotificationCenter.storyAlbumsCollectionsUpdate);
        NotificationCenter.getInstance(fragment.getCurrentAccount()).addObserver(this, NotificationCenter.storiesListUpdated);
        controller().getStoryAlbumsList(dialogId).load();
        rebuild();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getInstance(fragment.getCurrentAccount()).removeObserver(this, NotificationCenter.storyAlbumsCollectionsUpdate);
        NotificationCenter.getInstance(fragment.getCurrentAccount()).removeObserver(this, NotificationCenter.storiesListUpdated);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.storyAlbumsCollectionsUpdate) {
            if (args.length > 0 && args[0] instanceof Long && (Long) args[0] == dialogId) {
                rebuild();
            }
        } else if (id == NotificationCenter.storiesListUpdated && pendingAlbum != 0) {
            final StoriesController.StoriesList list = args.length > 0 && args[0] instanceof StoriesController.StoriesList
                ? (StoriesController.StoriesList) args[0] : null;
            if (list != null && list.dialogId == dialogId && list.albumId == pendingAlbum && !list.messageObjects.isEmpty()) {
                pendingAlbum = 0;
                play(list);
            }
        }
    }

    private void rebuild() {
        row.removeAllViews();
        final StoriesController.StoriesCollections albums = controller().getStoryAlbumsList(dialogId);
        if (own) {
            final Item add = new Item(getContext());
            add.setAdd();
            add.setOnClickListener(v -> createAlbum());
            row.addView(add, LayoutHelper.createLinear(78, LayoutHelper.WRAP_CONTENT));
        }
        for (StoriesController.StoryAlbum album : albums.collections) {
            final Item item = new Item(getContext());
            item.set(album);
            item.setOnClickListener(v -> openAlbum(album.album_id));
            row.addView(item, LayoutHelper.createLinear(78, LayoutHelper.WRAP_CONTENT));
        }
        setVisibility(own || !albums.collections.isEmpty() ? VISIBLE : GONE);
    }

    private void openAlbum(int albumId) {
        final StoriesController.StoriesList list = controller().getStoriesList(dialogId, StoriesController.StoriesList.TYPE_ALBUMS, albumId);
        if (!list.messageObjects.isEmpty()) {
            play(list);
            return;
        }
        pendingAlbum = albumId;
        list.load(false, 30);
    }

    private void play(StoriesController.StoriesList list) {
        if (fragment.getParentActivity() == null || list.messageObjects.isEmpty()) {
            return;
        }
        final StoryViewer.PlaceProvider place = (did, messageId, storyId, type, holder) -> false;
        fragment.getOrCreateStoryViewer().open(fragment.getParentActivity(), list.messageObjects.get(0).getId(), list, place);
    }

    // a name, then Telegram's album page to add stories to it
    private void createAlbum() {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        if (!controller().getStoryAlbumsList(dialogId).canCreateNewAlbum()) {
            return;
        }
        final EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setHint(LocaleController.getString(R.string.NexHighlightName));
        input.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), AndroidUtilities.dp(8));
        new AlertDialog.Builder(context)
            .setTitle(LocaleController.getString(R.string.StoriesAlbumCreateNew))
            .setView(input)
            .setPositiveButton(LocaleController.getString(R.string.NexHighlightCreate), (d, w) -> {
                final String name = input.getText().toString().trim();
                if (!name.isEmpty()) {
                    controller().createAlbum(dialogId, name, album -> {
                        if (album != null) {
                            openAlbumPage(album.album_id);
                        }
                    });
                }
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show();
    }

    private void openAlbumPage(int albumId) {
        final Bundle args = new Bundle();
        args.putLong("user_id", dialogId);
        args.putBoolean("my_profile", true);
        args.putInt("open_story_album_id", albumId);
        args.putBoolean("nex_classic", true);
        fragment.presentFragment(new ProfileActivity(args));
    }

    // a round cover with an accent ring and the album's name
    private static final class Item extends LinearLayout {
        private final FrameLayout ring;
        private final BackupImageView cover;
        private final TextView name;

        Item(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            ring = new FrameLayout(context);
            final GradientDrawable ringBg = new GradientDrawable();
            ringBg.setShape(GradientDrawable.OVAL);
            ringBg.setColor(NexLentaColors.BACKGROUND);
            ringBg.setStroke(AndroidUtilities.dp(2), NexLentaColors.ACCENT);
            ring.setBackground(ringBg);
            ring.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));
            cover = new BackupImageView(context);
            cover.setRoundRadius(AndroidUtilities.dp(29));
            ring.addView(cover, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            addView(ring, LayoutHelper.createLinear(66, 66));
            name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            name.setTextColor(NexLentaColors.TEXT);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 5, 4, 0));
            ScaleStateListAnimator.apply(this, 0.06f, 1.5f);
        }

        void set(StoriesController.StoryAlbum album) {
            name.setText(album.title);
            if (album.icon_photo != null && album.icon_photo.sizes != null && !album.icon_photo.sizes.isEmpty()) {
                final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(album.icon_photo.sizes, 160);
                cover.setImage(ImageLocation.getForPhoto(size, album.icon_photo), "64_64", (android.graphics.drawable.Drawable) null, album);
            } else if (album.icon_video != null && album.icon_video.thumbs != null && !album.icon_video.thumbs.isEmpty()) {
                final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(album.icon_video.thumbs, 160);
                cover.setImage(ImageLocation.getForDocument(size, album.icon_video), "64_64", (android.graphics.drawable.Drawable) null, album);
            } else {
                cover.setImageDrawable(NexLentaUi.initialsAvatar(album.title, true));
            }
        }

        void setAdd() {
            name.setText(LocaleController.getString(R.string.NexHighlightAdd));
            final GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(NexLentaColors.CARD);
            ring.setBackground(bg);
            final TextView plus = new TextView(getContext());
            plus.setText("+");
            plus.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
            plus.setTextColor(NexLentaColors.ACCENT);
            plus.setGravity(Gravity.CENTER);
            ring.removeAllViews();
            ring.addView(plus, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
    }
}
