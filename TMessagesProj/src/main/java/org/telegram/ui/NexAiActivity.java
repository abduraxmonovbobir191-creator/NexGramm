package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

import uz.nexgramm.core.NexAi;
import uz.nexgramm.core.NexAiPrompts;

// NexGramm: the NEX AI main tab (design "Bugun nima qilamiz?"): orb, greeting, Chat / Imagine / Build,
// quick actions, then a conversation with streamed answers. Earlier turns of the conversation are sent
// again with each question (the backend answers one message at a time). Voice input uses the system
// speech recognizer. Nothing is stored: a new conversation ("+") forgets the old one.
public class NexAiActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {

    private static final int MODE_CHAT = 0;
    private static final int MODE_IMAGINE = 1;
    private static final int MODE_BUILD = 2;
    private static final int VOICE_REQUEST = 7201;

    private final ArrayList<String[]> turns = new ArrayList<>();
    private String threadId = NexAi.newThreadId();
    private int mode = MODE_CHAT;
    private NexAi.Call call;

    private FrameLayout root;
    private View heroView;
    private ScrollView chatScroll;
    private LinearLayout chatList;
    private NexMotion.Tabs modesView;
    private LinearLayout quickRow;
    private View orbView;
    private EditText inputView;
    private ImageView sendButton;

    public static NexAiActivity create() {
        final Bundle args = new Bundle();
        args.putBoolean("hasMainTabs", true);
        return new NexAiActivity(args);
    }

    public NexAiActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean isLightStatusBar() {
        return !NexLentaColors.dark;
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }

    @Override
    public View createView(Context context) {
        NexLentaColors.apply();
        actionBar.setAddToContainer(false);
        root = new FrameLayout(context);
        root.setBackgroundColor(NexLentaColors.BACKGROUND);

        // top: menu (new chat), NEX title, +
        final FrameLayout top = new FrameLayout(context);
        top.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        final ImageView left = NexLentaUi.circleButton(context, R.drawable.msg_settings, NexLentaUi.PILL_CARD, false);
        left.setOnClickListener(v -> presentFragment(new NexAppearanceActivity()));
        top.addView(left, LayoutHelper.createFrame(46, 46, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 8, 0, 8));
        final LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = NexLentaUi.serifTitle(context, 34);
        title.setText("NEX");
        titleRow.addView(title);
        top.addView(titleRow, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        final ImageView plus = NexLentaUi.circleButton(context, R.drawable.msg_add, NexLentaUi.PILL_CARD, false);
        plus.setOnClickListener(v -> newConversation());
        top.addView(plus, LayoutHelper.createFrame(46, 46, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 8, 16, 8));

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final FrameLayout middle = new FrameLayout(context);
        heroView = createHero(context);
        middle.addView(heroView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        chatScroll = new ScrollView(context);
        chatScroll.setVisibility(View.GONE);
        chatList = new LinearLayout(context);
        chatList.setOrientation(LinearLayout.VERTICAL);
        chatList.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        chatScroll.addView(chatList);
        middle.addView(chatScroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        column.addView(middle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        column.addView(createInput(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 0));
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // above the floating tab bar; above the keyboard when it is open
        ViewCompat.setOnApplyWindowInsetsListener(column, (v, insets) -> {
            final Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            final int bottom = ime.bottom > 0 ? ime.bottom + AndroidUtilities.dp(8) : bars.bottom + AndroidUtilities.dp(92);
            v.setPadding(0, 0, 0, bottom);
            return insets;
        });
        fragmentView = root;
        return fragmentView;
    }

    private View createHero(Context context) {
        final ScrollView scroll = new ScrollView(context);
        final LinearLayout hero = new LinearLayout(context);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));

        final View orb = new View(context);
        orb.setBackground(new NexAiUi.OrbDrawable(true));
        orb.setOnClickListener(v -> {
            NexMotion.pulse(orb);
            focusInput(null);
        });
        orbView = orb;
        hero.addView(orb, LayoutHelper.createLinear(190, 190, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 18));

        final TextView hello = new TextView(context);
        hello.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        hello.setTextColor(NexLentaColors.SECONDARY_TEXT);
        hello.setGravity(Gravity.CENTER);
        final TLRPC.User user = getUserConfig().getCurrentUser();
        hello.setText(LocaleController.formatString(R.string.NexAiHello, user != null ? UserObject.getFirstName(user) : ""));
        hero.addView(hello, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView question = NexLentaUi.serifTitle(context, 46);
        question.setGravity(Gravity.CENTER);
        question.setText(LocaleController.getString(R.string.NexAiWhatToday));
        hero.addView(question, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        final TextView italic = NexLentaUi.serifTitle(context, 46);
        italic.setTypeface(Typeface.create(NexLentaUi.serif(), Typeface.ITALIC));
        italic.setGravity(Gravity.CENTER);
        italic.setText(LocaleController.getString(R.string.NexAiWhatTodayItalic));
        hero.addView(italic, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Chat / Imagine / Build: one accent pill slides between them
        modesView = new NexMotion.Tabs(context, NexMotion.Tabs.PILL, NexLentaColors.ACCENT);
        modesView.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 26));
        modesView.setPadding(AndroidUtilities.dp(5), AndroidUtilities.dp(5), AndroidUtilities.dp(5), AndroidUtilities.dp(5));
        final int[] names = {R.string.NexAiModeChat, R.string.NexAiModeImagine, R.string.NexAiModeBuild};
        for (int i = 0; i < names.length; i++) {
            final int m = i;
            final TextView option = new TextView(context);
            option.setGravity(Gravity.CENTER);
            option.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            option.setTypeface(AndroidUtilities.bold());
            option.setText(LocaleController.getString(names[i]));
            option.setOnClickListener(v -> setMode(m));
            modesView.addView(option, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        }
        hero.addView(modesView, LayoutHelper.createLinear(300, 52, Gravity.CENTER_HORIZONTAL, 0, 24, 0, 0));

        quickRow = new LinearLayout(context);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        hero.addView(quickRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 0, 0));
        applyMode(false);

        for (int i = 0; i < hero.getChildCount(); i++) {
            NexMotion.enter(hero.getChildAt(i), i);
        }
        scroll.addView(hero);
        return scroll;
    }

    private void setMode(int m) {
        if (m == mode) {
            NexMotion.pulse(modesView.getChildAt(m));
            return;
        }
        mode = m;
        applyMode(true);
        if (orbView != null) {
            NexMotion.pulse(orbView);
        }
    }

    // pill, label colors, the input's hint and the quick actions of the mode
    private void applyMode(boolean animated) {
        modesView.select(mode, animated);
        for (int i = 0; i < modesView.getChildCount(); i++) {
            ((TextView) modesView.getChildAt(i)).setTextColor(i == mode ? NexLentaColors.ON_ACCENT : NexLentaColors.TEXT);
        }
        if (inputView != null) {
            inputView.setHint(modeHint());
        }
        final Context context = getContext();
        quickRow.removeAllViews();
        if (mode == MODE_IMAGINE) {
            addQuick(context, quickRow, R.drawable.msg_photos, R.string.NexAiQuickPortrait, () -> focusInput(R.string.NexAiPromptPortrait));
            addQuick(context, quickRow, R.drawable.msg_gallery, R.string.NexAiQuickLandscape, () -> focusInput(R.string.NexAiPromptLandscape));
            addQuick(context, quickRow, R.drawable.msg_palette, R.string.NexAiQuickLogo, () -> focusInput(R.string.NexAiPromptLogo));
        } else if (mode == MODE_BUILD) {
            addQuick(context, quickRow, R.drawable.msg_edit, R.string.NexAiQuickCode, () -> focusInput(R.string.NexAiPromptCode));
            addQuick(context, quickRow, R.drawable.msg_report, R.string.NexAiQuickBug, () -> focusInput(R.string.NexAiPromptBug));
            addQuick(context, quickRow, R.drawable.msg_help, R.string.NexAiQuickExplain, () -> focusInput(R.string.NexAiPromptExplain));
        } else {
            addQuick(context, quickRow, R.drawable.msg_list, R.string.NexAiQuickSummary, this::pickChatToSummarize);
            addQuick(context, quickRow, R.drawable.input_mic, R.string.NexAiQuickVoice, this::startVoice);
            addQuick(context, quickRow, R.drawable.msg_edit, R.string.NexAiQuickCaption, () -> focusInput(R.string.NexAiCaptionPrompt));
        }
        if (animated) {
            for (int i = 0; i < quickRow.getChildCount(); i++) {
                NexMotion.enter(quickRow.getChildAt(i), i);
            }
        }
    }

    private String modeHint() {
        return LocaleController.getString(mode == MODE_IMAGINE ? R.string.NexAiHintImagine
            : mode == MODE_BUILD ? R.string.NexAiHintBuild : R.string.NexAiInputHint);
    }

    // the input with a starting text (or empty), cursor at the end, keyboard up
    private void focusInput(Integer startRes) {
        if (inputView == null) {
            return;
        }
        if (startRes != null) {
            String start = LocaleController.getString(startRes);
            if (!start.endsWith(" ") && !start.endsWith("\n")) {
                start += " ";
            }
            inputView.setText(start);
        }
        inputView.setSelection(inputView.length());
        inputView.requestFocus();
        AndroidUtilities.showKeyboard(inputView);
    }

    // "Summary": pick a chat, it opens and NEX's ✦ sheet sums up its recent messages
    private void pickChatToSummarize() {
        final Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_DEFAULT);
        final DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty()) {
                return false;
            }
            final long did = dids.get(0).dialogId;
            final Bundle chatArgs = new Bundle();
            if (DialogObject.isEncryptedDialog(did)) {
                chatArgs.putInt("enc_id", DialogObject.getEncryptedChatId(did));
            } else if (DialogObject.isUserDialog(did)) {
                chatArgs.putLong("user_id", did);
            } else {
                chatArgs.putLong("chat_id", -did);
            }
            chatArgs.putBoolean("nex_open_ai", true);
            fragment.presentFragment(new ChatActivity(chatArgs), true);
            return true;
        });
        presentFragment(picker);
        toast(R.string.NexAiPickChat);
    }

    private void addQuick(Context context, LinearLayout row, int icon, int label, Runnable action) {
        final LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        final ImageView circle = NexLentaUi.circleButton(context, icon, NexLentaUi.PILL_CARD, false);
        circle.setOnClickListener(v -> {
            NexMotion.pulse(circle);
            action.run();
        });
        item.addView(circle, LayoutHelper.createLinear(64, 64));
        final TextView text = new TextView(context);
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        text.setTextColor(NexLentaColors.TEXT);
        text.setGravity(Gravity.CENTER);
        text.setText(LocaleController.getString(label));
        item.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        row.addView(item, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
    }

    private View createInput(Context context) {
        final LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 30));
        pill.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(6), AndroidUtilities.dp(6), AndroidUtilities.dp(6));
        inputView = new EditText(context);
        inputView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        inputView.setTextColor(NexLentaColors.TEXT);
        inputView.setHintTextColor(NexLentaColors.SECONDARY_TEXT);
        inputView.setBackground(null);
        inputView.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        inputView.setMaxLines(5);
        inputView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        pill.addView(inputView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        final ImageView mic = new ImageView(context);
        mic.setScaleType(ImageView.ScaleType.CENTER);
        mic.setImageResource(R.drawable.input_mic);
        mic.setColorFilter(new android.graphics.PorterDuffColorFilter(NexLentaColors.TEXT, android.graphics.PorterDuff.Mode.SRC_IN));
        mic.setOnClickListener(v -> startVoice());
        pill.addView(mic, LayoutHelper.createLinear(44, 48));
        sendButton = NexLentaUi.circleButton(context, R.drawable.msg_arrowright, NexLentaUi.PILL_PRIMARY, false);
        sendButton.setRotation(-90);
        sendButton.setOnClickListener(v -> send());
        pill.addView(sendButton, LayoutHelper.createLinear(48, 48, 4, 0, 0, 0));
        inputView.setHint(modeHint());
        return pill;
    }

    private void newConversation() {
        if (call != null) {
            call.cancel();
            call = null;
            sendButton.setAlpha(1f);
        }
        if (chatScroll.getVisibility() != View.VISIBLE) {
            // nothing to clear: a new question then
            if (orbView != null) {
                NexMotion.pulse(orbView);
            }
            focusInput(null);
            return;
        }
        turns.clear();
        threadId = NexAi.newThreadId();
        chatScroll.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            chatList.removeAllViews();
            chatScroll.setVisibility(View.GONE);
            chatScroll.setAlpha(1f);
            heroView.setVisibility(View.VISIBLE);
            NexMotion.enter(heroView, 0);
        }).start();
    }

    private void send() {
        final String question = inputView.getText().toString().trim();
        if (question.isEmpty()) {
            AndroidUtilities.shakeView(inputView);
            focusInput(null);
            return;
        }
        if (call != null) {
            return;
        }
        if (!NexAi.configured()) {
            // the server is not connected yet: say so right in the conversation (nothing is sent, no free use spent)
            inputView.setText("");
            heroView.setVisibility(View.GONE);
            chatScroll.setVisibility(View.VISIBLE);
            addBubble(question, true);
            addBubble(NexAiUi.errorText(NexAi.ERROR_NOT_CONFIGURED, null), false);
            return;
        }
        NexAiUi.withConsent(this, () -> {
            if (!NexAiUi.takeFreeUse(this)) {
                return; // today's free requests are used up: the Premium screen opened
            }
            inputView.setText("");
            heroView.setVisibility(View.GONE);
            chatScroll.setVisibility(View.VISIBLE);
            addBubble(question, true);
            final TextView answerView = addBubble(LocaleController.getString(R.string.NexAiThinking), false);
            answerView.setAlpha(0.6f);
            final String language = NexAiUi.language();
            final String asked = mode == MODE_IMAGINE ? NexAiPrompts.imagine(question, language)
                : mode == MODE_BUILD ? NexAiPrompts.build(question, language) : question;
            final String prompt = NexAiPrompts.conversation(turns, asked, language);
            final long userId = getUserConfig().getClientUserId();
            sendButton.setAlpha(0.5f);
            NexAiUi.withCoreLogin(currentAccount, userId, () -> ask(userId, question, prompt, answerView), () -> {
                answerView.setAlpha(1f);
                answerView.setText(NexAiUi.errorText(NexAi.ERROR_LOGIN, null));
                sendButton.setAlpha(1f);
            });
        });
    }

    private void ask(long userId, String question, String prompt, TextView answerView) {
        final StringBuilder answer = new StringBuilder();
        call = NexAi.chat(userId, threadId, prompt, true, AndroidUtilities::runOnUIThread, new NexAi.Listener() {
            @Override
            public void onText(String chunk) {
                answer.append(chunk);
                answerView.setAlpha(1f);
                answerView.setText(answer);
                scrollToEnd();
            }

            @Override
            public void onDone() {
                call = null;
                sendButton.setAlpha(1f);
                if (answer.length() == 0) {
                    answerView.setAlpha(1f);
                    answerView.setText(NexAiUi.errorText(NexAi.ERROR_UNAVAILABLE, null));
                    return;
                }
                turns.add(new String[]{LocaleController.getString(R.string.NexAiMe), question});
                turns.add(new String[]{"NEX", answer.toString()});
                final List<String[]> keep = new ArrayList<>(turns.subList(Math.max(0, turns.size() - 12), turns.size()));
                turns.clear();
                turns.addAll(keep);
            }

            @Override
            public void onError(int code, String message) {
                call = null;
                sendButton.setAlpha(1f);
                answerView.setAlpha(1f);
                answerView.setText(NexAiUi.errorText(code, message));
            }
        });
    }

    private TextView addBubble(String text, boolean mine) {
        final Context context = getContext();
        final TextView bubble = new TextView(context);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        bubble.setLineSpacing(AndroidUtilities.dp(3), 1f);
        bubble.setTextIsSelectable(true);
        bubble.setText(text);
        if (mine) {
            bubble.setTextColor(NexLentaColors.ON_ACCENT);
            bubble.setBackground(NexLentaUi.roundRect(NexLentaColors.ACCENT, 20));
            bubble.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(14), AndroidUtilities.dp(10));
            chatList.addView(bubble, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.END, 48, 6, 0, 6));
        } else {
            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            final View orb = new View(context);
            orb.setBackground(new NexAiUi.OrbDrawable(true));
            row.addView(orb, LayoutHelper.createLinear(28, 28, 0, 4, 10, 0));
            bubble.setTextColor(NexLentaColors.TEXT);
            row.addView(bubble, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            chatList.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 24, 8));
        }
        NexMotion.enter(mine ? bubble : (View) bubble.getParent(), 0);
        scrollToEnd();
        return bubble;
    }

    private void scrollToEnd() {
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void startVoice() {
        final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, LocaleController.getInstance().getCurrentLocale().toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, LocaleController.getString(R.string.NexAiVoicePrompt));
        try {
            startActivityForResult(intent, VOICE_REQUEST);
        } catch (Exception e) {
            toast(R.string.NexAiVoiceMissing);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != VOICE_REQUEST || resultCode != Activity.RESULT_OK || data == null) {
            return;
        }
        final ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (results != null && !results.isEmpty() && !TextUtils.isEmpty(results.get(0)) && inputView != null) {
            inputView.setText(results.get(0));
            inputView.setSelection(inputView.length());
        }
    }

    private void toast(int textRes) {
        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), LocaleController.getString(textRes), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (call != null) {
            call.cancel();
            call = null;
        }
    }
}
