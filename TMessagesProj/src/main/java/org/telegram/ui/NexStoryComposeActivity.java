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
    private static final int PICK_MUSIC = 7202;
    private static final int PHOTO_SECONDS = 6;

    private final Runnable onPosted;
    private Uri uri;
    private boolean video;
    private boolean posting;
    private boolean pickerShown;

    // a song over the story (optional): the file, its name, length and where it starts
    private Uri music;
    private long musicLengthMs;
    private long musicStartMs;
    private LinearLayout musicPanel;
    private TextView musicTitleView;
    private TextView musicStartView;
    private android.widget.SeekBar musicStartBar;
    private TextView musicPlayButton;
    private android.media.MediaPlayer musicPreview;
    private final Runnable stopPreview = this::stopMusicPreview;

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
        bottom.addView(createMusicPanel(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));
        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        final TextView change = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexStoryChange), NexLentaUi.PILL_CARD);
        change.setOnClickListener(v -> {
            if (!posting) {
                pick();
            }
        });
        buttons.addView(change, LayoutHelper.createLinear(0, 50, 1f));
        final TextView musicButton = NexLentaUi.pillButton(context, "🎵 " + LocaleController.getString(R.string.NexStoryMusic), NexLentaUi.PILL_CARD);
        musicButton.setOnClickListener(v -> {
            if (!posting) {
                pickMusic();
            }
        });
        buttons.addView(musicButton, LayoutHelper.createLinear(0, 50, 1f, 10, 0, 0, 0));
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

    // ---- music ----

    private View createMusicPanel(Context context) {
        musicPanel = new LinearLayout(context);
        musicPanel.setOrientation(LinearLayout.VERTICAL);
        musicPanel.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(18), 0x66000000));
        musicPanel.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
        musicPanel.setVisibility(View.GONE);
        final LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        musicPlayButton = new TextView(context);
        musicPlayButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        musicPlayButton.setTextColor(0xFFFFFFFF);
        musicPlayButton.setGravity(Gravity.CENTER);
        musicPlayButton.setText("▶");
        musicPlayButton.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(36), 0x33FFFFFF));
        musicPlayButton.setOnClickListener(v -> toggleMusicPreview());
        top.addView(musicPlayButton, LayoutHelper.createLinear(36, 36));
        musicTitleView = new TextView(context);
        musicTitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        musicTitleView.setTextColor(0xFFFFFFFF);
        musicTitleView.setSingleLine(true);
        musicTitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(musicTitleView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 6, 0));
        final ImageView remove = new ImageView(context);
        remove.setScaleType(ImageView.ScaleType.CENTER);
        remove.setImageResource(R.drawable.ic_close_white);
        remove.setOnClickListener(v -> setMusic(null, null, 0));
        top.addView(remove, LayoutHelper.createLinear(36, 36));
        musicPanel.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        musicStartView = new TextView(context);
        musicStartView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        musicStartView.setTextColor(0xB3FFFFFF);
        musicPanel.addView(musicStartView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        musicStartBar = new android.widget.SeekBar(context);
        final android.content.res.ColorStateList white = android.content.res.ColorStateList.valueOf(0xFFFFFFFF);
        musicStartBar.setProgressTintList(white);
        musicStartBar.setThumbTintList(white);
        musicStartBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                musicStartMs = progress * 1000L;
                updateMusicStart();
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
                stopMusicPreview();
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
                toggleMusicPreview(); // hear where it starts now
            }
        });
        musicPanel.addView(musicStartBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 32));
        return musicPanel;
    }

    private void pickMusic() {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, PICK_MUSIC);
        } catch (Exception e) {
            toast(R.string.NexComposeNoPicker);
        }
    }

    private void setMusic(Uri uri, String title, long lengthMs) {
        stopMusicPreview();
        music = uri;
        musicLengthMs = lengthMs;
        musicStartMs = 0;
        if (uri == null) {
            musicPanel.setVisibility(View.GONE);
            return;
        }
        musicTitleView.setText("🎵 " + title);
        musicStartBar.setMax((int) Math.max(0, (lengthMs - PixelfedMedia.MUSIC_PHOTO_MS) / 1000));
        musicStartBar.setProgress(0);
        updateMusicStart();
        if (musicPanel.getVisibility() != View.VISIBLE) {
            musicPanel.setVisibility(View.VISIBLE);
            NexMotion.enter(musicPanel, 0);
        }
        toggleMusicPreview();
    }

    private void updateMusicStart() {
        final long s = musicStartMs / 1000;
        musicStartView.setText(LocaleController.formatString(R.string.NexStoryMusicFrom, String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)));
    }

    // plays the part that goes into the story; a second tap stops it
    private void toggleMusicPreview() {
        if (musicPreview != null) {
            stopMusicPreview();
            return;
        }
        final Context context = getParentActivity();
        if (context == null || music == null) {
            return;
        }
        try {
            final android.media.MediaPlayer player = new android.media.MediaPlayer();
            player.setDataSource(context, music);
            player.setOnPreparedListener(mp -> {
                if (musicPreview != mp) {
                    return;
                }
                mp.seekTo((int) musicStartMs);
                mp.start();
                AndroidUtilities.runOnUIThread(stopPreview, PixelfedMedia.MUSIC_PHOTO_MS);
            });
            player.setOnCompletionListener(mp -> stopMusicPreview());
            musicPreview = player;
            musicPlayButton.setText("❚❚");
            player.prepareAsync();
        } catch (Exception e) {
            stopMusicPreview();
        }
    }

    private void stopMusicPreview() {
        AndroidUtilities.cancelRunOnUIThread(stopPreview);
        if (musicPreview != null) {
            try {
                musicPreview.release();
            } catch (Exception ignore) {
            }
            musicPreview = null;
        }
        if (musicPlayButton != null) {
            musicPlayButton.setText("▶");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        stopMusicPreview();
    }

    @Override
    public void onFragmentDestroy() {
        stopMusicPreview();
        super.onFragmentDestroy();
    }

    // the song's name ("Artist — Title", else the file name) and length
    private void readMusic(Uri uri) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        CoreClient.EXECUTOR.execute(() -> {
            String title = null;
            long length = 0;
            final android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            try {
                retriever.setDataSource(context, uri);
                final String t = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE);
                final String a = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST);
                final String d = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (t != null && !t.isEmpty()) {
                    title = a != null && !a.isEmpty() ? a + " — " + t : t;
                }
                length = d == null ? 0 : Long.parseLong(d);
            } catch (Exception ignore) {
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignore) {
                }
            }
            if (title == null) {
                try (android.database.Cursor c = context.getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        title = c.getString(0);
                    }
                } catch (Exception ignore) {
                }
            }
            final String finalTitle = title == null ? "" : title;
            final long finalLength = length;
            AndroidUtilities.runOnUIThread(() -> {
                if (fragmentView == null) {
                    return;
                }
                if (finalLength <= 0) {
                    toast(R.string.NexStoryMusicBad);
                    return;
                }
                setMusic(uri, finalTitle, finalLength);
            });
        });
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode == PICK_MUSIC) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                readMusic(data.getData());
            }
            return;
        }
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
        stopMusicPreview();
        shareButton.setAlpha(0.5f);
        statusView.setText(LocaleController.getString(R.string.NexStoryPosting));
        final long userId = getUserConfig().getClientUserId();
        final Uri picked = uri;
        final boolean isVideo = video;
        final Uri song = music;
        final long songStart = musicStartMs;
        CoreClient.EXECUTOR.execute(() -> {
            File file = null;
            int error = 0;
            try {
                final int[] duration = {PHOTO_SECONDS};
                final boolean asVideo = isVideo || song != null; // a photo with a song becomes a video
                if (song != null) {
                    file = PixelfedMedia.prepareStoryWithMusic(picked, isVideo, song, songStart, duration, fraction -> AndroidUtilities.runOnUIThread(() ->
                        status(LocaleController.formatString(R.string.NexCoverPreparing, (int) (fraction * 100)))));
                } else if (isVideo) {
                    file = PixelfedMedia.prepareStoryVideo(picked, duration, fraction -> AndroidUtilities.runOnUIThread(() ->
                        status(LocaleController.formatString(R.string.NexCoverPreparing, (int) (fraction * 100)))));
                } else {
                    file = PixelfedMedia.prepareImage(picked);
                }
                AndroidUtilities.runOnUIThread(() -> status(LocaleController.getString(R.string.NexStoryPosting)));
                PixelfedClient.postStoryBlocking(userId, file, asVideo ? PixelfedMedia.VIDEO_MIME : PixelfedMedia.IMAGE_MIME, duration[0],
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
