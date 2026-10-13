package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Gifts.ProfileGiftsContainer;

// NexGramm: the user's Telegram gifts (from the Profile tab), Telegram's own gifts list on a page of its own
public class NexGiftsActivity extends BaseFragment {

    @Override
    public View createView(Context context) {
        NexLentaUi.setupActionBar(this, actionBar, LocaleController.getString(R.string.ProfileGifts));
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(NexLentaColors.BACKGROUND);
        final ProfileGiftsContainer gifts = new ProfileGiftsContainer(this, context, currentAccount, getUserConfig().getClientUserId(), null);
        root.addView(gifts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;
        return fragmentView;
    }

    @Override
    public boolean isLightStatusBar() {
        return !NexLentaColors.dark;
    }
}
