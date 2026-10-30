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
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
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
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.PengramAudioPulse;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.PengramLyrics;
import org.telegram.messenger.PengramTrackRecognizer;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.tgnet.ConnectionsManager;
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
    /** что показываем в панели: очередь (0) или музыку этого чата (1) */
    private int panelTab;
    private TextView queueTabView;
    private TextView chatTabView;
    private TextView viewModeView;
    private final java.util.ArrayList<MessageObject> chatTracks = new java.util.ArrayList<>();
    private final java.util.HashSet<Integer> chatTrackIds = new java.util.HashSet<>();
    private long chatTracksDialogId;
    private boolean chatTracksLoading;
    private boolean chatTracksEndReached;
    private boolean chatTracksLoadError;
    private int chatTracksMinLoadedId;
    private int chatTracksRequestIndex;
    private int chatTracksWindowStart;
    private int chatTracksGuid;
    private Runnable chatTracksTimeout;
    private PengramTrackButton prevTrackButton;
    private PengramTrackButton nextTrackButton;
    private TextView speedChip;
    private FrameLayout speedPanel;
    private final BackgroundView backgroundView;
    private final BackupImageView coverView;
    /** пингвин на месте отсутствующей обложки */
    private PengramPenguinView penguinCover;
    private FrameLayout penguinHolder;
    private PengramBeatHalo beatHalo;
    /** 0 — реакция на музыку выключена в настройках */
    private final float beatIntensity = PengramConfig.getPlayerBeatIntensity();
    private PengramAudioPulse.Listener pulseListener;
    private final BackupImageView smallCoverView;
    private final TextView titleView;
    private final TextView artistView;
    private final TextView metaBadgeView;
    private final ImageView likeButton;
    private PengramTrackRecognizer.TrackInfo recognizedTrackInfo;
    private int recognitionToken;
    private TextView openFullChatLibraryButton;
    private final Runnable favStateListener = this::updateLikeButtonState;
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
    private int lyricsToken;
    private int accentColor = 0xFF5FD0A0;

    public PengramMusicPlayerSheet(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, true, resourcesProvider);
        setApplyBottomPadding(false);
        setApplyTopPadding(false);
        setUseLightStatusBar(false);

        accentColor = resolveAccent(context, resourcesProvider);
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

        cardLayout = new FrameLayout(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                if (!compact) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                    return;
                }
                // в компактных стилях высоту задаёт только содержимое,
                // иначе фон во весь экран растягивал карточку и всё уезжало вверх
                final int width = MeasureSpec.getSize(widthMeasureSpec);
                final int available = MeasureSpec.getSize(heightMeasureSpec);
                if (queueShown && queueContainer != null && queueContainer.getVisibility() == VISIBLE) {
                    final int expandedH = Math.max(dp(340), Math.min(available - dp(48), dp(476)));
                    for (int a = 0; a < getChildCount(); ++a) {
                        final View child = getChildAt(a);
                        if (child == null || child == backgroundView || child.getVisibility() == GONE) {
                            continue;
                        }
                        child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                                MeasureSpec.makeMeasureSpec(expandedH, child == queueContainer ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST));
                    }
                    if (backgroundView != null) {
                        backgroundView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                                MeasureSpec.makeMeasureSpec(expandedH, MeasureSpec.EXACTLY));
                    }
                    setMeasuredDimension(width, expandedH);
                    return;
                }
                int height = 0;
                for (int a = 0; a < getChildCount(); ++a) {
                    final View child = getChildAt(a);
                    if (child == null || child == backgroundView || child.getVisibility() == GONE) {
                        continue;
                    }
                    child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST));
                    height = Math.max(height, child.getMeasuredHeight());
                }
                height = Math.max(dp(120), Math.min(height, available));
                if (backgroundView != null) {
                    backgroundView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
                }
                setMeasuredDimension(width, height);
            }
        };
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
        content.setPadding(0, compact ? dp(6) : AndroidUtilities.statusBarHeight + dp(6), 0,
                dp(12) + (compact ? Math.max(0, AndroidUtilities.navigationBarHeight) : 0));
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
        headerView.setSingleLine(true);
        headerView.setEllipsize(TextUtils.TruncateAt.END);
        topBar.addView(headerView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 54, 0,
                PengramConfig.isTrackForwardButton() ? 196 : 150, 0));

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
        queueButton.setOnClickListener(v -> {
            if (compact && !queueShown) {
                panelTab = 1; // В мини-плеере по умолчанию сразу показываем плейлист чата
            }
            toggleQueue(!queueShown);
        });
        queueButton.setOnLongClickListener(v -> {
            openFullChatMusicSheet();
            return true;
        });
        queueButton.setVisibility((!compact || PengramConfig.isMiniPlayerQueueBtn()) ? View.VISIBLE : View.GONE);
        topBar.addView(queueButton, LayoutHelper.createFrame(42, 42, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 54, 0));

        // One tap sends the configured card into THIS chat. Long tap lets the
        // user preview and choose image or text just for this send.
        final ImageView shareCardButton = new ImageView(context);
        shareCardButton.setScaleType(ImageView.ScaleType.CENTER);
        shareCardButton.setImageResource(R.drawable.msg_share);
        shareCardButton.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        shareCardButton.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, 1, dp(20)));
        shareCardButton.setContentDescription(getString(R.string.PengramNowPlayingShare));
        shareCardButton.setVisibility(PengramNowPlayingCard.currentChat() == null ? View.GONE : View.VISIBLE);
        shareCardButton.setOnClickListener(v -> {
            final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing != null && playing.isMusic()) {
                final android.graphics.Bitmap visible = shareCoverBitmap();
                dismiss();
                AndroidUtilities.runOnUIThread(() -> PengramNowPlayingCard.send(
                        context, resourcesProvider, playing, visible, PengramConfig.getTrackCardFormat()), 120);
            }
        });
        shareCardButton.setOnLongClickListener(v -> {
            final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing != null && playing.isMusic()) {
                final android.graphics.Bitmap visible = shareCoverBitmap();
                PengramNowPlayingCard.show(context, resourcesProvider, playing, visible);
            }
            return true;
        });
        topBar.addView(shareCardButton, LayoutHelper.createFrame(42, 42,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, PengramConfig.isTrackForwardButton() ? 146 : 100, 0));

        // Pengram: пересылка трека одной кнопкой — манера берётся из настроек
        if (PengramConfig.isTrackForwardButton()) {
            final ImageView forwardButton = new ImageView(context);
            forwardButton.setScaleType(ImageView.ScaleType.CENTER);
            forwardButton.setImageResource(R.drawable.msg_forward);
            forwardButton.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            forwardButton.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, 1, dp(20)));
            forwardButton.setContentDescription(getString(R.string.PengramTrackForwardTitle));
            forwardButton.setOnClickListener(v -> {
                final MessageObject track = MediaController.getInstance().getPlayingMessageObject();
                if (track == null) {
                    return;
                }
                dismiss();
                PengramTrackForward.start(context, resourcesProvider, track);
            });
            topBar.addView(forwardButton, LayoutHelper.createFrame(42, 42, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 100, 0));
        }

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
        // Картинка может не приехать вовсе (битая ссылка артворка, нет сети) —
        // тогда никто бы не сообщил, что обложки нет. Слушаем сам приёмник:
        // пришла — прячем пингвина, не пришла — он остаётся на месте.
        coverView.getImageReceiver().setDelegate((receiver, set, thumb, memCache) -> {
            if (set) {
                hideEmptyCover();
                final android.graphics.Bitmap bitmap = receiver.getBitmap();
                if (bitmap != null && !bitmap.isRecycled()) {
                    backgroundView.setCover(bitmap);
                }
            } else {
                // A request is still in flight. Replacing the receiver image
                // here cancels that request and leaves a false "missing" cover.
                applyEmptyCover(true);
            }
        });
        lyricsContainer = new FrameLayout(context);

        if (style == PengramConfig.PLAYER_STYLE_MINI_LYRICS) {
            // мини-режим: слева обложка, справа текст
            final FrameLayout coverSlot = new FrameLayout(context);
            penguinHolder = createPenguinHolder(context);
            coverSlot.addView(penguinHolder, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            coverSlot.addView(coverView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            centerLayout.addView(coverSlot, LayoutHelper.createLinear(132, 132, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
            centerLayout.addView(lyricsContainer, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        } else if (style == PengramConfig.PLAYER_STYLE_LYRICS) {
            // текст на весь экран, обложка маленькая у названия, а пингвин —
            // мягким силуэтом за строками: пустое место занято, читать не мешает
            final FrameLayout lyricsOverlay = new FrameLayout(context);
            penguinHolder = createPenguinHolder(context);
            penguinHolder.setAlpha(0.55f);
            lyricsOverlay.addView(penguinHolder, LayoutHelper.createFrame(160, 160, Gravity.CENTER));
            lyricsOverlay.addView(lyricsContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            centerLayout.addView(lyricsOverlay, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        } else {
            // обложка во весь блок, текст открывается поверх неё
            final FrameLayout overlay = new FrameLayout(context);
            penguinHolder = createPenguinHolder(context);
            overlay.addView(penguinHolder, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
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

        // никаких кнопок поверх текста: всё нужное живёт в долгом нажатии
        lyricsContainer.setOnLongClickListener(v -> {
            showLyricsMenu();
            return true;
        });

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
        titleColumn.setOnLongClickListener(v -> {
            showTrackInfoDialog();
            return true;
        });
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

        metaBadgeView = new TextView(context);
        metaBadgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        metaBadgeView.setTextColor(0xB3FFFFFF);
        metaBadgeView.setSingleLine();
        metaBadgeView.setEllipsize(TextUtils.TruncateAt.END);
        metaBadgeView.setVisibility(View.GONE);
        metaBadgeView.setOnClickListener(v -> showTrackInfoDialog());
        titleColumn.addView(metaBadgeView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        likeButton = new ImageView(context);
        likeButton.setScaleType(ImageView.ScaleType.CENTER);
        likeButton.setBackground(Theme.createSelectorDrawable(0x26FFFFFF, 1, dp(22)));
        likeButton.setVisibility(PengramConfig.isPlayerFavoritesEnabled() ? View.VISIBLE : View.GONE);
        likeButton.setOnClickListener(v -> {
            final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing == null || !playing.isMusic() || !PengramConfig.isPlayerFavoritesEnabled()) {
                return;
            }
            if (PengramConfig.isVibrationEnabled()) {
                try {
                    likeButton.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                } catch (Throwable ignore) {}
            }
            likeButton.animate().cancel();
            likeButton.setScaleX(0.76f);
            likeButton.setScaleY(0.76f);
            likeButton.animate().scaleX(1f).scaleY(1f).setDuration(300)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2.6f)).start();
            org.telegram.messenger.PengramMusicFavorites.toggleFavorite(playing, getContext());
            updateLikeButtonState();
        });
        likeButton.setOnLongClickListener(v -> {
            if (!PengramConfig.isPlayerFavoritesEnabled()) {
                return false;
            }
            dismiss();
            AndroidUtilities.runOnUIThread(() -> {
                if (LaunchActivity.instance != null) {
                    org.telegram.messenger.PengramMusicFavorites.openVaultChat(LaunchActivity.getSafeLastFragment());
                }
            }, 140);
            return true;
        });
        titleRow.addView(likeButton, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

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
            if (queueShown) {
                updateQueue();
            }
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

        if (!compact || PengramConfig.isMiniPlayerQueueBtn()) {
            buildQueuePanel(context);
        }

        startPulse();
        org.telegram.messenger.PengramMusicFavorites.addListener(favStateListener);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.musicPlaybackOrderChanged);

        updateTitle();
        updateLikeButtonState();
        updateRepeatButton();
        updateSpeedButton();
    }

    /** панель «Очередь»: что играет сейчас и что будет дальше */
    private void buildQueuePanel(Context context) {
        queueContainer = new FrameLayout(context);
        queueContainer.setVisibility(View.GONE);
        queueContainer.setAlpha(0f);
        queueContainer.setBackgroundColor(compact ? 0xD90E0E12 : 0xB3000000);
        queueContainer.setOnClickListener(v -> toggleQueue(false));

        final FrameLayout panel = new FrameLayout(context);
        panel.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(18), 0xF21A1A1E, 0xF21A1A1E));
        panel.setOnClickListener(v -> {
        });
        final int panelTopMargin = compact ? 50 : AndroidUtilities.statusBarHeight + 54;
        final int panelBottomMargin = compact ? 10 + Math.max(0, (int) (AndroidUtilities.navigationBarHeight / AndroidUtilities.density)) : 12;
        queueContainer.addView(panel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.FILL, 10, panelTopMargin, 10, panelBottomMargin));

        // две вкладки: очередь и вся музыка того чата, откуда играет трек
        final LinearLayout tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(tabs, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.TOP, 12, 10, 12, 0));

        queueTabView = createPanelTab(context, getString(R.string.PengramPlayerTracks), () -> switchPanelTab(0));
        chatTabView = createPanelTab(context, getString(R.string.PengramChatMusicTitle), () -> switchPanelTab(1));
        tabs.addView(queueTabView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 6, 0));
        tabs.addView(chatTabView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34));

        final LinearLayout rightActions = new LinearLayout(context);
        rightActions.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(rightActions, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.RIGHT | Gravity.TOP, 12, 10, 12, 0));

        openFullChatLibraryButton = createPanelTab(context, "↗", this::openFullChatMusicSheet);
        openFullChatLibraryButton.setContentDescription(getString(R.string.PengramChatMusicTitle));
        rightActions.addView(openFullChatLibraryButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34, 0, 0, 6, 0));

        viewModeView = createPanelTab(context, getString(PengramConfig.getTracksViewName(PengramConfig.getTracksView())),
                this::cyclePanelView);
        rightActions.addView(viewModeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 34));

        queueScroll = new android.widget.ScrollView(context);
        queueList = new LinearLayout(context);
        queueList.setOrientation(LinearLayout.VERTICAL);
        queueList.setPadding(0, 0, 0, dp(10));
        queueScroll.addView(queueList, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        panel.addView(queueScroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.FILL, 0, 52, 0, 0));

        cardLayout.addView(queueContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    public void openQueueOnTab(int tab) {
        if (queueContainer == null) {
            return;
        }
        panelTab = tab;
        toggleQueue(true);
    }

    private void openFullChatMusicSheet() {
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null || getContext() == null) {
            return;
        }
        final long dialogId = playing.getDialogId();
        if (dialogId == 0) {
            return;
        }
        final CharSequence chatName = resolveDialogTitle(currentAccount, dialogId);
        final Context ctx = getContext();
        dismiss();
        AndroidUtilities.runOnUIThread(() -> {
            final PengramChatMusicSheet sheet = new PengramChatMusicSheet(ctx, resourcesProvider, currentAccount, dialogId, chatName, msg -> {
                if (msg == null || LaunchActivity.instance == null) {
                    return;
                }
                final org.telegram.ui.ActionBar.BaseFragment frag = LaunchActivity.getSafeLastFragment();
                if (frag instanceof org.telegram.ui.ChatActivity && ((org.telegram.ui.ChatActivity) frag).getDialogId() == dialogId) {
                    ((org.telegram.ui.ChatActivity) frag).scrollToMessageId(msg.getId(), 0, true, 0, true, 0);
                } else {
                    final android.os.Bundle args = new android.os.Bundle();
                    if (dialogId > 0) {
                        args.putLong("user_id", dialogId);
                    } else {
                        args.putLong("chat_id", -dialogId);
                    }
                    args.putInt("message_id", msg.getId());
                    if (frag != null) {
                        frag.presentFragment(new org.telegram.ui.ChatActivity(args));
                    }
                }
            });
            sheet.show();
        }, 120);
    }

    public static CharSequence resolveDialogTitle(int account, long dialogId) {
        if (dialogId > 0) {
            if (dialogId == org.telegram.messenger.UserConfig.getInstance(account).getClientUserId()) {
                return LocaleController.getString(R.string.SavedMessages);
            }
            final TLRPC.User u = org.telegram.messenger.MessagesController.getInstance(account).getUser(dialogId);
            if (u != null) {
                return org.telegram.messenger.UserObject.getUserName(u);
            }
        } else if (dialogId < 0) {
            final TLRPC.Chat c = org.telegram.messenger.MessagesController.getInstance(account).getChat(-dialogId);
            if (c != null && !TextUtils.isEmpty(c.title)) {
                return c.title;
            }
        }
        return LocaleController.getString(R.string.PengramChatMusicTitle);
    }

    private void toggleQueue(boolean show) {
        if (queueContainer == null || queueShown == show) {
            return;
        }
        queueShown = show;
        if (show) {
            updatePanelTabs();
            if (panelTab == 1) {
                loadChatTracks();
            }
            updateQueue();
            queueContainer.setVisibility(View.VISIBLE);
            if (compact) {
                cardLayout.requestLayout();
            }
            queueContainer.setTranslationY(dp(28));
            queueContainer.animate().alpha(1f).translationY(0).setDuration(220)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        } else {
            queueContainer.animate().alpha(0f).translationY(dp(20)).setDuration(180)
                    .setInterpolator(CubicBezierInterpolator.DEFAULT)
                    .withEndAction(() -> {
                        queueContainer.setVisibility(View.GONE);
                        if (compact) {
                            cardLayout.requestLayout();
                        }
                    }).start();
        }
        if (queueButton != null) {
            queueButton.setColorFilter(new PorterDuffColorFilter(show ? accentColor : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        }
    }

    /** кнопка-таблетка в шапке панели */
    private TextView createPanelTab(Context context, CharSequence text, Runnable onClick) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        view.setTypeface(AndroidUtilities.bold());
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(14), 0, dp(14), 0);
        view.setText(text);
        view.setOnClickListener(v -> onClick.run());
        return view;
    }

    /** подкрасить вкладки под текущее состояние */
    private void updatePanelTabs() {
        if (queueTabView == null || chatTabView == null) {
            return;
        }
        final int active = ColorUtils.setAlphaComponent(accentColor, 58);
        queueTabView.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(17),
                panelTab == 0 ? active : 0x16FFFFFF, 0x22FFFFFF));
        queueTabView.setTextColor(panelTab == 0 ? 0xFFFFFFFF : 0x99FFFFFF);
        chatTabView.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(17),
                panelTab == 1 ? active : 0x16FFFFFF, 0x22FFFFFF));
        chatTabView.setTextColor(panelTab == 1 ? 0xFFFFFFFF : 0x99FFFFFF);
        if (viewModeView != null) {
            viewModeView.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(17), 0x16FFFFFF, 0x22FFFFFF));
            viewModeView.setTextColor(0x99FFFFFF);
            viewModeView.setText(getString(PengramConfig.getTracksViewName(PengramConfig.getTracksView())));
        }
        if (openFullChatLibraryButton != null) {
            openFullChatLibraryButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(17), 0x16FFFFFF, 0x22FFFFFF));
            openFullChatLibraryButton.setTextColor(0xCCFFFFFF);
        }
    }

    private void switchPanelTab(int tab) {
        if (panelTab == tab) {
            return;
        }
        panelTab = tab;
        updatePanelTabs();
        if (tab == 1) {
            loadChatTracks();
        }
        updateQueue();
    }

    /** следующий вид списка по кругу: список, компактный, названия, сетка, карточки */
    private void cyclePanelView() {
        PengramConfig.setTracksView((PengramConfig.getTracksView() + 1) % PengramConfig.TRACKS_VIEW_COUNT);
        updatePanelTabs();
        updateQueue();
    }

    /** вся музыка того чата, откуда играет нынешний трек */
    private void loadChatTracks() {
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        final long dialogId = playing == null ? 0 : playing.getDialogId();
        if (chatTracksDialogId != dialogId) {
            if (chatTracksTimeout != null) AndroidUtilities.cancelRunOnUIThread(chatTracksTimeout);
            chatTracksDialogId = dialogId;
            chatTracks.clear();
            chatTrackIds.clear();
            chatTracksMinLoadedId = 0;
            chatTracksWindowStart = 0;
            chatTracksEndReached = false;
            chatTracksLoadError = false;
            chatTracksLoading = false;
        }
        if (dialogId == 0 || chatTracksEndReached || chatTracksLoading) return;
        chatTracksLoading = true;
        chatTracksLoadError = false;
        if (chatTracksGuid == 0) {
            chatTracksGuid = ConnectionsManager.generateClassGuid();
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.mediaDidLoad);
        }
        final int requestIndex = ++chatTracksRequestIndex;
        MediaDataController.getInstance(currentAccount).loadMedia(dialogId, 60, chatTracksMinLoadedId, 0,
                MediaDataController.MEDIA_MUSIC, 0, 0, chatTracksGuid, requestIndex, null, null);
        if (chatTracksTimeout != null) AndroidUtilities.cancelRunOnUIThread(chatTracksTimeout);
        chatTracksTimeout = () -> {
            if (chatTracksLoading && chatTracksDialogId == dialogId && chatTracksRequestIndex == requestIndex) {
                chatTracksLoading = false;
                chatTracksLoadError = true;
                if (queueShown && panelTab == 1) updateQueue();
            }
        };
        AndroidUtilities.runOnUIThread(chatTracksTimeout, 15000);
    }

    private long lastQueueSignature = Long.MIN_VALUE;

    private static long computePlaylistSignature(int tab, int mode, int accent, boolean reverse,
                                                 MessageObject playing, java.util.List<MessageObject> list,
                                                 int from, int to, boolean loading) {
        long h = 1125899906842597L;
        h = h * 31 + tab;
        h = h * 31 + mode;
        h = h * 31 + accent;
        h = h * 31 + (reverse ? 1 : 0);
        h = h * 31 + (loading ? 1 : 0);
        h = h * 31 + (playing != null ? playing.getId() : 0);
        h = h * 31 + (playing != null ? playing.getDialogId() : 0L);
        if (list != null) {
            h = h * 31 + list.size();
            h = h * 31 + from;
            h = h * 31 + to;
            for (int i = from; i < to; i++) {
                final MessageObject m = list.get(reverse ? list.size() - 1 - i : i);
                if (m != null) {
                    h = h * 31 + m.getId();
                    h = h * 31 + m.getDialogId();
                }
            }
        }
        return h;
    }

    private void updateQueue() {
        if (queueList == null) {
            return;
        }
        if (panelTab == 1) {
            updateChatTracks();
            return;
        }
        final java.util.ArrayList<MessageObject> playlist = MediaController.getInstance().getPlaybackPlaylist();
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playlist == null || playlist.isEmpty()) {
            final long emptySig = computePlaylistSignature(0, PengramConfig.getTracksView(), accentColor, false, null, null, 0, 0, false);
            if (emptySig == lastQueueSignature && queueList.getChildCount() > 0) {
                return;
            }
            lastQueueSignature = emptySig;
            queueList.removeAllViews();
            final TextView empty = new TextView(getContext());
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            empty.setTextColor(0x99FFFFFF);
            empty.setGravity(Gravity.CENTER);
            empty.setText(getString(R.string.PengramPlayerTracksEmpty));
            queueList.addView(empty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 80));
            return;
        }
        // MediaController advances toward lower indices by default. Display
        // that order in reverse so the next song is below the active row.
        final boolean reverse = !SharedConfig.playOrderReversed;
        int current = 0;
        for (int a = 0; a < playlist.size(); ++a) {
            if (playing != null && playlist.get(a).getId() == playing.getId()
                    && playlist.get(a).getDialogId() == playing.getDialogId()) {
                current = reverse ? playlist.size() - 1 - a : a;
                break;
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
        final long sig = computePlaylistSignature(0, PengramConfig.getTracksView(), accentColor, reverse, playing, playlist, from, to, false);
        if (sig == lastQueueSignature && queueList.getChildCount() > 0) {
            return;
        }
        lastQueueSignature = sig;
        queueList.removeAllViews();
        View activeRow = null;
        for (int a = from; a < to; ++a) {
            final MessageObject messageObject = playlist.get(reverse ? playlist.size() - 1 - a : a);
            final boolean active = playing != null && messageObject.getId() == playing.getId()
                    && messageObject.getDialogId() == playing.getDialogId();
            addTrackView(messageObject, active, null);
            if (active) {
                // In grid/card mode multiple tracks share one actual row.
                activeRow = queueList.getChildAt(queueList.getChildCount() - 1);
            }
        }
        if (queueScroll != null && activeRow != null) {
            final View row = activeRow;
            queueScroll.post(() -> {
                if (row.getParent() == queueList) {
                    queueScroll.scrollTo(0, Math.max(0, row.getTop() - dp(120)));
                }
            });
        }
    }

    /** музыка чата в той же панели */
    private void updateChatTracks() {
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (chatTracks.isEmpty()) {
            final long emptySig = computePlaylistSignature(1, PengramConfig.getTracksView(), accentColor, false, null, null, 0, 0, chatTracksLoading || chatTracksLoadError);
            if (emptySig == lastQueueSignature && queueList.getChildCount() > 0) {
                return;
            }
            lastQueueSignature = emptySig;
            queueList.removeAllViews();
            final TextView empty = new TextView(getContext());
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            empty.setTextColor(0x99FFFFFF);
            empty.setGravity(Gravity.CENTER);
            empty.setText(getString(chatTracksLoading ? R.string.Loading : chatTracksLoadError ? R.string.TryAgain : R.string.PengramChatMusicEmpty));
            if (chatTracksLoadError) {
                empty.setOnClickListener(v -> {
                    chatTracksLoadError = false;
                    loadChatTracks();
                    updateQueue();
                });
            }
            queueList.addView(empty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 80));
            return;
        }
        final java.util.ArrayList<MessageObject> list = new java.util.ArrayList<>(chatTracks);
        int current = 0;
        for (int a = 0; a < list.size(); ++a) {
            final MessageObject track = list.get(a);
            if (playing != null && track.getId() == playing.getId()
                    && track.getDialogId() == playing.getDialogId()) {
                current = a;
                break;
            }
        }
        final int max = 120;
        if (current < chatTracksWindowStart || current >= chatTracksWindowStart + max) {
            chatTracksWindowStart = Math.max(0, current - max / 3);
        }
        final int from = Math.min(chatTracksWindowStart, Math.max(0, list.size() - 1));
        final int to = Math.min(list.size(), from + max);
        final long sig = computePlaylistSignature(1, PengramConfig.getTracksView(), accentColor, false, playing, list, from, to,
                chatTracksLoading || chatTracksLoadError || !chatTracksEndReached || to < list.size());
        if (sig == lastQueueSignature && queueList.getChildCount() > 0) {
            return;
        }
        lastQueueSignature = sig;
        queueList.removeAllViews();
        for (int a = from; a < to; ++a) {
            final MessageObject track = list.get(a);
            final boolean active = playing != null && track.getId() == playing.getId()
                    && track.getDialogId() == playing.getDialogId();
            addTrackView(track, active, list);
        }
        if (from > 0 || to < list.size() || !chatTracksEndReached || chatTracksLoading || chatTracksLoadError) {
            final LinearLayout footer = new LinearLayout(getContext());
            footer.setOrientation(LinearLayout.HORIZONTAL);
            if (from > 0) {
                final int newerStart = Math.max(0, from - max);
                footer.addView(createChatTracksFooterButton(getString(R.string.PengramChatMusicShowNewer),
                        () -> { chatTracksWindowStart = newerStart; updateQueue(); }),
                        new LinearLayout.LayoutParams(0, dp(46), 1f));
            }
            if (to < list.size()) {
                footer.addView(createChatTracksFooterButton(getString(R.string.PengramChatMusicShowOlder),
                        () -> { chatTracksWindowStart = to; updateQueue(); }),
                        new LinearLayout.LayoutParams(0, dp(46), 1f));
            }
            if (chatTracksLoadError || (to >= list.size() && (!chatTracksEndReached || chatTracksLoading))) {
                final String label = chatTracksLoading ? getString(R.string.Loading)
                        : chatTracksLoadError ? getString(R.string.TryAgain)
                        : getString(R.string.PengramChatMusicLoadMore);
                footer.addView(createChatTracksFooterButton(label, () -> {
                    if (chatTracksLoading) return;
                    chatTracksLoadError = false;
                    loadChatTracks();
                    updateQueue();
                }), new LinearLayout.LayoutParams(0, dp(46), 1f));
            }
            queueList.addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46));
        }
    }

    private TextView createChatTracksFooterButton(CharSequence title, Runnable action) {
        final TextView button = new TextView(getContext());
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        button.setTextColor(0xFF5FD0A0);
        button.setText(title);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    /**
     * Одна строка списка в выбранном виде.
     *
     * Сетка и карточки складываются по две-три штуки в ряд, поэтому последний
     * ряд достраивается на лету: так не нужен отдельный адаптер ради красоты.
     */
    private void addTrackView(MessageObject messageObject, boolean active, java.util.ArrayList<MessageObject> playlist) {
        final int mode = PengramConfig.getTracksView();
        if (mode == PengramConfig.TRACKS_VIEW_GRID || mode == PengramConfig.TRACKS_VIEW_CARDS) {
            final int columns = mode == PengramConfig.TRACKS_VIEW_GRID ? 3 : 2;
            LinearLayout row = null;
            if (queueList.getChildCount() > 0) {
                final View last = queueList.getChildAt(queueList.getChildCount() - 1);
                if (last instanceof LinearLayout && last.getTag() instanceof Integer
                        && (Integer) last.getTag() < columns) {
                    row = (LinearLayout) last;
                }
            }
            if (row == null) {
                row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setTag(0);
                queueList.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                        LayoutHelper.WRAP_CONTENT, 6, 4, 6, 0));
            }
            final View cell = createTrackCard(messageObject, active, playlist, mode == PengramConfig.TRACKS_VIEW_CARDS);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp(3);
            lp.rightMargin = dp(3);
            row.addView(cell, lp);
            row.setTag((Integer) row.getTag() + 1);
            return;
        }
        final int height = mode == PengramConfig.TRACKS_VIEW_TITLES ? 38
                : (mode == PengramConfig.TRACKS_VIEW_COMPACT ? 46 : 58);
        queueList.addView(createTrackRow(messageObject, active, playlist, mode),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, height, 8, 2, 8, 0));
    }

    /** плитка с обложкой: мелкая сеткой или крупной карточкой */
    private View createTrackCard(MessageObject messageObject, boolean active,
                                 java.util.ArrayList<MessageObject> playlist, boolean big) {
        final Context context = getContext();
        final LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(6), dp(6), dp(6), dp(8));
        cell.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(14),
                active ? ColorUtils.setAlphaComponent(accentColor, 38) : 0x10FFFFFF, 0x22FFFFFF));

        final BackupImageView cover = new BackupImageView(context);
        cover.setRoundRadius(dp(big ? 12 : 10));
        loadTrackCover(cover, messageObject);
        cell.addView(cover, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                dp(big ? 128 : 84)));

        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, big ? 14 : 12);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextColor(active ? accentColor : 0xFFFFFFFF);
        if (active) {
            title.setTypeface(AndroidUtilities.bold());
        }
        final String[] displayPair = PengramConfig.isSmartTrackRecognition()
                ? org.telegram.messenger.PengramMusicMeta.resolveDisplayPair(messageObject)
                : new String[]{messageObject.getMusicAuthor(false), messageObject.getMusicTitle(false)};
        title.setText(displayPair[1]);
        cell.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        final TextView author = new TextView(context);
        author.setTextSize(TypedValue.COMPLEX_UNIT_DIP, big ? 12 : 10);
        author.setMaxLines(1);
        author.setEllipsize(TextUtils.TruncateAt.END);
        author.setTextColor(0x99FFFFFF);
        author.setText(displayPair[0]);
        cell.addView(author, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        cell.setOnClickListener(v -> playTrack(messageObject, playlist));
        return cell;
    }

    /** строка списка: обычная, компактная или только название */
    private View createTrackRow(MessageObject messageObject, boolean active,
                                java.util.ArrayList<MessageObject> playlist, int mode) {
        final Context context = getContext();
        final FrameLayout row = new FrameLayout(context);
        row.setBackground(active
                ? Theme.createSimpleSelectorRoundRectDrawable(dp(12), ColorUtils.setAlphaComponent(accentColor, 38), 0x22FFFFFF)
                : Theme.createSimpleSelectorRoundRectDrawable(dp(12), 0x00000000, 0x22FFFFFF));

        final boolean titlesOnly = mode == PengramConfig.TRACKS_VIEW_TITLES;
        final boolean compactRow = mode == PengramConfig.TRACKS_VIEW_COMPACT;
        int textLeft = 12;
        if (!titlesOnly) {
            final int size = compactRow ? 32 : 40;
            final BackupImageView cover = new BackupImageView(context);
            cover.setRoundRadius(dp(8));
            loadTrackCover(cover, messageObject);
            row.addView(cover, LayoutHelper.createFrame(size, size, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));
            textLeft = 12 + size + 12;
        }

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        row.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, textLeft, 0, 58, 0));

        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, compactRow || titlesOnly ? 14 : 15);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextColor(active ? accentColor : 0xFFFFFFFF);
        if (active) {
            title.setTypeface(AndroidUtilities.bold());
        }
        final String[] rowPair = PengramConfig.isSmartTrackRecognition()
                ? org.telegram.messenger.PengramMusicMeta.resolveDisplayPair(messageObject)
                : new String[]{messageObject.getMusicAuthor(false), messageObject.getMusicTitle(false)};
        title.setText(rowPair[1]);
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (!titlesOnly) {
            final TextView author = new TextView(context);
            author.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            author.setMaxLines(1);
            author.setEllipsize(TextUtils.TruncateAt.END);
            author.setTextColor(0x99FFFFFF);
            author.setText(rowPair[0]);
            column.addView(author, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        }

        final TextView duration = new TextView(context);
        duration.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        duration.setTextColor(0x80FFFFFF);
        duration.setText(AndroidUtilities.formatShortDuration((int) messageObject.getDuration()));
        row.addView(duration, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

        row.setOnClickListener(v -> playTrack(messageObject, playlist));
        return row;
    }

    private void loadTrackCover(BackupImageView cover, MessageObject messageObject) {
        final TLRPC.Document document = messageObject.getDocument();
        final TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 90) : null;
        final ImageLocation thumbLocation = thumb instanceof TLRPC.TL_photoSize || thumb instanceof TLRPC.TL_photoSizeProgressive
                ? ImageLocation.getForDocument(thumb, document) : null;
        final String artworkUrl = messageObject.getArtworkUrl(false);
        if (!TextUtils.isEmpty(artworkUrl)) {
            cover.setImage(ImageLocation.getForPath(artworkUrl), "200_200", thumbLocation, null, null, 0, 1, messageObject);
        } else if (thumbLocation != null) {
            cover.setImage(null, null, thumbLocation, null, null, 0, 1, messageObject);
        } else {
            cover.setImageDrawable(createTrackPlaceholder(messageObject));
        }
    }

    private static Drawable createTrackPlaceholder(MessageObject messageObject) {
        final String title = messageObject != null ? messageObject.getMusicTitle(false) : "";
        final int seed = title != null ? title.hashCode() : 0;
        final int hue = ((seed % 360) + 360) % 360;
        final int first = android.graphics.Color.HSVToColor(new float[]{hue, 0.58f, 0.64f});
        final int second = android.graphics.Color.HSVToColor(new float[]{(hue + 48) % 360, 0.76f, 0.28f});
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF rect = new RectF();

            @Override
            public void draw(Canvas canvas) {
                final Rect b = getBounds();
                if (b.width() <= 0 || b.height() <= 0) return;
                rect.set(b);
                paint.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                        first, second, Shader.TileMode.CLAMP));
                canvas.drawRoundRect(rect, dp(8), dp(8), paint);
                paint.setShader(null);
                final float cx = rect.centerX();
                final float cy = rect.centerY();
                final float radius = Math.min(rect.width(), rect.height()) * 0.28f;
                paint.setColor(0x24FFFFFF);
                canvas.drawCircle(cx, cy, radius * 1.55f, paint);
                paint.setColor(0x35FFFFFF);
                canvas.drawCircle(cx, cy, radius, paint);
                paint.setColor(0x66FFFFFF);
                canvas.drawCircle(cx, cy, Math.max(dp(2), radius * 0.22f), paint);
                paint.setColor(0xA8FFFFFF);
                paint.setStrokeWidth(Math.max(dp(1.5f), rect.width() * 0.045f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                final float barW = Math.max(dp(1.5f), rect.width() * 0.055f);
                final float baseY = rect.bottom - rect.height() * 0.16f;
                for (int i = 0; i < 4; i++) {
                    final float x = rect.left + rect.width() * (0.31f + i * 0.13f);
                    final float h = rect.height() * (0.10f + 0.06f * ((seed >>> (i * 3)) & 3));
                    canvas.drawRoundRect(x, baseY - h, x + barW, baseY,
                            barW * 0.5f, barW * 0.5f, paint);
                }
            }

            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
            @Override public void setColorFilter(ColorFilter colorFilter) { paint.setColorFilter(colorFilter); invalidateSelf(); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    /** включить выбранный трек (для музыки чата — вместе со всем списком) */
    private void playTrack(MessageObject messageObject, java.util.ArrayList<MessageObject> playlist) {
        if (messageObject == null) {
            return;
        }
        if (playlist == null) {
            final MediaController controller = MediaController.getInstance();
            final int index = controller.getPlaybackPlaylist().indexOf(messageObject);
            if (index < 0) {
                return; // stale row from an earlier playlist
            }
            controller.playMessageAtPlaybackIndex(index);
        } else {
            MediaController.getInstance().setPlaylist(playlist, messageObject, 0, false, null);
        }
        AndroidUtilities.runOnUIThread(this::updateQueue, 120);
    }

    private View createQueueRow(MessageObject messageObject, boolean active) {
        final Context context = getContext();
        final FrameLayout row = new FrameLayout(context);
        row.setBackground(active
                ? Theme.createSimpleSelectorRoundRectDrawable(dp(12), ColorUtils.setAlphaComponent(accentColor, 38), 0x22FFFFFF)
                : Theme.createSimpleSelectorRoundRectDrawable(dp(12), 0x00000000, 0x22FFFFFF));

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
            cover.setImageDrawable(createTrackPlaceholder(messageObject));
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

        if (active) {
            // живой эквалайзер у того трека, что сейчас играет
            final View equalizer = new View(context) {
                private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

                @Override
                protected void onDraw(Canvas canvas) {
                    final float time = android.os.SystemClock.elapsedRealtime() % 100000L / 1000f;
                    final boolean playing = !MediaController.getInstance().isMessagePaused();
                    barPaint.setColor(accentColor);
                    final float barWidth = dp(2.5f);
                    for (int a = 0; a < 3; ++a) {
                        final float energy = playing
                                ? 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(time * 6f + a * 1.7f))
                                : 0.35f;
                        final float height = getMeasuredHeight() * energy;
                        final float x = a * dp(5);
                        canvas.drawRoundRect(x, getMeasuredHeight() - height, x + barWidth, getMeasuredHeight(),
                                barWidth / 2f, barWidth / 2f, barPaint);
                    }
                    if (playing) {
                        invalidate();
                    }
                }
            };
            row.addView(equalizer, LayoutHelper.createFrame(13, 14, Gravity.LEFT | Gravity.CENTER_VERTICAL, 46, 0, 0, 0));
        }

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
        private final Rect crop = new Rect();
        private Bitmap blurred;
        private Bitmap unblurred;
        private Bitmap prevBlurred;
        private Bitmap prevUnblurred;
        private long coverFadeStartMs;
        private int coverToken;
        private LinearGradient gradient;
        private int gradientHeight;

        BackgroundView(Context context) {
            super(context);
        }

        void setCover(Bitmap bitmap) {
            final int token = ++coverToken;
            if (bitmap == null || bitmap.isRecycled()) {
                prevBlurred = blurred;
                prevUnblurred = unblurred;
                blurred = null;
                unblurred = null;
                gradient = null;
                coverFadeStartMs = android.os.SystemClock.uptimeMillis();
                invalidate();
                return;
            }
            // Копируем лёгкий сэмпл на UI-потоке за ~0.1 мс (ImageReceiver может переиспользовать свой Bitmap),
            // а тяжёлые stackBlurBitmap + accent выносим на фоновую очередь без блокировки UI.
            final Bitmap sourceSnapshot;
            try {
                final int maxSide = Math.max(bitmap.getWidth(), bitmap.getHeight());
                final float scale = Math.min(1f, 512f / Math.max(1, maxSide));
                final int bw = Math.max(1, Math.round(bitmap.getWidth() * scale));
                final int bh = Math.max(1, Math.round(bitmap.getHeight() * scale));
                final Bitmap scaled = Bitmap.createScaledBitmap(bitmap, bw, bh, true);
                sourceSnapshot = scaled == bitmap ? bitmap.copy(Bitmap.Config.ARGB_8888, false) : scaled;
            } catch (Throwable ignore) {
                blurred = null;
                unblurred = null;
                gradient = null;
                invalidate();
                return;
            }
            final int accentMode = PengramConfig.getPlayerAccentMode();
            final int bgMode = PengramConfig.getPlayerBg();
            Utilities.globalQueue.postRunnable(() -> {
                Bitmap nextBlurred = null;
                Bitmap nextUnblurred = null;
                Integer nextAccent = null;
                try {
                    final Bitmap small = Bitmap.createScaledBitmap(sourceSnapshot, 48, 48, true);
                    if (accentMode == PengramConfig.PLAYER_ACCENT_COVER) {
                        nextAccent = pickAccent(small);
                    }
                    if (bgMode == PengramConfig.PLAYER_BG_COVER) {
                        // The blur switch selects between two images, not between
                        // artwork and a fallback gradient. Keep copies independent of
                        // the image receiver (which can recycle its bitmap).
                        final Bitmap blurredCopy = small.copy(Bitmap.Config.ARGB_8888, true);
                        Utilities.stackBlurBitmap(blurredCopy, 6);
                        nextBlurred = blurredCopy;
                        nextUnblurred = sourceSnapshot;
                    }
                    if (small != sourceSnapshot && !small.isRecycled()) {
                        small.recycle();
                    }
                } catch (Throwable ignore) {
                    // Missing or unreadable artwork still gets the gradient.
                }
                final Bitmap readyBlurred = nextBlurred;
                final Bitmap readyUnblurred = nextUnblurred;
                final Integer readyAccent = nextAccent;
                AndroidUtilities.runOnUIThread(() -> {
                    if (token != coverToken) {
                        if (readyBlurred != null && !readyBlurred.isRecycled()) readyBlurred.recycle();
                        if (readyUnblurred != null && !readyUnblurred.isRecycled()) readyUnblurred.recycle();
                        return;
                    }
                    if (readyAccent != null) {
                        accentColor = readyAccent;
                        lastQueueSignature = Long.MIN_VALUE;
                        PengramMusicPlayerSheet.this.applyAccentToViews();
                    }
                    prevBlurred = blurred;
                    prevUnblurred = unblurred;
                    blurred = readyBlurred;
                    unblurred = readyUnblurred;
                    gradient = null;
                    coverFadeStartMs = android.os.SystemClock.uptimeMillis();
                    invalidate();
                });
            });
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
            if (bg == PengramConfig.PLAYER_BG_COVER) {
                final boolean blur = PengramConfig.getBool(PengramConfig.KEY_PLAYER_BLUR, true);
                final Bitmap artwork = blur ? blurred : unblurred;
                final Bitmap prevArtwork = blur ? prevBlurred : prevUnblurred;
                if (artwork != null && !artwork.isRecycled()) {
                    float fade = 1f;
                    if (coverFadeStartMs > 0L && prevArtwork != null && !prevArtwork.isRecycled() && prevArtwork != artwork) {
                        final long elapsed = android.os.SystemClock.uptimeMillis() - coverFadeStartMs;
                        if (elapsed < 240L) {
                            fade = CubicBezierInterpolator.EASE_OUT_QUINT.getInterpolation(Math.max(0f, elapsed / 240f));
                            drawArtworkLayer(canvas, prevArtwork, blur, w, h, 255);
                        } else {
                            coverFadeStartMs = 0L;
                            prevBlurred = null;
                            prevUnblurred = null;
                        }
                    }
                    paint.setFilterBitmap(true);
                    paint.setAlpha(Math.max(0, Math.min(255, Math.round(255f * fade))));
                    if (blur) {
                        canvas.save();
                        canvas.scale(w / (float) artwork.getWidth(), h / (float) artwork.getHeight());
                        canvas.drawBitmap(artwork, 0, 0, paint);
                        canvas.restore();
                    } else {
                        // Fill the background without stretching the unblurred cover.
                        final int bw = artwork.getWidth(), bh = artwork.getHeight();
                        final float aspect = w / (float) Math.max(1, h);
                        final int cw = Math.min(bw, Math.max(1, Math.round(bh * aspect)));
                        final int ch = Math.min(bh, Math.max(1, Math.round(bw / Math.max(0.001f, aspect))));
                        crop.set((bw - cw) / 2, (bh - ch) / 2, (bw + cw) / 2, (bh + ch) / 2);
                        rect.set(0, 0, w, h);
                        canvas.drawBitmap(artwork, crop, rect, paint);
                    }
                    paint.setAlpha(255);
                    canvas.drawColor(0x77000000);
                    if (fade < 1f) {
                        postInvalidateOnAnimation();
                    }
                    return;
                }
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

        private void drawArtworkLayer(Canvas canvas, Bitmap artwork, boolean blur, int w, int h, int alpha) {
            paint.setFilterBitmap(true);
            paint.setAlpha(alpha);
            if (blur) {
                canvas.save();
                canvas.scale(w / (float) artwork.getWidth(), h / (float) artwork.getHeight());
                canvas.drawBitmap(artwork, 0, 0, paint);
                canvas.restore();
            } else {
                final int bw = artwork.getWidth(), bh = artwork.getHeight();
                final float aspect = w / (float) Math.max(1, h);
                final int cw = Math.min(bw, Math.max(1, Math.round(bh * aspect)));
                final int ch = Math.min(bh, Math.max(1, Math.round(bw / Math.max(0.001f, aspect))));
                crop.set((bw - cw) / 2, (bh - ch) / 2, (bw + cw) / 2, (bh + ch) / 2);
                rect.set(0, 0, w, h);
                canvas.drawBitmap(artwork, crop, rect, paint);
            }
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
        final String newKey = PengramLyrics.keyFor(playing);
        if (!TextUtils.equals(newKey, lyricsKey)) {
            lyricsView.clear();   // новая песня — старый текст убираем сразу
        }
        lyricsKey = newKey;
        lyricsView.setOffsetKey(lyricsKey);
        lyricsView.setTrackIdentity(playing);
        final long duration = (long) (playing.getDuration() * 1000);
        lyricsView.setColors(accentColor, 0xFFFFFFFF);
        if (prevTrackButton != null) {
            prevTrackButton.setColor(0xFFFFFFFF);
            nextTrackButton.setColor(0xFFFFFFFF);
        }
        final int token = ++lyricsToken;
        PengramLyrics.request(playing, force, (key, raw, state) -> {
            // ответ прошлого трека не должен подменять текущий
            if (token != lyricsToken || !TextUtils.equals(key, lyricsKey)) {
                return;
            }
            applyLyricsState(raw, state, duration, playing);
        });
    }

    /** журнал подбора: видно, какие кандидаты были и почему их отсеяли */
    private void showLyricsDebug() {
        final java.util.ArrayList<String> log = PengramLyrics.debugLog();
        final StringBuilder text = new StringBuilder();
        for (int a = Math.max(0, log.size() - 40); a < log.size(); ++a) {
            text.append(log.get(a)).append('\n');
        }
        if (text.length() == 0) {
            text.append(getString(R.string.PengramLyricsDebugEmpty));
        }
        final org.telegram.ui.ActionBar.AlertDialog.Builder builder =
                new org.telegram.ui.ActionBar.AlertDialog.Builder(getContext(), resourcesProvider);
        builder.setTitle(getString(R.string.PengramLyricsDebug));
        builder.setMessage(text.toString());
        builder.setPositiveButton(getString(R.string.OK), null);
        builder.show();
    }

    /** долгое нажатие по тексту: подвинуть его или поискать другой */
    private void showLyricsMenu() {
        if (getContext() == null || TextUtils.isEmpty(lyricsKey) || lyricsView == null || lyricsView.isEmpty()) {
            return;
        }
        final CharSequence[] items = new CharSequence[]{
                getString(R.string.PengramLyricsWrong),
                getString(R.string.PengramLyricsHurries),
                getString(R.string.PengramLyricsLags),
                getString(R.string.PengramLyricsDebug),
        };
        final org.telegram.ui.ActionBar.AlertDialog.Builder builder =
                new org.telegram.ui.ActionBar.AlertDialog.Builder(getContext(), resourcesProvider);
        builder.setTitle(getString(R.string.PengramLyricsMenuTitle));
        builder.setItems(items, (dialog, which) -> {
            if (which == 0) {
                PengramLyrics.reject(lyricsKey);
                lyricsView.clear();
                if (lyricsStatusView != null) {
                    lyricsStatusView.setVisibility(View.VISIBLE);
                    lyricsStatusView.setText(getString(R.string.PengramLyricsSearchingOther));
                }
                loadLyrics(true);
            } else if (which == 3) {
                showLyricsDebug();
            } else {
                shiftLyrics(which == 1 ? 500 : -500);
            }
        });
        builder.show();
    }

    /** пока играет этот трек, текст и обложка следующего уже готовятся */
    private void pengramPrefetchNext() {
        try {
            final java.util.ArrayList<MessageObject> playlist = MediaController.getInstance().getPlaybackPlaylist();
            if (playlist == null || playlist.isEmpty()) {
                return;
            }
            final int index = MediaController.getInstance().getPlayingMessageObjectNum();
            if (index < 0 || index >= playlist.size()) {
                return;
            }
            final int direction = SharedConfig.playOrderReversed ? 1 : -1;
            for (int a = 1; a <= Math.min(2, playlist.size() - 1); ++a) {
                final int next = (index + a * direction + playlist.size()) % playlist.size();
                final MessageObject object = playlist.get(next);
                PengramLyrics.prefetch(object);
                org.telegram.messenger.PengramCovers.prefetch(object);
            }
        } catch (Throwable ignore) {
        }
    }

    /** сдвинуть текст этого трека на полсекунды вперёд или назад */
    private void shiftLyrics(int deltaMs) {
        if (TextUtils.isEmpty(lyricsKey)) {
            return;
        }
        final int value = PengramLyrics.getTrackOffset(lyricsKey) + deltaMs;
        PengramLyrics.setTrackOffset(lyricsKey, value);
        lyricsView.invalidate();
        if (lyricsStatusView != null) {
            final int now = PengramLyrics.getTrackOffset(lyricsKey);
            lyricsStatusView.setVisibility(View.VISIBLE);
            lyricsStatusView.setTranslationY(dp(54));
            lyricsStatusView.setText(LocaleController.formatString(R.string.PengramLyricsShifted,
                    (now > 0 ? "+" : "") + String.format(java.util.Locale.US, "%.1f", now / 1000f)));
            AndroidUtilities.cancelRunOnUIThread(hideShiftHint);
            AndroidUtilities.runOnUIThread(hideShiftHint, 1200);
        }
    }

    private final Runnable hideShiftHint = this::hideShiftHintNow;

    private void hideShiftHintNow() {
        if (lyricsStatusView != null && lyricsView != null && !lyricsView.isEmpty()) {
            lyricsStatusView.setVisibility(View.GONE);
            lyricsStatusView.setTranslationY(0);
        }
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

    private void updateLikeButtonState() {
        if (likeButton == null) {
            return;
        }
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        final boolean enabled = playing != null && playing.isMusic() && PengramConfig.isPlayerFavoritesEnabled();
        likeButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) {
            return;
        }
        final boolean liked = org.telegram.messenger.PengramMusicFavorites.isLiked(playing);
        likeButton.setImageResource(liked ? R.drawable.ic_pengram_heart_filled : R.drawable.ic_pengram_heart_outline);
        likeButton.setColorFilter(new PorterDuffColorFilter(liked ? 0xFFFF3B5C : 0xD9FFFFFF, PorterDuff.Mode.SRC_IN));
        likeButton.setContentDescription(getString(liked ? R.string.PengramPlayerFavRemoveAction : R.string.PengramPlayerFavAddAction));
    }

    private void showTrackInfoDialog() {
        if (getContext() == null) {
            return;
        }
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null || !playing.isMusic()) {
            return;
        }
        final PengramTrackRecognizer.TrackInfo info = recognizedTrackInfo != null
                ? recognizedTrackInfo : PengramTrackRecognizer.getCached(playing);
        final String[] cleanPair = org.telegram.messenger.PengramMusicMeta.resolveDisplayPair(playing);
        final String dispTitle = (info != null && !TextUtils.isEmpty(info.title)) ? info.title : cleanPair[1];
        final String dispArtist = (info != null && !TextUtils.isEmpty(info.artist)) ? info.artist : cleanPair[0];
        final StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.PengramTrackInfoTitleLabel)).append(": ").append(dispTitle);
        if (!TextUtils.isEmpty(dispArtist)) {
            sb.append('\n').append(getString(R.string.PengramTrackInfoArtistLabel)).append(": ").append(dispArtist);
        }
        if (info != null) {
            if (!TextUtils.isEmpty(info.album)) {
                sb.append('\n').append(getString(R.string.PengramTrackInfoAlbumLabel)).append(": ").append(info.album);
            }
            if (!TextUtils.isEmpty(info.year)) {
                sb.append('\n').append(getString(R.string.PengramTrackInfoYearLabel)).append(": ").append(info.year);
            }
            if (!TextUtils.isEmpty(info.genre)) {
                sb.append('\n').append(getString(R.string.PengramTrackInfoGenreLabel)).append(": ").append(info.genre);
            }
            final String qBadge = info.qualityBadge();
            if (!TextUtils.isEmpty(qBadge)) {
                sb.append('\n').append(getString(R.string.PengramTrackInfoQualityLabel)).append(": ").append(qBadge);
            }
            if (!TextUtils.isEmpty(info.source)) {
                sb.append('\n').append(getString(R.string.PengramTrackInfoSourceLabel)).append(": ").append(info.source);
            }
        }
        final org.telegram.ui.ActionBar.AlertDialog.Builder builder =
                new org.telegram.ui.ActionBar.AlertDialog.Builder(getContext(), resourcesProvider);
        builder.setTitle(getString(R.string.PengramTrackInfoHeader));
        builder.setMessage(sb.toString());
        builder.setPositiveButton(getString(R.string.OK), null);
        builder.setNeutralButton(getString(R.string.Copy), (d, w) -> {
            final String copyStr = (!TextUtils.isEmpty(dispArtist) ? dispArtist + " — " : "") + dispTitle;
            AndroidUtilities.addToClipboard(copyStr);
        });
        builder.show();
    }

    private void updateTitle() {
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing == null || !playing.isMusic()) {
            if (isShowing()) {
                dismiss();
            }
            return;
        }
        if (PengramConfig.isSmartTrackRecognition()) {
            final String[] cleanPair = org.telegram.messenger.PengramMusicMeta.resolveDisplayPair(playing);
            titleView.setText(cleanPair[1]);
            artistView.setText(cleanPair[0]);
            recognizedTrackInfo = PengramTrackRecognizer.getCached(playing);
            applyRecognizedTrackInfo(playing, recognizedTrackInfo);
            final int myToken = ++recognitionToken;
            final String targetKey = PengramTrackRecognizer.keyFor(playing);
            PengramTrackRecognizer.recognize(playing, (key, info) -> {
                if (myToken != recognitionToken || !isShowing() || key == null || !key.equals(targetKey)) {
                    return;
                }
                recognizedTrackInfo = info;
                final MessageObject cur = MediaController.getInstance().getPlayingMessageObject();
                if (cur != null) {
                    applyRecognizedTrackInfo(cur, info);
                }
            });
        } else {
            recognizedTrackInfo = null;
            titleView.setText(playing.getMusicTitle());
            artistView.setText(playing.getMusicAuthor());
            if (metaBadgeView != null) {
                metaBadgeView.setVisibility(View.GONE);
            }
        }
        updateLikeButtonState();
        durationView.setText(AndroidUtilities.formatShortDuration((int) playing.getDuration()));
        updateCover(playing);
        playPauseDrawable.setPause(!MediaController.getInstance().isMessagePaused(), false);
        updateProgress(playing);
        // Текст нужен не только стилям с видимой панелью: строка песни живёт
        // ещё и в шапке чата/списка чатов. Раньше ORIGINAL и COMPACT не
        // запускали поиск вовсе, и шапка оставалась без текста, хотя он есть.
        if (PengramConfig.playerStyleHasLyrics(style) || PengramConfig.isHeaderLyrics()) {
            loadLyrics(false);
        }
    }

    private void applyRecognizedTrackInfo(MessageObject playing, PengramTrackRecognizer.TrackInfo info) {
        if (info == null) {
            if (metaBadgeView != null) {
                metaBadgeView.setVisibility(View.GONE);
            }
            return;
        }
        if (!TextUtils.isEmpty(info.title)) {
            titleView.setText(info.title);
        }
        if (!TextUtils.isEmpty(info.artist)) {
            artistView.setText(info.artist);
        }
        if (metaBadgeView != null) {
            final String badge = PengramTrackRecognizer.buildBadgeLine(info);
            if (!TextUtils.isEmpty(badge)) {
                metaBadgeView.setText(badge);
                metaBadgeView.setVisibility(View.VISIBLE);
            } else {
                metaBadgeView.setVisibility(View.GONE);
            }
        }
    }

    private android.graphics.Bitmap shareCoverBitmap() {
        final android.graphics.Bitmap main = coverView.getImageReceiver().getBitmap();
        return main != null ? main : smallCoverView.getImageReceiver().getBitmap();
    }

    private String displayedCoverKey;

    private void updateCover(MessageObject messageObject) {
        final String trackKey = org.telegram.messenger.PengramCovers.keyFor(messageObject);
        if (!TextUtils.equals(trackKey, displayedCoverKey)) {
            displayedCoverKey = trackKey;
            // Don't keep the previous track's background while the new cover loads.
            backgroundView.setCover(null);
            // Don't share the previous track's artwork while the new one loads.
            coverView.setImageDrawable(null);
            smallCoverView.setImageDrawable(null);
        }
        final AudioInfo audioInfo = MediaController.getInstance().getAudioInfo();
        applyCoverShape();
        if (audioInfo != null && (audioInfo.getCover() != null || audioInfo.getSmallCover() != null)) {
            applyCoverBitmap(audioInfo.getCover() != null ? audioInfo.getCover() : audioInfo.getSmallCover());
            return;
        }
        final android.graphics.Bitmap cached = org.telegram.messenger.PengramCovers.getCached(messageObject);
        if (cached != null) {
            applyCoverBitmap(cached);
            return;
        }
        // обложка может лежать в тегах файла — достаём её в фоне и показываем, как только готова
        org.telegram.messenger.PengramCovers.request(messageObject, (key, bitmap) -> {
            final MessageObject now = MediaController.getInstance().getPlayingMessageObject();
            if (bitmap == null || key == null || !key.equals(org.telegram.messenger.PengramCovers.keyFor(now))) {
                return;
            }
            applyCoverBitmap(bitmap);
        });
        final TLRPC.Document document = messageObject.getDocument();
        TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 360) : null;
        if (!(thumb instanceof TLRPC.TL_photoSize) && !(thumb instanceof TLRPC.TL_photoSizeProgressive)) {
            thumb = null;
        }
        final String artworkUrl = messageObject.getArtworkUrl(false);
        final ImageLocation thumbLocation = thumb != null ? ImageLocation.getForDocument(thumb, document) : null;
        if (!TextUtils.isEmpty(artworkUrl)) {
            applyEmptyCover(true);
            coverView.setImage(ImageLocation.getForPath(artworkUrl), null, thumbLocation, null, null, 0, 1, messageObject);
            smallCoverView.setImage(ImageLocation.getForPath(artworkUrl), "44_44", thumbLocation, null, null, 0, 1, messageObject);
        } else if (thumbLocation != null) {
            applyEmptyCover(true);
            coverView.setImage(null, null, thumbLocation, null, null, 0, 1, messageObject);
            smallCoverView.setImage(null, null, thumbLocation, null, null, 0, 1, messageObject);
        } else {
            applyEmptyCover();
            return;
        }
        // A cached image receiver can complete synchronously during setImage.
        // Do not erase the background it just supplied.
    }

    /**
     * Какого цвета плеер.
     *
     * «Из обложки» остаётся поведением по умолчанию, но когда картинки нет
     * (а это ровно тот случай, ради которого появился пингвин), брать цвет
     * неоткуда — тогда подхватывается акцент темы Telegram. Material You
     * снимает цвет с обоев системы; на Android младше 12 такой возможности
     * нет, и выбор молча падает обратно на тему.
     */
    private int resolveAccent(Context context, Theme.ResourcesProvider provider) {
        final int mode = PengramConfig.getPlayerAccentMode();
        if (mode == PengramConfig.PLAYER_ACCENT_CUSTOM) {
            return PengramConfig.getPlayerAccentColor();
        }
        if (mode == PengramConfig.PLAYER_ACCENT_MONET) {
            final int monet = monetAccent(context);
            if (monet != 0) {
                return monet;
            }
        }
        if (mode == PengramConfig.PLAYER_ACCENT_THEME || mode == PengramConfig.PLAYER_ACCENT_MONET) {
            return themeAccent(provider);
        }
        return themeAccent(provider);
    }

    /** акцент текущей темы, подтянутый до читаемого на тёмном фоне */
    private static int themeAccent(Theme.ResourcesProvider provider) {
        final int color = Theme.getColor(Theme.key_featuredStickers_addButton, provider);
        final float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.15f + 0.1f);
        hsv[2] = Math.max(hsv[2], 0.85f);
        return Color.HSVToColor(hsv);
    }

    /** цвет обоев системы, если устройство это умеет */
    private static int monetAccent(Context context) {
        if (android.os.Build.VERSION.SDK_INT < 31 || context == null) {
            return 0;
        }
        try {
            return context.getColor(android.R.color.system_accent1_200);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** цвет мог измениться в настройках — обновляем всё, что его носит */
    private void applyAccentToViews() {
        try {
            lyricsView.setColors(accentColor, 0xFFFFFFFF);
            if (beatHalo != null) {
                beatHalo.setAccentColor(accentColor);
            }
            if (penguinHolder != null && penguinHolder.getVisibility() == View.VISIBLE) {
                penguinHolder.setBackground(emptyCoverBackground(coverCornerRadius()));
            }
            seekBarView.invalidate();
        } catch (Throwable ignore) {
        }
    }

    /**
     * Подложка под пингвина.
     *
     * Сам пингвин рисуется на TextureView и фона не имеет, поэтому под него
     * кладётся мягкий прямоугольник в форме обложки — так пустое место
     * выглядит частью оформления, а не дыркой в вёрстке.
     */
    private FrameLayout createPenguinHolder(Context context) {
        final FrameLayout holder = new FrameLayout(context);
        holder.setVisibility(View.GONE);
        if (beatIntensity > 0) {
            // сияние живёт под пингвином и чуть выходит за его границы
            beatHalo = new PengramBeatHalo(context);
            beatHalo.setIntensity(beatIntensity);
            beatHalo.setAccentColor(accentColor);
            holder.addView(beatHalo, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        penguinCover = new PengramPenguinView(context);
        penguinCover.setSkin(PengramConfig.getPenguinSkin());
        penguinCover.setOnTapListener(() -> {
            // тап — маленькая награда за любопытство
            penguinCover.doFlip();
            AndroidUtilities.vibrateCursor(holder);
        });
        holder.addView(penguinCover, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 10, 10, 10, 10));
        return holder;
    }

    /** фон-заглушка в форме обложки */
    private android.graphics.drawable.Drawable emptyCoverBackground(int radius) {
        final android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setCornerRadius(radius);
        // лёгкий оттенок акцента вместо серой пустоты: заглушка выглядит задуманной
        shape.setColor(ColorUtils.setAlphaComponent(accentColor, 54));
        shape.setStroke(dp(1), ColorUtils.setAlphaComponent(accentColor, 70));
        return shape;
    }

    /**
     * Обложки нет — решаем, что показать.
     *
     * Пингвин занимает место картинки, «скрыть» убирает блок целиком (в
     * компактных режимах и в мини-тексте это возвращает экрану половину
     * высоты), «как в оригинале» оставляет прежнюю заливку.
     */
    private void applyEmptyCover() {
        applyEmptyCover(false);
    }

    /**
     * @param pending обложку ещё только грузим: пингвин уже на сцене, но место
     *                под картинку остаётся — она появится поверх него сама.
     *                Так пустой блок не висит, пока сеть думает, и не мигает,
     *                если обложка в итоге не придёт.
     */
    private boolean applyingEmptyCover;

    private void applyEmptyCover(boolean pending) {
        if (applyingEmptyCover) {
            return;   // заглушка сама дёргает приёмник картинки — второй заход не нужен
        }
        applyingEmptyCover = true;
        try {
            applyEmptyCoverInner(pending);
        } finally {
            applyingEmptyCover = false;
        }
    }

    private void applyEmptyCoverInner(boolean pending) {
        final int mode = PengramConfig.getEmptyCoverMode();
        final boolean penguin = mode == PengramConfig.EMPTY_COVER_PENGUIN && penguinHolder != null;
        final boolean hide = mode == PengramConfig.EMPTY_COVER_HIDE;

        if (penguinHolder != null) {
            penguinHolder.setVisibility(penguin ? View.VISIBLE : View.GONE);
            if (penguin) {
                penguinHolder.setBackground(emptyCoverBackground(coverCornerRadius()));
                penguinCover.setSkin(PengramConfig.getPenguinSkin());
                updatePenguinMood();
            } else if (penguinCover != null) {
                penguinCover.setPaused(true);
            }
        }
        coverView.setVisibility((penguin && !pending) || (hide && !pending) ? View.GONE : View.VISIBLE);
        if (!penguin && !hide) {
            coverView.setBackground(emptyCoverBackground(coverCornerRadius()));
        }
        if (!pending && !penguin && !hide) {
            coverView.setImageDrawable(emptyCoverBackground(coverCornerRadius()));
        }
        if (!pending && (smallCoverView.getVisibility() != View.GONE || !hide)) {
            if (hide) {
                smallCoverView.setVisibility(View.GONE);
            } else if (style == PengramConfig.PLAYER_STYLE_LYRICS) {
                smallCoverView.setVisibility(View.VISIBLE);
                if (penguin) {
                    // в маленьком квадрате живой пингвин не читается — ставим его силуэт
                    final android.graphics.drawable.Drawable glyph = getContext().getResources()
                            .getDrawable(R.drawable.pengram_penguin_glyph).mutate();
                    glyph.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
                    final android.graphics.drawable.LayerDrawable layers =
                            new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                                    emptyCoverBackground(dp(10)), glyph});
                    layers.setLayerInset(1, dp(9), dp(9), dp(9), dp(9));
                    smallCoverView.setImageDrawable(layers);
                } else {
                    smallCoverView.setImageDrawable(emptyCoverBackground(dp(10)));
                }
            }
        }
        backgroundView.setCover(null);
    }

    /** радиус, который сейчас у обложки — чтобы заглушка повторяла её форму */
    private int coverCornerRadius() {
        final int shape = PengramConfig.getCoverShape();
        if (shape == PengramConfig.COVER_SHAPE_CIRCLE) {
            return dp(1000);
        }
        if (shape == PengramConfig.COVER_SHAPE_SQUARE) {
            return 0;
        }
        return dp(18);
    }

    /** пингвин танцует под музыку и дремлет на паузе */
    private void updatePenguinMood() {
        if (penguinCover == null || penguinHolder == null || penguinHolder.getVisibility() != View.VISIBLE) {
            return;
        }
        final boolean paused = MediaController.getInstance().isMessagePaused();
        penguinCover.setPaused(false);
        if (!PengramConfig.isPenguinDancing()) {
            penguinCover.setDanceLoop(false);
            penguinCover.setSleeping(false);
            return;
        }
        penguinCover.setSleeping(paused);
        penguinCover.setDanceLoop(!paused);
        if (paused && beatHalo != null) {
            beatHalo.reset();
        }
    }

    /** обложка нашлась — пингвин уходит со сцены */
    private void hideEmptyCover() {
        if (penguinHolder != null && penguinHolder.getVisibility() != View.GONE) {
            penguinHolder.setVisibility(View.GONE);
            if (penguinCover != null) {
                penguinCover.setDanceLoop(false);
                penguinCover.setPaused(true);
            }
        }
        coverView.setVisibility(View.VISIBLE);
        coverView.setBackground(null);
        if (style == PengramConfig.PLAYER_STYLE_LYRICS) {
            smallCoverView.setVisibility(View.VISIBLE);
        }
    }

    private void applyCoverBitmap(android.graphics.Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        hideEmptyCover();
        coverView.setImageBitmap(bitmap);
        smallCoverView.setImageBitmap(bitmap);
        backgroundView.setCover(bitmap);
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
            pengramPrefetchNext();
            if (queueShown) {
                if (panelTab == 1) {
                    loadChatTracks();
                }
                updateQueue();
            }
        } else if (id == NotificationCenter.messagePlayingPlayStateChanged) {
            playPauseDrawable.setPause(!MediaController.getInstance().isMessagePaused(), true);
            updatePenguinMood();
        } else if (id == NotificationCenter.mediaDidLoad) {
            if (args.length > 7 && args[0] instanceof Long && (Long) args[0] == chatTracksDialogId
                    && args[3] instanceof Integer && (Integer) args[3] == chatTracksGuid
                    && args[4] instanceof Integer && (Integer) args[4] == MediaDataController.MEDIA_MUSIC
                    && args[7] instanceof Integer && (Integer) args[7] == chatTracksRequestIndex) {
                if (chatTracksTimeout != null) {
                    AndroidUtilities.cancelRunOnUIThread(chatTracksTimeout);
                    chatTracksTimeout = null;
                }
                chatTracksLoading = false;
                chatTracksLoadError = false;
                int received = 0;
                if (args[2] instanceof java.util.ArrayList) {
                    for (Object object : (java.util.ArrayList<?>) args[2]) {
                        if (object instanceof MessageObject && ((MessageObject) object).isMusic()) {
                            final MessageObject track = (MessageObject) object;
                            if (chatTrackIds.add(track.getId())) {
                                chatTracks.add(track);
                                received++;
                                if (chatTracksMinLoadedId == 0 || track.getId() < chatTracksMinLoadedId) {
                                    chatTracksMinLoadedId = track.getId();
                                }
                            }
                        }
                    }
                }
                chatTracksEndReached = received == 0 || args[5] instanceof Boolean && (Boolean) args[5];
                if (queueShown && panelTab == 1) updateQueue();
            }
        } else if (id == NotificationCenter.musicPlaybackOrderChanged) {
            updateRepeatButton();
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

    /**
     * Подписка на пульс музыки.
     *
     * Пока слушателя нет, плеер вообще не тратит время на спектр — поэтому
     * подписываемся только на время, когда шторка открыта, и обязательно
     * отписываемся при закрытии.
     */
    private void startPulse() {
        if (beatIntensity <= 0 || pulseListener != null) {
            return;
        }
        pulseListener = new PengramAudioPulse.Listener() {
            @Override
            public void onPulse(float level, float bass) {
                applyPulse(level, bass);
            }

            @Override
            public void onBeat(float power) {
                applyBeat(power);
            }
        };
        PengramAudioPulse.addListener(pulseListener);
    }

    private void stopPulse() {
        if (pulseListener != null) {
            PengramAudioPulse.removeListener(pulseListener);
            pulseListener = null;
        }
    }

    private void applyPulse(float level, float bass) {
        if (beatHalo != null) {
            beatHalo.onPulse(level, bass);
        }
        if (penguinCover != null) {
            penguinCover.setEnergy(level);
        }
        if (coverView != null && coverView.getVisibility() == View.VISIBLE) {
            // обложка едва заметно «дышит» вместе с басом
            final float scale = 1f + Math.min(0.045f, bass * 0.03f * beatIntensity);
            coverView.setScaleX(scale);
            coverView.setScaleY(scale);
        }
    }

    private void applyBeat(float power) {
        if (beatHalo != null) {
            beatHalo.onBeat(power);
        }
        if (penguinCover != null && penguinHolder != null && penguinHolder.getVisibility() == View.VISIBLE) {
            penguinCover.pulse(Math.min(1f, power * beatIntensity));
        }
    }

    @Override
    public void dismiss() {
        super.dismiss();
        stopPulse();
        if (chatTracksTimeout != null) {
            AndroidUtilities.cancelRunOnUIThread(chatTracksTimeout);
            chatTracksTimeout = null;
        }
        org.telegram.messenger.PengramMusicFavorites.removeListener(favStateListener);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidReset);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.musicPlaybackOrderChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.mediaDidLoad);
    }

    @Override
    protected boolean canDismissWithSwipe() {
        // Свайп вверх по очереди — это прокрутка списка, а не жест закрытия плеера.
        return !queueShown && super.canDismissWithSwipe();
    }

    /** какой плеер показывать — наш или оригинальный */
    public static BottomSheet create(Context context, Theme.ResourcesProvider resourcesProvider) {
        if (PengramConfig.isNewPlayer()) {
            return new PengramMusicPlayerSheet(context, resourcesProvider);
        }
        return new AudioPlayerAlert(context, resourcesProvider);
    }
}
