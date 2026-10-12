package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.CoreLogin;
import uz.nexgramm.core.CoreTokens;
import uz.nexgramm.core.PixelfedClient;

// NexGramm: Lenta tab. Not logged in to the core -> silent initData login with a short loading view;
// only on error a card with "Qayta urinish" and the fallback "Bot orqali kirish" (nonce + bot flow).
// Logged in -> the native Lenta (NexNativeFeedView) with the app's own Pixelfed token. No token yet -> Pixelfed
// OAuth inside NexLentaView (CONTRACT v1.4; the "Authorize" page is shown once), then the native feed. If the
// OAuth fails, the Lenta (Pixelfed) WebView stays as the fallback, opened with a one-time URL from POST /v1/lenta/session.
public class NexFeedActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate, CoreLogin.Listener {

    private TextView titleView;
    private TextView textView;
    private TextView statusView;
    private TextView buttonView;
    private Runnable buttonAction;
    private TextView linkView;
    private LinearLayout card;
    private LinearLayout loadingView;
    private NexLentaView lentaView;
    private NexNativeFeedView nativeView;
    // OAuth failed in this screen: the WebView feed is used until the screen is created again
    private boolean oauthFailed;
    private int notConnectedCount;
    private boolean openBotWhenSent;
    // true once a Lenta session was requested for the current login; false again to request a new one
    private boolean lentaRequested;
    private int lentaGeneration;

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

        // Full screen under the status bar and above the floating bottom tabs (and the navigation bar)
        lentaView = new NexLentaView(context, getResourceProvider(), this::openLenta);
        lentaView.setVisibility(View.GONE);
        frameLayout.addView(lentaView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        nativeView = new NexNativeFeedView(context, tgUserId(), getResourceProvider(), this::onFeedNotConnected);
        nativeView.setVisibility(View.GONE);
        frameLayout.addView(nativeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        ViewCompat.setOnApplyWindowInsetsListener(frameLayout, (v, insets) -> {
            final Insets systemInsets = AndroidUtilities.getDefaultWindowInsets(insets, false);
            setLentaInsets(systemInsets.top, systemInsets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        setLentaInsets(AndroidUtilities.statusBarHeight, AndroidUtilities.navigationBarHeight);
        lentaRequested = false;
        fragmentView = frameLayout;
        updateUi();
        return fragmentView;
    }

    private void setLentaInsets(int top, int bottom) {
        final int tabs = bottom + AndroidUtilities.dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS);
        lentaView.setPadding(0, top, 0, tabs);
        nativeView.setInsets(top, tabs);
    }

    @Override
    public void onResume() {
        super.onResume();
        login().setListener(this);
        maybeAutoLogin();
        updateUi();
        if (lentaView != null) {
            lentaView.onResume();
        }
        if (nativeView != null) {
            nativeView.onResume();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (lentaView != null) {
            lentaView.onPause();
        }
        if (nativeView != null) {
            nativeView.onPause();
        }
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
        lentaGeneration++;
        if (lentaView != null) {
            lentaView.destroy();
        }
        if (nativeView != null) {
            nativeView.clear();
        }
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

    // Native feed when this account has a Pixelfed token; otherwise connect (OAuth); WebView after a failed OAuth
    private void openLenta() {
        lentaRequested = true;
        if (PixelfedClient.getTokens(tgUserId()) != null) {
            showNativeFeed();
        } else if (!oauthFailed) {
            startOAuth();
        } else {
            openWebLenta();
        }
    }

    private void showNativeFeed() {
        ++lentaGeneration;
        lentaView.clear();
        lentaView.setVisibility(View.GONE);
        nativeView.setVisibility(View.VISIBLE);
        if (!nativeView.isLoaded()) {
            nativeView.reload();
        }
    }

    private void showLentaWebView() {
        nativeView.clear();
        nativeView.setVisibility(View.GONE);
        lentaView.setVisibility(View.VISIBLE);
    }

    // The Pixelfed token is gone or rejected: connect again once, then fall back to the WebView feed
    private void onFeedNotConnected() {
        PixelfedClient.clearTokens(tgUserId());
        if (++notConnectedCount > 1) {
            oauthFailed = true;
        }
        openLenta();
    }

    // Pixelfed OAuth (CONTRACT v1.4): one-time session URL + username from the core, then the WebView logs in
    // (cookies cleared first) and opens /oauth/authorize; the code is exchanged here and the account checked.
    private void startOAuth() {
        final long userId = tgUserId();
        final int gen = ++lentaGeneration;
        showLentaWebView();
        lentaView.showLoading();
        CoreClient.EXECUTOR.execute(() -> {
            CoreClient.LentaSession session = null;
            PixelfedClient.Authorization auth = null;
            boolean loggedOut = false;
            try {
                session = CoreClient.lentaSessionBlocking(userId);
                auth = PixelfedClient.newAuthorizationBlocking(session.username);
            } catch (CoreClient.NotLoggedInException e) {
                loggedOut = true;
            } catch (Exception ignore) {
                // falls back to the WebView feed (it shows the core's error itself)
            }
            final CoreClient.LentaSession finalSession = session;
            final PixelfedClient.Authorization finalAuth = auth;
            final boolean finalLoggedOut = loggedOut;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != lentaGeneration || lentaView == null) {
                    return;
                }
                if (finalLoggedOut) {
                    onCoreLoggedOut();
                } else if (finalSession == null || finalAuth == null) {
                    oauthFailedFallback();
                } else {
                    lentaView.startOAuth(finalSession.url, finalAuth.authorizeUrl, new NexLentaView.OAuthListener() {
                        @Override
                        public void onOAuthCode(String code, String state) {
                            if (gen != lentaGeneration) {
                                return;
                            }
                            if (!finalAuth.isState(state)) {
                                oauthFailedFallback();
                                return;
                            }
                            exchangeCode(gen, userId, finalAuth, code);
                        }

                        @Override
                        public void onOAuthFailed(String reason) {
                            if (gen == lentaGeneration) {
                                oauthFailedFallback();
                            }
                        }
                    });
                }
            });
        });
    }

    private void exchangeCode(int gen, long userId, PixelfedClient.Authorization auth, String code) {
        lentaView.showLoading();
        CoreClient.EXECUTOR.execute(() -> {
            boolean ok = false;
            boolean wrongAccount = false;
            try {
                PixelfedClient.exchangeCodeBlocking(userId, auth, code);
                ok = true;
            } catch (PixelfedClient.WrongAccountException e) {
                wrongAccount = true;
            } catch (Exception ignore) {
            }
            final boolean success = ok;
            final boolean finalWrongAccount = wrongAccount;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != lentaGeneration || lentaView == null) {
                    return;
                }
                if (success) {
                    notConnectedCount = 0;
                    showNativeFeed();
                } else if (finalWrongAccount) {
                    // token already thrown away; the Pixelfed session in the WebView is not this user's either
                    NexLentaView.clearFeedCookies();
                    lentaView.showError(LocaleController.getString(R.string.NexFeedWrongAccount));
                } else {
                    oauthFailedFallback();
                }
            });
        });
    }

    private void oauthFailedFallback() {
        oauthFailed = true;
        openWebLenta();
    }

    private void onCoreLoggedOut() {
        // refresh token rejected, tokens dropped: back to the silent login
        lentaRequested = false;
        if (!isPaused) {
            maybeAutoLogin();
        }
        updateUi();
    }

    // Requests a one-time Lenta URL (the access token is refreshed when needed) and opens it.
    // The URL carries a ticket: it is never stored or logged; each retry asks for a new one.
    private void openWebLenta() {
        final long userId = tgUserId();
        final int gen = ++lentaGeneration;
        lentaRequested = true;
        showLentaWebView();
        lentaView.showLoading();
        CoreClient.EXECUTOR.execute(() -> {
            String url = null;
            String reason = null;
            boolean loggedOut = false;
            try {
                url = CoreClient.lentaSessionUrlBlocking(userId);
            } catch (CoreClient.NotLoggedInException e) {
                loggedOut = true;
            } catch (CoreClient.ApiException e) {
                reason = TextUtils.isEmpty(e.messageUz) ? "core HTTP " + e.code : e.messageUz;
            } catch (Exception e) {
                reason = LocaleController.getString(R.string.NexFeedLoginError) + "\n(" + e.getClass().getSimpleName() + ")";
            }
            final String finalUrl = url;
            final String finalReason = reason;
            final boolean finalLoggedOut = loggedOut;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != lentaGeneration || lentaView == null) {
                    return;
                }
                if (finalLoggedOut) {
                    onCoreLoggedOut();
                } else if (finalUrl != null) {
                    lentaView.load(finalUrl);
                } else {
                    lentaView.showError(finalReason);
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
            card.setVisibility(View.GONE);
            loadingView.setVisibility(View.GONE);
            if (!lentaRequested) {
                openLenta();
            }
            return;
        }
        if (lentaRequested || lentaView.getVisibility() == View.VISIBLE || nativeView.getVisibility() == View.VISIBLE) {
            // logged out: the feed must not stay on screen
            lentaGeneration++;
            lentaRequested = false;
            lentaView.clear();
            lentaView.setVisibility(View.GONE);
            nativeView.clear();
            nativeView.setVisibility(View.GONE);
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

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        // The open feed keeps horizontal swipes (photo carousels); tabs switch by tapping
        if (nativeView != null && nativeView.getVisibility() == View.VISIBLE) {
            return false;
        }
        return lentaView == null || !lentaView.isPageShown();
    }
}
