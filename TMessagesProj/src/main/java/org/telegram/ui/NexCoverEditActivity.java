package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

import java.io.File;

import uz.nexgramm.core.CoreClient;
import uz.nexgramm.core.NexCover;
import uz.nexgramm.core.PixelfedAccount;
import uz.nexgramm.core.PixelfedMedia;

// NexGramm: "Muqova" — the profile cover editor of the design: live preview card (cover, avatar, name),
// tabs Rasm / Video / Rang + emoji, color circles (⊘ = none), "Emoji naqsh" switch and glyphs, "Saqlash".
// Photo / video are prepared on the device (PixelfedMedia: ≤ 1600 px; first 6 s muted ≤ 3 MB) and sent to
// the core (PUT /v1/covers); color / emoji is a small JSON; nothing chosen = DELETE (default band).
public class NexCoverEditActivity extends BaseFragment {

    public interface Callback {
        void onSaved(NexCover cover);
    }

    private static final int TAB_PHOTO = 0;
    private static final int TAB_VIDEO = 1;
    private static final int TAB_COLOR = 2;
    private static final int PICK_PHOTO = 7101;
    private static final int PICK_VIDEO = 7102;

    // who the preview shows, and the cache key the saved cover is kept under
    private final String displayName;
    private final String handle;
    private final String avatarUrl;
    private final org.telegram.tgnet.TLRPC.User telegramUser;
    private final String cacheKey;
    private final Callback callback;
    private int tab = TAB_COLOR;
    private Integer color;
    private boolean pattern;
    private String glyph = NexCover.GLYPHS[0];
    private Uri photoUri;
    private Uri videoUri;
    private Bitmap pickedPreview;
    private final NexCover current;
    private boolean saving;

    private NexCoverView coverView;
    private LinearLayout tabsView;
    private LinearLayout optionsView;
    private TextView saveButton;

    // From the Lenta profile
    public NexCoverEditActivity(PixelfedAccount account, NexCover current, Callback callback) {
        this(account.displayName, account.username, account.avatarUrl, null, account.username, current, callback);
    }

    // From the Profile tab: the Telegram name and photo; the cover is the same NexGramm cover
    public NexCoverEditActivity(org.telegram.tgnet.TLRPC.User user, String cacheKey, NexCover current, Callback callback) {
        this(org.telegram.messenger.UserObject.getUserName(user), org.telegram.messenger.UserObject.getPublicUsername(user), null, user, cacheKey, current, callback);
    }

    private NexCoverEditActivity(String displayName, String handle, String avatarUrl, org.telegram.tgnet.TLRPC.User telegramUser,
                                 String cacheKey, NexCover current, Callback callback) {
        this.displayName = displayName == null ? "" : displayName;
        this.handle = handle;
        this.avatarUrl = avatarUrl;
        this.telegramUser = telegramUser;
        this.cacheKey = cacheKey;
        this.current = current;
        this.callback = callback;
        if (current != null) {
            if (NexCover.MODE_PHOTO.equals(current.mode)) {
                tab = TAB_PHOTO;
            } else if (NexCover.MODE_VIDEO.equals(current.mode)) {
                tab = TAB_VIDEO;
            } else {
                color = current.color;
                pattern = current.emojiGlyph != null;
                if (current.emojiGlyph != null) {
                    glyph = current.emojiGlyph;
                }
            }
        }
    }

    @Override
    public boolean isLightStatusBar() {
        return !NexLentaColors.dark;
    }

    @Override
    public View createView(Context context) {
        NexLentaUi.setupActionBar(this, actionBar, null);
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(NexLentaColors.BACKGROUND);

        final ScrollView scrollView = new ScrollView(context);
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), AndroidUtilities.dp(120));
        scrollView.addView(content);
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final TextView title = NexLentaUi.serifTitle(context, 42);
        title.setText(LocaleController.getString(R.string.NexCoverTitle));
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 0, 0, 16));

        content.addView(createPreview(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        tabsView = new LinearLayout(context);
        tabsView.setOrientation(LinearLayout.HORIZONTAL);
        tabsView.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 26));
        tabsView.setPadding(AndroidUtilities.dp(5), AndroidUtilities.dp(5), AndroidUtilities.dp(5), AndroidUtilities.dp(5));
        content.addView(tabsView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 0, 16, 0, 0));

        optionsView = new LinearLayout(context);
        optionsView.setOrientation(LinearLayout.VERTICAL);
        content.addView(optionsView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 18, 0, 0));

        final LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            new int[]{NexLentaColors.BACKGROUND, NexLentaColors.BACKGROUND, NexLentaColors.BACKGROUND & 0x00FFFFFF}));
        bottom.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        final TextView visible = new TextView(context);
        visible.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        visible.setTextColor(NexLentaColors.SECONDARY_TEXT);
        visible.setGravity(Gravity.CENTER);
        visible.setText(LocaleController.getString(R.string.NexCoverVisible));
        bottom.addView(visible, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
        saveButton = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexCoverSave), NexLentaUi.PILL_PRIMARY);
        saveButton.setOnClickListener(v -> save());
        bottom.addView(saveButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
        root.addView(bottom, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        NexLentaUi.padForKeyboard(root);

        fragmentView = root;
        rebuild();
        return fragmentView;
    }

    // Card: cover, avatar overlapping it, serif name and @username (as other people will see it)
    private View createPreview(Context context) {
        final FrameLayout card = new FrameLayout(context);
        card.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 28));
        card.setClipToOutline(true);
        card.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AndroidUtilities.dp(28));
            }
        });
        coverView = new NexCoverView(context, false);
        coverView.setCover(current);
        card.addView(coverView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 150, Gravity.TOP));

        final FrameLayout avatarFrame = new FrameLayout(context);
        final GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(NexLentaColors.CARD);
        avatarFrame.setBackground(ring);
        avatarFrame.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));
        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(AndroidUtilities.dp(60));
        if (telegramUser != null) {
            avatar.setForUserOrChat(telegramUser, new org.telegram.ui.Components.AvatarDrawable(telegramUser));
        } else if (avatarUrl == null || avatarUrl.contains("/avatars/default")) {
            avatar.setImageDrawable(NexLentaUi.initialsAvatar(displayName, true));
        } else {
            avatar.setImage(avatarUrl, "120_120", NexLentaUi.initialsAvatar(displayName, true));
        }
        avatarFrame.addView(avatar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        card.addView(avatarFrame, LayoutHelper.createFrame(88, 88, Gravity.TOP | Gravity.LEFT, 18, 106, 0, 0));

        final TextView name = NexLentaUi.serifTitle(context, 30);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setText(displayName);
        card.addView(name, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 120, 158, 16, 0));
        final TextView username = new TextView(context);
        username.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        username.setTextColor(NexLentaColors.SECONDARY_TEXT);
        username.setSingleLine(true);
        username.setText(handle == null ? "" : "@" + handle);
        card.addView(username, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 120, 196, 16, 18));
        return card;
    }

    private void rebuild() {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        tabsView.removeAllViews();
        final int[] names = {R.string.NexCoverTabPhoto, R.string.NexCoverTabVideo, R.string.NexCoverTabColor};
        for (int i = 0; i < names.length; i++) {
            final int t = i;
            final TextView option = new TextView(context);
            option.setGravity(Gravity.CENTER);
            option.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            option.setTypeface(AndroidUtilities.bold());
            option.setSingleLine(true);
            option.setText(LocaleController.getString(names[i]));
            if (t == tab) {
                option.setTextColor(NexLentaColors.ON_ACCENT);
                option.setBackground(NexLentaUi.roundRect(NexLentaColors.ACCENT, 21));
            } else {
                option.setTextColor(NexLentaColors.TEXT);
                option.setOnClickListener(v -> {
                    tab = t;
                    rebuild();
                    updatePreview();
                });
            }
            tabsView.addView(option, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        }

        optionsView.removeAllViews();
        if (tab == TAB_COLOR) {
            buildColorOptions(context);
        } else {
            final boolean video = tab == TAB_VIDEO;
            final TextView pick = NexLentaUi.pillButton(context, LocaleController.getString(R.string.NexComposePick), NexLentaUi.PILL_CARD);
            pick.setOnClickListener(v -> pick(video));
            optionsView.addView(pick, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));
            final TextView hint = new TextView(context);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            hint.setTextColor(NexLentaColors.SECONDARY_TEXT);
            hint.setGravity(Gravity.CENTER);
            hint.setText(LocaleController.getString(video ? R.string.NexCoverVideoHint : R.string.NexCoverPhotoHint));
            optionsView.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 10, 8, 0));
        }
    }

    private void buildColorOptions(Context context) {
        optionsView.addView(label(context, R.string.NexCoverColor), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 10));
        final LinearLayout colors = new LinearLayout(context);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = -1; i < NexCover.COLORS.length; i++) {
            final Integer c = i < 0 ? null : NexCover.COLORS[i];
            final ColorDot dot = new ColorDot(context, c, c == null ? color == null : color != null && color.intValue() == c);
            dot.setOnClickListener(v -> {
                color = c;
                rebuild();
                updatePreview();
            });
            colors.addView(dot, LayoutHelper.createLinear(0, 44, 1f));
        }
        optionsView.addView(colors, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));

        final LinearLayout switchRow = new LinearLayout(context);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setBackground(NexLentaUi.roundRect(NexLentaColors.CARD, 22));
        switchRow.setPadding(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(14), 0);
        final TextView switchLabel = new TextView(context);
        switchLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        switchLabel.setTypeface(AndroidUtilities.bold());
        switchLabel.setTextColor(NexLentaColors.TEXT);
        switchLabel.setText(LocaleController.getString(R.string.NexCoverPattern));
        switchRow.addView(switchLabel, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        final Switch toggle = new Switch(context);
        toggle.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
        toggle.setChecked(pattern, false);
        switchRow.addView(toggle, LayoutHelper.createLinear(37, 40));
        switchRow.setOnClickListener(v -> {
            pattern = !pattern;
            toggle.setChecked(pattern, true);
            rebuild();
            updatePreview();
        });
        optionsView.addView(switchRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 60, 0, 18, 0, 0));

        if (pattern) {
            final LinearLayout glyphs = new LinearLayout(context);
            glyphs.setOrientation(LinearLayout.HORIZONTAL);
            for (String g : NexCover.GLYPHS) {
                final TextView cell = new TextView(context);
                cell.setGravity(Gravity.CENTER);
                cell.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
                cell.setText(g);
                final boolean selected = g.equals(glyph);
                cell.setTextColor(selected ? NexLentaColors.ON_ACCENT : NexLentaColors.TEXT);
                cell.setBackground(NexLentaUi.roundRect(selected ? NexLentaColors.ACCENT : NexLentaColors.CARD, 18));
                cell.setOnClickListener(v -> {
                    glyph = g;
                    rebuild();
                    updatePreview();
                });
                glyphs.addView(cell, LayoutHelper.createLinear(0, 56, 1f, 4, 0, 4, 0));
            }
            optionsView.addView(glyphs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56, -4, 14, -4, 0));
        }
    }

    private TextView label(Context context, int textRes) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(NexLentaColors.TEXT);
        view.setText(LocaleController.getString(textRes));
        return view;
    }

    private void updatePreview() {
        if (tab == TAB_COLOR) {
            coverView.showPattern(color, pattern ? glyph : null);
        } else if (pickedPreview != null && (tab == TAB_PHOTO ? photoUri != null : videoUri != null)) {
            coverView.setLocalImage(pickedPreview);
        } else if (current != null && (tab == TAB_PHOTO ? NexCover.MODE_PHOTO : NexCover.MODE_VIDEO).equals(current.mode)) {
            coverView.setCover(current);
        } else {
            coverView.showPattern(null, null);
        }
    }

    private void pick(boolean video) {
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType(video ? "video/*" : "image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, video ? PICK_VIDEO : PICK_PHOTO);
        } catch (Exception e) {
            toast(R.string.NexComposeNoPicker);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if ((requestCode != PICK_PHOTO && requestCode != PICK_VIDEO) || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final boolean video = requestCode == PICK_VIDEO;
        final Uri uri = data.getData();
        if (video) {
            videoUri = uri;
            photoUri = null;
        } else {
            photoUri = uri;
            videoUri = null;
        }
        final int size = AndroidUtilities.displaySize.x;
        CoreClient.EXECUTOR.execute(() -> {
            final Bitmap preview = PixelfedMedia.thumbnail(uri, video, size);
            AndroidUtilities.runOnUIThread(() -> {
                if (fragmentView == null) {
                    return;
                }
                pickedPreview = preview;
                updatePreview();
            });
        });
    }

    private void save() {
        if (saving) {
            return;
        }
        final long userId = getUserConfig().getClientUserId();
        final String username = cacheKey;
        if (tab != TAB_COLOR) {
            final Uri uri = tab == TAB_PHOTO ? photoUri : videoUri;
            if (uri == null) {
                if (current != null && (tab == TAB_PHOTO ? NexCover.MODE_PHOTO : NexCover.MODE_VIDEO).equals(current.mode)) {
                    finishFragment(); // nothing changed
                } else {
                    toast(R.string.NexCoverNoMedia);
                }
                return;
            }
            setSaving(true, LocaleController.getString(R.string.NexCoverUploading));
            final boolean video = tab == TAB_VIDEO;
            CoreClient.EXECUTOR.execute(() -> {
                File file = null;
                NexCover result = null;
                int error = R.string.NexActionFailed;
                try {
                    final int[] duration = new int[1];
                    if (video) {
                        file = PixelfedMedia.prepareCoverVideo(uri, duration, fraction -> AndroidUtilities.runOnUIThread(() ->
                            setSaving(true, LocaleController.formatString(R.string.NexCoverPreparing, (int) (fraction * 100)))));
                        AndroidUtilities.runOnUIThread(() -> setSaving(true, LocaleController.getString(R.string.NexCoverUploading)));
                    } else {
                        file = PixelfedMedia.prepareCoverImage(uri);
                    }
                    result = NexCover.saveMediaBlocking(userId, username, file, video, duration[0]);
                } catch (CoreClient.NotLoggedInException e) {
                    error = R.string.NexCoverLogin;
                } catch (PixelfedMedia.TooLargeException e) {
                    error = R.string.NexCoverTooLarge;
                } catch (PixelfedMedia.VideoException e) {
                    error = "video_short".equals(e.getMessage()) ? R.string.NexCoverVideoShort : R.string.NexComposeVideoFailed;
                } catch (NexCover.CoverException e) {
                    error = "covers_storage_unavailable".equals(e.code) ? R.string.NexCoverStorageOff
                        : "cover_too_large".equals(e.code) ? R.string.NexCoverTooLarge : R.string.NexActionFailed;
                } catch (Exception ignore) {
                } finally {
                    if (file != null) {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                }
                finishSave(result, result != null, error);
            });
            return;
        }
        setSaving(true, null);
        final Integer c = color;
        final String g = pattern ? glyph : null;
        CoreClient.EXECUTOR.execute(() -> {
            NexCover result = null;
            boolean ok = false;
            int error = R.string.NexActionFailed;
            try {
                if (c == null && g == null) {
                    NexCover.deleteBlocking(userId, username);
                } else {
                    final String mode = c == null ? NexCover.MODE_EMOJI : g == null ? NexCover.MODE_COLOR : NexCover.MODE_COLOR_EMOJI;
                    result = NexCover.savePlainBlocking(userId, username, mode, c, g);
                }
                ok = true;
            } catch (CoreClient.NotLoggedInException e) {
                error = R.string.NexCoverLogin;
            } catch (Exception ignore) {
            }
            finishSave(result, ok, error);
        });
    }

    private void finishSave(NexCover result, boolean ok, int error) {
        AndroidUtilities.runOnUIThread(() -> {
            if (fragmentView == null) {
                return;
            }
            setSaving(false, null);
            if (!ok) {
                toast(error);
                return;
            }
            toast(R.string.NexCoverSaved);
            callback.onSaved(result);
            finishFragment();
        });
    }

    private void setSaving(boolean value, String text) {
        saving = value;
        saveButton.setAlpha(value ? 0.7f : 1f);
        saveButton.setText(text != null ? text : LocaleController.getString(R.string.NexCoverSave));
    }

    private void toast(int textRes) {
        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), LocaleController.getString(textRes), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (coverView != null) {
            coverView.pause();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (coverView != null) {
            coverView.resume();
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (coverView != null) {
            coverView.release();
        }
    }

    // Color circle; null = "none" (⊘). Selected: ring in the text color
    private static final class ColorDot extends View {
        private final Integer color;
        private final boolean selected;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ColorDot(Context context, Integer color, boolean selected) {
            super(context);
            this.color = color;
            this.selected = selected;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;
            final float outer = Math.min(cx, cy);
            final float r = outer - AndroidUtilities.dp(4);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color != null ? color : NexLentaColors.CARD);
            canvas.drawCircle(cx, cy, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(AndroidUtilities.dp(selected ? 2.5f : 1f));
            paint.setColor(selected ? NexLentaColors.TEXT : Theme.multAlpha(NexLentaColors.TEXT, 0.12f));
            canvas.drawCircle(cx, cy, selected ? outer - AndroidUtilities.dp(1.5f) : r, paint);
            if (color == null) {
                paint.setStrokeWidth(AndroidUtilities.dp(1.8f));
                paint.setColor(NexLentaColors.SECONDARY_TEXT);
                final float a = r * 0.45f;
                canvas.drawCircle(cx, cy, a, paint);
                canvas.drawLine(cx - a * 0.7f, cy + a * 0.7f, cx + a * 0.7f, cy - a * 0.7f, paint);
            }
        }
    }
}
