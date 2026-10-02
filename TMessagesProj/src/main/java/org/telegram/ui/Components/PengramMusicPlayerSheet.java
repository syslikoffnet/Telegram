package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.PengramLyrics;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PengramPlayerStyleActivity;

/**
 * Pengram: музыкальный плеер в духе Spotify.
 * Большая обложка, фон из самой обложки, крупные контролы и текст песни, который
 * находится сам и анимируется побуквенно. Вид выбирается в «Настройки → Pengram → Плеер»,
 * там же — живые превью всех вариантов.
 */
public class PengramMusicPlayerSheet extends BottomSheet implements NotificationCenter.NotificationCenterDelegate {

    private final int style;
    private final boolean compact;

    private final FrameLayout rootLayout;
    private final FrameLayout cardLayout;
    private FrameLayout queueContainer;
    private LinearLayout queueList;
    private android.widget.ScrollView queueScroll;
    private ImageView queueButton;
    private boolean queueShown;
    private PengramTrackButton prevTrackButton;
    private PengramTrackButton nextTrackButton;
    private TextView speedChip;
    private FrameLayout speedPanel;
    private final BackgroundView backgroundView;
    private final BackupImageView coverView;
    private final BackupImageView smallCoverView;
    private final TextView titleView;
    private final TextView artistView;
    private final SeekBarView seekBarView;
    private final TextView timeView;
    private final TextView durationView;
    private final ImageView playButton;
    private final PlayPauseDrawable playPauseDrawable;

    private final ImageView repeatButton;
    private final ImageView lyricsButton;
    private final FrameLayout lyricsContainer;
    private final PengramLyricsView lyricsView;
    private final TextView lyricsStatusView;
    private final TextView retryButton;
    private final RadialProgressView lyricsProgress;

    private boolean lyricsShown;
    private int lastTime = -1;
    private String lyricsKey;
    private int accentColor = 0xFF5FD0A0;

    public PengramMusicPlayerSheet(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, true, resourcesProvider);
        setApplyBottomPadding(false);
        setApplyTopPadding(false);
        setUseLightStatusBar(false);

        style = PengramConfig.getPlayerStyle();
        compact = style == PengramConfig.PLAYER_STYLE_COMPACT || style == PengramConfig.PLAYER_STYLE_MINI_LYRICS;
        final boolean lyricsAlways = style == PengramConfig.PLAYER_STYLE_LYRICS || style == PengramConfig.PLAYER_STYLE_MINI_LYRICS;
        final boolean lyricsSupported = PengramConfig.playerStyleHasLyrics(style);

        rootLayout = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(
                        MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.EXACTLY));
            }
        };
        containerView = rootLayout;

        cardLayout = new FrameLayout(context);
        if (compact) {
            cardLayout.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight() + dp(20), dp(20));
                }
            });
            cardLayout.setClipToOutline(true);
            rootLayout.addView(cardLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
            // в компактных видах карточка занимает не весь экран: тап по пустому месту закрывает плеер
            rootLayout.setOnClickListener(v -> dismiss());
            cardLayout.setOnClickListener(v -> {
            });
        } else {
            rootLayout.addView(cardLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }

        backgroundView = new BackgroundView(context);
        cardLayout.addView(backgroundView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, compact ? dp(6) : AndroidUtilities.statusBarHeight + dp(6), 0, dp(12));
        cardLayout.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                compact ? LayoutHelper.WRAP_CONTENT : LayoutHelper.MATCH_PARENT));

        // ---------- верхняя строка ----------
        final FrameLayout topBar = new FrameLayout(context);
        content.addView(topBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));

        final ImageView closeButton = new ImageView(context);
        closeButton.setScaleType(ImageView.ScaleType.CENTER);
        closeButton.setImageResource(R.drawable.msg_go_down);
        closeButton.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        closeButton.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, 1, dp(20)));
        closeButton.setOnClickListener(v -> dismiss());
        topBar.addView(closeButton, LayoutHelper.createFrame(42, 42, Gravity.LEFT | Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

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
                if (LaunchActivity.instance != null) {
                    LaunchActivity.instance.presentFragment(new PengramPlayerStyleActivity());
                }
            } catch (Throwable ignore) {
            }
        });
        topBar.addView(settingsButton, LayoutHelper.createFrame(42, 42, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        queueButton = new ImageView(context);
        queueButton.setScaleType(ImageView.ScaleType.CENTER);
        queueButton.setImageResource(R.drawable.msg_list);
        queueButton.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        queueButton.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, 1, dp(20)));
        queueButton.setOnClickListener(v -> toggleQueue(!queueShown));
        queueButton.setVisibility(compact ? View.GONE : View.VISIBLE);
        topBar.addView(queueButton, LayoutHelper.createFrame(42, 42, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 54, 0));

        // ---------- обложка и текст песни ----------
        final LinearLayout centerLayout = new LinearLayout(context);
        centerLayout.setOrientation(style == PengramConfig.PLAYER_STYLE_MINI_LYRICS
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        if (compact) {
            content.addView(centerLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                    style == PengramConfig.PLAYER_STYLE_MINI_LYRICS ? 132 : 190, 20, 4, 20, 6));
        } else {
            content.addView(centerLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f, 20, 8, 20, 8));
        }

        coverView = new BackupImageView(context);
        applyCoverShape();
        lyricsContainer = new FrameLayout(context);

        if (style == PengramConfig.PLAYER_STYLE_MINI_LYRICS) {
            // мини-режим: слева обложка, справа текст
            centerLayout.addView(coverView, LayoutHelper.createLinear(132, 132, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
            centerLayout.addView(lyricsContainer, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        } else if (style == PengramConfig.PLAYER_STYLE_LYRICS) {
            // текст на весь экран, обложка маленькая у названия
            centerLayout.addView(lyricsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        } else {
            // обложка во весь блок, текст открывается поверх неё
            final FrameLayout overlay = new FrameLayout(context);
            overlay.addView(coverView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            overlay.addView(lyricsContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            centerLayout.addView(overlay, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        }
        if (!lyricsSupported) {
            lyricsContainer.setVisibility(View.GONE);
        } else if (lyricsAlways) {
            lyricsShown = true;
        } else {
            lyricsContainer.setVisibility(View.GONE);
            lyricsContainer.setAlpha(0f);
        }

        lyricsView = new PengramLyricsView(context);
        lyricsView.setSeekCallback(progress -> {
            final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing != null) {
                MediaController.getInstance().seekToProgress(playing, progress);
            }
        });
        lyricsContainer.addView(lyricsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        lyricsProgress = new RadialProgressView(context);
        lyricsProgress.setSize(dp(28));
        lyricsProgress.setStrokeWidth(2.5f);
        lyricsProgress.setProgressColor(0xFFFFFFFF);
        lyricsProgress.setVisibility(View.GONE);
        lyricsContainer.addView(lyricsProgress, LayoutHelper.createFrame(40, 40, Gravity.CENTER, 0, 0, 0, 40));

        lyricsStatusView = new TextView(context);
        lyricsStatusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        lyricsStatusView.setTextColor(0xB3FFFFFF);
        lyricsStatusView.setGravity(Gravity.CENTER);
        lyricsStatusView.setVisibility(View.GONE);
        lyricsContainer.addView(lyricsStatusView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 24, 0, 24, 0));

        retryButton = new TextView(context);
        retryButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        retryButton.setTextColor(0xFF000000);
        retryButton.setTypeface(AndroidUtilities.bold());
        retryButton.setGravity(Gravity.CENTER);
        retryButton.setPadding(dp(18), 0, dp(18), 0);
        retryButton.setText(getString(R.string.PengramLyricsRetry));
        retryButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18), 0xFFFFFFFF, 0x33000000));
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(v -> loadLyrics(true));
        lyricsContainer.addView(retryButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 0, 0, 0, 8));

        // ---------- название ----------
        final LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(titleRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, compact ? 4 : 12, 22, 0));

        smallCoverView = new BackupImageView(context);
        smallCoverView.setRoundRadius(dp(10));
        smallCoverView.setVisibility(style == PengramConfig.PLAYER_STYLE_LYRICS ? View.VISIBLE : View.GONE);
        titleRow.addView(smallCoverView, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

        final LinearLayout titleColumn = new LinearLayout(context);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        titleRow.addView(titleColumn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, compact ? 18 : 22);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine();
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleColumn.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        artistView = new TextView(context);
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        artistView.setTextColor(0x99FFFFFF);
        artistView.setSingleLine();
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        titleColumn.addView(artistView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

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
        content.addView(seekBarView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 34, 17, 8, 17, 0));

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
        content.addView(controls, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, compact ? 70 : 76, 12, 4, 12, 0));

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
        controls.addView(repeatButton, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 2, 0, 0, 0));

        prevTrackButton = new PengramTrackButton(context, false);
        prevTrackButton.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, 1, dp(26)));
        prevTrackButton.setOnClickListener(v -> MediaController.getInstance().playPreviousMessage());
        controls.addView(prevTrackButton, LayoutHelper.createFrame(52, 52, Gravity.CENTER, -88, 0, 0, 0));

        playButton = new ImageView(context);
        playButton.setScaleType(ImageView.ScaleType.CENTER);
        playPauseDrawable = new PlayPauseDrawable(32);
        playPauseDrawable.setPause(!MediaController.getInstance().isMessagePaused(), false);
        playButton.setImageDrawable(playPauseDrawable);
        playButton.setColorFilter(new PorterDuffColorFilter(0xFF000000, PorterDuff.Mode.SRC_IN));
        playButton.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(62), 0xFFFFFFFF, 0x22000000));
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
        controls.addView(playButton, LayoutHelper.createFrame(62, 62, Gravity.CENTER));

        nextTrackButton = new PengramTrackButton(context, true);
        nextTrackButton.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, 1, dp(26)));
        nextTrackButton.setOnClickListener(v -> MediaController.getInstance().playNextMessage());
        controls.addView(nextTrackButton, LayoutHelper.createFrame(52, 52, Gravity.CENTER, 88, 0, 0, 0));

        lyricsButton = new ImageView(context);
        lyricsButton.setScaleType(ImageView.ScaleType.CENTER);
        lyricsButton.setImageResource(R.drawable.msg_message);
        lyricsButton.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        lyricsButton.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, 1, dp(22)));
        lyricsButton.setOnClickListener(v -> toggleLyrics(!lyricsShown));
        lyricsButton.setVisibility(lyricsSupported && !lyricsAlways ? View.VISIBLE : View.GONE);
        controls.addView(lyricsButton, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 2, 0));

        speedChip = new TextView(context);
        speedChip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        speedChip.setTypeface(AndroidUtilities.bold());
        speedChip.setGravity(Gravity.CENTER);
        speedChip.setTextColor(0xFFFFFFFF);
        speedChip.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, 1, dp(18)));
        speedChip.setOnClickListener(v -> {
            final float current = MediaController.getInstance().getPlaybackSpeed(true);
            float next = SPEEDS[0];
            for (int a = 0; a < SPEEDS.length; ++a) {
                if (current < SPEEDS[a] - 0.01f) {
                    next = SPEEDS[a];
                    break;
                }
            }
            setSpeed(next);
        });
        speedChip.setOnLongClickListener(v -> {
            toggleSpeedPanel(true);
            return true;
        });
        controls.addView(speedChip, LayoutHelper.createFrame(40, 36, Gravity.RIGHT | Gravity.CENTER_VERTICAL,
                0, 0, lyricsSupported && !lyricsAlways ? 48 : 6, 0));

        if (!compact) {
            buildQueuePanel(context);
        }

        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);

        updateTitle();
        updateRepeatButton();
        updateSpeedButton();
    }

    /** панель «Очередь»: что играет сейчас и что будет дальше */
    private void buildQueuePanel(Context context) {
        queueContainer = new FrameLayout(context);
        queueContainer.setVisibility(View.GONE);
        queueContainer.setAlpha(0f);
        queueContainer.setBackgroundColor(0xB3000000);
        queueContainer.setOnClickListener(v -> toggleQueue(false));

        final FrameLayout panel = new FrameLayout(context);
        panel.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18), 0xF21A1A1E, 0xF21A1A1E));
        panel.setOnClickListener(v -> {
        });
        queueContainer.addView(panel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.FILL, 10, AndroidUtilities.statusBarHeight + 54, 10, 12));

        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(0xFFFFFFFF);
        title.setText(getString(R.string.PengramPlayerQueue));
        panel.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.TOP, 18, 16, 18, 0));

        queueScroll = new android.widget.ScrollView(context);
        queueList = new LinearLayout(context);
        queueList.setOrientation(LinearLayout.VERTICAL);
        queueList.setPadding(0, 0, 0, dp(10));
        queueScroll.addView(queueList, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        panel.addView(queueScroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.FILL, 0, 44, 0, 0));

        cardLayout.addView(queueContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    private void toggleQueue(boolean show) {
        if (queueContainer == null || queueShown == show) {
            return;
        }
        queueShown = show;
        if (show) {
            updateQueue();
            queueContainer.setVisibility(View.VISIBLE);
            queueContainer.setTranslationY(dp(28));
            queueContainer.animate().alpha(1f).translationY(0).setDuration(220)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        } else {
            queueContainer.animate().alpha(0f).translationY(dp(20)).setDuration(180)
                    .setInterpolator(CubicBezierInterpolator.DEFAULT)
                    .withEndAction(() -> queueContainer.setVisibility(View.GONE)).start();
        }
        if (queueButton != null) {
            queueButton.setColorFilter(new PorterDuffColorFilter(show ? accentColor : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        }
    }

    private void updateQueue() {
        if (queueList == null) {
            return;
        }
        queueList.removeAllViews();
        final java.util.ArrayList<MessageObject> playlist = MediaController.getInstance().getPlaylist();
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playlist == null || playlist.isEmpty()) {
            final TextView empty = new TextView(getContext());
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            empty.setTextColor(0x99FFFFFF);
            empty.setGravity(Gravity.CENTER);
            empty.setText(getString(R.string.PengramPlayerQueueEmpty));
            queueList.addView(empty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 80));
            return;
        }
        int current = 0;
        for (int a = 0; a < playlist.size(); ++a) {
            if (playing != null && playlist.get(a).getId() == playing.getId()) {
                current = a;
            }
        }
        // очень длинные плейлисты не строим целиком — берём окно вокруг текущего трека
        final int max = 120;
        int from = 0, to = playlist.size();
        if (playlist.size() > max) {
            from = Math.max(0, current - max / 3);
            to = Math.min(playlist.size(), from + max);
            from = Math.max(0, to - max);
        }
        int currentRow = 0;
        for (int a = from; a < to; ++a) {
            final MessageObject messageObject = playlist.get(a);
            final boolean active = playing != null && messageObject.getId() == playing.getId();
            if (active) {
                currentRow = a - from;
            }
            queueList.addView(createQueueRow(messageObject, active), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
        }
        final int scrollTo = Math.max(0, dp(56) * currentRow - dp(120));
        if (queueScroll != null) {
            queueScroll.post(() -> queueScroll.scrollTo(0, scrollTo));
        }
    }

    private View createQueueRow(MessageObject messageObject, boolean active) {
        final Context context = getContext();
        final FrameLayout row = new FrameLayout(context);
        row.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, 2));

        final BackupImageView cover = new BackupImageView(context);
        cover.setRoundRadius(dp(8));
        final TLRPC.Document document = messageObject.getDocument();
        final TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 90) : null;
        final ImageLocation thumbLocation = thumb instanceof TLRPC.TL_photoSize || thumb instanceof TLRPC.TL_photoSizeProgressive
                ? ImageLocation.getForDocument(thumb, document) : null;
        final String artworkUrl = messageObject.getArtworkUrl(true);
        if (!TextUtils.isEmpty(artworkUrl)) {
            cover.setImage(ImageLocation.getForPath(artworkUrl), "40_40", thumbLocation, null, null, 0, 1, messageObject);
        } else if (thumbLocation != null) {
            cover.setImage(null, null, thumbLocation, null, null, 0, 1, messageObject);
        } else {
            cover.setImageDrawable(Theme.createSimpleSelectorRoundRectDrawable(dp(8), 0x33FFFFFF, 0x33FFFFFF));
        }
        row.addView(cover, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        row.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, 64, 0, 64, 0));

        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextColor(active ? accentColor : 0xFFFFFFFF);
        if (active) {
            title.setTypeface(AndroidUtilities.bold());
        }
        title.setText(messageObject.getMusicTitle(false));
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView author = new TextView(context);
        author.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        author.setMaxLines(1);
        author.setEllipsize(TextUtils.TruncateAt.END);
        author.setTextColor(0x99FFFFFF);
        author.setText(messageObject.getMusicAuthor(false));
        column.addView(author, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        final TextView duration = new TextView(context);
        duration.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        duration.setTextColor(0x80FFFFFF);
        duration.setText(AndroidUtilities.formatShortDuration((int) messageObject.getDuration()));
        row.addView(duration, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

        row.setOnClickListener(v -> {
            if (!active) {
                MediaController.getInstance().playMessage(messageObject);
            }
            updateQueue();
        });
        return row;
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
        private final RectF rect = new RectF();
        private Bitmap blurred;
        private LinearGradient gradient;
        private int gradientHeight;

        BackgroundView(Context context) {
            super(context);
        }

        void setCover(Bitmap bitmap) {
            blurred = null;
            if (bitmap != null) {
                try {
                    final Bitmap small = Bitmap.createScaledBitmap(bitmap, 48, 48, true);
                    accentColor = pickAccent(small);
                    if (PengramConfig.getPlayerBg() == PengramConfig.PLAYER_BG_COVER) {
                        Utilities.stackBlurBitmap(small, 6);
                        blurred = small;
                    }
                } catch (Throwable ignore) {
                }
            }
            gradient = null;
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
                canvas.drawColor(ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider), 0xFF000000, 0.55f));
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
                gradient = new LinearGradient(0, 0, 0, Math.max(1, h),
                        new int[]{ColorUtils.blendARGB(accentColor, 0xFF000000, 0.35f), 0xFF0E0E12},
                        null, Shader.TileMode.CLAMP);
            }
            overlay.setShader(gradient);
            rect.set(0, 0, w, h);
            canvas.drawRect(rect, overlay);
        }
    }

    private void toggleLyrics(boolean show) {
        if (lyricsShown == show) {
            return;
        }
        lyricsShown = show;
        if (show) {
            loadLyrics(false);
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

    /** текст ищется сам: кэш → сеть; руками ничего вводить не нужно */
    private void loadLyrics(boolean force) {
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null) {
            return;
        }
        lyricsKey = PengramLyrics.keyFor(playing);
        final long duration = (long) (playing.getDuration() * 1000);
        lyricsView.setColors(accentColor, 0xFFFFFFFF);
        if (prevTrackButton != null) {
            prevTrackButton.setColor(0xFFFFFFFF);
            nextTrackButton.setColor(0xFFFFFFFF);
        }
        PengramLyrics.request(playing, force, (key, raw, state) -> {
            if (!TextUtils.equals(key, lyricsKey)) {
                return;
            }
            applyLyricsState(raw, state, duration, playing);
        });
    }

    private void applyLyricsState(String raw, int state, long duration, MessageObject playing) {
        if (state == PengramLyrics.STATE_FOUND && !TextUtils.isEmpty(raw)) {
            lyricsView.setLyrics(raw, duration);
            lyricsView.setVisibility(View.VISIBLE);
            lyricsProgress.setVisibility(View.GONE);
            lyricsStatusView.setVisibility(View.GONE);
            retryButton.setVisibility(View.GONE);
            final MessageObject current = MediaController.getInstance().getPlayingMessageObject();
            if (current != null) {
                lyricsView.setProgress(current.audioProgress);
            }
            return;
        }
        lyricsView.setLyrics(null, duration);
        lyricsView.setVisibility(View.INVISIBLE);
        if (state == PengramLyrics.STATE_LOADING) {
            lyricsProgress.setVisibility(View.VISIBLE);
            lyricsStatusView.setVisibility(View.VISIBLE);
            lyricsStatusView.setText(getString(R.string.PengramLyricsSearching));
            lyricsStatusView.setTranslationY(-dp(34));
            retryButton.setVisibility(View.GONE);
        } else {
            lyricsProgress.setVisibility(View.GONE);
            lyricsStatusView.setVisibility(View.VISIBLE);
            lyricsStatusView.setText(getString(R.string.PengramLyricsNotFound));
            lyricsStatusView.setTranslationY(0);
            retryButton.setVisibility(View.VISIBLE);
        }
    }

    private static final float[] SPEEDS = new float[]{0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};

    private static String speedLabel(float speed) {
        if (Math.abs(speed - Math.round(speed)) < 0.01f) {
            return Math.round(speed) + "\u00d7";
        }
        return String.format(java.util.Locale.getDefault(), "%.2f", speed).replaceAll("0$", "") + "\u00d7";
    }

    private void setSpeed(float speed) {
        MediaController.getInstance().setPlaybackSpeed(true, speed);
        updateSpeedButton();
        if (speedPanel != null && speedPanel.getVisibility() == View.VISIBLE) {
            toggleSpeedPanel(false);
        }
    }

    /** подпись показывает ровно ту скорость, которая играет — без «примерных» иконок */
    private void updateSpeedButton() {
        if (speedChip == null) {
            return;
        }
        final float speed = MediaController.getInstance().getPlaybackSpeed(true);
        speedChip.setText(speedLabel(speed));
        speedChip.setTextColor(Math.abs(speed - 1f) > 0.01f ? accentColor : 0xFFFFFFFF);
        if (speedPanel != null) {
            for (int a = 0; a < speedPanel.getChildCount(); ++a) {
                final View child = speedPanel.getChildAt(a);
                if (child instanceof LinearLayout) {
                    final LinearLayout row = (LinearLayout) child;
                    for (int b = 0; b < row.getChildCount(); ++b) {
                        final View chip = row.getChildAt(b);
                        if (chip instanceof TextView && b < SPEEDS.length) {
                            final boolean active = Math.abs(SPEEDS[b] - speed) < 0.01f;
                            ((TextView) chip).setTextColor(active ? 0xFF000000 : 0xFFFFFFFF);
                            chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(14),
                                    active ? 0xFFFFFFFF : 0x22FFFFFF, 0x33FFFFFF));
                        }
                    }
                }
            }
        }
    }

    /** панель выбора скорости: все значения сразу, а не вслепую по кругу */
    private void toggleSpeedPanel(boolean show) {
        if (speedPanel == null) {
            if (!show) {
                return;
            }
            final Context context = getContext();
            speedPanel = new FrameLayout(context);
            speedPanel.setBackgroundColor(0x80000000);
            speedPanel.setOnClickListener(v -> toggleSpeedPanel(false));

            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18), 0xF21A1A1E, 0xF21A1A1E));
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setOnClickListener(v -> {
            });
            for (float value : SPEEDS) {
                final TextView chip = new TextView(context);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                chip.setTypeface(AndroidUtilities.bold());
                chip.setGravity(Gravity.CENTER);
                chip.setText(speedLabel(value));
                chip.setOnClickListener(v -> setSpeed(value));
                row.addView(chip, LayoutHelper.createLinear(0, 36, 1f, 2, 0, 2, 0));
            }
            speedPanel.addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.BOTTOM, 10, 0, 10, 86));
            cardLayout.addView(speedPanel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            speedPanel.setVisibility(View.GONE);
            speedPanel.setAlpha(0f);
        }
        if (show) {
            updateSpeedButton();
            speedPanel.setVisibility(View.VISIBLE);
            speedPanel.animate().alpha(1f).setDuration(180).start();
        } else {
            speedPanel.animate().alpha(0f).setDuration(150)
                    .withEndAction(() -> speedPanel.setVisibility(View.GONE)).start();
        }
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
            if (isShowing()) {
                dismiss();
            }
            return;
        }
        titleView.setText(playing.getMusicTitle());
        artistView.setText(playing.getMusicAuthor());
        durationView.setText(AndroidUtilities.formatShortDuration((int) playing.getDuration()));
        updateCover(playing);
        playPauseDrawable.setPause(!MediaController.getInstance().isMessagePaused(), false);
        updateProgress(playing);
        if (PengramConfig.playerStyleHasLyrics(style)) {
            loadLyrics(false);
        }
    }

    private void updateCover(MessageObject messageObject) {
        final AudioInfo audioInfo = MediaController.getInstance().getAudioInfo();
        applyCoverShape();
        if (audioInfo != null && audioInfo.getCover() != null) {
            coverView.setImageBitmap(audioInfo.getCover());
            smallCoverView.setImageBitmap(audioInfo.getCover());
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
            smallCoverView.setImage(ImageLocation.getForPath(artworkUrl), "44_44", thumbLocation, null, null, 0, 1, messageObject);
        } else if (thumbLocation != null) {
            coverView.setImage(null, null, thumbLocation, null, null, 0, 1, messageObject);
            smallCoverView.setImage(null, null, thumbLocation, null, null, 0, 1, messageObject);
        } else {
            coverView.setImageDrawable(Theme.createSimpleSelectorRoundRectDrawable(dp(18), 0x33FFFFFF, 0x33FFFFFF));
            smallCoverView.setImageDrawable(Theme.createSimpleSelectorRoundRectDrawable(dp(10), 0x33FFFFFF, 0x33FFFFFF));
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
            if (queueShown) {
                updateQueue();
            }
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
    public void onBackPressed() {
        if (speedPanel != null && speedPanel.getVisibility() == View.VISIBLE) {
            toggleSpeedPanel(false);
            return;
        }
        if (queueShown) {
            toggleQueue(false);
            return;
        }
        super.onBackPressed();
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
