package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;

import java.util.ArrayList;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.NexPalette;
import uz.nexgramm.core.PixelfedAccount;
import uz.nexgramm.core.PixelfedClient;
import uz.nexgramm.core.PixelfedStories;

// NexGramm: the stories row on top of the Lenta, as in Instagram: "Your story" first (the photo with a +,
// a ring once there is a story), then the followed accounts, unseen ones with a colored ring.
final class NexStoriesBar extends HorizontalScrollView {

    interface Delegate {
        void onOpenStories(ArrayList<PixelfedStories.Group> groups, int index);

        void onAddStory();
    }

    // own photo for "Your story", loaded once per app run
    private static String ownAvatarUrl;
    private static String ownName;
    private static boolean ownLoaded;

    private final long tgUserId;
    private final Delegate delegate;
    private final LinearLayout row;
    private PixelfedStories stories;
    private boolean loading;
    private int generation;

    NexStoriesBar(Context context, long tgUserId, Delegate delegate) {
        super(context);
        this.tgUserId = tgUserId;
        this.delegate = delegate;
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10), 0);
        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        rebuild();
    }

    void load() {
        if (loading) {
            return;
        }
        loading = true;
        final int gen = ++generation;
        CoreClient.EXECUTOR.execute(() -> {
            PixelfedStories result = null;
            try {
                if (!ownLoaded) {
                    final PixelfedAccount own = PixelfedClient.ownAccountBlocking(tgUserId);
                    ownAvatarUrl = own.avatarUrl;
                    ownName = own.displayName;
                    ownLoaded = true;
                }
                result = PixelfedClient.storiesBlocking(tgUserId);
            } catch (Exception ignore) {
                // stories off on the server or no network: "Your story" stays alone
            }
            final PixelfedStories finalResult = result;
            AndroidUtilities.runOnUIThread(() -> {
                loading = false;
                if (gen != generation) {
                    return;
                }
                if (finalResult != null) {
                    stories = finalResult;
                }
                rebuild();
            });
        });
    }

    // the seen marks changed in the viewer
    void refreshRings() {
        rebuild();
    }

    private ArrayList<PixelfedStories.Group> groups() {
        final ArrayList<PixelfedStories.Group> list = new ArrayList<>();
        if (stories != null) {
            if (stories.self != null) {
                list.add(stories.self);
            }
            // unseen first, as Instagram
            for (PixelfedStories.Group g : stories.others) {
                if (!g.allSeen()) {
                    list.add(g);
                }
            }
            for (PixelfedStories.Group g : stories.others) {
                if (g.allSeen()) {
                    list.add(g);
                }
            }
        }
        return list;
    }

    private void rebuild() {
        row.removeAllViews();
        final ArrayList<PixelfedStories.Group> groups = groups();
        final boolean hasSelf = stories != null && stories.self != null;
        final StoryCell own = new StoryCell(getContext());
        own.set(ownAvatarUrl, ownName, LocaleController.getString(R.string.NexStoryYours), hasSelf ? (stories.self.allSeen() ? 1 : 2) : 0, true);
        own.setOnClickListener(v -> {
            if (hasSelf) {
                delegate.onOpenStories(groups, 0);
            } else {
                delegate.onAddStory();
            }
        });
        own.badge.setOnClickListener(v -> delegate.onAddStory());
        row.addView(own, LayoutHelper.createLinear(78, LayoutHelper.WRAP_CONTENT));
        for (int i = hasSelf ? 1 : 0; i < groups.size(); i++) {
            final PixelfedStories.Group g = groups.get(i);
            final int index = i;
            final StoryCell cell = new StoryCell(getContext());
            cell.set(g.avatarUrl, g.username, g.username, g.allSeen() ? 1 : 2, false);
            cell.setOnClickListener(v -> delegate.onOpenStories(groups, index));
            row.addView(cell, LayoutHelper.createLinear(78, LayoutHelper.WRAP_CONTENT));
        }
    }

    // ring (none / seen / unseen), photo, name; "Your story" has a + badge
    private static final class StoryCell extends LinearLayout {
        final RingView ring;
        final BackupImageView avatar;
        final ImageView badge;
        final TextView name;

        StoryCell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));
            final FrameLayout frame = new FrameLayout(context);
            ring = new RingView(context);
            frame.addView(ring, LayoutHelper.createFrame(72, 72, Gravity.CENTER));
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(AndroidUtilities.dp(31));
            frame.addView(avatar, LayoutHelper.createFrame(62, 62, Gravity.CENTER));
            badge = new ImageView(context);
            badge.setScaleType(ImageView.ScaleType.CENTER);
            badge.setImageResource(R.drawable.msg_add);
            badge.setColorFilter(new android.graphics.PorterDuffColorFilter(NexLentaColors.ON_ACCENT, android.graphics.PorterDuff.Mode.SRC_IN));
            final android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
            dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setColor(NexLentaColors.ACCENT);
            dot.setStroke(AndroidUtilities.dp(2.5f), NexLentaColors.BACKGROUND);
            badge.setBackground(dot);
            badge.setVisibility(GONE);
            frame.addView(badge, LayoutHelper.createFrame(26, 26, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 2, 2));
            addView(frame, LayoutHelper.createLinear(74, 74, Gravity.CENTER_HORIZONTAL));
            name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            name.setTextColor(NexLentaColors.TEXT);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity(Gravity.CENTER);
            addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 3, 4, 0));
            ScaleStateListAnimator.apply(this, 0.06f, 1.5f);
        }

        // state: 0 = no story, 1 = all seen, 2 = something new
        void set(String avatarUrl, String displayName, String label, int state, boolean own) {
            if (avatarUrl == null || avatarUrl.contains("/avatars/default")) {
                avatar.setImageDrawable(NexLentaUi.initialsAvatar(displayName, true));
            } else {
                avatar.setImage(avatarUrl, "124_124", NexLentaUi.initialsAvatar(displayName, true));
            }
            name.setText(label);
            name.setTextColor(state == 1 && !own ? NexLentaColors.SECONDARY_TEXT : NexLentaColors.TEXT);
            ring.state = state;
            ring.invalidate();
            badge.setVisibility(own ? VISIBLE : GONE);
        }
    }

    private static final class RingView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        int state;

        RingView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (state == 0) {
                return;
            }
            final float stroke = AndroidUtilities.dp(state == 2 ? 2.6f : 1.4f);
            paint.setStrokeWidth(stroke);
            rect.set(stroke, stroke, getWidth() - stroke, getHeight() - stroke);
            if (state == 2) {
                // the accent running into a lighter, warmer tone of it, like Instagram's ring
                final int a = NexLentaColors.ACCENT;
                final int b = NexPalette.blend(a, 0xFFFF7A59, 0.55f);
                final int c = NexPalette.blend(a, 0xFFD7C4FA, 0.6f);
                paint.setShader(new SweepGradient(getWidth() / 2f, getHeight() / 2f, new int[]{a, b, c, a}, null));
            } else {
                paint.setShader(null);
                paint.setColor(NexLentaColors.DIVIDER);
            }
            canvas.drawOval(rect, paint);
        }
    }
}
