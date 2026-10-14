package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
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

import java.io.File;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.PixelfedClient;
import uz.nexgramm.core.PixelfedMedia;

// NexGramm: a new Lenta story. The gallery opens at once (photo or video); the pick shows full screen with
// "Share to story". Photos go as JPEG (≤ 2048 px), videos as the first 30 s (≤ 720p, ≤ 15 MB), then
// POST /api/v1.1/stories/add + publish. The story lives 24 h.
public class NexStoryComposeActivity extends BaseFragment {

    private static final int PICK = 7201;
    private static final int PHOTO_SECONDS = 6;

    private final Runnable onPosted;
    private Uri uri;
    private boolean video;
    private boolean posting;
    private boolean pickerShown;

    private BackupImageView previewView;
    private TextView shareButton;
    private TextView statusView;

    public NexStoryComposeActivity(Runnable onPosted) {
        this.onPosted = onPosted;
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0xFF000000);

        previewView = new BackupImageView(context);
        previewView.getImageReceiver().setAspectFit(true);
        previewView.setOnClickListener(v -> pick());
        root.addView(previewView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final ImageView close = new ImageView(context);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setImageResource(R.drawable.ic_close_white);
        close.setBackground(Theme.createSelectorDrawable(0x33FFFFFF, Theme.RIPPLE_MASK_CIRCLE_20DP));
        close.setOnClickListener(v -> {
            if (!posting) {
                finishFragment();
            }
        });
        root.addView(close, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.TOP, 8, AndroidUtilities.statusBarHeight / AndroidUtilities.density + 6, 0, 0));

        final LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x99000000, 0x00000000}));
        bottom.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(40), AndroidUtilities.dp(16), AndroidUtilities.navigationBarHeight + AndroidUtilities.dp(16));
        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusView.setTextColor(0xE6FFFFFF);
        statusView.setGravity(Gravity.CENTER);
        statusView.setText(LocaleController.getString(R.string.NexStoryHint));
        bottom.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));
        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        final TextView change = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexStoryChange), NexLentaUi.PILL_CARD);
        change.setOnClickListener(v -> {
            if (!posting) {
                pick();
            }
        });
        buttons.addView(change, LayoutHelper.createLinear(0, 50, 1f));
        shareButton = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexStoryShare), NexLentaUi.PILL_PRIMARY);
        shareButton.setOnClickListener(v -> post());
        shareButton.setEnabled(false);
        shareButton.setAlpha(0.5f);
        buttons.addView(shareButton, LayoutHelper.createLinear(0, 50, 1.4f, 10, 0, 0, 0));
        bottom.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(bottom, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        fragmentView = root;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!pickerShown) {
            pickerShown = true;
            AndroidUtilities.runOnUIThread(this::pick, 150);
        }
    }

    private void pick() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, PICK);
        } catch (Exception e) {
            toast(R.string.NexComposeNoPicker);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != PICK) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            if (uri == null) {
                finishFragment(); // nothing picked: back to the Lenta
            }
            return;
        }
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        uri = data.getData();
        video = PixelfedMedia.isVideo(context, uri);
        shareButton.setEnabled(true);
        shareButton.setAlpha(1f);
        statusView.setText(LocaleController.getString(video ? R.string.NexStoryVideoHint : R.string.NexStoryHint));
        final Uri picked = uri;
        final boolean isVideo = video;
        final int size = AndroidUtilities.displaySize.y;
        CoreClient.EXECUTOR.execute(() -> {
            final Bitmap preview = PixelfedMedia.thumbnail(picked, isVideo, size);
            AndroidUtilities.runOnUIThread(() -> {
                if (fragmentView != null && picked == uri) {
                    previewView.setImageBitmap(preview);
                }
            });
        });
    }

    private void post() {
        if (posting || uri == null) {
            return;
        }
        posting = true;
        shareButton.setAlpha(0.5f);
        statusView.setText(LocaleController.getString(R.string.NexStoryPosting));
        final long userId = getUserConfig().getClientUserId();
        final Uri picked = uri;
        final boolean isVideo = video;
        CoreClient.EXECUTOR.execute(() -> {
            File file = null;
            int error = 0;
            try {
                final int[] duration = {PHOTO_SECONDS};
                if (isVideo) {
                    file = PixelfedMedia.prepareStoryVideo(picked, duration, fraction -> AndroidUtilities.runOnUIThread(() ->
                        status(LocaleController.formatString(R.string.NexCoverPreparing, (int) (fraction * 100)))));
                } else {
                    file = PixelfedMedia.prepareImage(picked);
                }
                AndroidUtilities.runOnUIThread(() -> status(LocaleController.getString(R.string.NexStoryPosting)));
                PixelfedClient.postStoryBlocking(userId, file, isVideo ? PixelfedMedia.VIDEO_MIME : PixelfedMedia.IMAGE_MIME, duration[0],
                    (sent, total) -> {
                        if (total > 0) {
                            AndroidUtilities.runOnUIThread(() -> status(LocaleController.getString(R.string.NexStoryPosting) + " " + (int) (100 * sent / total) + "%"));
                        }
                    });
            } catch (PixelfedClient.StoriesOffException e) {
                error = R.string.NexStoryOff;
            } catch (PixelfedClient.StoriesLimitException e) {
                error = R.string.NexStoryLimit;
            } catch (PixelfedMedia.TooLargeException e) {
                error = R.string.NexCoverTooLarge;
            } catch (PixelfedMedia.VideoException e) {
                error = R.string.NexComposeVideoFailed;
            } catch (Exception e) {
                error = R.string.NexActionFailed;
            } finally {
                if (file != null) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
            final int finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                posting = false;
                if (fragmentView == null) {
                    return;
                }
                if (finalError != 0) {
                    shareButton.setAlpha(1f);
                    statusView.setText(LocaleController.getString(finalError));
                    toast(finalError);
                    return;
                }
                toast(R.string.NexStoryPosted);
                if (onPosted != null) {
                    onPosted.run();
                }
                finishFragment();
            });
        });
    }

    private void status(String text) {
        if (statusView != null && posting) {
            statusView.setText(text);
        }
    }

    private void toast(int res) {
        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), LocaleController.getString(res), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        return !posting;
    }
}
