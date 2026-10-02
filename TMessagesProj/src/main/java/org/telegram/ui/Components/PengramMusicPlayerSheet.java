package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Shader;
import android.text.Editable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AudioInfo;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.PengramLyrics;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.PengramSettingsActivity;
import org.telegram.ui.LaunchActivity;

/**
 * Pengram: музыкальный плеер в духе Spotify.
 * Большая обложка, фон из самой обложки, крупные контролы и экран с текстом песни,
 * который анимируется побуквенно. Всё настраивается в Настройки → Pengram → Плеер.
 */
public class PengramMusicPlayerSheet extends BottomSheet implements NotificationCenter.NotificationCenterDelegate {

    private final FrameLayout rootLayout;
    private final BackgroundView backgroundView;
    private final BackupImageView coverView;
    private final TextView titleView;
    private final TextView artistView;
    private final SeekBarView seekBarView;
    private final TextView timeView;
    private final TextView durationView;
    private final ImageView playButton;
    private final PlayPauseDrawable playPauseDrawable;
    private final ImageView prevButton;
    private final ImageView nextButton;
    private final ImageView repeatButton;
    private final ImageView lyricsButton;
    private final ImageView speedButton;
    private final FrameLayout lyricsContainer;
    private final PengramLyricsView lyricsView;
    private final TextView lyricsEmptyView;
    private final TextView addLyricsButton;

    private boolean lyricsShown;
    private int lastTime = -1;
    private String lyricsKey;
    private int accentColor = 0xFF5FD0A0;

    public PengramMusicPlayerSheet(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, true, resourcesProvider);
        setApplyBottomPadding(false);
        setApplyTopPadding(false);
        setUseLightStatusBar(false);

        rootLayout = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(
                        MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.EXACTLY));
            }
        };
        containerView = rootLayout;

        backgroundView = new BackgroundView(context);
        rootLayout.addView(backgroundView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.statusBarHeight + dp(6), 0, dp(10));
        rootLayout.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // ---------- верхняя строка ----------
        final FrameLayout topBar = new FrameLayout(context);
        content.addView(topBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

        final ImageView closeButton = new ImageView(context);
        closeButton.setScaleType(ImageView.ScaleType.CENTER);
        closeButton.setImageResource(R.drawable.msg_go_down);
        closeButton.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        closeButton.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, 1, dp(20)));
        closeButton.setOnClickListener(v -> dismiss());
        topBar.addView(closeButton, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

        final TextView headerView = new TextView(context);
        headerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        headerView.setTextColor(0xCCFFFFFF);
        headerView.setGravity(Gravity.CENTER);
        headerView.setText(getString(R.string.PengramPlayerNowPlaying));
        topBar.addView(headerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 60, 0, 60, 0));

        final ImageView settingsButton = new ImageView(context);
        settingsButton.setScaleType(ImageView.ScaleType.CENTER);
        settingsButton.setImageResource(R.drawable.msg_settings_old);
        settingsButton.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        settingsButton.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, 1, dp(20)));
        settingsButton.setOnClickListener(v -> {
            dismiss();
            try {
                if (LaunchActivity.instance != null && LaunchActivity.instance.getActionBarLayout() != null) {
                    LaunchActivity.instance.presentFragment(new PengramSettingsActivity(PengramSettingsActivity.SECTION_PLAYER));
                }
            } catch (Throwable ignore) {
            }
        });
        topBar.addView(settingsButton, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        // ---------- обложка и текст песни ----------
        final FrameLayout centerLayout = new FrameLayout(context);
        content.addView(centerLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f, 20, 10, 20, 10));

        coverView = new BackupImageView(context);
        applyCoverShape();
        centerLayout.addView(coverView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        lyricsContainer = new FrameLayout(context);
        lyricsContainer.setVisibility(View.GONE);
        lyricsContainer.setAlpha(0f);
        centerLayout.addView(lyricsContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        lyricsView = new PengramLyricsView(context);
        lyricsView.setSeekCallback(progress -> {
            final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing != null) {
                MediaController.getInstance().seekToProgress(playing, progress);
            }
        });
        lyricsContainer.addView(lyricsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        lyricsEmptyView = new TextView(context);
        lyricsEmptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        lyricsEmptyView.setTextColor(0xB3FFFFFF);
        lyricsEmptyView.setGravity(Gravity.CENTER);
        lyricsEmptyView.setText(getString(R.string.PengramLyricsEmpty));
        lyricsContainer.addView(lyricsEmptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 0, 24, 40));

        addLyricsButton = new TextView(context);
        addLyricsButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        addLyricsButton.setTextColor(0xFF000000);
        addLyricsButton.setTypeface(AndroidUtilities.bold());
        addLyricsButton.setGravity(Gravity.CENTER);
        addLyricsButton.setPadding(dp(20), 0, dp(20), 0);
        addLyricsButton.setText(getString(R.string.PengramLyricsAdd));
        addLyricsButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(22), 0xFFFFFFFF, 0x33000000));
        addLyricsButton.setOnClickListener(v -> showLyricsEditor());
        lyricsContainer.addView(addLyricsButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 44, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 0, 0, 0, 10));

        // ---------- название ----------
        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine();
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 14, 22, 0));

        artistView = new TextView(context);
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        artistView.setTextColor(0x99FFFFFF);
        artistView.setSingleLine();
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(artistView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 4, 22, 0));

        // ---------- прогресс ----------
        seekBarView = new SeekBarView(context, resourcesProvider);
        seekBarView.setLineWidth(4);
        seekBarView.setReportChanges(true);
        seekBarView.setDelegate(new SeekBarView.SeekBarViewDelegate() {
            @Override
            public void onSeekBarDrag(boolean stop, float progress) {
                final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
                if (playing == null) {
                    return;
                }
                if (stop) {
                    MediaController.getInstance().seekToProgress(playing, progress);
                }
                updateProgress(playing);
            }

            @Override
            public void onSeekBarPressed(boolean pressed) {
            }
        });
        content.addView(seekBarView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38, 17, 10, 17, 0));

        final FrameLayout timeLayout = new FrameLayout(context);
        content.addView(timeLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 0));

        timeView = new TextView(context);
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        timeView.setTextColor(0x99FFFFFF);
        timeView.setText("0:00");
        timeLayout.addView(timeView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));

        durationView = new TextView(context);
        durationView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        durationView.setTextColor(0x99FFFFFF);
        durationView.setText("0:00");
        timeLayout.addView(durationView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT));

        // ---------- кнопки ----------
        final FrameLayout controls = new FrameLayout(context);
        content.addView(controls, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 76, 12, 6, 12, 0));

        repeatButton = new ImageView(context);
        repeatButton.setScaleType(ImageView.ScaleType.CENTER);
        repeatButton.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, 1, dp(22)));
        repeatButton.setOnClickListener(v -> {
            if (SharedConfig.repeatMode == 0 && !SharedConfig.shuffleMusic) {
                MediaController.getInstance().setPlaybackOrderType(2);
            } else if (SharedConfig.shuffleMusic) {
                MediaController.getInstance().setPlaybackOrderType(0);
                SharedConfig.setRepeatMode(1);
            } else if (SharedConfig.repeatMode == 1) {
                SharedConfig.setRepeatMode(2);
            } else {
                SharedConfig.setRepeatMode(0);
            }
            updateRepeatButton();
        });
        controls.addView(repeatButton, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 4, 0, 0, 0));

        prevButton = new ImageView(context);
        prevButton.setScaleType(ImageView.ScaleType.CENTER);
        prevButton.setImageResource(R.drawable.msg_go_up);
        prevButton.setRotation(-90);
        prevButton.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        prevButton.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, 1, dp(26)));
        prevButton.setOnClickListener(v -> MediaController.getInstance().playPreviousMessage());
        controls.addView(prevButton, LayoutHelper.createFrame(52, 52, Gravity.CENTER, -92, 0, 0, 0));

        playButton = new ImageView(context);
        playButton.setScaleType(ImageView.ScaleType.CENTER);
        playPauseDrawable = new PlayPauseDrawable(32);
        playPauseDrawable.setPause(!MediaController.getInstance().isMessagePaused(), false);
        playButton.setImageDrawable(playPauseDrawable);
        playButton.setColorFilter(new PorterDuffColorFilter(0xFF000000, PorterDuff.Mode.SRC_IN));
        playButton.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(66), 0xFFFFFFFF, 0x22000000));
        playButton.setOnClickListener(v -> {
            final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing == null) {
                return;
            }
            if (MediaController.getInstance().isMessagePaused()) {
                MediaController.getInstance().playMessage(playing);
            } else {
                MediaController.getInstance().pauseMessage(playing);
            }
            playPauseDrawable.setPause(!MediaController.getInstance().isMessagePaused(), true);
        });
        controls.addView(playButton, LayoutHelper.createFrame(66, 66, Gravity.CENTER));

        nextButton = new ImageView(context);
        nextButton.setScaleType(ImageView.ScaleType.CENTER);
        nextButton.setImageResource(R.drawable.msg_go_up);
        nextButton.setRotation(90);
        nextButton.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        nextButton.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, 1, dp(26)));
        nextButton.setOnClickListener(v -> MediaController.getInstance().playNextMessage());
        controls.addView(nextButton, LayoutHelper.createFrame(52, 52, Gravity.CENTER, 92, 0, 0, 0));

        lyricsButton = new ImageView(context);
        lyricsButton.setScaleType(ImageView.ScaleType.CENTER);
        lyricsButton.setImageResource(R.drawable.msg_message);
        lyricsButton.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        lyricsButton.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, 1, dp(22)));
        lyricsButton.setOnClickListener(v -> toggleLyrics(!lyricsShown));
        controls.addView(lyricsButton, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        speedButton = new ImageView(context);
        speedButton.setScaleType(ImageView.ScaleType.CENTER);
        speedButton.setImageResource(R.drawable.msg_speed_slow);
        speedButton.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        speedButton.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, 1, dp(18)));
        speedButton.setOnClickListener(v -> {
            final float current = MediaController.getInstance().getPlaybackSpeed(true);
            final float next = current < 1.2f ? 1.5f : current < 1.7f ? 2f : 1f;
            MediaController.getInstance().setPlaybackSpeed(true, next);
            updateSpeedButton();
        });
        controls.addView(speedButton, LayoutHelper.createFrame(36, 36, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 52, 0));

        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);

        updateTitle();
        updateRepeatButton();
        updateSpeedButton();
    }

    private void applyCoverShape() {
        final int shape = PengramConfig.getCoverShape();
        if (shape == PengramConfig.COVER_SHAPE_CIRCLE) {
            coverView.setRoundRadius(dp(1000));
        } else if (shape == PengramConfig.COVER_SHAPE_SQUARE) {
            coverView.setRoundRadius(0);
        } else {
            coverView.setRoundRadius(dp(18));
        }
    }

    /** фон плеера — размытая обложка, градиент или просто тёмный */
    private class BackgroundView extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint overlay = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap blurred;
        private LinearGradient gradient;
        private int gradientHeight;

        BackgroundView(Context context) {
            super(context);
        }

        void setCover(Bitmap bitmap) {
            blurred = null;
            if (bitmap != null && PengramConfig.getPlayerBg() == PengramConfig.PLAYER_BG_COVER) {
                try {
                    final Bitmap small = Bitmap.createScaledBitmap(bitmap, 48, 48, true);
                    Utilities.stackBlurBitmap(small, 6);
                    blurred = small;
                    accentColor = pickAccent(small);
                    lyricsView.setColors(0xFFFFFFFF, 0xFFFFFFFF);
                } catch (Throwable ignore) {
                }
            }
            invalidate();
        }

        private int pickAccent(Bitmap bitmap) {
            try {
                long r = 0, g = 0, b = 0;
                int count = 0;
                for (int x = 0; x < bitmap.getWidth(); x += 4) {
                    for (int y = 0; y < bitmap.getHeight(); y += 4) {
                        final int c = bitmap.getPixel(x, y);
                        r += Color.red(c);
                        g += Color.green(c);
                        b += Color.blue(c);
                        count++;
                    }
                }
                if (count == 0) {
                    return accentColor;
                }
                final float[] hsv = new float[3];
                Color.colorToHSV(Color.rgb((int) (r / count), (int) (g / count), (int) (b / count)), hsv);
                hsv[1] = Math.min(1f, hsv[1] * 1.6f + 0.25f);
                hsv[2] = 0.98f;
                return Color.HSVToColor(hsv);
            } catch (Throwable e) {
                return accentColor;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int bg = PengramConfig.getPlayerBg();
            final int w = getMeasuredWidth();
            final int h = getMeasuredHeight();
            if (bg == PengramConfig.PLAYER_BG_THEME) {
                canvas.drawColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
                return;
            }
            if (bg == PengramConfig.PLAYER_BG_DARK) {
                canvas.drawColor(0xFF101014);
                return;
            }
            if (bg == PengramConfig.PLAYER_BG_COVER && blurred != null && PengramConfig.getBool(PengramConfig.KEY_PLAYER_BLUR, true)) {
                paint.setFilterBitmap(true);
                canvas.save();
                canvas.scale(w / (float) blurred.getWidth(), h / (float) blurred.getHeight());
                canvas.drawBitmap(blurred, 0, 0, paint);
                canvas.restore();
                canvas.drawColor(0x77000000);
                return;
            }
            if (gradient == null || gradientHeight != h) {
                gradientHeight = h;
                gradient = new LinearGradient(0, 0, 0, h,
                        new int[]{ColorUtils.blendARGB(accentColor, 0xFF000000, 0.35f), 0xFF0E0E12},
                        null, Shader.TileMode.CLAMP);
            }
            overlay.setShader(gradient);
            canvas.drawRect(0, 0, w, h, overlay);
        }
    }

    private void toggleLyrics(boolean show) {
        if (lyricsShown == show) {
            return;
        }
        lyricsShown = show;
        if (show) {
            reloadLyrics();
            lyricsContainer.setVisibility(View.VISIBLE);
            lyricsContainer.setTranslationY(dp(28));
            lyricsContainer.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            coverView.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(260).start();
        } else {
            lyricsContainer.animate().alpha(0f).translationY(dp(28)).setDuration(220).withEndAction(() -> lyricsContainer.setVisibility(View.GONE)).start();
            coverView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).start();
        }
        lyricsButton.setColorFilter(new PorterDuffColorFilter(show ? accentColor : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
    }

    private void reloadLyrics() {
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        lyricsKey = PengramLyrics.keyFor(playing);
        final String raw = PengramLyrics.getRaw(lyricsKey);
        final long duration = playing == null ? 0 : (long) (playing.getDuration() * 1000);
        lyricsView.setColors(accentColor, 0xFFFFFFFF);
        lyricsView.setLyrics(raw, duration);
        final boolean empty = lyricsView.isEmpty();
        lyricsEmptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        addLyricsButton.setText(getString(empty ? R.string.PengramLyricsAdd : R.string.PengramLyricsEdit));
        if (playing != null) {
            lyricsView.setProgress(playing.audioProgress);
        }
    }

    @SuppressLint("SetTextI18n")
    private void showLyricsEditor() {
        final Context context = getContext();
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null) {
            return;
        }
        lyricsKey = PengramLyrics.keyFor(playing);
        final AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.PengramLyricsAdd));

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(22), dp(4), dp(22), 0);

        final TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        hint.setText(getString(R.string.PengramLyricsHint));
        layout.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        final EditText editText = new EditText(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        editText.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, resourcesProvider));
        editText.setBackground(null);
        editText.setMaxLines(10);
        editText.setMinLines(5);
        editText.setGravity(Gravity.TOP | Gravity.LEFT);
        editText.setHint(getString(R.string.PengramLyricsPlaceholder));
        final String existing = PengramLyrics.getRaw(lyricsKey);
        if (!TextUtils.isEmpty(existing)) {
            editText.setText(existing);
        }
        layout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 180));

        builder.setView(layout);
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            final Editable value = editText.getText();
            PengramLyrics.setRaw(lyricsKey, value == null ? null : value.toString());
            reloadLyrics();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.show();
    }

    private void updateSpeedButton() {
        final float speed = MediaController.getInstance().getPlaybackSpeed(true);
        speedButton.setImageResource(speed > 1.4f ? R.drawable.msg_speed_fast : speed > 1.1f ? R.drawable.msg_speed_medium : R.drawable.msg_speed_slow);
        speedButton.setColorFilter(new PorterDuffColorFilter(speed > 1.05f ? accentColor : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
    }

    private void updateRepeatButton() {
        final int mode = SharedConfig.repeatMode;
        final boolean active;
        if (SharedConfig.shuffleMusic) {
            repeatButton.setImageResource(R.drawable.player_new_shuffle);
            active = true;
        } else if (mode == 2) {
            repeatButton.setImageResource(R.drawable.player_new_repeatone);
            active = true;
        } else if (mode == 1) {
            repeatButton.setImageResource(R.drawable.player_new_repeatall);
            active = true;
        } else {
            repeatButton.setImageResource(R.drawable.player_new_repeatall);
            active = false;
        }
        repeatButton.setColorFilter(new PorterDuffColorFilter(active ? accentColor : 0x80FFFFFF, PorterDuff.Mode.SRC_IN));
    }

    private void updateTitle() {
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null || !playing.isMusic()) {
            dismiss();
            return;
        }
        titleView.setText(playing.getMusicTitle());
        artistView.setText(playing.getMusicAuthor());
        durationView.setText(AndroidUtilities.formatShortDuration((int) playing.getDuration()));
        updateCover(playing);
        playPauseDrawable.setPause(!MediaController.getInstance().isMessagePaused(), false);
        updateProgress(playing);
        if (lyricsShown) {
            reloadLyrics();
        }
    }

    private void updateCover(MessageObject messageObject) {
        final AudioInfo audioInfo = MediaController.getInstance().getAudioInfo();
        applyCoverShape();
        if (audioInfo != null && audioInfo.getCover() != null) {
            coverView.setImageBitmap(audioInfo.getCover());
            backgroundView.setCover(audioInfo.getCover());
            return;
        }
        final TLRPC.Document document = messageObject.getDocument();
        TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 360) : null;
        if (!(thumb instanceof TLRPC.TL_photoSize) && !(thumb instanceof TLRPC.TL_photoSizeProgressive)) {
            thumb = null;
        }
        final String artworkUrl = messageObject.getArtworkUrl(false);
        final ImageLocation thumbLocation = thumb != null ? ImageLocation.getForDocument(thumb, document) : null;
        if (!TextUtils.isEmpty(artworkUrl)) {
            coverView.setImage(ImageLocation.getForPath(artworkUrl), null, thumbLocation, null, null, 0, 1, messageObject);
        } else if (thumbLocation != null) {
            coverView.setImage(null, null, thumbLocation, null, null, 0, 1, messageObject);
        } else {
            coverView.setImageDrawable(Theme.createSimpleSelectorRoundRectDrawable(dp(18), 0x33FFFFFF, 0x33FFFFFF));
        }
        backgroundView.setCover(null);
    }

    private void updateProgress(MessageObject messageObject) {
        if (messageObject == null) {
            return;
        }
        if (!seekBarView.isDragging()) {
            seekBarView.setProgress(messageObject.audioProgress, false);
        }
        final int time = messageObject.audioProgressSec;
        if (lastTime != time) {
            lastTime = time;
            timeView.setText(AndroidUtilities.formatShortDuration(time));
        }
        if (lyricsShown) {
            lyricsView.setProgress(messageObject.audioProgress);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing != null) {
                updateProgress(playing);
            }
        } else if (id == NotificationCenter.messagePlayingDidStart) {
            lastTime = -1;
            updateTitle();
        } else if (id == NotificationCenter.messagePlayingPlayStateChanged) {
            playPauseDrawable.setPause(!MediaController.getInstance().isMessagePaused(), true);
        } else if (id == NotificationCenter.messagePlayingDidReset) {
            final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing == null) {
                dismiss();
            }
        }
    }

    @Override
    public void dismiss() {
        super.dismiss();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
    }

    /** какой плеер показывать — наш или оригинальный */
    public static BottomSheet create(Context context, Theme.ResourcesProvider resourcesProvider) {
        if (PengramConfig.isNewPlayer()) {
            return new PengramMusicPlayerSheet(context, resourcesProvider);
        }
        return new AudioPlayerAlert(context, resourcesProvider);
    }
}
