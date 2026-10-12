package org.telegram.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.net.Uri;
import android.text.InputFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.io.File;
import java.util.ArrayList;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.PixelfedClient;
import uz.nexgramm.core.PixelfedMedia;
import uz.nexgramm.core.PixelfedStatus;

// NexGramm: new Lenta post. The system gallery picker opens at once (photos: up to 4 = album; or one
// video). "Joylash": each file is prepared on the device (PixelfedMedia), sent to POST /api/v1/media,
// then POST /api/v1/statuses (caption, media_ids[], public). Progress on screen; on error "Qayta urinish"
// continues from the first file not yet uploaded. URIs, files and tokens are never logged.
public class NexComposeActivity extends BaseFragment {

    public interface Listener {
        void onPosted(PixelfedStatus status);
    }

    private static final int PICK_REQUEST = 0x4E47;
    private static final int THUMB_DP = 96;

    private final Listener listener;
    private final ArrayList<Uri> picked = new ArrayList<>();
    private boolean pickedVideo;
    private final ArrayList<String> uploadedIds = new ArrayList<>();
    private boolean pickerShown;
    private boolean publishing;
    private int generation;

    private LinearLayout thumbsRow;
    private TextView emptyHint;
    private EditText captionView;
    private TextView statusView;
    private ProgressBar progressBar;
    private TextView pickButton;
    private TextView publishButton;

    public NexComposeActivity(Listener listener) {
        this.listener = listener;
    }

    private long tgUserId() {
        return getUserConfig().getClientUserId();
    }

    @Override
    public boolean isLightStatusBar() {
        return !NexLentaColors.dark;
    }

    @Override
    public View createView(Context context) {
        NexLentaUi.setupActionBar(this, actionBar, LocaleController.getString(R.string.NexComposeTitle));

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(NexLentaColors.BACKGROUND);
        final ScrollView scroll = new ScrollView(context);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(24));
        scroll.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // Chosen media
        final LinearLayout mediaCard = card(context);
        final HorizontalScrollView thumbsScroll = new HorizontalScrollView(context);
        thumbsScroll.setHorizontalScrollBarEnabled(false);
        thumbsRow = new LinearLayout(context);
        thumbsRow.setOrientation(LinearLayout.HORIZONTAL);
        thumbsScroll.addView(thumbsRow, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, THUMB_DP));
        mediaCard.addView(thumbsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, THUMB_DP));
        emptyHint = new TextView(context);
        emptyHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        emptyHint.setTextColor(NexLentaColors.SECONDARY_TEXT);
        emptyHint.setText(LocaleController.getString(R.string.NexComposeEmpty));
        mediaCard.addView(emptyHint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        pickButton = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexComposePick), false);
        pickButton.setOnClickListener(v -> openPicker());
        mediaCard.addView(pickButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 12, 0, 0));
        content.addView(mediaCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Caption
        final LinearLayout captionCard = card(context);
        captionView = new EditText(context);
        captionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        captionView.setTextColor(NexLentaColors.TEXT);
        captionView.setHintTextColor(NexLentaColors.SECONDARY_TEXT);
        captionView.setHint(LocaleController.getString(R.string.NexComposeCaptionHint));
        captionView.setBackground(null);
        captionView.setPadding(0, 0, 0, 0);
        captionView.setMinLines(3);
        captionView.setGravity(Gravity.TOP | Gravity.START);
        captionView.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});
        captionCard.addView(captionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        content.addView(captionCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        // Progress / error
        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        statusView.setGravity(Gravity.CENTER_HORIZONTAL);
        statusView.setVisibility(View.GONE);
        content.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 14, 4, 0));
        progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setVisibility(View.GONE);
        content.addView(progressBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8, 4, 8, 4, 0));

        publishButton = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexComposePublish), NexLentaUi.PILL_PRIMARY);
        publishButton.setOnClickListener(v -> publish());
        content.addView(publishButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 16, 0, 0));

        NexLentaUi.padForKeyboard(root);
        fragmentView = root;
        updateMediaUi();
        return fragmentView;
    }

    private static LinearLayout card(Context context) {
        final LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14));
        card.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(20), NexLentaColors.CARD));
        return card;
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (isOpen && !backward && !pickerShown) {
            pickerShown = true;
            openPicker();
        }
    }

    private void openPicker() {
        if (publishing) {
            return;
        }
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, PICK_REQUEST);
        } catch (Exception e) {
            toast(R.string.NexComposeNoPicker);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != PICK_REQUEST || resultCode != Activity.RESULT_OK || data == null || getParentActivity() == null) {
            return;
        }
        final ArrayList<Uri> uris = new ArrayList<>();
        final ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                final Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            return;
        }
        // One video, or up to 4 photos (an album); a video is never mixed with photos
        Uri video = null;
        final ArrayList<Uri> images = new ArrayList<>();
        for (Uri uri : uris) {
            if (PixelfedMedia.isVideo(getParentActivity(), uri)) {
                if (video == null) {
                    video = uri;
                }
            } else {
                images.add(uri);
            }
        }
        picked.clear();
        uploadedIds.clear();
        if (video != null) {
            pickedVideo = true;
            picked.add(video);
            if (uris.size() > 1) {
                toast(R.string.NexComposeOneVideo);
            }
        } else {
            pickedVideo = false;
            for (int i = 0; i < images.size() && i < PixelfedMedia.MAX_IMAGES; i++) {
                picked.add(images.get(i));
            }
            if (images.size() > PixelfedMedia.MAX_IMAGES) {
                toast(R.string.NexComposeTooManyImages);
            }
        }
        setStatus(null, -1);
        publishButton.setText(LocaleController.getString(R.string.NexComposePublish));
        updateMediaUi();
    }

    private void updateMediaUi() {
        if (thumbsRow == null) {
            return;
        }
        thumbsRow.removeAllViews();
        emptyHint.setVisibility(picked.isEmpty() ? View.VISIBLE : View.GONE);
        ((View) thumbsRow.getParent()).setVisibility(picked.isEmpty() ? View.GONE : View.VISIBLE);
        final int gen = ++generation;
        final int sizePx = AndroidUtilities.dp(THUMB_DP);
        for (int i = 0; i < picked.size(); i++) {
            final Uri uri = picked.get(i);
            final FrameLayout frame = new FrameLayout(getParentActivity());
            final ImageView image = new ImageView(getParentActivity());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(NexLentaColors.PLACEHOLDER);
            frame.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            if (pickedVideo) {
                final TextView badge = NexPostCell.createPill(getParentActivity());
                badge.setText("▶");
                frame.addView(badge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT, 6, 0, 0, 6));
            }
            frame.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AndroidUtilities.dp(12));
                }
            });
            frame.setClipToOutline(true);
            thumbsRow.addView(frame, LayoutHelper.createLinear(THUMB_DP, THUMB_DP, 0, 0, 8, 0));
            final boolean video = pickedVideo;
            CoreClient.EXECUTOR.execute(() -> {
                final Bitmap bitmap = PixelfedMedia.thumbnail(uri, video, sizePx);
                AndroidUtilities.runOnUIThread(() -> {
                    if (gen == generation && bitmap != null) {
                        image.setImageBitmap(bitmap);
                    }
                });
            });
        }
    }

    // text null = hide; progress < 0 = no bar, 0..1 = determinate
    private void setStatus(String text, float progress) {
        if (statusView == null) {
            return;
        }
        statusView.setText(text);
        statusView.setVisibility(text == null ? View.GONE : View.VISIBLE);
        progressBar.setVisibility(progress < 0 ? View.GONE : View.VISIBLE);
        if (progress >= 0) {
            progressBar.setProgress((int) (progress * 1000));
        }
    }

    private void toast(int resId) {
        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), LocaleController.getString(resId), Toast.LENGTH_LONG).show();
        }
    }

    private void postStatus(String text, float progress) {
        AndroidUtilities.runOnUIThread(() -> {
            if (publishing) {
                setStatus(text, progress);
            }
        });
    }

    private void publish() {
        if (publishing) {
            return;
        }
        if (picked.isEmpty()) {
            toast(R.string.NexComposeEmpty);
            return;
        }
        publishing = true;
        statusView.setTextColor(NexLentaColors.SECONDARY_TEXT);
        pickButton.setEnabled(false);
        publishButton.setEnabled(false);
        publishButton.setAlpha(0.6f);
        captionView.setEnabled(false);
        AndroidUtilities.hideKeyboard(captionView);
        final long userId = tgUserId();
        final ArrayList<Uri> items = new ArrayList<>(picked);
        final boolean video = pickedVideo;
        final String caption = captionView.getText().toString().trim();
        final int total = items.size();
        CoreClient.EXECUTOR.execute(() -> {
            PixelfedStatus posted = null;
            String error = null;
            boolean notConnected = false;
            try {
                for (int i = 0; i < total; i++) {
                    synchronized (uploadedIds) {
                        if (i < uploadedIds.size()) {
                            continue; // uploaded before a retry
                        }
                    }
                    final int number = i + 1;
                    final File file;
                    if (video) {
                        postStatus(LocaleController.formatString(R.string.NexComposeCompressing, 0), 0);
                        file = PixelfedMedia.prepareVideo(items.get(i), fraction ->
                            postStatus(LocaleController.formatString(R.string.NexComposeCompressing, (int) (fraction * 100)), fraction));
                    } else {
                        postStatus(LocaleController.formatString(R.string.NexComposePreparing, number, total), -1);
                        file = PixelfedMedia.prepareImage(items.get(i));
                    }
                    try {
                        final String id = PixelfedClient.uploadMediaBlocking(userId, file, video ? PixelfedMedia.VIDEO_MIME : PixelfedMedia.IMAGE_MIME,
                            (sent, all) -> {
                                final float fraction = all > 0 ? sent / (float) all : 0;
                                postStatus(LocaleController.formatString(R.string.NexComposeUploading, number, total, (int) (fraction * 100)), fraction);
                            });
                        synchronized (uploadedIds) {
                            uploadedIds.add(id);
                        }
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                }
                postStatus(LocaleController.getString(R.string.NexComposePosting), -1);
                final ArrayList<String> ids;
                synchronized (uploadedIds) {
                    ids = new ArrayList<>(uploadedIds);
                }
                posted = PixelfedClient.createStatusBlocking(userId, caption, ids, null);
                PixelfedMedia.clearCache();
            } catch (PixelfedMedia.TooLargeException e) {
                error = LocaleController.getString(R.string.NexComposeVideoTooLarge);
            } catch (PixelfedMedia.VideoException e) {
                error = LocaleController.getString(R.string.NexComposeVideoFailed);
            } catch (PixelfedClient.NotConnectedException e) {
                notConnected = true;
                error = LocaleController.getString(R.string.NexComposeNotConnected);
            } catch (PixelfedClient.HttpException e) {
                error = e.code == 413 || e.code == 415 || e.code == 422
                    ? LocaleController.formatString(R.string.NexComposeFileRejected, e.reason())
                    : LocaleController.getString(R.string.NexComposeUploadError) + "\n(" + e.reason() + ")";
            } catch (Throwable e) {
                error = LocaleController.getString(R.string.NexComposeUploadError);
            }
            final PixelfedStatus result = posted;
            final String finalError = error;
            final boolean finalNotConnected = notConnected;
            AndroidUtilities.runOnUIThread(() -> onPublishDone(result, finalError, finalNotConnected));
        });
    }

    private void onPublishDone(PixelfedStatus status, String error, boolean notConnected) {
        publishing = false;
        if (fragmentView == null) {
            return;
        }
        pickButton.setEnabled(true);
        publishButton.setEnabled(true);
        publishButton.setAlpha(1f);
        captionView.setEnabled(true);
        if (status != null) {
            toast(R.string.NexComposeDone);
            if (listener != null) {
                listener.onPosted(status);
            }
            finishFragment();
            return;
        }
        setStatus(TextUtils.isEmpty(error) ? LocaleController.getString(R.string.NexComposeUploadError) : error, -1);
        statusView.setTextColor(NexLentaColors.ERROR);
        publishButton.setText(LocaleController.getString(notConnected ? R.string.NexComposePublish : R.string.NexFeedLoginRetry));
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        generation++;
        if (!publishing) {
            CoreClient.EXECUTOR.execute(PixelfedMedia::clearCache);
        }
    }
}
