package org.telegram.ui;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.PixelfedAccount;
import uz.nexgramm.core.PixelfedClient;

// NexGramm: edit the own Lenta profile — name, bio, link (POST /api/v1/accounts/update_credentials)
public class NexProfileEditActivity extends BaseFragment {

    public interface Callback {
        void onSaved(PixelfedAccount account);
    }

    private final PixelfedAccount account;
    private final Callback callback;
    private EditText nameField;
    private EditText bioField;
    private EditText websiteField;
    private TextView saveButton;
    private boolean saving;

    public NexProfileEditActivity(PixelfedAccount account, Callback callback) {
        this.account = account;
        this.callback = callback;
    }

    @Override
    public boolean isLightStatusBar() {
        return !NexLentaColors.dark;
    }

    @Override
    public View createView(Context context) {
        NexLentaUi.setupActionBar(this, actionBar, LocaleController.getString(R.string.NexProfileEditTitle));
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(NexLentaColors.BACKGROUND);

        final ScrollView scrollView = new ScrollView(context);
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(100));
        scrollView.addView(content);
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        nameField = field(context, content, R.string.NexProfileEditName, account.displayName, 30, false);
        bioField = field(context, content, R.string.NexProfileEditBio, NexCommentsActivity.htmlToText(account.noteHtml), 200, true);
        websiteField = field(context, content, R.string.NexProfileEditWebsite, account.website == null ? "" : account.website, 120, false);
        websiteField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        websiteField.setHint("https://");

        saveButton = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexProfileEditSave), NexLentaUi.PILL_PRIMARY);
        saveButton.setOnClickListener(v -> save());
        root.addView(saveButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 54, Gravity.BOTTOM, 16, 0, 16, 16));
        NexLentaUi.padForKeyboard(root);

        fragmentView = root;
        return fragmentView;
    }

    private EditText field(Context context, LinearLayout content, int labelRes, String value, int max, boolean multiLine) {
        final TextView label = new TextView(context);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        label.setTypeface(AndroidUtilities.bold());
        label.setTextColor(NexLentaColors.SECONDARY_TEXT);
        label.setText(LocaleController.getString(labelRes));
        content.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 6, 16, 6, 8));

        final EditText edit = new EditText(context);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        edit.setTextColor(NexLentaColors.TEXT);
        edit.setHintTextColor(NexLentaColors.SECONDARY_TEXT);
        edit.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 18));
        edit.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(16), AndroidUtilities.dp(14));
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        if (multiLine) {
            edit.setMinLines(3);
            edit.setGravity(Gravity.TOP | Gravity.START);
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        } else {
            edit.setSingleLine(true);
        }
        edit.setText(value);
        content.addView(edit, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return edit;
    }

    private void save() {
        if (saving) {
            return;
        }
        final String name = nameField.getText().toString().trim();
        if (name.isEmpty()) {
            AndroidUtilities.shakeViewSpring(nameField);
            return;
        }
        saving = true;
        saveButton.setAlpha(0.6f);
        final long userId = getUserConfig().getClientUserId();
        final String bio = bioField.getText().toString();
        final String website = websiteField.getText().toString();
        CoreClient.EXECUTOR.execute(() -> {
            PixelfedAccount result = null;
            try {
                result = PixelfedClient.updateProfileBlocking(userId, name, bio, website);
            } catch (Exception ignore) {
            }
            final PixelfedAccount updated = result;
            AndroidUtilities.runOnUIThread(() -> {
                saving = false;
                if (fragmentView == null) {
                    return;
                }
                saveButton.setAlpha(1f);
                if (updated == null) {
                    if (getParentActivity() != null) {
                        Toast.makeText(getParentActivity(), LocaleController.getString(R.string.NexActionFailed), Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                callback.onSaved(updated);
                finishFragment();
            });
        });
    }
}
