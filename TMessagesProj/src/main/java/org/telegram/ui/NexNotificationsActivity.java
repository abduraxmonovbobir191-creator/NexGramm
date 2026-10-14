package org.telegram.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.util.ArrayList;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.PixelfedClient;
import uz.nexgramm.core.PixelfedNotification;
import uz.nexgramm.core.PixelfedStatus;

// NexGramm: Lenta notifications (the ♥ in the Lenta header): likes, comments, mentions, new followers and
// shares from GET /api/v1/notifications, newest first, paged by max_id. A row opens the post (a comment:
// the post it was left on) or, for a follow, the profile; the photo always opens the profile.
public class NexNotificationsActivity extends BaseFragment {

    private static final int TYPE_ROW = 0;
    private static final int TYPE_STATE = 1;

    private final ArrayList<PixelfedNotification> items = new ArrayList<>();
    private boolean loading;
    private boolean failed;
    private boolean endReached;
    private boolean opening;
    private int generation;

    private final Adapter adapter = new Adapter();

    private long tgUserId() {
        return getUserConfig().getClientUserId();
    }

    @Override
    public boolean isLightStatusBar() {
        return !NexLentaColors.dark;
    }

    @Override
    public View createView(Context context) {
        NexLentaUi.setupActionBar(this, actionBar, LocaleController.getString(R.string.NexFeedNotifications));

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(NexLentaColors.BACKGROUND);
        final RecyclerView listView = new RecyclerView(context);
        final LinearLayoutManager layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false);
        listView.setLayoutManager(layoutManager);
        listView.setItemAnimator(null);
        listView.setClipToPadding(false);
        listView.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(12));
        listView.setAdapter(adapter);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (!loading && !failed && !endReached && !items.isEmpty()
                    && layoutManager.findLastVisibleItemPosition() >= adapter.getItemCount() - 5) {
                    load(false);
                }
            }
        });
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = root;
        load(true);
        return fragmentView;
    }

    private void load(boolean first) {
        if (loading) {
            return;
        }
        loading = true;
        failed = false;
        if (first) {
            items.clear();
            endReached = false;
        }
        adapter.notifyDataSetChanged();
        final int gen = ++generation;
        final long userId = tgUserId();
        final String maxId = items.isEmpty() ? null : items.get(items.size() - 1).id;
        CoreClient.EXECUTOR.execute(() -> {
            ArrayList<PixelfedNotification> result = null;
            try {
                result = PixelfedClient.notificationsBlocking(userId, maxId);
            } catch (Exception ignore) {
            }
            final ArrayList<PixelfedNotification> page = result;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || fragmentView == null) {
                    return;
                }
                loading = false;
                if (page == null) {
                    failed = true;
                } else {
                    items.addAll(page);
                    endReached = page.isEmpty();
                }
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void openProfile(String accountId) {
        if (accountId != null && !accountId.isEmpty()) {
            presentFragment(new NexProfileActivity(accountId));
        }
    }

    private void open(PixelfedNotification n) {
        if (PixelfedNotification.FOLLOW.equals(n.type) || n.status == null) {
            openProfile(n.accountId);
            return;
        }
        if (!n.isComment()) {
            presentFragment(new NexCommentsActivity(n.status));
            return;
        }
        // a comment: the post it was left on, with all its comments
        if (opening) {
            return;
        }
        opening = true;
        final long userId = tgUserId();
        CoreClient.EXECUTOR.execute(() -> {
            PixelfedStatus parent = null;
            try {
                parent = PixelfedClient.statusBlocking(userId, n.inReplyToId);
            } catch (Exception ignore) {
            }
            final PixelfedStatus post = parent;
            AndroidUtilities.runOnUIThread(() -> {
                opening = false;
                if (fragmentView == null) {
                    return;
                }
                if (post == null) {
                    Toast.makeText(getParentActivity(), LocaleController.getString(R.string.NexFeedLoadError), Toast.LENGTH_SHORT).show();
                    return;
                }
                presentFragment(new NexCommentsActivity(post));
            });
        });
    }

    private static String actionText(PixelfedNotification n) {
        switch (n.type) {
            case PixelfedNotification.FAVOURITE:
                return LocaleController.getString(R.string.NexNotifLiked);
            case PixelfedNotification.FOLLOW:
                return LocaleController.getString(R.string.NexNotifFollowed);
            case PixelfedNotification.REBLOG:
                return LocaleController.getString(R.string.NexNotifShared);
            default:
                return LocaleController.getString(n.isComment() ? R.string.NexNotifCommented : R.string.NexNotifMentioned);
        }
    }

    private static int badgeIcon(PixelfedNotification n) {
        switch (n.type) {
            case PixelfedNotification.FAVOURITE:
                return R.drawable.media_like_active;
            case PixelfedNotification.FOLLOW:
                return R.drawable.msg_addcontact;
            case PixelfedNotification.REBLOG:
                return R.drawable.msg_share;
            default:
                return n.isComment() ? R.drawable.msg_msgbubble3 : R.drawable.msg_mention;
        }
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private boolean showState() {
            return loading || failed || items.isEmpty();
        }

        @Override
        public int getItemCount() {
            return items.size() + (showState() ? 1 : 0);
        }

        @Override
        public int getItemViewType(int position) {
            return position < items.size() ? TYPE_ROW : TYPE_STATE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View view = viewType == TYPE_ROW ? new RowCell(parent.getContext()) : new StateCell(parent.getContext());
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.itemView instanceof RowCell) {
                ((RowCell) holder.itemView).set(items.get(position));
            } else {
                ((StateCell) holder.itemView).update();
            }
        }
    }

    // Photo with a small badge (♥, 💬, +, share), "Name liked your post · 2h", the comment text, the post thumbnail
    private class RowCell extends FrameLayout {
        private final BackupImageView avatarView;
        private final ImageView badgeView;
        private final TextView textView;
        private final BackupImageView thumbView;
        private PixelfedNotification item;

        RowCell(Context context) {
            super(context);
            setBackground(Theme.createSelectorDrawable(Theme.multAlpha(NexLentaColors.TEXT, 0.06f), Theme.RIPPLE_MASK_ALL));
            setOnClickListener(v -> {
                if (item != null) {
                    open(item);
                }
            });

            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(AndroidUtilities.dp(23));
            avatarView.setOnClickListener(v -> {
                if (item != null) {
                    openProfile(item.accountId);
                }
            });
            addView(avatarView, LayoutHelper.createFrame(46, 46, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 10, 0, 10));

            badgeView = new ImageView(context);
            badgeView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            badgeView.setPadding(AndroidUtilities.dp(3), AndroidUtilities.dp(3), AndroidUtilities.dp(3), AndroidUtilities.dp(3));
            badgeView.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 11));
            addView(badgeView, LayoutHelper.createFrame(22, 22, Gravity.LEFT | Gravity.CENTER_VERTICAL, 46, 22, 0, 0));

            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            textView.setTextColor(NexLentaColors.TEXT);
            textView.setLineSpacing(AndroidUtilities.dp(1), 1f);
            textView.setMaxLines(3);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 74, 10, 72, 10));

            thumbView = new BackupImageView(context);
            thumbView.setRoundRadius(AndroidUtilities.dp(8));
            addView(thumbView, LayoutHelper.createFrame(46, 46, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 10, 16, 10));
            setMinimumHeight(AndroidUtilities.dp(66));
        }

        void set(PixelfedNotification n) {
            item = n;
            if (n.avatarUrl == null || n.avatarUrl.contains("/avatars/default")) {
                avatarView.setImageDrawable(NexLentaUi.initialsAvatar(n.accountName, true));
            } else {
                avatarView.setImage(n.avatarUrl, "92_92", NexLentaUi.initialsAvatar(n.accountName, true));
            }
            badgeView.setImageResource(badgeIcon(n));
            final int badgeColor = PixelfedNotification.FAVOURITE.equals(n.type) ? NexLentaColors.LIKED : NexLentaColors.ACCENT;
            badgeView.setColorFilter(new PorterDuffColorFilter(badgeColor, PorterDuff.Mode.SRC_IN));

            final SpannableStringBuilder text = new SpannableStringBuilder();
            text.append(n.accountName);
            text.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.append(' ').append(actionText(n));
            if (PixelfedNotification.MENTION.equals(n.type) && n.status != null) {
                final String body = NexCommentsActivity.htmlToText(n.status.contentHtml);
                if (!body.isEmpty()) {
                    text.append(": ").append(body);
                }
            }
            final int timeStart = text.length();
            text.append(" · ").append(NexLentaUi.timeAgo(n.createdAt));
            text.setSpan(new ForegroundColorSpan(NexLentaColors.SECONDARY_TEXT), timeStart, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            textView.setText(text);

            // the post's picture; a comment shows its post's picture only when it has its own media
            final PixelfedStatus.Media media = n.status != null && !n.status.media.isEmpty() ? n.status.media.get(0) : null;
            if (media != null && media.previewUrl != null) {
                thumbView.setVisibility(VISIBLE);
                thumbView.setImage(media.previewUrl, "92_92", null);
            } else {
                thumbView.setVisibility(GONE);
                thumbView.setImageDrawable(null);
            }
        }
    }

    private class StateCell extends FrameLayout {
        private final RadialProgressView progressView;
        private final TextView textView;

        StateCell(Context context) {
            super(context);
            progressView = new RadialProgressView(context);
            progressView.setSize(AndroidUtilities.dp(24));
            progressView.setProgressColor(NexLentaColors.ACCENT);
            addView(progressView, LayoutHelper.createFrame(32, 32, Gravity.CENTER, 0, 24, 0, 24));
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            textView.setGravity(Gravity.CENTER);
            textView.setTextColor(NexLentaColors.SECONDARY_TEXT);
            textView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(40), AndroidUtilities.dp(16), AndroidUtilities.dp(40));
            textView.setOnClickListener(v -> {
                if (failed) {
                    load(items.isEmpty());
                }
            });
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        }

        void update() {
            progressView.setVisibility(loading ? View.VISIBLE : View.GONE);
            textView.setVisibility(loading ? View.GONE : View.VISIBLE);
            textView.setText(failed
                ? LocaleController.getString(R.string.NexFeedLoadError) + "\n" + LocaleController.getString(R.string.NexFeedLoginRetry)
                : LocaleController.getString(R.string.NexNotifEmpty));
        }
    }
}
