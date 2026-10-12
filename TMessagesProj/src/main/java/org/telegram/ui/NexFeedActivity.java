package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.CoreLogin;
import uz.nexgramm.core.CoreTokens;

// NexGramm: Lenta tab. Not logged in to the core -> silent initData login with a short loading view;
// only on error a card with "Qayta urinish" and the fallback "Bot orqali kirish" (nonce + bot flow).
// Logged in -> "Ulandi" with the access token expiry (time only, never the token) and a logout button.
public class NexFeedActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate, CoreLogin.Listener {

    private TextView titleView;
    private TextView textView;
    private TextView statusView;
    private TextView buttonView;
    private Runnable buttonAction;
    private TextView linkView;
    private LinearLayout card;
    private LinearLayout loadingView;
    private boolean openBotWhenSent;
    private int refreshGeneration;

    public static NexFeedActivity create() {
        final Bundle args = new Bundle();
        args.putBoolean("hasMainTabs", true);
        return new NexFeedActivity(args);
    }

    public NexFeedActivity(Bundle args) {
        super(args);
    }

    private CoreLogin login() {
        return CoreLogin.getInstance(currentAccount);
    }

    private long tgUserId() {
        return getUserConfig().getClientUserId();
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        final FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(20));
        card.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24), getThemedColor(Theme.key_windowBackgroundWhite)));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        card.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        textView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        card.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusView.setTypeface(AndroidUtilities.bold());
        statusView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        card.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        // Black round pill button (accent = text color of the light theme, white text)
        buttonView = new TextView(context);
        buttonView.setGravity(Gravity.CENTER);
        buttonView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        buttonView.setTypeface(AndroidUtilities.bold());
        buttonView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhite));
        buttonView.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(24),
            getThemedColor(Theme.key_windowBackgroundWhiteBlackText), Theme.multAlpha(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), 0.8f)));
        buttonView.setOnClickListener(v -> {
            if (buttonAction != null) {
                buttonAction.run();
            }
        });
        card.addView(buttonView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 20, 0, 0));

        // Fallback login, shown only after the silent login failed
        linkView = new TextView(context);
        linkView.setGravity(Gravity.CENTER);
        linkView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        linkView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        linkView.setText(LocaleController.getString(R.string.NexFeedLoginViaBot));
        linkView.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(4));
        linkView.setOnClickListener(v -> startBotLogin());
        card.addView(linkView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        frameLayout.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 20, 0, 20, 0));

        loadingView = new LinearLayout(context);
        loadingView.setOrientation(LinearLayout.VERTICAL);
        loadingView.setGravity(Gravity.CENTER_HORIZONTAL);
        final RadialProgressView progressView = new RadialProgressView(context);
        progressView.setSize(AndroidUtilities.dp(32));
        progressView.setProgressColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        loadingView.addView(progressView, LayoutHelper.createLinear(40, 40, Gravity.CENTER_HORIZONTAL));
        final TextView loadingText = new TextView(context);
        loadingText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        loadingText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        loadingText.setText(LocaleController.getString(R.string.NexFeedLoginStarting));
        loadingView.addView(loadingText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));
        frameLayout.addView(loadingView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        fragmentView = frameLayout;
        updateUi();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        login().setListener(this);
        maybeAutoLogin();
        updateUi();
        refreshExpiry();
    }

    // Silent login when there are no tokens: on the first open of the tab, or when the tokens were
    // dropped after a successful login (refresh token rejected)
    private void maybeAutoLogin() {
        if (CoreClient.getTokens(tgUserId()) == null && login().shouldAutoStart()) {
            login().startInitData();
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        login().setListener(null);
    }

    @Override
    public void onCoreLoginChanged() {
        // The bot chat opens once "/start app_<nonce>" is sent, so the user can press [Ha, kirish]
        if (openBotWhenSent && login().getState() != CoreLogin.STATE_STARTING) {
            openBotWhenSent = false;
            if (login().getState() == CoreLogin.STATE_WAITING && !isPaused) {
                openBot();
            }
        }
        updateUi();
    }

    // Refreshes the access token when it is (almost) expired, so the shown expiry is current
    private void refreshExpiry() {
        final long userId = tgUserId();
        if (CoreClient.getTokens(userId) == null) {
            return;
        }
        final int gen = ++refreshGeneration;
        CoreClient.EXECUTOR.execute(() -> {
            try {
                CoreClient.freshTokensBlocking(userId);
            } catch (Exception ignore) {
                // not logged in any more (tokens dropped) or offline: the stored state is shown
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (gen == refreshGeneration) {
                    if (!isPaused) {
                        maybeAutoLogin();
                    }
                    updateUi();
                }
            });
        });
    }

    private void updateUi() {
        if (fragmentView == null) {
            return;
        }
        final CoreTokens tokens = CoreClient.getTokens(tgUserId());
        if (tokens != null) {
            showCard(LocaleController.getString(R.string.NexFeedConnected), LocaleController.getString(R.string.NexFeedConnectedText));
            final String expiry = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US).format(new Date(tokens.accessExpiresAt));
            final String method = CoreLogin.getWorkingMethodName();
            final String expiresText = LocaleController.formatString(R.string.NexFeedTokenExpires, expiry);
            statusView.setText(method == null ? expiresText : expiresText + "\n" + LocaleController.formatString(R.string.NexFeedLoginMethod, method));
            setButton(LocaleController.getString(R.string.NexFeedLogout), this::logout, false);
            return;
        }

        final CoreLogin login = login();
        if (!CoreLogin.hasBotUsername() || login.getState() == CoreLogin.STATE_NO_BOT) {
            showCard(null, null);
            statusView.setText(LocaleController.getString(R.string.NexFeedLoginNoBot));
            setButton(null, null, false);
            return;
        }
        if (login.isViaBot()) {
            updateBotLoginUi(login);
            return;
        }
        switch (login.getState()) {
            case CoreLogin.STATE_ERROR: {
                showCard(null, LocaleController.getString(login.isSlowedDown() ? R.string.NexFeedLoginRateLimited : R.string.NexFeedSilentLoginError));
                statusView.setText(login.getErrorDetail());
                setButton(LocaleController.getString(R.string.NexFeedLoginRetry), this::startSilentLogin, true);
                break;
            }
            case CoreLogin.STATE_IDLE:
                if (!login.shouldAutoStart()) {
                    // After logout: log in again only on request
                    showCard(null, LocaleController.getString(R.string.NexFeedLoggedOut));
                    statusView.setText(null);
                    setButton(LocaleController.getString(R.string.NexFeedLogin), this::startSilentLogin, false);
                    break;
                }
                // fall through: the silent login starts on resume
            default:
                card.setVisibility(View.GONE);
                loadingView.setVisibility(View.VISIBLE);
                break;
        }
    }

    // Fallback bot (nonce) flow, unchanged apart from the entry point
    private void updateBotLoginUi(CoreLogin login) {
        showCard(LocaleController.getString(R.string.NexFeedLoginTitle), LocaleController.formatString(R.string.NexFeedLoginText, BuildConfig.NEXGRAMM_BOT_USERNAME));
        final String retry = LocaleController.getString(R.string.NexFeedLoginRetry);
        switch (login.getState()) {
            case CoreLogin.STATE_STARTING:
                statusView.setText(LocaleController.getString(R.string.NexFeedLoginStarting));
                setButton(null, null, false);
                break;
            case CoreLogin.STATE_WAITING:
                statusView.setText(LocaleController.getString(login.isSlowedDown() ? R.string.NexFeedLoginSlow : R.string.NexFeedLoginWaiting));
                setButton(LocaleController.getString(R.string.NexFeedLoginOpenBot), this::openBot, false);
                break;
            case CoreLogin.STATE_DENIED:
                statusView.setText(LocaleController.getString(R.string.NexFeedLoginDenied));
                setButton(retry, this::startBotLogin, false);
                break;
            case CoreLogin.STATE_EXPIRED:
                statusView.setText(LocaleController.getString(R.string.NexFeedLoginExpired));
                setButton(retry, this::startBotLogin, false);
                break;
            case CoreLogin.STATE_ERROR:
            default:
                statusView.setText(LocaleController.getString(login.isSlowedDown() ? R.string.NexFeedLoginRateLimited : R.string.NexFeedLoginError));
                setButton(retry, this::startBotLogin, false);
                break;
        }
    }

    private void showCard(String title, String text) {
        loadingView.setVisibility(View.GONE);
        card.setVisibility(View.VISIBLE);
        titleView.setText(title);
        titleView.setVisibility(title == null ? View.GONE : View.VISIBLE);
        textView.setText(text);
        textView.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    private void setButton(String text, Runnable action, boolean showBotLink) {
        buttonView.setText(text);
        buttonAction = action;
        buttonView.setVisibility(text == null ? View.GONE : View.VISIBLE);
        statusView.setVisibility(statusView.length() == 0 ? View.GONE : View.VISIBLE);
        linkView.setVisibility(showBotLink ? View.VISIBLE : View.GONE);
    }

    private void startSilentLogin() {
        login().startInitData();
    }

    private void startBotLogin() {
        openBotWhenSent = true;
        login().start();
    }

    private void openBot() {
        final long botId = login().getBotUserId();
        if (botId == 0) {
            return;
        }
        final Bundle args = new Bundle();
        args.putLong("user_id", botId);
        presentFragment(new ChatActivity(args));
    }

    private void logout() {
        CoreClient.logout(tgUserId());
        login().reset();
        updateUi();
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }
}
