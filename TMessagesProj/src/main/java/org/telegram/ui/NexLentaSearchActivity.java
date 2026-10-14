package org.telegram.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

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
import org.telegram.ui.Components.ScaleStateListAnimator;

import java.util.ArrayList;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.PixelfedAccount;
import uz.nexgramm.core.PixelfedClient;

// NexGramm: find people in the Lenta by their Lenta username (as you type, a short pause first). A row opens
// the Lenta profile.
public class NexLentaSearchActivity extends BaseFragment {

    private EditText input;
    private TextView hintView;
    private final ArrayList<PixelfedAccount> results = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private int generation;
    private final Runnable search = this::searchNow;

    private long tgUserId() {
        return getUserConfig().getClientUserId();
    }

    @Override
    public boolean isLightStatusBar() {
        return !NexLentaColors.dark;
    }

    @Override
    public View createView(Context context) {
        NexLentaColors.apply();
        actionBar.setAddToContainer(false);
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(NexLentaColors.BACKGROUND);
        root.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);

        // back · the search field (a soft pill)
        final LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        final ImageView back = NexLentaUi.circleButton(context, R.drawable.ic_ab_back, NexLentaUi.PILL_CARD, false);
        back.setOnClickListener(v -> finishFragment());
        bar.addView(back, LayoutHelper.createLinear(40, 40));
        input = new EditText(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setTextColor(NexLentaColors.TEXT);
        input.setHintTextColor(NexLentaColors.SECONDARY_TEXT);
        input.setHint(LocaleController.getString(R.string.NexLentaSearchHint));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 20));
        input.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                AndroidUtilities.cancelRunOnUIThread(search);
                AndroidUtilities.runOnUIThread(search, 350);
            }
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            AndroidUtilities.cancelRunOnUIThread(search);
            searchNow();
            return true;
        });
        bar.addView(input, LayoutHelper.createLinear(0, 42, 1f, 10, 0, 0, 0));
        root.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final FrameLayout body = new FrameLayout(context);
        final RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setItemAnimator(null);
        list.setAdapter(adapter);
        NexMotion.bounce(list);
        body.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        hintView = new TextView(context);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        hintView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        hintView.setGravity(Gravity.CENTER);
        hintView.setText(LocaleController.getString(R.string.NexLentaSearchEmpty));
        body.addView(hintView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 32, 48, 32, 0));
        root.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        fragmentView = root;
        return fragmentView;
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (isOpen && input != null) {
            input.requestFocus();
            AndroidUtilities.showKeyboard(input);
        }
    }

    private void searchNow() {
        if (input == null) {
            return;
        }
        final String query = input.getText().toString().trim();
        final int gen = ++generation;
        if (query.isEmpty() || "@".equals(query)) {
            results.clear();
            adapter.notifyDataSetChanged();
            hintView.setText(LocaleController.getString(R.string.NexLentaSearchEmpty));
            hintView.setVisibility(View.VISIBLE);
            return;
        }
        final long userId = tgUserId();
        CoreClient.EXECUTOR.execute(() -> {
            ArrayList<PixelfedAccount> found = null;
            try {
                found = PixelfedClient.searchAccountsBlocking(userId, query);
            } catch (Exception ignore) {
            }
            final ArrayList<PixelfedAccount> result = found;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || fragmentView == null) {
                    return;
                }
                results.clear();
                if (result != null) {
                    results.addAll(result);
                }
                adapter.notifyDataSetChanged();
                hintView.setText(LocaleController.getString(result == null ? R.string.NexActionFailed : R.string.NexLentaSearchNothing));
                hintView.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemCount() {
            return results.size();
        }

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
            ((Row) holder.itemView).set(results.get(position));
            NexMotion.enter(holder.itemView, Math.min(position, 6));
        }
    }

    // avatar · name over @username
    private class Row extends LinearLayout {
        private final BackupImageView avatar;
        private final TextView name;
        private final TextView username;
        private PixelfedAccount account;

        Row(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
            setBackground(Theme.createSelectorDrawable(Theme.multAlpha(NexLentaColors.TEXT, 0.06f), Theme.RIPPLE_MASK_ALL));
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(AndroidUtilities.dp(24));
            addView(avatar, LayoutHelper.createLinear(48, 48));
            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            name.setTypeface(AndroidUtilities.bold());
            name.setTextColor(NexLentaColors.TEXT);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            username = new TextView(context);
            username.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            username.setTextColor(NexLentaColors.SECONDARY_TEXT);
            username.setSingleLine(true);
            username.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(username, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));
            setOnClickListener(v -> {
                if (account != null) {
                    presentFragment(new NexProfileActivity(account.id));
                }
            });
            ScaleStateListAnimator.apply(this, 0.02f, 1.5f);
        }

        void set(PixelfedAccount a) {
            account = a;
            final String display = TextUtils.isEmpty(a.displayName) ? a.username : a.displayName;
            name.setText(display);
            username.setText("@" + a.username);
            if (a.avatarUrl == null || a.avatarUrl.contains("/avatars/default")) {
                avatar.setImageDrawable(NexLentaUi.initialsAvatar(display, true));
            } else {
                avatar.setImage(a.avatarUrl, "96_96", NexLentaUi.initialsAvatar(display, true));
            }
        }
    }
}
