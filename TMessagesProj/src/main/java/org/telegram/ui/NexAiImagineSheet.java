package org.telegram.ui;

import android.app.Activity;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.ChatActivityEnterView;

import uz.nexgramm.core.NexAi;
import uz.nexgramm.core.NexAiPrompts;

// NexGramm: "NEX" in the attach menu of a chat: describe a picture, NEX imagines it (a vivid description and a
// ready image prompt) and the text can go into the message field. Pictures themselves come with the server.
public final class NexAiImagineSheet {

    private NexAiImagineSheet() {
    }

    public static void show(ChatActivity chat) {
        final Activity activity = chat.getParentActivity();
        if (activity == null) {
            return;
        }
        final EditText input = new EditText(activity);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setHint(LocaleController.getString(R.string.NexAiHintImagine));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(4);
        input.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(8), AndroidUtilities.dp(22), AndroidUtilities.dp(8));
        final AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(LocaleController.getString(R.string.NexAiImagineTitle))
            .setView(input)
            .setPositiveButton(LocaleController.getString(R.string.NexAiImagineCreate), (d, w) -> {
                final String idea = input.getText().toString().trim();
                if (!idea.isEmpty()) {
                    imagine(chat, idea);
                }
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .create();
        dialog.show();
        input.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(input), 200);
    }

    private static void imagine(ChatActivity chat, String idea) {
        final Activity activity = chat.getParentActivity();
        if (activity == null) {
            return;
        }
        if (!NexAi.configured()) {
            new AlertDialog.Builder(activity)
                .setTitle("NEX AI")
                .setMessage(LocaleController.getString(R.string.NexAiImagineNotYet))
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .show();
            return;
        }
        NexAiUi.withConsent(chat, () -> {
            if (!NexAiUi.takeFreeUse(chat)) {
                return;
            }
            final StringBuilder answer = new StringBuilder();
            final AlertDialog result = new AlertDialog.Builder(activity)
                .setTitle(LocaleController.getString(R.string.NexAiImagineTitle))
                .setMessage(LocaleController.getString(R.string.NexAiThinking))
                .setPositiveButton(LocaleController.getString(R.string.NexAiImagineInsert), (d, w) -> {
                    final ChatActivityEnterView field = chat.getChatActivityEnterView();
                    if (field != null && answer.length() > 0) {
                        field.setFieldText(answer.toString().trim());
                    } else {
                        Toast.makeText(activity, LocaleController.getString(R.string.NexActionFailed), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Close), null)
                .create();
            result.show();
            final long userId = chat.getUserConfig().getClientUserId();
            final String prompt = NexAiPrompts.imagine(idea, NexAiUi.language());
            NexAiUi.withCoreLogin(chat.getCurrentAccount(), userId, () -> NexAi.chat(userId, NexAi.newThreadId(), prompt, true,
                AndroidUtilities::runOnUIThread, new NexAi.Listener() {
                    @Override
                    public void onText(String chunk) {
                        answer.append(chunk);
                        result.setMessage(answer);
                    }

                    @Override
                    public void onDone() {
                        if (answer.length() == 0) {
                            result.setMessage(NexAiUi.errorText(NexAi.ERROR_UNAVAILABLE, null));
                        }
                    }

                    @Override
                    public void onError(int code, String message) {
                        result.setMessage(NexAiUi.errorText(code, message));
                    }
                }), () -> result.setMessage(NexAiUi.errorText(NexAi.ERROR_LOGIN, null)));
        });
    }
}
