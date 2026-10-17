package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
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
import uz.nexgramm.core.NexSaved;

// NexGramm: the NexGramm features hub (Settings → NexGramm features): the security center, ghost mode, keeping messages others
// delete or edit, and the list of those messages
public class NexFeaturesActivity extends BaseFragment {

    private TextCell ghostCell;
    private TextCell shieldCell;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.NexFeaturesTitle));
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

        shieldCell = new TextCell(context);
        shieldCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        shieldCell.setOnClickListener(v -> presentFragment(new NexSecurityActivity()));
        column.addView(shieldCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        ghostCell = new TextCell(context);
        ghostCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        ghostCell.setOnClickListener(v -> presentFragment(new NexGhostActivity()));
        column.addView(ghostCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final TextCell hiddenCell = new TextCell(context);
        hiddenCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        hiddenCell.setTextAndIcon(LocaleController.getString(R.string.NexHiddenTitle), R.drawable.msg_archive_hide, false);
        hiddenCell.setOnClickListener(v -> presentFragment(new NexHiddenChatsActivity()));
        column.addView(hiddenCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        column.addView(new ShadowSectionCell(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final HeaderCell header = new HeaderCell(context);
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        header.setText(LocaleController.getString(R.string.NexFeaturesMessages));
        column.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextCheckCell deletedCell = new TextCheckCell(context);
        deletedCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        deletedCell.setTextAndValueAndCheck(LocaleController.getString(R.string.NexFeaturesKeepDeleted),
            LocaleController.getString(R.string.NexFeaturesKeepDeletedInfo), NexSaved.keepDeleted(), true, true);
        deletedCell.setOnClickListener(v -> {
            if (!NexSaved.keepDeleted() && !NexPremiumActivity.check(this)) {
                return; // NexGramm Premium
            }
            NexSaved.setKeepDeleted(!NexSaved.keepDeleted());
            deletedCell.setChecked(NexSaved.keepDeleted());
        });
        column.addView(deletedCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextCheckCell editsCell = new TextCheckCell(context);
        editsCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        editsCell.setTextAndValueAndCheck(LocaleController.getString(R.string.NexFeaturesKeepEdits),
            LocaleController.getString(R.string.NexFeaturesKeepEditsInfo), NexSaved.keepEdits(), true, true);
        editsCell.setOnClickListener(v -> {
            if (!NexSaved.keepEdits() && !NexPremiumActivity.check(this)) {
                return; // NexGramm Premium
            }
            NexSaved.setKeepEdits(!NexSaved.keepEdits());
            editsCell.setChecked(NexSaved.keepEdits());
        });
        column.addView(editsCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextCell listCell = new TextCell(context);
        listCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        listCell.setTextAndIcon(LocaleController.getString(R.string.NexSavedTitle), R.drawable.msg_delete, false);
        listCell.setOnClickListener(v -> presentFragment(new NexSavedActivity(0)));
        column.addView(listCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText(LocaleController.getString(R.string.NexFeaturesMessagesNote));
        column.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final HeaderCell comfort = new HeaderCell(context);
        comfort.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        comfort.setText(LocaleController.getString(R.string.NexFeaturesComfort));
        column.addView(comfort, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final TextCheckCell callsCell = new TextCheckCell(context);
        callsCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        callsCell.setTextAndValueAndCheck(LocaleController.getString(R.string.NexFeaturesConfirmCalls),
            LocaleController.getString(R.string.NexFeaturesConfirmCallsInfo), uz.nexgramm.core.NexExtras.confirmCalls(), true, true);
        callsCell.setOnClickListener(v -> {
            uz.nexgramm.core.NexExtras.setConfirmCalls(!uz.nexgramm.core.NexExtras.confirmCalls());
            callsCell.setChecked(uz.nexgramm.core.NexExtras.confirmCalls());
        });
        column.addView(callsCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final TextCheckCell linkCell = new TextCheckCell(context);
        linkCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        linkCell.setTextAndValueAndCheck(LocaleController.getString(R.string.NexFeaturesLinkMedia),
            LocaleController.getString(R.string.NexFeaturesLinkMediaInfo), NexLinkMedia.enabled(), true, false);
        linkCell.setOnClickListener(v -> {
            NexLinkMedia.setEnabled(!NexLinkMedia.enabled());
            linkCell.setChecked(NexLinkMedia.enabled());
        });
        column.addView(linkCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final TextInfoPrivacyCell comfortInfo = new TextInfoPrivacyCell(context);
        comfortInfo.setText(LocaleController.getString(R.string.NexFeaturesComfortNote));
        column.addView(comfortInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        fragmentView = scrollView;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (shieldCell != null) {
            shieldCell.setTextAndValueAndIcon(LocaleController.getString(R.string.NexShieldTitle),
                uz.nexgramm.core.NexShield.activeCount() + " / 3", R.drawable.outline_shield_check, true);
        }
        if (ghostCell != null) {
            ghostCell.setTextAndValueAndIcon(LocaleController.getString(R.string.NexGhostTitle),
                LocaleController.getString(NexGhost.isEnabled() ? R.string.NexFeaturesOn : R.string.NexFeaturesOff), R.drawable.ghost, true);
        }
    }
}
