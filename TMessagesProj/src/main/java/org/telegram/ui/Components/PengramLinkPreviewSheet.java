package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.PengramLinkAssistant;
import org.telegram.messenger.PengramLinkResolver;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Интерактивная шторка предпросмотра контента и экран выбора при ошибке
 * для «Помощника ссылок» (TikTok / YouTube).
 *
 * Возможности предпросмотра:
 *  • Сетка всех фотографий поста: одиночное нажатие сразу отправляет выбранный кадр,
 *    удержание (или тап по кружку в углу) включает мультивыбор 2+ фотографий.
 *  • Отправка всех или только отмеченных фотографий альбомом либо склейка в MP4-видео
 *    с оригинальным звуком TikTok.
 *  • Карточка предпросмотра видео с обложкой, длительностью и автором.
 *  • Переключение режима подписи и звука на лету + быстрый переход в настройки.
 */
public final class PengramLinkPreviewSheet {

    public interface PreviewCallbacks {
        void onSendPhotos(@Nullable List<Integer> indices, int captionMode);
        void onStitchPhotos(@Nullable List<Integer> indices, int captionMode, boolean withAudio);
        void onSendVideo(int captionMode);
        default void onSendAudioOnly(int captionMode) {}
        void onSendPlainLink();
        void onCancelKeepInField();
        void onOpenSettings();
    }

    public static int platformBrandColor(int platform, int fallbackAccent) {
        switch (platform) {
            case PengramLinkResolver.PLATFORM_YOUTUBE: return 0xFFFF3B30;
            case PengramLinkResolver.PLATFORM_TIKTOK: return 0xFFFF2D55;
            case PengramLinkResolver.PLATFORM_INSTAGRAM: return 0xFFE1306C;
            case PengramLinkResolver.PLATFORM_TWITTER: return 0xFF1D9BF0;
            case PengramLinkResolver.PLATFORM_PINTEREST: return 0xFFE60023;
            case PengramLinkResolver.PLATFORM_VK: return 0xFF0077FF;
            case PengramLinkResolver.PLATFORM_RUTUBE: return 0xFF00A8E8;
            case PengramLinkResolver.PLATFORM_REDDIT: return 0xFFFF4500;
            case PengramLinkResolver.PLATFORM_SOUNDCLOUD: return 0xFFFF5500;
            case PengramLinkResolver.PLATFORM_YANDEX: return 0xFFFC3F1D;
            default: return fallbackAccent;
        }
    }

    public interface FallbackCallbacks {
        void onSendPlainLink();
        void onRetry();
        void onKeepInField();
        void onOpenSettings();
    }

    private static final ExecutorService THUMB_EXECUTOR = Executors.newFixedThreadPool(3);
    private static final LruCache<String, Bitmap> THUMB_CACHE = new LruCache<String, Bitmap>(16 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };

    private PengramLinkPreviewSheet() {
    }

    public static BottomSheet showPreview(
            @NonNull Context context,
            @Nullable Theme.ResourcesProvider resourcesProvider,
            @NonNull PengramLinkAssistant.Request req,
            @NonNull PengramLinkResolver.Resolved resolved,
            @NonNull PreviewCallbacks callbacks) {

        final BottomSheet sheet = new BottomSheet(context, false, resourcesProvider);
        sheet.fixNavigationBar();

        final int bgColor = Theme.getColor(Theme.key_dialogBackground, resourcesProvider);
        final int textColor = Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider);
        final int subColor = Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider);
        final int accentColor = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        final int accentTextColor = Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider);
        final boolean isDark = ColorUtils.calculateLuminance(bgColor) < 0.45;
        final int cardBg = ColorUtils.blendARGB(bgColor, isDark ? 0xFFFFFFFF : 0xFF000000, isDark ? 0.07f : 0.045f);

        final boolean[] actionTaken = new boolean[]{false};
        final int[] captionMode = new int[]{PengramConfig.getLinkCaptionMode()};
        final boolean hasSoundTrack = resolved.isPhotos() && !resolved.audioUrl.isEmpty();
        final boolean[] soundEnabled = new boolean[]{hasSoundTrack && PengramConfig.isLinkSlideAudioEnabled()};
        final Set<Integer> selected = new LinkedHashSet<>();
        final boolean[] multiSelectMode = new boolean[]{false};
        final List<PhotoTileView> tileViews = new ArrayList<>();

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(14));

        // --- Header ---
        final LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        final LinearLayout badgeRow = new LinearLayout(context);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setGravity(Gravity.CENTER_VERTICAL);

        final TextView platformPill = new TextView(context);
        platformPill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        platformPill.setTypeface(AndroidUtilities.bold());
        platformPill.setPadding(dp(8), dp(2), dp(8), dp(2));
        final boolean isTikTok = resolved.platform == PengramLinkResolver.PLATFORM_TIKTOK;
        final int pillTone = platformBrandColor(resolved.platform, accentColor);
        platformPill.setTextColor(pillTone);
        platformPill.setBackground(roundRect(ColorUtils.setAlphaComponent(pillTone, isDark ? 46 : 30), dp(6)));
        String pillLabel = PengramLinkAssistant.platformDisplayName(resolved.platform, req.url);
        if (resolved.isPhotos()) {
            pillLabel += " · " + resolved.photos.size();
        }
        platformPill.setText(pillLabel);
        badgeRow.addView(platformPill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        if (!resolved.author.isEmpty()) {
            final TextView authorTv = new TextView(context);
            authorTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            authorTv.setTextColor(subColor);
            authorTv.setSingleLine(true);
            authorTv.setEllipsize(TextUtils.TruncateAt.END);
            final boolean handlePrefix = isTikTok
                    || resolved.platform == PengramLinkResolver.PLATFORM_INSTAGRAM
                    || resolved.platform == PengramLinkResolver.PLATFORM_TWITTER;
            final String cleanAuthor = handlePrefix && !resolved.author.startsWith("@")
                    ? "@" + resolved.author : resolved.author;
            authorTv.setText("  " + cleanAuthor);
            badgeRow.addView(authorTv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        }
        titleCol.addView(badgeRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView headerTitle = new TextView(context);
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        headerTitle.setTypeface(AndroidUtilities.bold());
        headerTitle.setTextColor(textColor);
        headerTitle.setSingleLine(true);
        headerTitle.setEllipsize(TextUtils.TruncateAt.END);
        headerTitle.setText(LocaleController.getString(resolved.isPhotos()
                ? R.string.PengramLinkPreviewSheetPhotosTitle
                : (resolved.isAudio() ? R.string.PengramLinkPreviewSheetAudioTitle
                : R.string.PengramLinkPreviewSheetVideoTitle)));
        titleCol.addView(headerTitle, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        header.addView(titleCol, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        final ImageView settingsBtn = createHeaderIconButton(context, R.drawable.msg_settings, subColor, cardBg);
        settingsBtn.setContentDescription(LocaleController.getString(R.string.PengramLinkOpenSettings));
        settingsBtn.setOnClickListener(v -> {
            actionTaken[0] = true;
            sheet.dismiss();
            callbacks.onOpenSettings();
        });
        header.addView(settingsBtn, LayoutHelper.createLinear(36, 36, 0, 0, 6, 0));

        final ImageView closeBtn = createHeaderIconButton(context, R.drawable.ic_ab_close, subColor, cardBg);
        closeBtn.setContentDescription(LocaleController.getString(R.string.Close));
        closeBtn.setOnClickListener(v -> {
            actionTaken[0] = true;
            sheet.dismiss();
            callbacks.onCancelKeepInField();
        });
        header.addView(closeBtn, LayoutHelper.createLinear(36, 36));

        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        // --- Quick chips row (caption mode + sound toggle; wraps cleanly on narrow screens) ---
        final FlowChipsLayout chipsRow = new FlowChipsLayout(context, dp(6), dp(6));

        final TextView captionChip = createChipView(context, textColor, cardBg);
        updateCaptionChipText(captionChip, captionMode[0]);
        captionChip.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            captionMode[0] = (captionMode[0] + 1) % 3;
            updateCaptionChipText(captionChip, captionMode[0]);
        });
        chipsRow.addView(captionChip, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));

        if (hasSoundTrack && resolved.photos.size() > 1) {
            final TextView soundChip = createChipView(context, soundEnabled[0] ? accentColor : subColor, cardBg);
            soundChip.setText(LocaleController.getString(soundEnabled[0]
                    ? R.string.PengramLinkPreviewSoundChipOn
                    : R.string.PengramLinkPreviewSoundChipOff));
            soundChip.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                soundEnabled[0] = !soundEnabled[0];
                soundChip.setTextColor(soundEnabled[0] ? accentColor : subColor);
                soundChip.setText(LocaleController.getString(soundEnabled[0]
                        ? R.string.PengramLinkPreviewSoundChipOn
                        : R.string.PengramLinkPreviewSoundChipOff));
            });
            chipsRow.addView(soundChip, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        }

        if (!resolved.isPhotos() && !resolved.isAudio()) {
            final int[] qMode = new int[]{PengramConfig.getLinkQualityMode()};
            final TextView qualityChip = createChipView(context, textColor, cardBg);
            final Runnable syncQualityChip = () -> {
                final int res = qMode[0] == PengramConfig.LINK_QUALITY_SAVER_720
                        ? R.string.PengramLinkQualityChip720
                        : (qMode[0] == PengramConfig.LINK_QUALITY_MAX
                        ? R.string.PengramLinkQualityChipMax
                        : R.string.PengramLinkQualityChip1080);
                qualityChip.setText(LocaleController.getString(res));
            };
            syncQualityChip.run();
            qualityChip.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                qMode[0] = (qMode[0] + 1) % 3;
                PengramConfig.setLinkQualityMode(qMode[0]);
                syncQualityChip.run();
            });
            chipsRow.addView(qualityChip, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        }

        final boolean canSendAsAudio = PengramConfig.isLinkAudioChipEnabled()
                && !resolved.isAudio()
                && (!resolved.isPhotos() || !resolved.audioUrl.isEmpty());
        if (canSendAsAudio) {
            final TextView audioOnlyChip = createChipView(
                    context,
                    accentColor,
                    ColorUtils.setAlphaComponent(accentColor, isDark ? 42 : 24));
            audioOnlyChip.setText(LocaleController.getString(R.string.PengramLinkPreviewSendAudioChip));
            audioOnlyChip.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                actionTaken[0] = true;
                sheet.dismiss();
                callbacks.onSendAudioOnly(captionMode[0]);
            });
            chipsRow.addView(audioOnlyChip, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        }

        root.addView(chipsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        // --- Hint + Select-All bar for multi-photo interaction (selectAllBtn guaranteed inside bounds) ---
        final TextView hintView = new TextView(context);
        final TextView selectAllBtn = new TextView(context);
        if (resolved.isPhotos() && resolved.photos.size() > 1) {
            final LinearLayout selectionRow = new LinearLayout(context);
            selectionRow.setOrientation(LinearLayout.HORIZONTAL);
            selectionRow.setGravity(Gravity.CENTER_VERTICAL);

            hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            hintView.setTextColor(subColor);
            hintView.setText(LocaleController.getString(R.string.PengramLinkPreviewTapHint));
            selectionRow.addView(hintView, LayoutHelper.createLinear(
                    0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

            selectAllBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            selectAllBtn.setTypeface(AndroidUtilities.bold());
            selectAllBtn.setTextColor(accentColor);
            selectAllBtn.setSingleLine(true);
            selectAllBtn.setGravity(Gravity.CENTER);
            selectAllBtn.setPadding(dp(10), dp(5), dp(10), dp(5));
            selectAllBtn.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                    dp(12),
                    ColorUtils.setAlphaComponent(accentColor, isDark ? 36 : 20),
                    ColorUtils.setAlphaComponent(accentColor, 56)));
            selectAllBtn.setText(LocaleController.getString(R.string.PengramLinkPreviewSelectAll));
            selectionRow.addView(selectAllBtn, LayoutHelper.createLinear(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

            root.addView(selectionRow, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }

        // --- Action buttons (declared before tiles so selection listeners update them live) ---
        final TextView primaryBtn = createActionButton(context, accentColor, accentTextColor, true);
        final TextView stitchBtn = createActionButton(
                context,
                ColorUtils.setAlphaComponent(accentColor, isDark ? 44 : 28),
                accentColor,
                true);
        final TextView plainLinkBtn = createActionButton(context, cardBg, textColor, false);
        plainLinkBtn.setText(LocaleController.getString(R.string.PengramLinkPreviewSendPlainLink));

        final Runnable syncSelectionUi = () -> {
            if (!resolved.isPhotos()) {
                primaryBtn.setText(LocaleController.getString(resolved.isAudio()
                        ? R.string.PengramLinkPreviewSendAudio
                        : R.string.PengramLinkPreviewSendVideo));
                stitchBtn.setVisibility(View.GONE);
                return;
            }
            final int total = resolved.photos.size();
            final int selCount = selected.size();
            if (total > 1) {
                hintView.setText(LocaleController.getString(multiSelectMode[0]
                        ? R.string.PengramLinkPreviewSelectedHint
                        : R.string.PengramLinkPreviewTapHint));
                selectAllBtn.setText(LocaleController.getString(
                        selCount == total ? R.string.PengramLinkPreviewClearSelection : R.string.PengramLinkPreviewSelectAll));
            }
            for (int i = 0; i < tileViews.size(); i++) {
                tileViews.get(i).setCheckedState(selected.contains(i), multiSelectMode[0] || total > 1);
            }
            if (total == 1) {
                primaryBtn.setText(LocaleController.getString(R.string.PengramLinkPreviewSendSinglePhoto));
                stitchBtn.setVisibility(View.GONE);
            } else if (selCount == 0) {
                primaryBtn.setText(LocaleController.getString(R.string.PengramLinkPreviewSendAllPhotos) + " (" + total + ")");
                stitchBtn.setVisibility(View.VISIBLE);
                final String base = LocaleController.getString(hasSoundTrack && soundEnabled[0]
                        ? R.string.PengramLinkPreviewStitchWithSound
                        : R.string.PengramLinkPreviewStitchVideo);
                stitchBtn.setText(base + " (" + total + ")");
            } else if (selCount == 1) {
                primaryBtn.setText(LocaleController.getString(R.string.PengramLinkPreviewSendSinglePhoto) + " (1)");
                stitchBtn.setVisibility(View.GONE);
            } else {
                primaryBtn.setText(LocaleController.getString(R.string.PengramLinkPreviewSendSelectedPhotos) + " (" + selCount + ")");
                stitchBtn.setVisibility(View.VISIBLE);
                final String base = LocaleController.getString(hasSoundTrack && soundEnabled[0]
                        ? R.string.PengramLinkPreviewStitchWithSound
                        : R.string.PengramLinkPreviewStitchVideo);
                stitchBtn.setText(base + " (" + selCount + ")");
            }
        };

        // --- Scrollable media area (capped at 46% screen height so action buttons never hide) ---
        final int maxMediaHeight = (int) (AndroidUtilities.displaySize.y * 0.46f);
        final ScrollView mediaScroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                final int cappedSpec = MeasureSpec.makeMeasureSpec(maxMediaHeight, MeasureSpec.AT_MOST);
                super.onMeasure(widthMeasureSpec, cappedSpec);
            }
        };
        mediaScroll.setVerticalScrollBarEnabled(false);
        mediaScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        if (resolved.isPhotos()) {
            final int count = resolved.photos.size();
            if (count == 1) {
                final PhotoTileView singleTile = new PhotoTileView(context, 0, cardBg, accentColor, false);
                singleTile.loadThumb(
                        PengramLinkAssistant.getCachedTikTokPhotoFile(req.url, 0),
                        resolved.photos.get(0).thumbUrl, resolved.cookies, resolved.referer);
                singleTile.setOnClickListener(v -> {
                    actionTaken[0] = true;
                    sheet.dismiss();
                    callbacks.onSendPhotos(Collections.singletonList(0), captionMode[0]);
                });
                final FrameLayout holder = new FrameLayout(context);
                holder.addView(singleTile, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 240));
                mediaScroll.addView(holder);
            } else {
                final int cols = count <= 4 ? 2 : 3;
                final int tileHeightDp = cols == 2 ? 168 : 126;
                final LinearLayout gridRows = new LinearLayout(context);
                gridRows.setOrientation(LinearLayout.VERTICAL);

                for (int idx = 0; idx < count; idx += cols) {
                    final LinearLayout row = new LinearLayout(context);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    for (int c = 0; c < cols; c++) {
                        final int photoIndex = idx + c;
                        if (photoIndex < count) {
                            final PhotoTileView tile = new PhotoTileView(context, photoIndex, cardBg, accentColor, true);
                            tile.loadThumb(
                                    PengramLinkAssistant.getCachedTikTokPhotoFile(req.url, photoIndex),
                                    resolved.photos.get(photoIndex).thumbUrl, resolved.cookies, resolved.referer);
                            tile.setTileInteraction(new PhotoTileView.Interaction() {
                                @Override
                                public void onTapBody(int index) {
                                    if (!multiSelectMode[0]) {
                                        tile.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                                        actionTaken[0] = true;
                                        sheet.dismiss();
                                        callbacks.onSendPhotos(Collections.singletonList(index), captionMode[0]);
                                    } else {
                                        togglePhotoIndex(index, selected, multiSelectMode, tile, syncSelectionUi);
                                    }
                                }

                                @Override
                                public void onTapCheck(int index) {
                                    multiSelectMode[0] = true;
                                    togglePhotoIndex(index, selected, multiSelectMode, tile, syncSelectionUi);
                                }

                                @Override
                                public void onLongPress(int index) {
                                    tile.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                                    multiSelectMode[0] = true;
                                    selected.add(index);
                                    syncSelectionUi.run();
                                }
                            });
                            tileViews.add(tile);
                            row.addView(tile, LayoutHelper.createLinear(
                                    0, tileHeightDp, 1f,
                                    c == 0 ? 0 : 4, 0, c == cols - 1 ? 0 : 4, idx + cols < count ? 8 : 0));
                        } else {
                            row.addView(new View(context), LayoutHelper.createLinear(
                                    0, tileHeightDp, 1f,
                                    c == 0 ? 0 : 4, 0, c == cols - 1 ? 0 : 4, 0));
                        }
                    }
                    gridRows.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                }
                mediaScroll.addView(gridRows);
            }

            selectAllBtn.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (selected.size() == resolved.photos.size()) {
                    selected.clear();
                    multiSelectMode[0] = false;
                } else {
                    multiSelectMode[0] = true;
                    for (int i = 0; i < resolved.photos.size(); i++) {
                        selected.add(i);
                    }
                }
                syncSelectionUi.run();
            });
        } else {
            final VideoCardView videoCard = new VideoCardView(context, resolved, cardBg, textColor);
            videoCard.setOnClickListener(v -> {
                actionTaken[0] = true;
                sheet.dismiss();
                if (resolved.isAudio()) {
                    callbacks.onSendAudioOnly(captionMode[0]);
                } else {
                    callbacks.onSendVideo(captionMode[0]);
                }
            });
            mediaScroll.addView(videoCard, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        root.addView(mediaScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        // --- Bottom buttons ---
        primaryBtn.setOnClickListener(v -> {
            actionTaken[0] = true;
            sheet.dismiss();
            if (resolved.isPhotos()) {
                final List<Integer> list = selected.isEmpty() ? null : new ArrayList<>(selected);
                callbacks.onSendPhotos(list, captionMode[0]);
            } else if (resolved.isAudio()) {
                callbacks.onSendAudioOnly(captionMode[0]);
            } else {
                callbacks.onSendVideo(captionMode[0]);
            }
        });
        root.addView(primaryBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 0, 0, 0, 8));

        stitchBtn.setOnClickListener(v -> {
            actionTaken[0] = true;
            sheet.dismiss();
            final List<Integer> list = selected.isEmpty() ? null : new ArrayList<>(selected);
            callbacks.onStitchPhotos(list, captionMode[0], soundEnabled[0]);
        });
        root.addView(stitchBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 0, 0, 0, 8));

        plainLinkBtn.setOnClickListener(v -> {
            actionTaken[0] = true;
            sheet.dismiss();
            callbacks.onSendPlainLink();
        });
        root.addView(plainLinkBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));

        syncSelectionUi.run();

        sheet.setOnDismissListener(d -> {
            if (!actionTaken[0]) {
                callbacks.onCancelKeepInField();
            }
        });

        sheet.setCustomView(root);
        sheet.show();
        return sheet;
    }

    public static BottomSheet showFallbackChoice(
            @NonNull Context context,
            @Nullable Theme.ResourcesProvider resourcesProvider,
            @NonNull PengramLinkAssistant.Request req,
            @Nullable String reason,
            @NonNull FallbackCallbacks callbacks) {

        final BottomSheet sheet = new BottomSheet(context, false, resourcesProvider);
        sheet.fixNavigationBar();

        final int bgColor = Theme.getColor(Theme.key_dialogBackground, resourcesProvider);
        final int textColor = Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider);
        final int subColor = Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider);
        final int accentColor = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        final int accentTextColor = Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider);
        final boolean isDark = ColorUtils.calculateLuminance(bgColor) < 0.45;
        final int cardBg = ColorUtils.blendARGB(bgColor, isDark ? 0xFFFFFFFF : 0xFF000000, isDark ? 0.07f : 0.045f);

        final boolean[] handled = new boolean[]{false};

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(10), dp(18), dp(14));

        final LinearLayout topRow = new LinearLayout(context);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        final FrameLayout iconCircle = new FrameLayout(context);
        iconCircle.setBackground(roundRect(ColorUtils.setAlphaComponent(accentColor, isDark ? 44 : 28), dp(20)));
        final ImageView iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER);
        iconView.setImageResource(R.drawable.msg_link2);
        iconView.setColorFilter(new PorterDuffColorFilter(accentColor, PorterDuff.Mode.SRC_IN));
        iconCircle.addView(iconView, LayoutHelper.createFrame(40, 40, Gravity.CENTER));
        topRow.addView(iconCircle, LayoutHelper.createLinear(40, 40, 0, 0, 12, 0));

        final TextView titleTv = new TextView(context);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        titleTv.setTypeface(AndroidUtilities.bold());
        titleTv.setTextColor(textColor);
        titleTv.setText(LocaleController.getString(R.string.PengramLinkFallbackSheetTitle));
        topRow.addView(titleTv, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        final ImageView closeBtn = createHeaderIconButton(context, R.drawable.ic_ab_close, subColor, cardBg);
        closeBtn.setOnClickListener(v -> {
            handled[0] = true;
            sheet.dismiss();
            callbacks.onKeepInField();
        });
        topRow.addView(closeBtn, LayoutHelper.createLinear(36, 36));
        root.addView(topRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        final TextView subTv = new TextView(context);
        subTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subTv.setTextColor(subColor);
        subTv.setLineSpacing(dp(2), 1f);
        final boolean tooLong = reason != null && reason.contains("too long");
        subTv.setText(LocaleController.getString(tooLong
                ? R.string.PengramLinkFallbackSheetTooLongSub
                : R.string.PengramLinkFallbackSheetSub));
        root.addView(subTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        final TextView sendLinkBtn = createActionButton(context, accentColor, accentTextColor, true);
        sendLinkBtn.setText(LocaleController.getString(R.string.PengramLinkFallbackBtnSendLink));
        sendLinkBtn.setOnClickListener(v -> {
            handled[0] = true;
            sheet.dismiss();
            callbacks.onSendPlainLink();
        });
        root.addView(sendLinkBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 0, 0, 0, 8));

        if (!tooLong) {
            final TextView retryBtn = createActionButton(
                    context,
                    ColorUtils.setAlphaComponent(accentColor, isDark ? 42 : 26),
                    accentColor,
                    true);
            retryBtn.setText(LocaleController.getString(R.string.PengramLinkFallbackBtnRetry));
            retryBtn.setOnClickListener(v -> {
                handled[0] = true;
                sheet.dismiss();
                callbacks.onRetry();
            });
            root.addView(retryBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 0, 0, 0, 8));
        }

        final TextView keepBtn = createActionButton(context, cardBg, textColor, false);
        keepBtn.setText(LocaleController.getString(R.string.PengramLinkFallbackBtnKeep));
        keepBtn.setOnClickListener(v -> {
            handled[0] = true;
            sheet.dismiss();
            callbacks.onKeepInField();
        });
        root.addView(keepBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 0, 0, 10));

        final TextView settingsLink = new TextView(context);
        settingsLink.setGravity(Gravity.CENTER);
        settingsLink.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        settingsLink.setTextColor(subColor);
        settingsLink.setPadding(dp(8), dp(6), dp(8), dp(4));
        settingsLink.setText(LocaleController.getString(R.string.PengramLinkOpenSettings));
        settingsLink.setOnClickListener(v -> {
            handled[0] = true;
            sheet.dismiss();
            callbacks.onOpenSettings();
        });
        root.addView(settingsLink, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        sheet.setOnDismissListener(d -> {
            if (!handled[0]) {
                callbacks.onKeepInField();
            }
        });

        sheet.setCustomView(root);
        sheet.show();
        return sheet;
    }

    private static void togglePhotoIndex(int index, Set<Integer> selected, boolean[] multiSelectMode,
                                         View hapticView, Runnable syncUi) {
        hapticView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (selected.contains(index)) {
            selected.remove(index);
            if (selected.isEmpty()) {
                multiSelectMode[0] = false;
            }
        } else {
            selected.add(index);
        }
        syncUi.run();
    }

    private static void updateCaptionChipText(TextView chip, int mode) {
        final int res;
        if (mode == PengramConfig.LINK_CAPTION_AUTHOR) {
            res = R.string.PengramLinkCaptionAuthor;
        } else if (mode == PengramConfig.LINK_CAPTION_NONE) {
            res = R.string.PengramLinkCaptionNone;
        } else {
            res = R.string.PengramLinkCaptionFull;
        }
        chip.setText(LocaleController.getString(R.string.PengramLinkCaptionMode) + ": " + LocaleController.getString(res));
    }

    private static ImageView createHeaderIconButton(Context context, int iconRes, int tint, int bg) {
        final ImageView iv = new ImageView(context);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setImageResource(iconRes);
        iv.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN));
        iv.setBackground(Theme.createSimpleSelectorCircleDrawable(dp(36), bg, ColorUtils.setAlphaComponent(tint, 32)));
        return iv;
    }

    private static TextView createChipView(Context context, int textColor, int bg) {
        final TextView tv = new TextView(context);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tv.setTypeface(AndroidUtilities.bold());
        tv.setTextColor(textColor);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(dp(10), 0, dp(10), 0);
        tv.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(15), bg, ColorUtils.setAlphaComponent(textColor, 26)));
        return tv;
    }

    private static final class FlowChipsLayout extends ViewGroup {
        private final int hGap;
        private final int vGap;

        FlowChipsLayout(Context context, int hGap, int vGap) {
            super(context);
            this.hGap = hGap;
            this.vGap = vGap;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int maxWidth = MeasureSpec.getSize(widthMeasureSpec);
            final int availWidth = Math.max(0, maxWidth - getPaddingLeft() - getPaddingRight());
            int x = 0;
            int y = getPaddingTop();
            int rowHeight = 0;
            final int count = getChildCount();
            for (int i = 0; i < count; i++) {
                final View child = getChildAt(i);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                final LayoutParams lp = child.getLayoutParams();
                final int childHeightSpec = lp != null && lp.height > 0
                        ? MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY)
                        : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                child.measure(MeasureSpec.makeMeasureSpec(availWidth, MeasureSpec.AT_MOST), childHeightSpec);
                final int cw = child.getMeasuredWidth();
                final int ch = child.getMeasuredHeight();
                if (x > 0 && x + cw > availWidth) {
                    y += rowHeight + vGap;
                    x = 0;
                    rowHeight = 0;
                }
                x += cw + hGap;
                rowHeight = Math.max(rowHeight, ch);
            }
            final int totalHeight = y + rowHeight + getPaddingBottom();
            setMeasuredDimension(maxWidth, resolveSize(totalHeight, heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            final int availWidth = Math.max(0, (r - l) - getPaddingLeft() - getPaddingRight());
            int x = getPaddingLeft();
            int y = getPaddingTop();
            int rowHeight = 0;
            final int count = getChildCount();
            for (int i = 0; i < count; i++) {
                final View child = getChildAt(i);
                if (child.getVisibility() == GONE) {
                    continue;
                }
                final int cw = child.getMeasuredWidth();
                final int ch = child.getMeasuredHeight();
                if (x > getPaddingLeft() && (x - getPaddingLeft()) + cw > availWidth) {
                    y += rowHeight + vGap;
                    x = getPaddingLeft();
                    rowHeight = 0;
                }
                child.layout(x, y, x + cw, y + ch);
                x += cw + hGap;
                rowHeight = Math.max(rowHeight, ch);
            }
        }
    }

    private static TextView createActionButton(Context context, int bg, int fg, boolean bold) {
        final TextView tv = new TextView(context);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        if (bold) {
            tv.setTypeface(AndroidUtilities.bold());
        }
        tv.setTextColor(fg);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(dp(16), 0, dp(16), 0);
        final int ripple = ColorUtils.setAlphaComponent(fg, 32);
        tv.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(12), bg, ripple));
        return tv;
    }

    private static GradientDrawable roundRect(int color, int radiusPx) {
        final GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusPx);
        d.setColor(color);
        return d;
    }

    private static int dp(float v) {
        return AndroidUtilities.dp(v);
    }

    // --- Асинхронная загрузка миниатюр с дисковым/RAM-кешированием ---

    private static void fetchBitmapAsync(@Nullable File localFile, String url,
                                         Map<String, String> cookies, String referer,
                                         int maxPx, BitmapReceiver receiver) {
        final String cacheKey = (url != null && !url.isEmpty())
                ? url
                : (localFile != null ? "file:" + localFile.getAbsolutePath() : "");
        if (cacheKey.isEmpty()) {
            return;
        }
        final Bitmap cached = THUMB_CACHE.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            receiver.onBitmap(cached);
            return;
        }
        if (localFile != null && localFile.exists() && localFile.length() > 128L) {
            THUMB_EXECUTOR.execute(() -> {
                try {
                    final Bitmap bmp = decodeSampledFile(localFile, maxPx);
                    if (bmp != null) {
                        THUMB_CACHE.put(cacheKey, bmp);
                        AndroidUtilities.runOnUIThread(() -> receiver.onBitmap(bmp));
                        return;
                    }
                } catch (Throwable ignore) {
                }
                fetchBitmapFromNetwork(cacheKey, url, cookies, referer, maxPx, receiver);
            });
            return;
        }
        if (url == null || url.isEmpty()) {
            return;
        }
        THUMB_EXECUTOR.execute(() -> fetchBitmapFromNetwork(cacheKey, url, cookies, referer, maxPx, receiver));
    }

    private static void fetchBitmapAsync(String url, Map<String, String> cookies, String referer,
                                         int maxPx, BitmapReceiver receiver) {
        fetchBitmapAsync(null, url, cookies, referer, maxPx, receiver);
    }

    @Nullable
    private static Bitmap decodeSampledFile(File file, int maxPx) {
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) {
            sample *= 2;
        }
        final BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    private static void fetchBitmapFromNetwork(String cacheKey, String url,
                                               Map<String, String> cookies, String referer,
                                               int maxPx, BitmapReceiver receiver) {
        if (url == null || url.isEmpty()) {
            return;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(12_000);
            conn.setRequestProperty("User-Agent", PengramLinkResolver.UA_DESKTOP);
            if (referer != null && !referer.isEmpty()) {
                conn.setRequestProperty("Referer", referer);
            }
            if (cookies != null && !cookies.isEmpty()) {
                final StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> e : cookies.entrySet()) {
                    if (sb.length() > 0) {
                        sb.append("; ");
                    }
                    sb.append(e.getKey()).append('=').append(e.getValue());
                }
                conn.setRequestProperty("Cookie", sb.toString());
            }
            if (conn.getResponseCode() / 100 != 2) {
                return;
            }
            final ByteArrayOutputStream bos = new ByteArrayOutputStream(32 * 1024);
            try (InputStream in = conn.getInputStream()) {
                final byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (bos.size() + n > 8 * 1024 * 1024) {
                        return;
                    }
                    bos.write(buf, 0, n);
                }
            }
            final byte[] bytes = bos.toByteArray();
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return;
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) {
                sample *= 2;
            }
            final BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            final Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
            if (bmp != null) {
                THUMB_CACHE.put(cacheKey, bmp);
                AndroidUtilities.runOnUIThread(() -> receiver.onBitmap(bmp));
            }
        } catch (Throwable ignore) {
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private interface BitmapReceiver {
        void onBitmap(Bitmap bmp);
    }

    // --- Плитка фотографии с поддержкой одиночного тапа и мультивыбора ---

    private static final class PhotoTileView extends View {
        interface Interaction {
            void onTapBody(int index);
            void onTapCheck(int index);
            void onLongPress(int index);
        }

        private final int photoIndex;
        private final int placeholderColor;
        private final int accentColor;
        private final boolean showBadges;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final RectF rect = new RectF();
        private final RectF dstRect = new RectF();
        private Bitmap bitmap;
        private boolean checked;
        private boolean checkVisible;
        private float scaleFactor = 1f;
        private Drawable checkIcon;
        private Interaction interaction;
        private float lastDownX;
        private float lastDownY;

        PhotoTileView(Context context, int index, int placeholderColor, int accentColor, boolean showBadges) {
            super(context);
            this.photoIndex = index;
            this.placeholderColor = placeholderColor;
            this.accentColor = accentColor;
            this.showBadges = showBadges;
            this.checkVisible = showBadges;
            textPaint.setColor(0xFFFFFFFF);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextSize(dp(11));
        }

        void setTileInteraction(Interaction inter) {
            this.interaction = inter;
            setClickable(true);
            setLongClickable(true);
            setOnClickListener(v -> {
                if (interaction == null) {
                    return;
                }
                if (showBadges && lastDownX >= getWidth() - dp(38) && lastDownY <= dp(38)) {
                    interaction.onTapCheck(photoIndex);
                } else {
                    interaction.onTapBody(photoIndex);
                }
            });
            setOnLongClickListener(v -> {
                if (interaction != null) {
                    interaction.onLongPress(photoIndex);
                    return true;
                }
                return false;
            });
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                lastDownX = event.getX();
                lastDownY = event.getY();
            }
            return super.onTouchEvent(event);
        }

        void loadThumb(@Nullable File localFile, String url, Map<String, String> cookies, String referer) {
            fetchBitmapAsync(localFile, url, cookies, referer, 420, bmp -> {
                this.bitmap = bmp;
                invalidate();
            });
        }

        void setCheckedState(boolean checked, boolean showCheckCircle) {
            final boolean changed = this.checked != checked || this.checkVisible != showCheckCircle;
            this.checked = checked;
            this.checkVisible = showCheckCircle;
            this.scaleFactor = checked ? 0.94f : 1f;
            if (changed) {
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getWidth();
            final int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            canvas.save();
            if (scaleFactor != 1f) {
                canvas.scale(scaleFactor, scaleFactor, w / 2f, h / 2f);
            }

            final float r = dp(12);
            rect.set(0, 0, w, h);
            clipPath.reset();
            clipPath.addRoundRect(rect, r, r, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(placeholderColor);
            canvas.drawRect(rect, paint);

            if (bitmap != null && !bitmap.isRecycled()) {
                final float scale = Math.max((float) w / bitmap.getWidth(), (float) h / bitmap.getHeight());
                final float dw = bitmap.getWidth() * scale;
                final float dh = bitmap.getHeight() * scale;
                dstRect.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
                paint.setColor(0xFFFFFFFF);
                canvas.drawBitmap(bitmap, null, dstRect, paint);
            }

            if (checked) {
                paint.setColor(0x33000000);
                canvas.drawRect(rect, paint);
            }
            canvas.restore();

            if (checked) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2.5f));
                paint.setColor(accentColor);
                rect.inset(dp(1.25f), dp(1.25f));
                canvas.drawRoundRect(rect, r, r, paint);
            }

            if (showBadges) {
                // Номер кадра слева сверху
                final String num = Integer.toString(photoIndex + 1);
                final float numW = Math.max(dp(20), textPaint.measureText(num) + dp(10));
                rect.set(dp(6), dp(6), dp(6) + numW, dp(24));
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x88000000);
                canvas.drawRoundRect(rect, dp(9), dp(9), paint);
                final float tx = rect.centerX() - textPaint.measureText(num) / 2f;
                final float ty = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f;
                canvas.drawText(num, tx, ty, textPaint);

                // Кружок выбора справа сверху
                if (checkVisible) {
                    final float cx = w - dp(16);
                    final float cy = dp(16);
                    final float cr = dp(10);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(checked ? accentColor : 0x66000000);
                    canvas.drawCircle(cx, cy, cr, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(1.5f));
                    paint.setColor(0xFFFFFFFF);
                    canvas.drawCircle(cx, cy, cr, paint);

                    if (checked) {
                        if (checkIcon == null) {
                            checkIcon = getContext().getResources().getDrawable(R.drawable.ic_ab_done).mutate();
                            checkIcon.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
                        }
                        final int isz = dp(14);
                        checkIcon.setBounds((int) (cx - isz / 2f), (int) (cy - isz / 2f),
                                (int) (cx + isz / 2f), (int) (cy + isz / 2f));
                        checkIcon.draw(canvas);
                    }
                }
            }
            canvas.restore();
        }
    }

    // --- Карточка предпросмотра видеоролика ---

    private static final class VideoCardView extends FrameLayout {
        private Bitmap coverBitmap;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Path clipPath = new Path();
        private final RectF rect = new RectF();
        private final RectF dstRect = new RectF();
        private final int placeholderColor;
        private LinearGradient scrim;
        private int scrimHeight;

        VideoCardView(Context context, PengramLinkResolver.Resolved resolved, int cardBg, int textColor) {
            super(context);
            this.placeholderColor = cardBg;
            setWillNotDraw(false);

            // Play circle in center
            final FrameLayout playBadge = new FrameLayout(context);
            playBadge.setBackground(roundRect(0x99000000, dp(26)));
            final ImageView playIcon = new ImageView(context);
            playIcon.setScaleType(ImageView.ScaleType.CENTER);
            playIcon.setImageResource(R.drawable.ic_action_play);
            playIcon.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            playBadge.addView(playIcon, LayoutHelper.createFrame(52, 52, Gravity.CENTER));
            addView(playBadge, LayoutHelper.createFrame(52, 52, Gravity.CENTER));

            // Duration pill
            if (resolved.durationMs > 0) {
                final TextView dur = new TextView(context);
                dur.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
                dur.setTypeface(AndroidUtilities.bold());
                dur.setTextColor(0xFFFFFFFF);
                dur.setPadding(dp(8), dp(3), dp(8), dp(3));
                dur.setBackground(roundRect(0x99000000, dp(8)));
                final long totalSec = resolved.durationMs / 1000L;
                dur.setText(String.format(Locale.US, "%d:%02d", totalSec / 60L, totalSec % 60L));
                addView(dur, LayoutHelper.createFrame(
                        LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.TOP | Gravity.RIGHT, 10, 10, 10, 10));
            }

            // Bottom title overlay
            if (!resolved.title.isEmpty()) {
                final TextView captionTv = new TextView(context);
                captionTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                captionTv.setTextColor(0xFFFFFFFF);
                captionTv.setMaxLines(2);
                captionTv.setEllipsize(TextUtils.TruncateAt.END);
                captionTv.setText(resolved.title);
                addView(captionTv, LayoutHelper.createFrame(
                        LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.LEFT, 12, 12, 12, 10));
            }

            fetchBitmapAsync(resolved.thumbnailUrl, resolved.cookies, resolved.referer, 720, bmp -> {
                this.coverBitmap = bmp;
                invalidate();
            });
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(206), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getWidth();
            final int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            rect.set(0, 0, w, h);
            clipPath.reset();
            clipPath.addRoundRect(rect, dp(14), dp(14), Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clipPath);

            paint.setShader(null);
            paint.setColor(placeholderColor);
            canvas.drawRect(rect, paint);

            if (coverBitmap != null && !coverBitmap.isRecycled()) {
                final float scale = Math.max((float) w / coverBitmap.getWidth(), (float) h / coverBitmap.getHeight());
                final float dw = coverBitmap.getWidth() * scale;
                final float dh = coverBitmap.getHeight() * scale;
                dstRect.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
                paint.setColor(0xFFFFFFFF);
                canvas.drawBitmap(coverBitmap, null, dstRect, paint);
            }

            if (scrim == null || scrimHeight != h) {
                scrimHeight = h;
                scrim = new LinearGradient(0, h * 0.45f, 0, h,
                        0x00000000, 0xCC000000, Shader.TileMode.CLAMP);
            }
            paint.setShader(scrim);
            canvas.drawRect(0, h * 0.45f, w, h, paint);
            paint.setShader(null);
            canvas.restore();
        }
    }
}
