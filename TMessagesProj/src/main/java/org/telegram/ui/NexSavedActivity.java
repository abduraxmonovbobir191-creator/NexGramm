package org.telegram.ui;

import android.content.Context;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

import uz.nexgramm.core.NexSaved;

// NexGramm: messages others deleted or edited, as kept by NexSaved — one chat (from its ⋮ menu) or all
// chats (from NexGramm settings). A row opens the chat (at the message, for an edit).
public class NexSavedActivity extends BaseFragment {

    private final long dialogId; // 0 = all chats
    private ArrayList<NexSaved.Entry> items = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
    private TextView emptyView;

    public NexSavedActivity(long dialogId) {
        this.dialogId = dialogId;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.NexSavedTitle));
        if (dialogId != 0) {
            actionBar.setSubtitle(dialogName(dialogId));
        }
        final ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(1, R.drawable.msg_delete);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    confirmClear();
                }
            }
        });

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final RecyclerView listView = new RecyclerView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setItemAnimator(null);
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                final Row row = new Row(parent.getContext());
                row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(row) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                ((Row) holder.itemView).set(items.get(position));
            }

            @Override
            public int getItemCount() {
                return items.size();
            }
        };
        listView.setAdapter(adapter);
        NexMotion.bounce(listView);
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        root.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = root;
        reload();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null) {
            reload();
        }
    }

    private void reload() {
        items = NexSaved.list(currentAccount, dialogId);
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        emptyView.setText(LocaleController.getString(NexSaved.keepDeleted() || NexSaved.keepEdits()
            ? R.string.NexSavedEmpty : R.string.NexSavedOff));
    }

    private void confirmClear() {
        if (items.isEmpty() || getParentActivity() == null) {
            return;
        }
        final AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
            .setTitle(LocaleController.getString(R.string.NexSavedClearTitle))
            .setMessage(LocaleController.getString(R.string.NexSavedClearText))
            .setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> {
                NexSaved.clear(currentAccount, dialogId);
                reload();
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create();
        showDialog(dialog);
        dialog.redPositive();
    }

    private String dialogName(long id) {
        if (id > 0) {
            final TLRPC.User user = getMessagesController().getUser(id);
            return user == null ? "" : UserObject.getUserName(user);
        }
        final TLRPC.Chat chat = getMessagesController().getChat(-id);
        return chat == null ? "" : chat.title;
    }

    private void open(NexSaved.Entry e) {
        final Bundle args = new Bundle();
        if (e.dialogId > 0) {
            args.putLong("user_id", e.dialogId);
        } else {
            args.putLong("chat_id", -e.dialogId);
        }
        if (e.type == NexSaved.EDITED) {
            args.putInt("message_id", e.messageId);
        }
        presentFragment(new ChatActivity(args));
    }

    // photo, "Sender · chat", "🗑 deleted · time", the text (an edit: the old text struck through, then the new)
    private class Row extends FrameLayout {
        private final BackupImageView avatarView;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView titleView;
        private final TextView metaView;
        private final TextView textView;
        private NexSaved.Entry entry;

        Row(Context context) {
            super(context);
            setBackground(Theme.getSelectorDrawable(false));
            setOnClickListener(v -> {
                if (entry != null) {
                    open(entry);
                }
            });
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(AndroidUtilities.dp(21));
            addView(avatarView, LayoutHelper.createFrame(42, 42, Gravity.LEFT | Gravity.TOP, 16, 12, 0, 0));
            final LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            column.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            metaView = new TextView(context);
            metaView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            metaView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            metaView.setSingleLine(true);
            column.addView(metaView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setLineSpacing(AndroidUtilities.dp(2), 1f);
            textView.setMaxLines(8);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            column.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
            addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 72, 12, 16, 12));
        }

        void set(NexSaved.Entry e) {
            entry = e;
            Object who = null;
            if (e.fromId > 0) {
                final TLRPC.User user = getMessagesController().getUser(e.fromId);
                if (user != null) {
                    avatarDrawable.setInfo(currentAccount, user);
                    avatarView.setForUserOrChat(user, avatarDrawable);
                    who = user;
                }
            } else if (e.fromId < 0) {
                final TLRPC.Chat chat = getMessagesController().getChat(-e.fromId);
                if (chat != null) {
                    avatarDrawable.setInfo(currentAccount, chat);
                    avatarView.setForUserOrChat(chat, avatarDrawable);
                    who = chat;
                }
            }
            if (who == null) {
                avatarView.setImageDrawable(null);
            }
            final String sender = dialogName(e.fromId);
            final String chatName = e.fromId == e.dialogId || dialogId != 0 ? "" : dialogName(e.dialogId);
            titleView.setText(chatName.isEmpty() ? sender : sender + " · " + chatName);
            final String when = LocaleController.formatDateChat(e.savedAt / 1000) + " " + LocaleController.getInstance().getFormatterDay().format(e.savedAt);
            metaView.setText((e.type == NexSaved.DELETED ? LocaleController.getString(R.string.NexSavedDeleted)
                : LocaleController.getString(R.string.NexSavedEdited)) + " · " + when);
            if (e.type == NexSaved.EDITED) {
                final SpannableStringBuilder b = new SpannableStringBuilder(e.text);
                b.setSpan(new StrikethroughSpan(), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.setSpan(new ForegroundColorSpan(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText)), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.append("\n").append(e.newText == null ? "" : e.newText);
                textView.setText(b);
            } else {
                textView.setText(e.text);
            }
            textView.setPaintFlags(textView.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
        }
    }
}
