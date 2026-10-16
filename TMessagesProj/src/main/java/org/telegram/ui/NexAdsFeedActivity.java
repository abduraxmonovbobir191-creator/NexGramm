package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.ScaleStateListAnimator;

import java.util.ArrayList;
import java.util.HashSet;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.NexOwnAds;

// NexGramm: E'lonlar — ads to scroll through like the Lenta (Lenta header → 📣). Category chips and a region
// pick filter them; the server puts first what this person likes (GET /v1/ads/feed with the account's token).
// A card on screen for a second counts as a view, opening it as a click (the advertiser pays for one of the
// two). "E'lon berish" opens the bot's ad dialog (/start reklama) right inside the app.
public class NexAdsFeedActivity extends BaseFragment {

    private static final String PREFS = "nexgramm_ads";
    private static final HashSet<String> reported = new HashSet<>(); // views once per app run

    private final ArrayList<NexOwnAds.Ad> items = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private RecyclerView list;
    private LinearLayout chips;
    private TextView emptyView;
    private RadialProgressView progress;
    private String category;           // null = all
    private String city;               // null = the whole country
    private int nextOffset;            // < 0 = the end
    private boolean loading;
    private int generation;
    private NexOwnAds.Meta meta;

    private long tgUserId() {
        return getUserConfig().getClientUserId();
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public boolean isLightStatusBar() {
        return !NexLentaColors.dark;
    }

    @Override
    public View createView(Context context) {
        NexLentaColors.apply();
        actionBar.setAddToContainer(false);
        city = prefs().getString("city", null);
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(NexLentaColors.BACKGROUND);
        root.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);

        // ← · E'lonlar · [+ E'lon berish]
        final LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        final ImageView back = NexLentaUi.circleButton(context, R.drawable.ic_ab_back, NexLentaUi.PILL_CARD, false);
        back.setOnClickListener(v -> finishFragment());
        bar.addView(back, LayoutHelper.createLinear(40, 40));
        final TextView title = NexLentaUi.serifTitle(context, 28);
        title.setText(LocaleController.getString(R.string.NexAdsFeedTitle));
        bar.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));
        final TextView post = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexAdsFeedPost), NexLentaUi.PILL_PRIMARY);
        post.setOnClickListener(v -> openBot());
        bar.addView(post, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 36));
        root.addView(bar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // 📍 region · Hammasi · 💼 Ish · 🏠 Uy-joy …
        final HorizontalScrollView chipsScroll = new HorizontalScrollView(context);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(6), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        chipsScroll.addView(chips);
        root.addView(chipsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final FrameLayout body = new FrameLayout(context);
        list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setItemAnimator(null);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, AndroidUtilities.dp(24));
        list.setAdapter(adapter);
        NexMotion.bounce(list);
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                final LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm != null && lm.findLastVisibleItemPosition() >= items.size() - 3) {
                    load(false);
                }
            }
        });
        body.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        emptyView = new TextView(context);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        body.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 32, 64, 32, 0));
        progress = new RadialProgressView(context);
        progress.setSize(AndroidUtilities.dp(28));
        progress.setProgressColor(NexLentaColors.TEXT);
        body.addView(progress, LayoutHelper.createFrame(48, 48, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 48, 0, 0));
        root.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        fragmentView = root;
        buildChips();
        loadMeta();
        load(true);
        return fragmentView;
    }

    // ---- filters ----

    private void loadMeta() {
        CoreClient.EXECUTOR.execute(() -> {
            try {
                final NexOwnAds.Meta m = NexOwnAds.metaBlocking();
                AndroidUtilities.runOnUIThread(() -> {
                    meta = m;
                    buildChips();
                });
            } catch (Exception ignore) {
                // offline: only "Hammasi" and the region pick stay
            }
        });
    }

    private void buildChips() {
        if (chips == null) {
            return;
        }
        final Context context = chips.getContext();
        chips.removeAllViews();
        final TextView region = NexLentaUi.pillButton(context, "📍 " + cityName() + "  ⌄", NexLentaUi.PILL_SECONDARY);
        region.setOnClickListener(v -> pickCity());
        chips.addView(region, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 8, 0));
        addChip(context, null, LocaleController.getString(R.string.NexAdsFeedAll));
        if (meta != null) {
            for (String[] c : meta.categories) {
                addChip(context, c[0], c[1]);
            }
        }
    }

    private void addChip(Context context, String code, String name) {
        final boolean selected = TextUtils.equals(code, category);
        final TextView chip = NexLentaUi.pillButton(context, name, selected ? NexLentaUi.PILL_PRIMARY : NexLentaUi.PILL_CARD);
        chip.setOnClickListener(v -> {
            if (!TextUtils.equals(code, category)) {
                category = code;
                buildChips();
                load(true);
            }
        });
        chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 8, 0));
    }

    private String cityName() {
        if (meta != null && city != null) {
            for (String[] c : meta.cities) {
                if (c[0].equals(city)) {
                    return c[1];
                }
            }
        }
        return LocaleController.getString(R.string.NexAdsFeedRegion);
    }

    private void pickCity() {
        if (meta == null || meta.cities.isEmpty() || getParentActivity() == null) {
            return;
        }
        final CharSequence[] names = new CharSequence[meta.cities.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = meta.cities.get(i)[1];
        }
        new AlertDialog.Builder(getParentActivity())
            .setTitle(LocaleController.getString(R.string.NexAdsFeedRegion))
            .setItems(names, (d, which) -> {
                final String code = meta.cities.get(which)[0];
                city = "all".equals(code) ? null : code;
                prefs().edit().putString("city", city).apply();
                buildChips();
                load(true);
            })
            .show();
    }

    // ---- loading ----

    private void load(boolean fromStart) {
        if (fromStart) {
            generation++;
            items.clear();
            nextOffset = 0;
            loading = false;
            adapter.notifyDataSetChanged();
            emptyView.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
        }
        if (loading || nextOffset < 0) {
            return;
        }
        loading = true;
        final int gen = generation;
        final int offset = nextOffset;
        final String cat = category;
        final String reg = city;
        final long account = tgUserId();
        CoreClient.EXECUTOR.execute(() -> {
            NexOwnAds.Page page = null;
            try {
                page = NexOwnAds.feedBlocking(account, cat, reg, offset);
            } catch (Exception ignore) {
            }
            final NexOwnAds.Page result = page;
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || fragmentView == null) {
                    return;
                }
                loading = false;
                progress.setVisibility(View.GONE);
                if (result == null) {
                    if (items.isEmpty()) {
                        emptyView.setText(LocaleController.getString(R.string.NexAdsFeedError));
                        emptyView.setVisibility(View.VISIBLE);
                    }
                    return;
                }
                final int start = items.size();
                items.addAll(result.ads);
                nextOffset = result.nextOffset;
                adapter.notifyItemRangeInserted(start, result.ads.size());
                if (items.isEmpty()) {
                    emptyView.setText(LocaleController.getString(R.string.NexAdsFeedEmpty));
                    emptyView.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    // ---- actions ----

    private void open(NexOwnAds.Ad ad) {
        NexOwnAds.report(tgUserId(), ad.id, "click");
        if (getParentActivity() != null) {
            Browser.openUrl(getParentActivity(), ad.url);
        }
    }

    // The bot chat opens here and gets "/start reklama" (its ad dialog)
    private void openBot() {
        String bot = username(BuildConfig.NEXGRAMM_BOT_USERNAME);
        if (bot == null && meta != null) {
            bot = username(meta.botUsername);
        }
        if (bot == null || getParentActivity() == null) {
            failed();
            return;
        }
        getMessagesController().getUserNameResolver().resolve(bot, peerId -> {
            final TLRPC.User user = peerId != null && peerId > 0 ? getMessagesController().getUser(peerId) : null;
            if (user == null || fragmentView == null) {
                failed();
                return;
            }
            final Bundle args = new Bundle();
            args.putLong("user_id", user.id);
            final ChatActivity chat = new ChatActivity(args);
            if (presentFragment(chat)) {
                chat.setBotUser("reklama");
            }
        });
    }

    private static String username(String value) {
        final String v = value == null ? "" : value.trim().replaceFirst("^@", "");
        return v.matches("[A-Za-z][A-Za-z0-9_]{4,31}") ? v : null;
    }

    private void failed() {
        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), LocaleController.getString(R.string.NexActionFailed), Toast.LENGTH_SHORT).show();
        }
    }

    // ---- the cards ----

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemCount() {
            return items.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final AdCard card = new AdCard(parent.getContext());
            card.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(card) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ((AdCard) holder.itemView).bind(items.get(position));
        }
    }

    private class AdCard extends FrameLayout {
        private final LinearLayout card;
        private final TextView label;
        private final FrameLayout mediaHolder;
        private final TextView title;
        private final TextView text;
        private NexOwnAds.Ad ad;
        private final Runnable countView = () -> {
            if (ad != null && isAttachedToWindow() && getGlobalVisibleRect(new android.graphics.Rect())) {
                synchronized (reported) {
                    if (reported.add(ad.id)) {
                        NexOwnAds.report(tgUserId(), ad.id, "view");
                    }
                }
            }
        };

        AdCard(Context context) {
            super(context);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(6), AndroidUtilities.dp(16), AndroidUtilities.dp(10));
            card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 20));
            card.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(14));
            label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            label.setTextColor(NexLentaColors.SECONDARY_TEXT);
            card.addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            mediaHolder = new FrameLayout(context); // the photo / video of the bound ad
            card.addView(mediaHolder, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(NexLentaColors.TEXT);
            card.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
            text = new TextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            text.setTextColor(NexLentaColors.SECONDARY_TEXT);
            text.setLineSpacing(AndroidUtilities.dp(2), 1f);
            card.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            final TextView open = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexAdOpen), NexLentaUi.PILL_PRIMARY);
            open.setOnClickListener(v -> {
                if (ad != null) {
                    open(ad);
                }
            });
            card.addView(open, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 12, 0, 0));
            card.setOnClickListener(v -> {
                if (ad != null) {
                    open(ad);
                }
            });
            ScaleStateListAnimator.apply(card, 0.02f, 1.5f);
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        void bind(NexOwnAds.Ad ad) {
            this.ad = ad;
            final StringBuilder line = new StringBuilder();
            if (ad.top) {
                line.append("📌 TOP");
            }
            if (ad.sponsored) {
                line.append(line.length() > 0 ? " · " : "");
                line.append(LocaleController.getString(R.string.NexAdLabel));
            }
            final String where = place(ad);
            if (!where.isEmpty()) {
                line.append(line.length() > 0 ? " · " : "").append(where);
            }
            label.setText(line);
            label.setVisibility(line.length() > 0 ? VISIBLE : GONE);
            mediaHolder.removeAllViews();
            if (ad.imageUrl != null || ad.videoUrl != null) {
                final FrameLayout picture = NexAds.media(getContext(), ad, 210);
                mediaHolder.addView(picture, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 218, Gravity.TOP, 0, 8, 0, 0));
            }
            title.setText(ad.title);
            text.setText(ad.text);
            text.setVisibility(TextUtils.isEmpty(ad.text) ? GONE : VISIBLE);
            removeCallbacks(countView);
            if (isAttachedToWindow()) {
                postDelayed(countView, 1000);
            }
        }

        // "💼 Ish · Toshkent shahri" from the filters' names
        private String place(NexOwnAds.Ad ad) {
            if (meta == null) {
                return "";
            }
            String cat = "";
            String reg = "";
            for (String[] c : meta.categories) {
                if (c[0].equals(ad.category)) {
                    cat = c[1];
                }
            }
            for (String[] c : meta.cities) {
                if (c[0].equals(ad.city) && !"all".equals(ad.city)) {
                    reg = c[1];
                }
            }
            return cat.isEmpty() ? reg : reg.isEmpty() ? cat : cat + " · " + reg;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            postDelayed(countView, 1000); // on screen for a second = a view
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(countView);
        }
    }
}
