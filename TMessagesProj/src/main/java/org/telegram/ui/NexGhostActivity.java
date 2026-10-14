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
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

import uz.nexgramm.core.NexGhost;

// NexGramm: ghost mode settings — the master switch and its five parts (see NexGhost)
public class NexGhostActivity extends BaseFragment {

    private TextCheckCell masterCell;
    private final ArrayList<TextCheckCell> partCells = new ArrayList<>();

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.NexGhostTitle));
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

        masterCell = new TextCheckCell(context);
        masterCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        masterCell.setTextAndValueAndCheck(LocaleController.getString(R.string.NexGhostMaster),
            LocaleController.getString(R.string.NexGhostMasterInfo), NexGhost.isEnabled(), true, false);
        masterCell.setOnClickListener(v -> {
            NexGhost.setEnabled(!NexGhost.isEnabled());
            masterCell.setChecked(NexGhost.isEnabled());
            updateParts();
        });
        column.addView(masterCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        column.addView(new ShadowSectionCell(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final HeaderCell header = new HeaderCell(context);
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        header.setText(LocaleController.getString(R.string.NexGhostWhat));
        column.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        addPart(context, column, NexGhost.ONLINE, R.string.NexGhostOnline, R.string.NexGhostOnlineInfo, true);
        addPart(context, column, NexGhost.READ, R.string.NexGhostRead, R.string.NexGhostReadInfo, true);
        addPart(context, column, NexGhost.READ_ON_REPLY, R.string.NexGhostReadOnReply, R.string.NexGhostReadOnReplyInfo, true);
        addPart(context, column, NexGhost.TYPING, R.string.NexGhostTyping, R.string.NexGhostTypingInfo, true);
        addPart(context, column, NexGhost.STORIES, R.string.NexGhostStories, R.string.NexGhostStoriesInfo, true);
        addPart(context, column, NexGhost.LISTENED, R.string.NexGhostListened, R.string.NexGhostListenedInfo, false);

        final TextInfoPrivacyCell info = new TextInfoPrivacyCell(context);
        info.setText(LocaleController.getString(R.string.NexGhostNote));
        column.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        updateParts();
        fragmentView = scrollView;
        return fragmentView;
    }

    private void addPart(Context context, LinearLayout column, String part, int title, int value, boolean divider) {
        final TextCheckCell cell = new TextCheckCell(context);
        cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        cell.setTextAndValueAndCheck(LocaleController.getString(title), LocaleController.getString(value), NexGhost.get(part), true, divider);
        cell.setTag(part);
        cell.setOnClickListener(v -> {
            if (!NexGhost.isEnabled()) {
                return;
            }
            NexGhost.set(part, !NexGhost.get(part));
            cell.setChecked(NexGhost.get(part));
        });
        partCells.add(cell);
        column.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    // the parts are greyed out while ghost mode is off
    private void updateParts() {
        final boolean on = NexGhost.isEnabled();
        for (TextCheckCell cell : partCells) {
            cell.setAlpha(on ? 1f : 0.45f);
        }
    }
}
