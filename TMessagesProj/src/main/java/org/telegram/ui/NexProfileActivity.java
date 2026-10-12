package org.telegram.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.PixelfedAccount;
import uz.nexgramm.core.PixelfedClient;
import uz.nexgramm.core.PixelfedStatus;

// NexGramm: small Lenta profile. GET /api/v1/accounts/:id + relationships for the header, the account's
// posts below (NexNativeFeedView in account mode), "Kuzatish" / "Kuzatmaslik" via
// POST /api/v1/accounts/:id/follow | unfollow (not shown on one's own profile).
public class NexProfileActivity extends BaseFragment {

    private final String accountId;
    private NexNativeFeedView feedView;
    private BackupImageView avatarView;
    private TextView nameView;
    private TextView usernameView;
    private TextView statsView;
    private TextView bioView;
    private TextView followButton;
    private PixelfedAccount account;
    private boolean following;
    private boolean followPending;
    private int generation;

    public NexProfileActivity(String accountId) {
        this.accountId = accountId;
    }

    private long tgUserId() {
        return getUserConfig().getClientUserId();
    }

    @Override
    public boolean isLightStatusBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        NexLentaUi.setupActionBar(this, actionBar, LocaleController.getString(R.string.NexProfileTitle));

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(NexLentaColors.BACKGROUND);
        feedView = new NexNativeFeedView(context, tgUserId(), accountId, getResourceProvider(), new NexNativeFeedView.Delegate() {
            @Override
            public void onFeedNotConnected() {
                Toast.makeText(context, LocaleController.getString(R.string.NexComposeNotConnected), Toast.LENGTH_LONG).show();
                finishFragment();
            }

            @Override
            public void onOpenComments(PixelfedStatus status) {
                presentFragment(new NexCommentsActivity(status));
            }

            @Override
            public void onOpenProfile(String id) {
                presentFragment(new NexProfileActivity(id));
            }
        });
        feedView.setHeaderView(createHeader(context));
        feedView.setInsets(0, AndroidUtilities.navigationBarHeight);
        root.addView(feedView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        loadAccount();
        feedView.reload();
        return fragmentView;
    }

    private View createHeader(Context context) {
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(20), AndroidUtilities.dp(18), AndroidUtilities.dp(18));
        card.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24), NexLentaColors.CARD));

        avatarView = new BackupImageView(context);
        avatarView.setRoundRadius(AndroidUtilities.dp(42));
        avatarView.setImageDrawable(Theme.createCircleDrawable(AndroidUtilities.dp(84), NexLentaColors.PLACEHOLDER));
        card.addView(avatarView, LayoutHelper.createLinear(84, 84, Gravity.CENTER_HORIZONTAL));

        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(NexLentaColors.TEXT);
        nameView.setGravity(Gravity.CENTER);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        usernameView = new TextView(context);
        usernameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        usernameView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        usernameView.setGravity(Gravity.CENTER);
        card.addView(usernameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        statsView = new TextView(context);
        statsView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statsView.setTextColor(NexLentaColors.TEXT);
        statsView.setGravity(Gravity.CENTER);
        card.addView(statsView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        bioView = new TextView(context);
        bioView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        bioView.setLineSpacing(AndroidUtilities.dp(2), 1f);
        bioView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        bioView.setGravity(Gravity.CENTER);
        bioView.setVisibility(View.GONE);
        card.addView(bioView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        followButton = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexProfileFollow), true);
        followButton.setVisibility(View.GONE);
        followButton.setOnClickListener(v -> toggleFollow());
        card.addView(followButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 16, 0, 0));

        final FrameLayout frame = new FrameLayout(context);
        frame.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 12, 6, 12, 6));
        return frame;
    }

    private void loadAccount() {
        final int gen = ++generation;
        final long userId = tgUserId();
        CoreClient.EXECUTOR.execute(() -> {
            PixelfedAccount result = null;
            boolean isFollowing = false;
            try {
                result = PixelfedClient.accountBlocking(userId, accountId);
                isFollowing = PixelfedClient.isFollowingBlocking(userId, accountId);
            } catch (Exception ignore) {
            }
            final PixelfedAccount finalResult = result;
            final boolean finalFollowing = isFollowing;
            final String own = PixelfedClient.ownUsername(userId);
            AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation || fragmentView == null) {
                    return;
                }
                if (finalResult == null) {
                    nameView.setText(LocaleController.getString(R.string.NexFeedLoadError));
                    return;
                }
                account = finalResult;
                following = finalFollowing;
                bindAccount(own != null && own.equalsIgnoreCase(finalResult.username));
            });
        });
    }

    private void bindAccount(boolean ownProfile) {
        avatarView.setImage(account.avatarUrl, "84_84", Theme.createCircleDrawable(AndroidUtilities.dp(84), NexLentaColors.PLACEHOLDER));
        nameView.setText(account.displayName);
        usernameView.setText("@" + account.username);
        updateStats();
        final String bio = NexCommentsActivity.htmlToText(account.noteHtml);
        bioView.setText(bio);
        bioView.setVisibility(bio.isEmpty() ? View.GONE : View.VISIBLE);
        followButton.setVisibility(ownProfile ? View.GONE : View.VISIBLE);
        updateFollowButton();
    }

    private void updateStats() {
        statsView.setText(LocaleController.formatString(R.string.NexProfileStats, account.statusesCount, account.followersCount, account.followingCount));
    }

    private void updateFollowButton() {
        followButton.setText(LocaleController.getString(following ? R.string.NexProfileUnfollow : R.string.NexProfileFollow));
        final int bg = following ? NexLentaColors.SECONDARY : NexLentaColors.ACCENT;
        followButton.setTextColor(following ? NexLentaColors.TEXT : NexLentaColors.CARD);
        followButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(24), bg, Theme.multAlpha(bg, 0.8f)));
        followButton.setAlpha(followPending ? 0.6f : 1f);
    }

    private void toggleFollow() {
        if (account == null || followPending) {
            return;
        }
        final boolean follow = !following;
        followPending = true;
        following = follow;
        account.followersCount += follow ? 1 : -1;
        updateStats();
        updateFollowButton();
        final long userId = tgUserId();
        CoreClient.EXECUTOR.execute(() -> {
            boolean ok = false;
            try {
                PixelfedClient.setFollowBlocking(userId, accountId, follow);
                ok = true;
            } catch (Exception ignore) {
            }
            final boolean success = ok;
            AndroidUtilities.runOnUIThread(() -> {
                followPending = false;
                if (!success) {
                    following = !follow;
                    account.followersCount += follow ? -1 : 1;
                    if (getParentActivity() != null) {
                        Toast.makeText(getParentActivity(), LocaleController.getString(R.string.NexProfileFollowError), Toast.LENGTH_SHORT).show();
                    }
                }
                if (fragmentView != null) {
                    updateStats();
                    updateFollowButton();
                }
            });
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (feedView != null) {
            feedView.onResume();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (feedView != null) {
            feedView.onPause();
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        generation++;
        if (feedView != null) {
            feedView.clear();
        }
    }
}
