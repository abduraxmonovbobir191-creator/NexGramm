package org.telegram.ui;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Build;
import android.text.Html;
import android.text.InputFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import uz.nexgramm.core.PixelfedStatus;

// NexGramm: comments of a Lenta post. The post on top, then GET /api/v1/statuses/:id/context
// (descendants); new comment = POST /api/v1/statuses with in_reply_to_id. The post object is shared
// with the feed, so its comment counter there follows.
public class NexCommentsActivity extends BaseFragment implements NexPostCell.Delegate {

    private static final int TYPE_POST = 0;
    private static final int TYPE_COMMENT = 1;
    private static final int TYPE_STATE = 2;

    private final PixelfedStatus post;
    private final ArrayList<PixelfedStatus> comments = new ArrayList<>();
    private boolean loading = true;
    private boolean failed;
    private boolean sending;
    private int generation;

    private RecyclerView listView;
    private final Adapter adapter = new Adapter();
    private EditText inputView;
    private ImageView sendButton;

    public NexCommentsActivity(PixelfedStatus post) {
        this.post = post;
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
        NexLentaUi.setupActionBar(this, actionBar, LocaleController.getString(R.string.NexCommentsTitle));

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(NexLentaColors.BACKGROUND);

        listView = new RecyclerView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setItemAnimator(null);
        listView.setClipToPadding(false);
        listView.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(8));
        listView.setAdapter(adapter);
        root.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        // Input bar
        final LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(NexLentaColors.CARD);
        bar.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(6), AndroidUtilities.dp(6), AndroidUtilities.dp(6));
        inputView = new EditText(context);
        inputView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        inputView.setTextColor(NexLentaColors.TEXT);
        inputView.setHintTextColor(NexLentaColors.SECONDARY_TEXT);
        inputView.setHint(LocaleController.getString(R.string.NexCommentsHint));
        inputView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(20), NexLentaColors.BACKGROUND));
        inputView.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(14), AndroidUtilities.dp(10));
        inputView.setMaxLines(5);
        inputView.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        bar.addView(inputView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        sendButton = new ImageView(context);
        sendButton.setScaleType(ImageView.ScaleType.CENTER);
        sendButton.setImageResource(R.drawable.msg_send);
        sendButton.setColorFilter(new PorterDuffColorFilter(NexLentaColors.ACCENT, PorterDuff.Mode.SRC_IN));
        sendButton.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(NexLentaColors.TEXT, 0.1f), Theme.RIPPLE_MASK_CIRCLE_20DP));
        sendButton.setOnClickListener(v -> send());
        bar.addView(sendButton, LayoutHelper.createLinear(44, 44, 4, 0, 0, 0));
        root.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        NexLentaUi.padForKeyboard(root);
        fragmentView = root;
        load();
        return fragmentView;
    }

    private void load() {
        loading = true;
        failed = false;
        adapter.notifyDataSetChanged();
        final int gen = ++generation;
        final long userId = tgUserId();
        CoreClient.EXECUTOR.execute(() -> {
            ArrayList<PixelfedStatus> result = null;
            try {
                result = PixelfedClient.commentsBlocking(userId, post.statusId);
            } catch (Exception ignore) {
            }
            final ArrayList<PixelfedStatus> finalResult = result;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || fragmentView == null) {
                    return;
                }
                loading = false;
                if (finalResult == null) {
                    failed = true;
                } else {
                    comments.clear();
                    comments.addAll(finalResult);
                    if (post.repliesCount < comments.size()) {
                        post.repliesCount = comments.size();
                    }
                }
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void send() {
        final String text = inputView.getText().toString().trim();
        if (sending || text.isEmpty()) {
            return;
        }
        sending = true;
        sendButton.setAlpha(0.5f);
        final long userId = tgUserId();
        CoreClient.EXECUTOR.execute(() -> {
            PixelfedStatus created = null;
            try {
                created = PixelfedClient.createStatusBlocking(userId, text, null, post.statusId);
            } catch (Exception ignore) {
            }
            final PixelfedStatus result = created;
            AndroidUtilities.runOnUIThread(() -> {
                sending = false;
                if (fragmentView == null) {
                    return;
                }
                sendButton.setAlpha(1f);
                if (result == null) {
                    Toast.makeText(getParentActivity(), LocaleController.getString(R.string.NexCommentsSendError), Toast.LENGTH_SHORT).show();
                    return;
                }
                inputView.setText("");
                comments.add(result);
                post.repliesCount++;
                failed = false;
                loading = false;
                adapter.notifyDataSetChanged();
                listView.scrollToPosition(adapter.getItemCount() - 1);
            });
        });
    }

    // ---- post cell ----

    @Override
    public void onLikeClick(NexPostCell cell, PixelfedStatus status) {
        NexNativeFeedView.toggleLike(getParentActivity(), tgUserId(), status, cell::updateLike, () -> {
        });
    }

    @Override
    public void onMediaPageChanged(NexPostCell cell) {
    }

    private final NexPostActions.Listener postListener = new NexPostActions.Listener() {
        @Override
        public void onDeleted(PixelfedStatus status) {
            if (status == post) {
                finishFragment();
            } else {
                comments.remove(status);
                adapter.notifyDataSetChanged();
            }
        }

        @Override
        public void onChanged(PixelfedStatus status) {
            adapter.notifyItemChanged(0);
        }
    };

    @Override
    public void onShareClick(PixelfedStatus status) {
        NexPostActions.share(this, status);
    }

    @Override
    public void onBookmarkClick(NexPostCell cell, PixelfedStatus status) {
        NexPostActions.toggleBookmark(getParentActivity(), tgUserId(), status, postListener);
    }

    @Override
    public void onMenuClick(NexPostCell cell, PixelfedStatus status) {
        NexPostActions.showMenu(this, tgUserId(), status, postListener);
    }

    @Override
    public void onAiClick(PixelfedStatus status) {
        NexPostActions.aiSoon(getParentActivity());
    }

    @Override
    public void onAuthorClick(PixelfedStatus status) {
        openProfile(status.authorId);
    }

    private void openProfile(String accountId) {
        if (accountId != null && !accountId.isEmpty()) {
            presentFragment(new NexProfileActivity(accountId));
        }
    }

    @SuppressWarnings("deprecation")
    static String htmlToText(String html) {
        if (TextUtils.isEmpty(html)) {
            return "";
        }
        final CharSequence text = Build.VERSION.SDK_INT >= 24 ? Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT) : Html.fromHtml(html);
        return text.toString().trim();
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private boolean showState() {
            return loading || failed || comments.isEmpty();
        }

        @Override
        public int getItemCount() {
            return 1 + (showState() ? 1 : comments.size());
        }

        @Override
        public int getItemViewType(int position) {
            if (position == 0) {
                return TYPE_POST;
            }
            return showState() ? TYPE_STATE : TYPE_COMMENT;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            final View view;
            if (viewType == TYPE_POST) {
                view = new NexPostCell(context, getResourceProvider(), NexCommentsActivity.this);
            } else if (viewType == TYPE_COMMENT) {
                view = new CommentCell(context);
            } else {
                view = new StateCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final View view = holder.itemView;
            if (view instanceof NexPostCell) {
                ((NexPostCell) view).setStatus(post);
            } else if (view instanceof CommentCell) {
                ((CommentCell) view).set(comments.get(position - 1));
            } else if (view instanceof StateCell) {
                ((StateCell) view).update();
            }
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            if (holder.itemView instanceof NexPostCell) {
                ((NexPostCell) holder.itemView).releaseVideo();
            }
        }
    }

    // One comment: avatar, name, text
    private class CommentCell extends LinearLayout {
        private final BackupImageView avatarView;
        private final TextView nameView;
        private final TextView textView;
        private PixelfedStatus comment;

        CommentCell(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(14), AndroidUtilities.dp(10));
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(AndroidUtilities.dp(16));
            avatarView.setOnClickListener(v -> {
                if (comment != null) {
                    openProfile(comment.authorId);
                }
            });
            addView(avatarView, LayoutHelper.createLinear(32, 32));
            final LinearLayout bubble = new LinearLayout(context);
            bubble.setOrientation(VERTICAL);
            bubble.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
            bubble.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16), NexLentaColors.CARD));
            nameView = new TextView(context);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setTextColor(NexLentaColors.TEXT);
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            nameView.setOnClickListener(v -> {
                if (comment != null) {
                    openProfile(comment.authorId);
                }
            });
            bubble.addView(nameView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            textView.setLineSpacing(AndroidUtilities.dp(2), 1f);
            textView.setTextColor(NexLentaColors.TEXT);
            bubble.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            addView(bubble, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 0, 0, 0));
        }

        void set(PixelfedStatus comment) {
            this.comment = comment;
            NexPostCell.setAvatar(avatarView, comment.avatarUrl, comment.authorName, "32_32");
            nameView.setText(comment.authorName);
            textView.setText(htmlToText(comment.contentHtml));
        }
    }

    // Loading spinner, "no comments yet", or the load error with "Qayta urinish"
    private class StateCell extends FrameLayout {
        private final RadialProgressView progressView;
        private final TextView textView;

        StateCell(Context context) {
            super(context);
            progressView = new RadialProgressView(context);
            progressView.setSize(AndroidUtilities.dp(24));
            progressView.setProgressColor(NexLentaColors.ACCENT);
            addView(progressView, LayoutHelper.createFrame(32, 32, Gravity.CENTER, 0, 16, 0, 16));
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            textView.setGravity(Gravity.CENTER);
            textView.setTextColor(NexLentaColors.SECONDARY_TEXT);
            textView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(20), AndroidUtilities.dp(16), AndroidUtilities.dp(20));
            textView.setOnClickListener(v -> {
                if (failed) {
                    load();
                }
            });
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        }

        void update() {
            progressView.setVisibility(loading ? View.VISIBLE : View.GONE);
            textView.setVisibility(loading ? View.GONE : View.VISIBLE);
            textView.setText(failed
                ? LocaleController.getString(R.string.NexCommentsLoadError) + "\n" + LocaleController.getString(R.string.NexFeedLoginRetry)
                : LocaleController.getString(R.string.NexCommentsEmpty));
        }
    }
}
