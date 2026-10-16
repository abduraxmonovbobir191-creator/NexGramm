package org.telegram.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

import uz.nexgramm.core.NexOwnAds;
import uz.nexgramm.core.NexPremium;

// NexGramm: ads for users without NexGramm Premium. First the ads the server drew for this person
// (GET /v1/ads/next: advertisers' paid ads and ours, by what the person likes); when there are none, mostly
// AdMob (ADMOB_BANNER_ID at build time; empty = none), sometimes one of the general list (GET /v1/ads). A slot
// is a card in the Lenta, labelled "Ad". Ads report a view (once per app run) and clicks (POST /v1/ads/{id}/…).
final class NexAds {

    private static final float OWN_SHARE = 0.3f; // own ads get about a third of the slots when there are some

    private static boolean mobileAdsStarted;
    private static final HashSet<String> viewed = new HashSet<>();
    private static final Random random = new Random();
    private static long account; // whose token goes with the ad calls

    private NexAds() {
    }

    static boolean admobConfigured() {
        return !TextUtils.isEmpty(BuildConfig.ADMOB_BANNER_ID);
    }

    // Ads at all for this account (no Premium, and something to show)
    static boolean enabled(long tgUserId) {
        account = tgUserId;
        NexOwnAds.refresh();
        if (!NexPremium.isActive(tgUserId)) {
            NexOwnAds.refreshNext(tgUserId);
        }
        return !NexPremium.isActive(tgUserId) && (admobConfigured() || !NexOwnAds.current().isEmpty());
    }

    // A slot in a list: fills itself once (own ad or AdMob); stays empty (0 height) when nothing comes
    static final class Slot extends FrameLayout {
        private boolean filled;
        private AdView adView;

        Slot(Context context) {
            super(context);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10), AndroidUtilities.dp(16), AndroidUtilities.dp(14));
        }

        void fill() {
            if (filled) {
                return;
            }
            filled = true;
            final NexOwnAds.Ad drawn = NexOwnAds.pollNext(account);
            if (drawn != null) {
                showOwn(drawn);
                return;
            }
            final ArrayList<NexOwnAds.Ad> list = NexOwnAds.current();
            final boolean useOwn = !list.isEmpty() && (!admobConfigured() || random.nextFloat() < OWN_SHARE);
            if (useOwn) {
                showOwn(list.get(random.nextInt(list.size())));
            } else if (admobConfigured()) {
                showAdMob();
            } else {
                setVisibility(GONE);
            }
        }

        private TextView label(Context context) {
            final TextView label = new TextView(context);
            label.setText(LocaleController.getString(R.string.NexAdLabel));
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            label.setTextColor(NexLentaColors.SECONDARY_TEXT);
            return label;
        }

        private void showOwn(NexOwnAds.Ad ad) {
            final Context context = getContext();
            final LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 18));
            card.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(14));
            card.addView(label(context), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            if (ad.imageUrl != null || ad.videoUrl != null) {
                final FrameLayout picture = media(context, ad, 180);
                card.addView(picture, picture.getLayoutParams());
            }
            final TextView title = new TextView(context);
            title.setText(ad.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(NexLentaColors.TEXT);
            card.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
            if (!TextUtils.isEmpty(ad.text)) {
                final TextView text = new TextView(context);
                text.setText(ad.text);
                text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                text.setTextColor(NexLentaColors.SECONDARY_TEXT);
                card.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            }
            final TextView open = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexAdOpen), NexLentaUi.PILL_PRIMARY);
            card.addView(open, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 12, 0, 0));
            final View.OnClickListener click = v -> {
                NexOwnAds.report(account, ad.id, "click");
                Browser.openUrl(getContext(), ad.url);
            };
            card.setOnClickListener(click);
            org.telegram.ui.Components.ScaleStateListAnimator.apply(card, 0.02f, 1.5f);
            open.setOnClickListener(click);
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            synchronized (viewed) {
                if (viewed.add(ad.id)) {
                    NexOwnAds.report(account, ad.id, "view");
                }
            }
        }

        private void showAdMob() {
            final Context context = getContext();
            if (!mobileAdsStarted) {
                mobileAdsStarted = true;
                try {
                    MobileAds.initialize(ApplicationLoader.applicationContext, status -> {
                    });
                } catch (Throwable e) {
                    setVisibility(GONE);
                    return;
                }
            }
            final LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            column.addView(label(context), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));
            adView = new AdView(context);
            adView.setAdSize(AdSize.MEDIUM_RECTANGLE);
            adView.setAdUnitId(BuildConfig.ADMOB_BANNER_ID);
            adView.setAdListener(new AdListener() {
                @Override
                public void onAdFailedToLoad(LoadAdError error) {
                    setVisibility(GONE); // nothing to show: the slot takes no room
                }
            });
            column.addView(adView, LayoutHelper.createLinear(300, 250, Gravity.CENTER_HORIZONTAL, 0, 6, 0, 0));
            addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            try {
                adView.loadAd(new AdRequest.Builder().build());
            } catch (Throwable e) {
                setVisibility(GONE);
            }
        }

        void destroy() {
            if (adView != null) {
                adView.destroy();
                adView = null;
            }
        }
    }

    // An ad's picture: the photo, and over it its short video (muted, looping) while it is on screen
    static FrameLayout media(Context context, NexOwnAds.Ad ad, int heightDp) {
        final FrameLayout box = new FrameLayout(context) {
            private android.widget.VideoView video;

            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                if (ad.videoUrl == null || video != null) {
                    return;
                }
                video = new android.widget.VideoView(getContext());
                video.setAlpha(0f);
                video.setOnPreparedListener(mp -> {
                    mp.setLooping(true);
                    mp.setVolume(0f, 0f);
                    video.start();
                    video.animate().alpha(1f).setDuration(200).start();
                });
                video.setOnErrorListener((mp, what, extra) -> true); // the photo stays
                video.setVideoURI(android.net.Uri.parse(ad.videoUrl));
                addView(video, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
            }

            @Override
            protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                if (video != null) {
                    video.stopPlayback();
                    removeView(video);
                    video = null;
                }
            }
        };
        box.setClipToOutline(true);
        box.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AndroidUtilities.dp(14));
            }
        });
        final BackupImageView image = new BackupImageView(context);
        image.setRoundRadius(AndroidUtilities.dp(14));
        if (ad.imageUrl != null) {
            image.setImage(ad.imageUrl, null, null);
        }
        box.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        box.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, heightDp, 0, 8, 0, 0));
        return box;
    }
}
