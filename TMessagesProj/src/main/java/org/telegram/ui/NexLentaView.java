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

// NexGramm: Lenta (Pixelfed) inside the app. The fragment gets a one-time URL from the core
// (POST /v1/lenta/session) and passes it to load(); the WebView follows the OIDC chain itself
// (core /v1/lenta/web/start → Pixelfed /auth/oidc/start → core /v1/lenta/oidc/authorize → Pixelfed callback)
// and keeps the Pixelfed session cookie. Only ALLOWED_HOSTS stay inside, other links open in the
// external browser. URLs (they carry the ticket) and page console output are never logged.
@SuppressLint("ViewConstructor")
public class NexLentaView extends FrameLayout {

    public static final int BACKGROUND = 0xFFF4F4F7;
    private static final String FEED_HOST = "feed.nexecos.com";
    private static final String[] ALLOWED_HOSTS = {FEED_HOST, "ng.nexecos.com"};

    private final Runnable onRetry;
    private WebView webView;
    private final LinearLayout loadingView;
    private final LinearLayout errorCard;
    private final TextView errorReasonView;
    // true from load() until the first page finished without a main-frame error
    private boolean pageLoading;
    private boolean pageShown;

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
                if (request.isForMainFrame()) {
                    failPage(String.valueOf(error.getDescription()));
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                // Only while connecting (e.g. an expired ticket); later Pixelfed shows its own pages
                if (pageLoading && request.isForMainFrame()) {
                    failPage("HTTP " + errorResponse.getStatusCode());
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
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

    // true = handled outside the WebView
    private boolean handleNavigation(Uri uri) {
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
        showLoading();
        pageLoading = true;
        webView.loadUrl(url);
    }

    public boolean isPageShown() {
        return pageShown;
    }

    // After logout the page must not stay on screen
    public void clear() {
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
        if (webView != null) {
            webView.stopLoading();
            removeView(webView);
            webView.destroy();
            webView = null;
        }
    }
}
