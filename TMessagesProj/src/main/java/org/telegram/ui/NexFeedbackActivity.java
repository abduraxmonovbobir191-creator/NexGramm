package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

// NexGramm: suggestion / complaint / support. The text (and an optional photo) goes to the NexGramm bot as a
// normal message starting with #taklif, #shikoyat or #support; the bot passes it to the NexGramm team and
// their answer comes back in the chat with the bot. Support opens that chat after sending.
public class NexFeedbackActivity extends BaseFragment {

    public static final int SUGGESTION = 0;
    public static final int COMPLAINT = 1;
    public static final int SUPPORT = 2;
    private static final int PICK_PHOTO = 7301;

    private final int kind;
    private EditText input;
    private BackupImageView photoView;
    private ImageView removePhoto;
    private TextView sendButton;
    private Uri photo;
    private boolean sending;

    public NexFeedbackActivity(int kind) {
        this.kind = kind;
    }

    private int titleRes() {
        return kind == SUGGESTION ? R.string.NexFeedbackSuggestion : kind == COMPLAINT ? R.string.NexFeedbackComplaint : R.string.NexFeedbackSupport;
    }

    private String tag() {
        return kind == SUGGESTION ? "#taklif" : kind == COMPLAINT ? "#shikoyat" : "#support";
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(titleRes()));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final ScrollView scroll = new NexMotion.BounceScrollView(context);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(24));
        scroll.addView(column, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        final TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        hint.setText(LocaleController.getString(kind == SUGGESTION ? R.string.NexFeedbackSuggestionHint
            : kind == COMPLAINT ? R.string.NexFeedbackComplaintHint : R.string.NexFeedbackSupportHint));
        column.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        input = new EditText(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        input.setHint(LocaleController.getString(R.string.NexFeedbackInputHint));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(5);
        input.setGravity(Gravity.TOP);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        input.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(14), Theme.getColor(Theme.key_windowBackgroundGray)));
        input.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));
        column.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        // optional photo
        final FrameLayout photoFrame = new FrameLayout(context);
        photoView = new BackupImageView(context);
        photoView.setRoundRadius(AndroidUtilities.dp(12));
        photoView.setVisibility(View.GONE);
        photoFrame.addView(photoView, LayoutHelper.createFrame(120, 120, Gravity.LEFT | Gravity.TOP));
        removePhoto = new ImageView(context);
        removePhoto.setImageResource(R.drawable.ic_close_white);
        removePhoto.setScaleType(ImageView.ScaleType.CENTER);
        removePhoto.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(28), 0x99000000));
        removePhoto.setVisibility(View.GONE);
        removePhoto.setOnClickListener(v -> setPhoto(null));
        photoFrame.addView(removePhoto, LayoutHelper.createFrame(28, 28, Gravity.LEFT | Gravity.TOP, 88, 4, 0, 0));
        column.addView(photoFrame, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        final TextView addPhoto = new TextView(context);
        addPhoto.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        addPhoto.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        addPhoto.setText(LocaleController.getString(R.string.NexFeedbackAddPhoto));
        addPhoto.setCompoundDrawablePadding(AndroidUtilities.dp(8));
        addPhoto.setGravity(Gravity.CENTER_VERTICAL);
        addPhoto.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));
        addPhoto.setOnClickListener(v -> pickPhoto());
        org.telegram.ui.Components.ScaleStateListAnimator.apply(addPhoto, 0.05f, 1.6f);
        column.addView(addPhoto, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        sendButton = new TextView(context);
        sendButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        sendButton.setTypeface(AndroidUtilities.bold());
        sendButton.setGravity(Gravity.CENTER);
        sendButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        sendButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14),
            Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        sendButton.setText(LocaleController.getString(R.string.NexFeedbackSend));
        sendButton.setOnClickListener(v -> send());
        org.telegram.ui.Components.ScaleStateListAnimator.apply(sendButton, 0.04f, 1.6f);
        column.addView(sendButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, 0, 20, 0, 0));

        final TextView note = new TextView(context);
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        note.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        note.setText(LocaleController.formatString(R.string.NexFeedbackNote, BuildConfig.NEXGRAMM_BOT_USERNAME));
        column.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        fragmentView = scroll;
        return fragmentView;
    }

    private void pickPhoto() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, PICK_PHOTO);
        } catch (Exception e) {
            toast(R.string.NexComposeNoPicker);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode == PICK_PHOTO && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            setPhoto(data.getData());
        }
    }

    private void setPhoto(Uri uri) {
        photo = uri;
        if (uri == null) {
            photoView.setImageDrawable(null);
            photoView.setVisibility(View.GONE);
            removePhoto.setVisibility(View.GONE);
        } else {
            photoView.setVisibility(View.VISIBLE);
            removePhoto.setVisibility(View.VISIBLE);
            final int size = AndroidUtilities.dp(240);
            uz.nexgramm.core.CoreClient.EXECUTOR.execute(() -> {
                final android.graphics.Bitmap preview = uz.nexgramm.core.PixelfedMedia.thumbnail(uri, false, size);
                AndroidUtilities.runOnUIThread(() -> {
                    if (fragmentView != null && uri.equals(photo)) {
                        photoView.setImageBitmap(preview);
                    }
                });
            });
        }
    }

    private void send() {
        final String text = input.getText().toString().trim();
        if (sending) {
            return;
        }
        if (text.isEmpty() && photo == null) {
            AndroidUtilities.shakeView(input);
            return;
        }
        if (TextUtils.isEmpty(BuildConfig.NEXGRAMM_BOT_USERNAME)) {
            toast(R.string.NexActionFailed);
            return;
        }
        sending = true;
        sendButton.setAlpha(0.5f);
        final String body = tag() + "\n" + text;
        final Uri photoUri = photo;
        getMessagesController().getUserNameResolver().resolve(BuildConfig.NEXGRAMM_BOT_USERNAME, peerId -> {
            final TLRPC.User bot = peerId != null && peerId > 0 ? getMessagesController().getUser(peerId) : null;
            if (bot == null || fragmentView == null) {
                sending = false;
                if (sendButton != null) {
                    sendButton.setAlpha(1f);
                }
                toast(R.string.NexActionFailed);
                return;
            }
            if (photoUri != null) {
                SendMessagesHelper.prepareSendingPhoto(AccountInstance.getInstance(currentAccount), null, photoUri, bot.id, null, null, null,
                    body, null, null, null, 0, null, true, 0, 0, null);
            } else {
                getSendMessagesHelper().sendMessage(SendMessagesHelper.SendMessageParams.of(body, bot.id));
            }
            toast(R.string.NexFeedbackSent);
            if (kind == SUPPORT) {
                final Bundle args = new Bundle();
                args.putLong("user_id", bot.id);
                presentFragment(new ChatActivity(args), true); // the answer comes here
            } else {
                finishFragment();
            }
        });
    }

    private void toast(int res) {
        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), LocaleController.getString(res), Toast.LENGTH_SHORT).show();
        }
    }
}
