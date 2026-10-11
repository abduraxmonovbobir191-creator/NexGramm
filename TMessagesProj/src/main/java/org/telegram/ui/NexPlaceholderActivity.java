package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

// NexGramm: empty "coming soon" screen for the NEX AI and Feed main tabs.
public class NexPlaceholderActivity extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {

    public static final int TYPE_NEX_AI = 0;
    public static final int TYPE_FEED = 1;

    public static NexPlaceholderActivity create(int type) {
        final Bundle args = new Bundle();
        args.putInt("type", type);
        args.putBoolean("hasMainTabs", true);
        return new NexPlaceholderActivity(args);
    }

    public NexPlaceholderActivity(Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        final boolean isNexAi = arguments == null || arguments.getInt("type", TYPE_NEX_AI) == TYPE_NEX_AI;
        actionBar.setAddToContainer(false);

        final FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        if (isNexAi) {
            final TextView starView = new TextView(context);
            starView.setText("✦");
            starView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 56);
            starView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            starView.setGravity(Gravity.CENTER);
            layout.addView(starView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 8));
        }

        final TextView textView = new TextView(context);
        textView.setText(LocaleController.getString(isNexAi ? R.string.NexAiSoon : R.string.NexFeedSoon));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        textView.setTypeface(AndroidUtilities.bold());
        textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        textView.setGravity(Gravity.CENTER);
        layout.addView(textView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

        frameLayout.addView(layout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 0, 24, 0));
        fragmentView = frameLayout;
        return fragmentView;
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }
}
