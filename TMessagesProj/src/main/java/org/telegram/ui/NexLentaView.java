package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

import uz.nexgramm.core.PixelfedClient;

// NexGramm: Lenta (Pixelfed) inside the app. The fragment gets a one-time URL from the core
// (POST /v1/lenta/session) and passes it to load(); the WebView follows the OIDC chain itself
// (core /v1/lenta/web/start → Pixelfed /auth/oidc/start → core /v1/lenta/oidc/authorize → Pixelfed callback)
// and keeps the Pixelfed session cookie. Only ALLOWED_HOSTS stay inside, other links open in the
// external browser. URLs (they carry the ticket) and page console output are never logged.
// startOAuth() runs the same chain and then Pixelfed /oauth/authorize (CONTRACT v1.4): the "Authorize" page
// is shown to the user (never pressed by the app); the nexgramm://lenta/oauth redirect is caught here only
// (there is no intent filter for it) and its code goes to the listener.
@SuppressLint("ViewConstructor")
public class NexLentaView extends FrameLayout {

    public static final int BACKGROUND = 0xFFF4F4F7;
    private static final String FEED_HOST = PixelfedClient.HOST;
    private static final int OAUTH_NONE = 0;
    private static final int OAUTH_LOGIN = 1;     // following the core → Pixelfed OIDC chain
    private static final int OAUTH_AUTHORIZE = 2; // /oauth/authorize loaded

    public interface OAuthListener {
        // state is checked by the caller; code and state are never logged
        void onOAuthCode(String code, String state);

        void onOAuthFailed(String reason);
    }
    private static final String[] ALLOWED_HOSTS = {FEED_HOST, "ng.nexecos.com"};

    private final Runnable onRetry;
    private WebView webView;
    private final LinearLayout loadingView;
    private final LinearLayout errorCard;
    private final TextView errorReasonView;
    // true from load() until the first page finished without a main-frame error
    private boolean pageLoading;
    private boolean pageShown;
    private boolean feedHomeForced;
    private int oauthPhase = OAUTH_NONE;
    private String oauthAuthorizeUrl;
    private OAuthListener oauthListener;
    private int oauthAttempt;
    // AUTHORIZE: our /oauth/authorize navigation has started (callbacks of earlier loads are ignored) /
    // the consent page is on screen (no timeout then: the user decides)
    private boolean authorizeStarted;
    private boolean consentShown;
    private static final long LOGIN_TIMEOUT_MS = 45_000;
    private static final long AUTHORIZE_TIMEOUT_MS = 30_000;

    public NexLentaView(Context context, Theme.ResourcesProvider resourcesProvider, Runnable onRetry) {
        super(context);
        this.onRetry = onRetry;
        setBackgroundColor(BACKGROUND);

        try {
            webView = new WebView(context);
            setupWebView(webView);
            webView.setBackgroundColor(BACKGROUND);
            webView.setVisibility(View.INVISIBLE);
            addView(webView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        } catch (Throwable e) {
            // no or broken system WebView: shown as an error with the reason
            webView = null;
        }

        loadingView = new LinearLayout(context);
        loadingView.setOrientation(LinearLayout.VERTICAL);
        loadingView.setGravity(Gravity.CENTER_HORIZONTAL);
        final RadialProgressView progressView = new RadialProgressView(context);
        progressView.setSize(AndroidUtilities.dp(32));
        progressView.setProgressColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        loadingView.addView(progressView, LayoutHelper.createLinear(40, 40, Gravity.CENTER_HORIZONTAL));
        final TextView loadingText = new TextView(context);
        loadingText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        loadingText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        loadingText.setText(LocaleController.getString(R.string.NexFeedLoginStarting));
        loadingView.addView(loadingText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));
        addView(loadingView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        errorCard = new LinearLayout(context);
        errorCard.setOrientation(LinearLayout.VERTICAL);
        errorCard.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(20));
        errorCard.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24), Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)));

        final TextView errorTitle = new TextView(context);
        errorTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        errorTitle.setLineSpacing(AndroidUtilities.dp(2), 1f);
        errorTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        errorTitle.setText(LocaleController.getString(R.string.NexLentaError));
        errorCard.addView(errorTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        errorReasonView = new TextView(context);
        errorReasonView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        errorReasonView.setTypeface(AndroidUtilities.bold());
        errorReasonView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        errorCard.addView(errorReasonView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        final int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        final TextView retryButton = new TextView(context);
        retryButton.setGravity(Gravity.CENTER);
        retryButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        retryButton.setTypeface(AndroidUtilities.bold());
        retryButton.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
        retryButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(24), accent, Theme.multAlpha(accent, 0.8f)));
        retryButton.setText(LocaleController.getString(R.string.NexFeedLoginRetry));
        retryButton.setOnClickListener(v -> onRetry.run());
        errorCard.addView(retryButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 20, 0, 0));

        addView(errorCard, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 20, 0, 20, 0));
        showLoading();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(WebView webView) {
        final WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        final CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return true; // keep page output out of logcat
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (oauthPhase != OAUTH_NONE) {
                    onOAuthPageStarted(Uri.parse(url));
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (oauthPhase != OAUTH_NONE) {
                    onOAuthPageFinished(view, Uri.parse(url));
                    return;
                }
                // Some WebView versions also report the hops of the OIDC chain: wait for a Pixelfed page
                if (pageLoading && isFeedPage(Uri.parse(url))) {
                    if (!feedHomeForced && !isFeedStart(Uri.parse(url))) {
                        // the chain may end on any Pixelfed page (an older session's last page): start at the feed
                        feedHomeForced = true;
                        view.loadUrl("https://" + FEED_HOST + "/");
                        return;
                    }
                    view.clearHistory();
                    pageLoading = false;
                    pageShown = true;
                    loadingView.setVisibility(View.GONE);
                    errorCard.setVisibility(View.GONE);
                    view.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // Older WebViews may load the nexgramm:// redirect instead of asking shouldOverrideUrlLoading
                if (isOAuthRedirect(request.getUrl())) {
                    finishOAuth(request.getUrl());
                    return;
                }
                if (request.isForMainFrame() && oauthPhase != OAUTH_NONE) {
                    final String description = String.valueOf(error.getDescription());
                    if (description.contains("ERR_ABORTED")) {
                        return; // a load we replaced ourselves
                    }
                    if (description.contains("UNKNOWN_URL_SCHEME")) {
                        failOAuth("redirect_not_caught");
                        return;
                    }
                    failOAuth(oauthPhase == OAUTH_LOGIN ? "login_error" : "authorize_error");
                    return;
                }
                if (request.isForMainFrame()) {
                    failPage(String.valueOf(error.getDescription()));
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                // Only while connecting (e.g. an expired ticket); later Pixelfed shows its own pages
                if (oauthPhase != OAUTH_NONE && request.isForMainFrame()) {
                    if (oauthPhase == OAUTH_AUTHORIZE && !authorizeStarted) {
                        return; // late answer of an earlier load
                    }
                    failOAuth((oauthPhase == OAUTH_LOGIN ? "login_http_" : "authorize_http_") + errorResponse.getStatusCode());
                    return;
                }
                if (pageLoading && request.isForMainFrame()) {
                    failPage("HTTP " + errorResponse.getStatusCode());
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
                if (oauthPhase != OAUTH_NONE) {
                    failOAuth("ssl");
                    return;
                }
                if (pageLoading) {
                    failPage("SSL");
                }
            }
        });
    }

    static boolean isAllowed(Uri uri) {
        if (uri == null || !"https".equals(uri.getScheme())) {
            return false;
        }
        final String host = uri.getHost();
        for (String allowed : ALLOWED_HOSTS) {
            if (allowed.equalsIgnoreCase(host)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFeedPage(Uri uri) {
        final String path = uri.getPath();
        return FEED_HOST.equalsIgnoreCase(uri.getHost()) && (path == null || !path.startsWith("/auth/oidc/"));
    }

    // The feed itself: "/" or Pixelfed's web app under /i/web
    private static boolean isFeedStart(Uri uri) {
        final String path = uri.getPath();
        return path == null || path.isEmpty() || "/".equals(path) || path.startsWith("/i/web");
    }

    private static boolean isOAuthRedirect(Uri uri) {
        return uri != null && "nexgramm".equals(uri.getScheme()) && "lenta".equals(uri.getHost()) && "/oauth".equals(uri.getPath());
    }

    // true = handled outside the WebView
    private boolean handleNavigation(Uri uri) {
        if (isOAuthRedirect(uri)) {
            finishOAuth(uri);
            return true;
        }
        if (uri != null && "nexgramm".equals(uri.getScheme())) {
            return true; // our scheme never leaves the app
        }
        if (isAllowed(uri)) {
            return false;
        }
        try {
            final Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
        } catch (Exception ignore) {
            // nothing can open it
        }
        return true;
    }

    private static boolean isAuthorizePage(Uri uri) {
        final String path = uri.getPath();
        return FEED_HOST.equalsIgnoreCase(uri.getHost()) && path != null && path.startsWith("/oauth/authorize");
    }

    private static boolean isLoginPage(Uri uri) {
        final String path = uri.getPath();
        return FEED_HOST.equalsIgnoreCase(uri.getHost()) && path != null && (path.startsWith("/login") || path.startsWith("/i/auth"));
    }

    // Order (CONTRACT v1.4): 1) expire the WebView's Pixelfed cookies and WAIT until that is done,
    // 2) the one-time session URL (core → Pixelfed OIDC login), 3) once a Pixelfed page has loaded,
    // /oauth/authorize in the same WebView with the new session. Cookies are never touched after step 1.
    public void startOAuth(String sessionUrl, String authorizeUrl, OAuthListener listener) {
        if (webView == null) {
            listener.onOAuthFailed("webview_missing");
            return;
        }
        if (!isAllowed(Uri.parse(sessionUrl))) {
            listener.onOAuthFailed("bad_session_url");
            return;
        }
        showLoading();
        oauthListener = listener;
        oauthAuthorizeUrl = authorizeUrl;
        oauthPhase = OAUTH_LOGIN;
        authorizeStarted = false;
        consentShown = false;
        final int attempt = ++oauthAttempt;
        clearFeedCookies(() -> {
            if (attempt != oauthAttempt || oauthPhase != OAUTH_LOGIN || webView == null) {
                return;
            }
            webView.loadUrl(sessionUrl);
            postDelayed(() -> {
                if (attempt == oauthAttempt && oauthPhase == OAUTH_LOGIN) {
                    failOAuth("login_timeout");
                }
            }, LOGIN_TIMEOUT_MS);
        });
    }

    private void goAuthorize() {
        if (oauthPhase != OAUTH_LOGIN || webView == null) {
            return;
        }
        oauthPhase = OAUTH_AUTHORIZE;
        final int attempt = oauthAttempt;
        webView.loadUrl(oauthAuthorizeUrl);
        postDelayed(() -> {
            if (attempt == oauthAttempt && oauthPhase == OAUTH_AUTHORIZE && !consentShown) {
                failOAuth("authorize_timeout");
            }
        }, AUTHORIZE_TIMEOUT_MS);
    }

    private void onOAuthPageStarted(Uri uri) {
        if (isOAuthRedirect(uri)) {
            finishOAuth(uri);
        } else if (oauthPhase == OAUTH_AUTHORIZE && isAuthorizePage(uri)) {
            authorizeStarted = true;
        }
    }

    private void onOAuthPageFinished(WebView view, Uri uri) {
        if (isOAuthRedirect(uri)) {
            finishOAuth(uri);
            return;
        }
        if (oauthPhase == OAUTH_LOGIN) {
            // The OIDC chain ends on a Pixelfed page (Pixelfed redirects to "/" after the callback): logged in.
            // That page is loaded, not cut short, so no late callback of a cancelled load can follow.
            if (isFeedPage(uri)) {
                goAuthorize();
            }
            return;
        }
        if (isAuthorizePage(uri)) {
            // Pixelfed's "Authorize" / "Cancel" page: the user decides; it stays until the redirect comes back
            authorizeStarted = true;
            consentShown = true;
            pageShown = true;
            loadingView.setVisibility(View.GONE);
            errorCard.setVisibility(View.GONE);
            view.setVisibility(View.VISIBLE);
            return;
        }
        if (!authorizeStarted || !FEED_HOST.equalsIgnoreCase(uri.getHost())) {
            return; // late callback of an earlier load
        }
        if (isLoginPage(uri)) {
            failOAuth("authorize_login"); // the Pixelfed session did not stick
        } else if (consentShown) {
            failOAuth("redirect_not_caught");
        } else {
            failOAuth("authorize_failed");
        }
    }

    private void finishOAuth(Uri uri) {
        final OAuthListener listener = oauthListener;
        if (oauthPhase == OAUTH_NONE || listener == null) {
            return;
        }
        resetOAuth();
        if (webView != null) {
            webView.stopLoading();
        }
        showLoading();
        final String code = uri.getQueryParameter("code");
        if (TextUtils.isEmpty(code)) {
            final String error = uri.getQueryParameter("error");
            listener.onOAuthFailed("access_denied".equals(error) ? "denied" : "no_code");
        } else {
            listener.onOAuthCode(code, uri.getQueryParameter("state"));
        }
    }

    private void failOAuth(String reason) {
        final OAuthListener listener = oauthListener;
        if (oauthPhase == OAUTH_NONE || listener == null) {
            return;
        }
        resetOAuth();
        if (webView != null) {
            webView.stopLoading();
        }
        showLoading();
        listener.onOAuthFailed(reason);
    }

    private void resetOAuth() {
        oauthAttempt++;
        oauthPhase = OAUTH_NONE;
        oauthListener = null;
        oauthAuthorizeUrl = null;
        consentShown = false;
    }

    // Expires every cookie of the Pixelfed host (host-only and domain cookies); other sites keep theirs.
    // done runs on the UI thread after the cookie store has applied all changes.
    public static void clearFeedCookies(Runnable done) {
        final CookieManager manager;
        final String cookies;
        final String url = "https://" + FEED_HOST;
        try {
            manager = CookieManager.getInstance();
            cookies = manager.getCookie(url);
        } catch (Throwable e) {
            // no WebView: nothing stored either
            done.run();
            return;
        }
        final java.util.ArrayList<String> expired = new java.util.ArrayList<>();
        if (!TextUtils.isEmpty(cookies)) {
            final String parent = FEED_HOST.substring(FEED_HOST.indexOf('.') + 1);
            for (String part : cookies.split(";")) {
                final int eq = part.indexOf('=');
                final String name = (eq >= 0 ? part.substring(0, eq) : part).trim();
                if (name.isEmpty()) {
                    continue;
                }
                expired.add(name + "=; Max-Age=0; Path=/; Secure");
                expired.add(name + "=; Max-Age=0; Path=/; Secure; Domain=" + FEED_HOST);
                expired.add(name + "=; Max-Age=0; Path=/; Secure; Domain=" + parent);
            }
        }
        if (expired.isEmpty()) {
            done.run();
            return;
        }
        final int[] left = {expired.size()};
        final boolean[] finished = {false};
        final Runnable finish = () -> {
            if (finished[0]) {
                return;
            }
            finished[0] = true;
            try {
                manager.flush();
            } catch (Throwable ignore) {
            }
            done.run();
        };
        final ValueCallback<Boolean> callback = ignored -> {
            if (--left[0] <= 0) {
                finish.run();
            }
        };
        for (String cookie : expired) {
            try {
                manager.setCookie(url, cookie, callback);
            } catch (Throwable e) {
                callback.onReceiveValue(false);
            }
        }
        // Never hang if a callback is lost
        AndroidUtilities.runOnUIThread(finish, 3000);
    }

    public static void clearFeedCookies() {
        clearFeedCookies(() -> {
        });
    }

    private void failPage(String reason) {
        if (!pageLoading && !pageShown) {
            return;
        }
        pageLoading = false;
        pageShown = false;
        if (webView != null) {
            webView.stopLoading();
        }
        showError(reason);
    }

    public void showLoading() {
        pageLoading = false;
        pageShown = false;
        if (webView != null) {
            webView.setVisibility(View.INVISIBLE);
        }
        errorCard.setVisibility(View.GONE);
        loadingView.setVisibility(View.VISIBLE);
    }

    public void showError(String reason) {
        pageLoading = false;
        pageShown = false;
        if (webView != null) {
            webView.setVisibility(View.INVISIBLE);
        }
        loadingView.setVisibility(View.GONE);
        errorReasonView.setText(reason);
        errorReasonView.setVisibility(TextUtils.isEmpty(reason) ? View.GONE : View.VISIBLE);
        errorCard.setVisibility(View.VISIBLE);
    }

    // Opens the one-time session URL from the core
    public void load(String url) {
        if (webView == null) {
            showError("WebView");
            return;
        }
        if (!isAllowed(Uri.parse(url))) {
            showError(LocaleController.getString(R.string.NexLentaBadUrl));
            return;
        }
        resetOAuth();
        showLoading();
        pageLoading = true;
        feedHomeForced = false;
        webView.loadUrl(url);
    }

    public boolean isPageShown() {
        return pageShown;
    }

    // After logout the page must not stay on screen
    public void clear() {
        resetOAuth();
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
        }
        showLoading();
    }

    public void onResume() {
        if (webView != null) {
            webView.onResume();
        }
    }

    public void onPause() {
        if (webView != null) {
            webView.onPause();
        }
    }

    public void destroy() {
        resetOAuth();
        if (webView != null) {
            webView.stopLoading();
            removeView(webView);
            webView.destroy();
            webView = null;
        }
    }
}
