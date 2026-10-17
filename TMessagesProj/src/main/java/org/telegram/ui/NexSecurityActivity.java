package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;

import uz.nexgramm.core.NexGhost;
import uz.nexgramm.core.NexShield;

// NexGramm: Security center (Settings → NexGramm features → Security center). One place for every layer that
// protects the account and the phone: the shield switches (strangers' media, links, screenshots), then the
// account's own locks (passcode, two-step password, active sessions), the hidden chats and ghost mode, and
// what is always on (APK check, no backups, the memory-safe sticker engine).
public class NexSecurityActivity extends BaseFragment {

    private TextInfoPrivacyCell summary;
    private TextCell passcodeCell;
    private TextCell ghostCell;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.NexShieldTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final ScrollView scrollView = new NexMotion.BounceScrollView(context);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(column, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        summary = new TextInfoPrivacyCell(context);
        column.addView(summary, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        column.addView(header(context, R.string.NexShieldLayers), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        column.addView(toggle(context, NexShield.STRANGERS, R.string.NexShieldStrangers, R.string.NexShieldStrangersInfo, true), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        column.addView(toggle(context, NexShield.LINKS, R.string.NexShieldLinks, R.string.NexShieldLinksInfo, true), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        column.addView(toggle(context, NexShield.SCREEN, R.string.NexShieldScreen, R.string.NexShieldScreenInfo, false), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        column.addView(new ShadowSectionCell(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        column.addView(header(context, R.string.NexShieldAccount), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        passcodeCell = row(context, column, v -> presentFragment(PasscodeActivity.determineOpenFragment()));
        row(context, column, v -> presentFragment(new TwoStepVerificationActivity()))
            .setTextAndIcon(LocaleController.getString(R.string.TwoStepVerification), R.drawable.msg2_permissions, true);
        row(context, column, v -> presentFragment(new SessionsActivity(0)))
            .setTextAndIcon(LocaleController.getString(R.string.SessionsTitle), R.drawable.msg2_devices, true);
        row(context, column, v -> presentFragment(new NexHiddenChatsActivity()))
            .setTextAndIcon(LocaleController.getString(R.string.NexHiddenTitle), R.drawable.msg_archive_hide, true);
        ghostCell = row(context, column, v -> presentFragment(new NexGhostActivity()));

        final TextInfoPrivacyCell always = new TextInfoPrivacyCell(context);
        always.setText(LocaleController.getString(R.string.NexShieldAlways));
        column.addView(always, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        updateRows();
        fragmentView = scrollView;
        return fragmentView;
    }

    private HeaderCell header(Context context, int text) {
        final HeaderCell header = new HeaderCell(context);
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        header.setText(LocaleController.getString(text));
        return header;
    }

    private TextCheckCell toggle(Context context, String key, int title, int info, boolean divider) {
        final TextCheckCell cell = new TextCheckCell(context);
        cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        cell.setTextAndValueAndCheck(LocaleController.getString(title), LocaleController.getString(info), NexShield.isOn(key), true, divider);
        cell.setOnClickListener(v -> {
            NexShield.set(key, !NexShield.isOn(key));
            cell.setChecked(NexShield.isOn(key));
            updateRows();
        });
        return cell;
    }

    private TextCell row(Context context, LinearLayout column, View.OnClickListener click) {
        final TextCell cell = new TextCell(context);
        cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        cell.setOnClickListener(click);
        column.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return cell;
    }

    private void updateRows() {
        if (summary == null) {
            return;
        }
        final int on = NexShield.activeCount() + (SharedConfig.passcodeHash.length() > 0 ? 1 : 0);
        summary.setText(LocaleController.formatString(R.string.NexShieldSummary, on, 4));
        passcodeCell.setTextAndValueAndIcon(LocaleController.getString(R.string.Passcode),
            LocaleController.getString(SharedConfig.passcodeHash.length() > 0 ? R.string.NexFeaturesOn : R.string.NexFeaturesOff), R.drawable.msg2_secret, true);
        ghostCell.setTextAndValueAndIcon(LocaleController.getString(R.string.NexGhostTitle),
            LocaleController.getString(NexGhost.isEnabled() ? R.string.NexFeaturesOn : R.string.NexFeaturesOff), R.drawable.ghost, false);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateRows();
    }
}
