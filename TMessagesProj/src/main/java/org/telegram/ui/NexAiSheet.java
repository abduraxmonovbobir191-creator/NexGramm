package org.telegram.ui;

import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Components.LayoutHelper;

import java.util.List;

import uz.nexgramm.core.NexAi;
import uz.nexgramm.core.NexAiPrompts;

// NexGramm: NEX AI inside a chat. For one message: explain, translate, write a reply, or ask anything about
// it. For the chat: summary of the recent messages, three reply suggestions, rewrite of the draft (better,
// formal, friendly, shorter) or a question about the chat. The answer streams in; "Qo'yish" puts it into
// the message field. Only the text shown in the sheet is sent, and only after the user taps.
public class NexAiSheet extends BottomSheet {

    public interface Inserter {
        void insert(String text);
    }

    private final BaseFragment fragment;
    private final Inserter inserter;
    private final boolean forMessage;
    private final String messageText;
    private final String chatTitle;
    private final List<String> lines;
    private final String draft;

    private TextView answerView;
    private TextView statusView;
    private LinearLayout suggestionsView;
    private LinearLayout actionsRow;
    private TextView insertButton;
    private EditText inputView;
    private NexAi.Call call;
    private final StringBuilder answer = new StringBuilder();

    private NexAiSheet(BaseFragment fragment, boolean forMessage, String messageText, String chatTitle, List<String> lines, String draft, Inserter inserter) {
        super(fragment.getParentActivity(), true);
        this.fragment = fragment;
        this.forMessage = forMessage;
        this.messageText = messageText;
        this.chatTitle = chatTitle;
        this.lines = lines;
        this.draft = draft;
        this.inserter = inserter;
        NexLentaColors.apply();
        setCustomView(createView(fragment.getParentActivity()));
        setBackgroundColor(NexLentaColors.BACKGROUND);
    }

    // NEX about one message (from the message menu)
    static void forMessage(BaseFragment fragment, CharSequence text, Inserter inserter) {
        if (fragment.getParentActivity() == null || TextUtils.isEmpty(text)) {
            return;
        }
        fragment.showDialog(new NexAiSheet(fragment, true, text.toString(), null, null, null, inserter));
    }

    // NEX for the whole chat (✦ in the chat header): lines = recent messages "Name: text", oldest first
    static void forChat(BaseFragment fragment, String title, List<String> lines, String draft, Inserter inserter) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        fragment.showDialog(new NexAiSheet(fragment, false, null, title, lines, draft, inserter));
    }

    private View createView(Context context) {
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(12));

        // header: orb, title, close
        final LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final View orb = new View(context);
        orb.setBackground(new NexAiUi.OrbDrawable(true));
        header.addView(orb, LayoutHelper.createLinear(36, 36));
        final TextView title = NexLentaUi.serifTitle(context, 28);
        title.setText(LocaleController.getString(forMessage ? R.string.NexAiAskMenu : R.string.NexAiChatTitle));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 8, 0));
        final ImageView close = NexLentaUi.circleButton(context, R.drawable.ic_close_white, NexLentaUi.PILL_CARD, false);
        close.setOnClickListener(v -> dismiss());
        header.addView(close, LayoutHelper.createLinear(40, 40));
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 12));

        // what is sent: the message, or the chat name and how many messages
        final TextView context1 = new TextView(context);
        context1.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        context1.setTextColor(NexLentaColors.SECONDARY_TEXT);
        context1.setMaxLines(forMessage ? 3 : 1);
        context1.setEllipsize(TextUtils.TruncateAt.END);
        context1.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 16));
        context1.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(14), AndroidUtilities.dp(10));
        context1.setText(forMessage ? messageText
            : LocaleController.formatString(R.string.NexAiChatContext, chatTitle, lines == null ? 0 : lines.size()));
        root.addView(context1, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // quick actions
        final HorizontalScrollView chipsScroll = new HorizontalScrollView(context);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chipsScroll.addView(chips);
        if (forMessage) {
            addChip(chips, R.string.NexAiExplain, () -> run(NexAiPrompts.explain(messageText, NexAiUi.language()), false, false));
            addChip(chips, R.string.NexAiTranslate, () -> run(NexAiPrompts.translate(messageText, NexAiUi.language()), false, true));
            addChip(chips, R.string.NexAiReply, () -> run(NexAiPrompts.replyTo(messageText, NexAiUi.language()), false, true));
        } else {
            addChip(chips, R.string.NexAiSummary, () -> run(NexAiPrompts.summary(chatTitle, lines, NexAiUi.language()), false, false));
            addChip(chips, R.string.NexAiSuggest, () -> run(NexAiPrompts.suggestReplies(lines, NexAiUi.language()), true, false));
            if (!TextUtils.isEmpty(draft)) {
                addChip(chips, R.string.NexAiImprove, () -> run(NexAiPrompts.rewrite(draft, NexAiPrompts.STYLE_BETTER, NexAiUi.language()), false, true));
                addChip(chips, R.string.NexAiFormal, () -> run(NexAiPrompts.rewrite(draft, NexAiPrompts.STYLE_FORMAL, NexAiUi.language()), false, true));
                addChip(chips, R.string.NexAiFriendly, () -> run(NexAiPrompts.rewrite(draft, NexAiPrompts.STYLE_FRIENDLY, NexAiUi.language()), false, true));
                addChip(chips, R.string.NexAiShorter, () -> run(NexAiPrompts.rewrite(draft, NexAiPrompts.STYLE_SHORTER, NexAiUi.language()), false, true));
            }
        }
        root.addView(chipsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 12, 0, 0));

        // answer
        final ScrollView answerScroll = new ScrollView(context);
        final LinearLayout answerBox = new LinearLayout(context);
        answerBox.setOrientation(LinearLayout.VERTICAL);
        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        statusView.setText(LocaleController.getString(R.string.NexAiPick));
        answerBox.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 14, 2, 0));
        answerView = new TextView(context);
        answerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        answerView.setLineSpacing(AndroidUtilities.dp(3), 1f);
        answerView.setTextColor(NexLentaColors.TEXT);
        answerView.setTextIsSelectable(true);
        answerView.setVisibility(View.GONE);
        answerBox.addView(answerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 14, 2, 0));
        suggestionsView = new LinearLayout(context);
        suggestionsView.setOrientation(LinearLayout.VERTICAL);
        answerBox.addView(suggestionsView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        answerScroll.addView(answerBox);
        root.addView(answerScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        // copy / insert
        actionsRow = new LinearLayout(context);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setVisibility(View.GONE);
        final TextView copy = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexAiCopy), NexLentaUi.PILL_CARD);
        copy.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        copy.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(answer.toString().trim());
            Toast.makeText(getContext(), LocaleController.getString(R.string.NexMeCopied), Toast.LENGTH_SHORT).show();
        });
        actionsRow.addView(copy, LayoutHelper.createLinear(0, 44, 1f));
        insertButton = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexAiInsert), NexLentaUi.PILL_PRIMARY);
        insertButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        insertButton.setOnClickListener(v -> {
            if (inserter != null) {
                inserter.insert(answer.toString().trim());
            }
            dismiss();
        });
        actionsRow.addView(insertButton, LayoutHelper.createLinear(0, 44, 1f, 10, 0, 0, 0));
        root.addView(actionsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 10, 0, 0));

        // free question
        final LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 26));
        inputRow.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(5), 0);
        inputView = new EditText(context);
        inputView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        inputView.setTextColor(NexLentaColors.TEXT);
        inputView.setHintTextColor(NexLentaColors.SECONDARY_TEXT);
        inputView.setHint(LocaleController.getString(forMessage ? R.string.NexAiAskMessageHint : R.string.NexAiAskChatHint));
        inputView.setBackground(null);
        inputView.setMaxLines(4);
        inputView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        inputView.setImeOptions(EditorInfo.IME_ACTION_SEND);
        inputRow.addView(inputView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        final ImageView send = NexLentaUi.circleButton(context, R.drawable.nex_ic_sparkle, NexLentaUi.PILL_PRIMARY, false);
        send.setOnClickListener(v -> sendQuestion());
        inputRow.addView(send, LayoutHelper.createLinear(42, 42));
        root.addView(inputRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 0, 12, 0, 0));

        final FrameLayout frame = new FrameLayout(context);
        frame.addView(root, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, (int) (AndroidUtilities.displaySize.y / AndroidUtilities.density * 0.62f)));
        return frame;
    }

    private void addChip(LinearLayout chips, int textRes, Runnable action) {
        final TextView chip = NexLentaUi.pillButton(chips.getContext(), "✦ " + LocaleController.getString(textRes), NexLentaUi.PILL_CARD);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        chip.setOnClickListener(v -> action.run());
        chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 40, 0, 0, 8, 0));
    }

    private void sendQuestion() {
        final String question = inputView.getText().toString().trim();
        if (question.isEmpty()) {
            return;
        }
        inputView.setText("");
        AndroidUtilities.hideKeyboard(inputView);
        final String prompt = forMessage
            ? NexAiPrompts.ask(messageText, question, NexAiUi.language())
            : NexAiPrompts.ask(NexAiPrompts.summary(chatTitle, lines, NexAiUi.language()), question, NexAiUi.language());
        run(prompt, false, false);
    }

    // suggestions = the answer is three replies shown as tappable pills; insertable = "Qo'yish" is offered
    private void run(String prompt, boolean suggestions, boolean insertable) {
        NexAiUi.withConsent(fragment, () -> {
            if (!NexAiUi.takeFreeUse(fragment)) {
                dismiss(); // the Premium screen opened instead
                return;
            }
            if (call != null) {
                call.cancel();
            }
            answer.setLength(0);
            answerView.setText("");
            answerView.setVisibility(View.GONE);
            suggestionsView.removeAllViews();
            actionsRow.setVisibility(View.GONE);
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(LocaleController.getString(R.string.NexAiThinking));
            final int account = fragment.getCurrentAccount();
            final long userId = fragment.getUserConfig().getClientUserId();
            NexAiUi.withCoreLogin(account, userId, () -> start(userId, prompt, suggestions, insertable), () -> {
                statusView.setText(NexAiUi.errorText(NexAi.ERROR_LOGIN, null));
            });
        });
    }

    private void start(long userId, String prompt, boolean suggestions, boolean insertable) {
        call = NexAi.chat(userId, NexAi.newThreadId(), prompt, true, AndroidUtilities::runOnUIThread, new NexAi.Listener() {
            @Override
            public void onText(String chunk) {
                answer.append(chunk);
                if (!suggestions) {
                    statusView.setVisibility(View.GONE);
                    answerView.setVisibility(View.VISIBLE);
                    answerView.setText(answer);
                }
            }

            @Override
            public void onDone() {
                if (suggestions) {
                    showSuggestions(NexAiPrompts.parseReplies(answer.toString()));
                    return;
                }
                statusView.setVisibility(answer.length() == 0 ? View.VISIBLE : View.GONE);
                if (answer.length() == 0) {
                    statusView.setText(NexAiUi.errorText(NexAi.ERROR_UNAVAILABLE, null));
                    return;
                }
                actionsRow.setVisibility(View.VISIBLE);
                insertButton.setVisibility(insertable && inserter != null ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onError(int code, String message) {
                statusView.setVisibility(View.VISIBLE);
                statusView.setText(NexAiUi.errorText(code, message));
            }
        });
    }

    private void showSuggestions(List<String> replies) {
        if (replies.isEmpty()) {
            statusView.setText(NexAiUi.errorText(NexAi.ERROR_UNAVAILABLE, null));
            return;
        }
        statusView.setText(LocaleController.getString(R.string.NexAiSuggestHint));
        for (String reply : replies) {
            final TextView pill = NexLentaUi.pillButton(getContext(), "✦  " + reply, NexLentaUi.PILL_CARD);
            pill.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            pill.setSingleLine(false);
            pill.setMaxLines(3);
            pill.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
            pill.setOnClickListener(v -> {
                if (inserter != null) {
                    inserter.insert(reply);
                }
                dismiss();
            });
            suggestionsView.addView(pill, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }
    }

    @Override
    public void dismiss() {
        if (call != null) {
            call.cancel();
            call = null;
        }
        super.dismiss();
    }
}
