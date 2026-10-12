package org.telegram.ui;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

// NexGramm: shared look of the Lenta screens (light palette, back button, pill buttons)
final class NexLentaUi {

    private NexLentaUi() {
    }

    static void setupActionBar(BaseFragment fragment, ActionBar actionBar, CharSequence title) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setBackgroundColor(NexLentaColors.BACKGROUND);
        actionBar.setItemsColor(NexLentaColors.TEXT, false);
        actionBar.setItemsBackgroundColor(Theme.multAlpha(NexLentaColors.TEXT, 0.1f), false);
        actionBar.setTitleColor(NexLentaColors.TEXT);
        actionBar.setCastShadows(false);
        actionBar.setTitle(title);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    fragment.finishFragment();
                }
            }
        });
    }

    // Black pill (primary) or soft lilac pill (secondary)
    static TextView pillButton(Context context, CharSequence text, boolean primary) {
        final TextView button = new TextView(context);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        button.setTypeface(AndroidUtilities.bold());
        button.setPadding(AndroidUtilities.dp(20), 0, AndroidUtilities.dp(20), 0);
        final int bg = primary ? NexLentaColors.ACCENT : NexLentaColors.SECONDARY;
        button.setTextColor(primary ? NexLentaColors.CARD : NexLentaColors.TEXT);
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(24), bg, Theme.multAlpha(bg, 0.8f)));
        button.setText(text);
        return button;
    }

    // Keeps the view's bottom above the navigation bar and the keyboard
    static void padForKeyboard(View view) {
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            final Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            final Insets bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), Math.max(ime.bottom, bars.bottom));
            return insets;
        });
    }
}
