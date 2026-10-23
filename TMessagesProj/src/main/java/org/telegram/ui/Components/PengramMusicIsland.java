package org.telegram.ui.Components;

import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

/**
 * Островок свёрнутого плеера: пока музыка играет, а плеер закрыт (и открытый
 * чат не держит собственную шапку аудио), трек живёт маленькой капсулой
 * сверху экрана — название, исполнитель, play/pause, следующий трек, закрыть.
 * Отдельный тумблер «жидкое стекло» (playerIslandGlass) меняет плоский фон
 * капсулы на настоящее размытие экрана под ней; без тумблера — обычный фон
 * цвета key_inappPlayerBackground, как у штатной шапки аудиоплеера.
 *
 * Состояние плеера читается опросом раз в 400 мс: он универсален и для
 * текущего аккаунта, и для любого экрана, без привязки к уведомлениям.
 */
public final class PengramMusicIsland extends FrameLayout {

    private static PengramMusicIsland instance;

    private static final int TICK_MS = 400;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final TextView titleView;
    private final TextView performerView;
    private final ImageView playPauseView;
    private final ImageView closeView;
    private final PengramTrackButton nextView;
    private final PlayPauseDrawable playPauseDrawable;

    private PengramSurfaceGlass glass;
    private boolean userClosed;
    private boolean active;
    private boolean lastPaused;
    private MessageObject shown;

    private final Runnable tick = this::refreshLoop;

    private void refreshLoop() {
        if (!active) return;
        try {
            refresh();
        } catch (Throwable ignore) {
            // опрос не должен ронять кадр даже на неожиданном состоянии плеера
        }
        ticker.postDelayed(this::refreshLoop, TICK_MS);
    }

    private PengramMusicIsland(Context context) {
        super(context);

        final GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(AndroidUtilities.dp(22));
        background.setColor(Theme.getColor(Theme.key_inappPlayerBackground, (Theme.ResourcesProvider) null));
        setBackground(background);
        setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(6), AndroidUtilities.dp(6), AndroidUtilities.dp(6));

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        playPauseDrawable = new PlayPauseDrawable(16);
        playPauseDrawable.setColor(Theme.getColor(Theme.key_inappPlayerPlayPause, (Theme.ResourcesProvider) null));

        playPauseView = new ImageView(context);
        playPauseView.setScaleType(ImageView.ScaleType.CENTER);
        playPauseView.setImageDrawable(playPauseDrawable);
        playPauseView.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_inappPlayerPlayPause, (Theme.ResourcesProvider) null), PorterDuff.Mode.MULTIPLY));
        playPauseView.setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_inappPlayerPlayPause, (Theme.ResourcesProvider) null) & 0x19ffffff, 1, AndroidUtilities.dp(18)));
        playPauseView.setContentDescription("Play or pause");
        playPauseView.setOnClickListener(v -> {
            final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing == null) return;
            if (MediaController.getInstance().isMessagePaused()) {
                MediaController.getInstance().playMessage(playing);
            } else {
                MediaController.getInstance().pauseMessage(playing);
            }
            lastPaused = MediaController.getInstance().isMessagePaused();
            playPauseDrawable.setPause(!lastPaused, true);
        });
        row.addView(playPauseView, new LinearLayout.LayoutParams(
                AndroidUtilities.dp(36), AndroidUtilities.dp(36)));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.setPadding(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(4), 0);

        titleView = new TextView(context);
        titleView.setSingleLine(true);
        titleView.setLines(1);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        titleView.setMarqueeRepeatLimit(-1);
        titleView.setSelected(true);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        titleView.setTextColor(Theme.getColor(Theme.key_inappPlayerTitle, (Theme.ResourcesProvider) null));
        texts.addView(titleView, new LinearLayout.LayoutParams(
                AndroidUtilities.dp(150), LayoutParams.WRAP_CONTENT));

        performerView = new TextView(context);
        performerView.setSingleLine(true);
        performerView.setLines(1);
        performerView.setMaxLines(1);
        performerView.setEllipsize(TextUtils.TruncateAt.END);
        performerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        performerView.setTextColor(Theme.getColor(Theme.key_inappPlayerPerformer, (Theme.ResourcesProvider) null));
        texts.addView(performerView, new LinearLayout.LayoutParams(
                AndroidUtilities.dp(150), LayoutParams.WRAP_CONTENT));

        row.addView(texts, new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        nextView = new PengramTrackButton(context, true);
        nextView.setContentDescription("Next track");
        nextView.setBackground(Theme.createSelectorDrawable(0x22FFFFFF, 1, AndroidUtilities.dp(17)));
        nextView.setOnClickListener(v -> MediaController.getInstance().playNextMessage());
        row.addView(nextView, new LinearLayout.LayoutParams(
                AndroidUtilities.dp(34), AndroidUtilities.dp(34)));

        closeView = new ImageView(context);
        closeView.setScaleType(ImageView.ScaleType.CENTER);
        closeView.setImageResource(R.drawable.miniplayer_close);
        closeView.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_inappPlayerClose, (Theme.ResourcesProvider) null), PorterDuff.Mode.MULTIPLY));
        closeView.setContentDescription("Close");
        closeView.setOnClickListener(v -> {
            userClosed = true;
            refresh();
        });
        row.addView(closeView, new LinearLayout.LayoutParams(
                AndroidUtilities.dp(34), AndroidUtilities.dp(34)));

        // тап по корпусу (мимо кнопок) разворачивает полный плеер
        setOnClickListener(v -> {
            final Activity activity = AndroidUtilities.findActivity(getContext());
            final Activity target = activity != null ? activity : LaunchActivity.instance;
            if (target == null) return;
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            PengramMusicPlayerSheet.create(target, null).show();
        });

        setVisibility(GONE);
    }

    /** Глобальное окно: капсула лежит поверх контента приложения. */
    public static void attach(ViewGroup content) {
        if (instance != null || content == null) return;
        final View backdrop = content.getChildCount() > 0 ? content.getChildAt(0) : null;
        final PengramMusicIsland island = new PengramMusicIsland(content.getContext());
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        params.topMargin = AndroidUtilities.statusBarHeight + AndroidUtilities.dp(6);
        content.addView(island, params);
        if (backdrop != null) {
            island.glass = new PengramSurfaceGlass(island, backdrop, null, PengramConfig::isPlayerIslandGlass);
        }
        instance = island;
        island.active = true;
        island.refresh();
        island.ticker.postDelayed(island.tick, TICK_MS);
    }

    public static void detach() {
        if (instance == null) return;
        instance.active = false;
        instance.ticker.removeCallbacks(instance.tick);
        instance.glass = null;
        final ViewGroup parent = (ViewGroup) instance.getParent();
        if (parent != null) parent.removeView(instance);
        instance = null;
    }

    private void refresh() {
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        final boolean music = playing != null && playing.getId() != 0 && !playing.isVideo() && playing.isMusic();

        if (playing == null) {
            // трек кончился — крестик больше не прячет следующий запуск
            userClosed = false;
        }
        if (playing != shown) {
            shown = playing;
            userClosed = false;
            if (music) {
                final String title = playing.getMusicTitle(true);
                final String performer = playing.getMusicAuthor(true);
                if (!TextUtils.equals(titleView.getText(), title)) {
                    titleView.setText(title);
                }
                if (!TextUtils.equals(performerView.getText(), performer)) {
                    performerView.setText(performer);
                }
            }
        }

        boolean paused = !music || MediaController.getInstance().isMessagePaused();
        if (paused != lastPaused) {
            lastPaused = paused;
            playPauseDrawable.setPause(paused, false);
        }

        final boolean show = music && !userClosed && !chatHeaderOwnsPlayer();
        if (show != (getVisibility() == VISIBLE)) {
            setVisibility(show ? VISIBLE : GONE);
        }
    }

    /**
     * В открытом чате музыка уже живёт в штатной шапке аудиоплеера
     * (FragmentContextView): дублировать её островком поверх не нужно.
     */
    private boolean chatHeaderOwnsPlayer() {
        final BaseFragment fragment = LaunchActivity.getLastFragmentIncludeMainTabs();
        if (!(fragment instanceof org.telegram.ui.ChatActivity)) return false;
        final FragmentContextView header = ((org.telegram.ui.ChatActivity) fragment).getFragmentContextView();
        return header != null && header.getVisibility() == VISIBLE;
    }
}
