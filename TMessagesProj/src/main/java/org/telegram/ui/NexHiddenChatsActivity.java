package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

// NexGramm: "Hidden chats" (long press on the NexGramm title in the chats list). The screen opens covered by
// the starry lock until the phone's own unlock (fingerprint / face / screen PIN) succeeds; then the hidden
// chats of this account are listed, newest message first. A tap opens the chat (already unlocked here), a
// long press offers to unhide it.
public class NexHiddenChatsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private final ArrayList<Long> ids = new ArrayList<>();
    private RecyclerListView listView;
    private ListAdapter adapter;
    private TextView emptyView;
    private FrameLayout cover;
    private boolean unlocked;

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        return super.onFragmentCreate();
    }

    @Override
    public boolean isSwipeBackEnabled(android.view.MotionEvent event) {
        return cover == null; // a swipe would show the list under the lock
    }

    @Override
    public void onFragmentDestroy() {
        if (cover != null) {
            NexChatLock.detach(cover);
            cover = null;
        }
        getNotificationCenter().removeObserver(this, NotificationCenter.dialogsNeedReload);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.NexHiddenTitle));
        actionBar.setAddToContainer(false); // inside this screen, so the lock covers the header too
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final FrameLayout content = new FrameLayout(context);
        column.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        emptyView.setText(LocaleController.getString(R.string.NexHiddenEmpty));
        content.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setEmptyView(emptyView);
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < ids.size()) {
                openChat(ids.get(position));
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position >= 0 && position < ids.size()) {
                askUnhide(ids.get(position));
                return true;
            }
            return false;
        });
        content.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        if (!unlocked) {
            // over the whole window from the first frame: the chats list behind never shows
            cover = NexChatLock.createCover(context, LocaleController.getString(R.string.NexHiddenTitle), this::unlock, this::finishFragment);
            if (NexChatLock.attachToWindow(getParentActivity(), cover) == null) {
                cover.setTranslationZ(AndroidUtilities.dp(100));
                root.addView(cover, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            }
        }
        reload();
        fragmentView = root;
        return fragmentView;
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (isOpen && !backward && cover != null) {
            unlock(); // the unlock prompt comes up by itself; "Unlock" brings it back after a cancel
        }
    }

    private void unlock() {
        if (!(getParentActivity() instanceof androidx.fragment.app.FragmentActivity)) {
            return;
        }
        NexChatLock.authenticate((androidx.fragment.app.FragmentActivity) getParentActivity(), () -> {
            unlocked = true;
            if (cover != null) {
                final View c = cover;
                cover = null;
                NexChatLock.reveal(c);
            }
        }, () -> NexChatLock.shake(cover));
    }

    private void openChat(long dialogId) {
        final Bundle args = new Bundle();
        if (org.telegram.messenger.DialogObject.isEncryptedDialog(dialogId)) {
            args.putInt("enc_id", org.telegram.messenger.DialogObject.getEncryptedChatId(dialogId));
        } else if (dialogId > 0) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        args.putBoolean("nex_unlocked", true);
        presentFragment(new ChatActivity(args));
    }

    private void askUnhide(long dialogId) {
        if (getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity())
            .setTitle(LocaleController.getString(R.string.NexHiddenTitle))
            .setMessage(LocaleController.getString(R.string.NexHiddenUnhideAsk))
            .setPositiveButton(LocaleController.getString(R.string.NexHiddenUnhide), (d, w) -> {
                NexChatLock.setHidden(currentAccount, dialogId, false);
                getNotificationCenter().postNotificationName(NotificationCenter.dialogsNeedReload);
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .show();
    }

    // the hidden chats of this account, the one with the newest message first
    private void reload() {
        ids.clear();
        ids.addAll(NexChatLock.hiddenIds(currentAccount));
        java.util.Collections.sort(ids, (a, b) -> Integer.compare(lastDate(b), lastDate(a)));
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private int lastDate(long dialogId) {
        final TLRPC.Dialog dialog = getMessagesController().getDialog(dialogId);
        return dialog != null ? dialog.last_message_date : 0;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogsNeedReload) {
            reload();
        } else if (id == NotificationCenter.updateInterfaces && listView != null) {
            for (int i = 0; i < listView.getChildCount(); i++) {
                final View child = listView.getChildAt(i);
                if (child instanceof DialogCell) {
                    ((DialogCell) child).update((Integer) args[0]);
                }
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == 0;
        }

        @Override
        public int getItemCount() {
            return ids.isEmpty() ? 0 : ids.size() + 1;
        }

        @Override
        public int getItemViewType(int position) {
            return position < ids.size() ? 0 : 1;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final View view;
            if (viewType == 0) {
                view = new DialogCell(null, context, false, false, currentAccount, null);
            } else {
                final TextInfoPrivacyCell hint = new TextInfoPrivacyCell(context);
                hint.setText(LocaleController.getString(R.string.NexHiddenHint));
                view = hint;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() != 0) {
                return;
            }
            final DialogCell cell = (DialogCell) holder.itemView;
            final long dialogId = ids.get(position);
            final TLRPC.Dialog dialog = getMessagesController().getDialog(dialogId);
            cell.useSeparator = position != ids.size() - 1;
            if (dialog != null) {
                cell.setDialog(dialog, DialogsActivity.DIALOGS_TYPE_DEFAULT, 0);
            } else {
                cell.setDialog(dialogId, null, 0, false, false);
            }
        }
    }
}
