package org.telegram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.http.SslError;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
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
    private int oauthPhase = OAUTH_NONE;
    private String oauthAuthorizeUrl;
    private OAuthListener oauthListener;

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
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (oauthPhase != OAUTH_NONE) {
                    onOAuthPageFinished(view, Uri.parse(url));
                    return;
                }
                // Some WebView versions also report the hops of the OIDC chain: wait for a Pixelfed page
                if (pageLoading && isFeedPage(Uri.parse(url))) {
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
                    failOAuth(String.valueOf(error.getDescription()));
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
                    failOAuth("HTTP " + errorResponse.getStatusCode());
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
                    failOAuth("SSL");
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

    private static boolean isOAuthRedirect(Uri uri) {
        return uri != null && "nexgramm".equals(uri.getScheme()) && "lenta".equals(uri.getHost()) && "/oauth".equals(uri.getPath());
    }

    // Pixelfed's landing page after the OIDC callback
    private static boolean isFeedHome(Uri uri) {
        final String path = uri.getPath();
        return "https".equals(uri.getScheme()) && FEED_HOST.equalsIgnoreCase(uri.getHost()) && (path == null || path.isEmpty() || "/".equals(path));
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
        if (oauthPhase == OAUTH_LOGIN && isFeedHome(uri)) {
            // logged in to Pixelfed: skip loading the feed page, go straight to /oauth/authorize
            post(this::goAuthorize);
            return true;
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

    // Logs into Pixelfed with the one-time session URL, then opens authorizeUrl in the same WebView.
    // The WebView's Pixelfed cookies are cleared first, so the token can only be for the current user.
    public void startOAuth(String sessionUrl, String authorizeUrl, OAuthListener listener) {
        if (webView == null) {
            listener.onOAuthFailed("WebView");
            return;
        }
        if (!isAllowed(Uri.parse(sessionUrl))) {
            listener.onOAuthFailed(LocaleController.getString(R.string.NexLentaBadUrl));
            return;
        }
        showLoading();
        clearFeedCookies();
        oauthListener = listener;
        oauthAuthorizeUrl = authorizeUrl;
        oauthPhase = OAUTH_LOGIN;
        webView.loadUrl(sessionUrl);
    }

    private void goAuthorize() {
        if (oauthPhase != OAUTH_LOGIN || webView == null) {
            return;
        }
        oauthPhase = OAUTH_AUTHORIZE;
        webView.loadUrl(oauthAuthorizeUrl);
    }

    private void onOAuthPageFinished(WebView view, Uri uri) {
        if (isOAuthRedirect(uri)) {
            finishOAuth(uri);
            return;
        }
        if (oauthPhase == OAUTH_LOGIN) {
            // WebViews that do not report redirects: the Pixelfed page after the OIDC chain
            if (isFeedPage(uri)) {
                goAuthorize();
            }
            return;
        }
        if (!FEED_HOST.equalsIgnoreCase(uri.getHost())) {
            return;
        }
        final String path = uri.getPath();
        if (path != null && path.startsWith("/oauth/authorize")) {
            // Pixelfed's "Authorize" page (first time for this app): the user decides
            loadingView.setVisibility(View.GONE);
            errorCard.setVisibility(View.GONE);
            view.setVisibility(View.VISIBLE);
            pageShown = true;
        } else {
            // e.g. Pixelfed's own login page: the OIDC login did not stick
            failOAuth("login");
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
            listener.onOAuthFailed(TextUtils.isEmpty(error) ? "no code" : error);
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
        oauthPhase = OAUTH_NONE;
        oauthListener = null;
        oauthAuthorizeUrl = null;
    }

    // Expires every cookie of the Pixelfed host (host-only and domain cookies); other sites keep theirs
    public static void clearFeedCookies() {
        try {
            final CookieManager manager = CookieManager.getInstance();
            final String url = "https://" + FEED_HOST;
            final String cookies = manager.getCookie(url);
            if (!TextUtils.isEmpty(cookies)) {
                for (String part : cookies.split(";")) {
                    final int eq = part.indexOf('=');
                    final String name = (eq >= 0 ? part.substring(0, eq) : part).trim();
                    if (name.isEmpty()) {
                        continue;
                    }
                    manager.setCookie(url, name + "=; Max-Age=0; Path=/; Secure");
                    manager.setCookie(url, name + "=; Max-Age=0; Path=/; Secure; Domain=" + FEED_HOST);
                }
            }
            manager.flush();
        } catch (Throwable ignore) {
            // no WebView: nothing stored either
        }
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
