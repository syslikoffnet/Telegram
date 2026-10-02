package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PengramLyricsView;
import org.telegram.ui.Components.PengramPlayerMockView;
import org.telegram.ui.Components.RadioButton;

import java.util.ArrayList;

/**
 * Pengram: визуальный выбор вида плеера.
 * Вместо списка галочек — живые превью: видно, как будет выглядеть плеер и как
 * оживает текст песни. Выбор применяется сразу, с анимацией.
 */
public class PengramPlayerStyleActivity extends BaseFragment {

    private final ArrayList<StyleCard> styleCards = new ArrayList<>();
    private final ArrayList<AnimCard> animCards = new ArrayList<>();
    private final ArrayList<ChipView> bgChips = new ArrayList<>();
    private final ArrayList<ChipView> shapeChips = new ArrayList<>();

    private LinearLayout animSection;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.PengramPlayerLook));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(24));
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        content.addView(header(context, getString(R.string.PengramPlayerLookHeader)));
        content.addView(hint(context, getString(R.string.PengramPlayerLookInfo)));

        for (int a = 0; a < PengramConfig.PLAYER_STYLE_COUNT; ++a) {
            final StyleCard card = new StyleCard(context, a);
            styleCards.add(card);
            content.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, a == 0 ? 2 : 8, 12, 0));
        }

        // ------- анимация текста -------
        animSection = new LinearLayout(context);
        animSection.setOrientation(LinearLayout.VERTICAL);
        content.addView(animSection, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        animSection.addView(header(context, getString(R.string.PengramLyricsAnim)));
        animSection.addView(hint(context, getString(R.string.PengramLyricsAnimInfo)));

        LinearLayout row = null;
        for (int a = 0; a < PengramConfig.LYRICS_ANIM_COUNT; ++a) {
            if (a % 2 == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                animSection.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, a == 0 ? 2 : 8, 8, 0));
            }
            final AnimCard card = new AnimCard(context, a);
            animCards.add(card);
            row.addView(card, LayoutHelper.createLinear(0, 104, 1f, 4, 0, 4, 0));
        }

        // ------- фон и форма обложки -------
        content.addView(header(context, getString(R.string.PengramPlayerBg)));
        final LinearLayout bgRow = new LinearLayout(context);
        bgRow.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(bgRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 2, 8, 0));
        for (int a = 0; a < 4; ++a) {
            final ChipView chip = new ChipView(context, ChipView.KIND_BG, a);
            bgChips.add(chip);
            bgRow.addView(chip, LayoutHelper.createLinear(0, 92, 1f, 4, 0, 4, 0));
        }

        content.addView(header(context, getString(R.string.PengramCoverShape)));
        final LinearLayout shapeRow = new LinearLayout(context);
        shapeRow.setOrientation(LinearLayout.HORIZONTAL);
        content.addView(shapeRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 8, 2, 8, 0));
        for (int a = 0; a < 3; ++a) {
            final ChipView chip = new ChipView(context, ChipView.KIND_SHAPE, a);
            shapeChips.add(chip);
            shapeRow.addView(chip, LayoutHelper.createLinear(0, 86, 1f, 4, 0, 4, 0));
        }

        // ------- тонкая настройка текста -------
        final TextView more = new TextView(context);
        more.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        more.setTypeface(AndroidUtilities.bold());
        more.setGravity(Gravity.CENTER);
        more.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        more.setText(getString(R.string.PengramLyricsMore));
        more.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(12),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        more.setOnClickListener(v -> presentFragment(new PengramSettingsActivity(PengramSettingsActivity.SECTION_PLAYER)));
        content.addView(more, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 12, 18, 12, 0));

        fragmentView = scrollView;
        updateState(false);
        return fragmentView;
    }

    private TextView header(Context context, CharSequence text) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        view.setTypeface(AndroidUtilities.bold());
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        view.setText(text);
        view.setPadding(dp(20), dp(16), dp(20), dp(2));
        return view;
    }

    private TextView hint(Context context, CharSequence text) {
        final TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        view.setText(text);
        view.setPadding(dp(20), dp(2), dp(20), dp(6));
        return view;
    }

    private void updateState(boolean animated) {
        final int style = PengramConfig.getPlayerStyle();
        for (StyleCard card : styleCards) {
            card.setSelected(card.style == style, animated);
        }
        final boolean lyrics = PengramConfig.playerStyleHasLyrics(style);
        animSection.animate().cancel();
        if (animated) {
            animSection.animate().alpha(lyrics ? 1f : 0.35f).setDuration(180).start();
        } else {
            animSection.setAlpha(lyrics ? 1f : 0.35f);
        }
        final int anim = PengramConfig.getLyricsAnim();
        for (AnimCard card : animCards) {
            card.setSelected(card.anim == anim, animated);
        }
        final int bg = PengramConfig.getPlayerBg();
        for (ChipView chip : bgChips) {
            chip.setSelected(chip.index == bg, animated);
        }
        final int shape = PengramConfig.getCoverShape();
        for (ChipView chip : shapeChips) {
            chip.setSelected(chip.index == shape, animated);
        }
    }

    private void pop(View view) {
        view.animate().cancel();
        view.setScaleX(0.96f);
        view.setScaleY(0.96f);
        view.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
        if (PengramConfig.isVibrationEnabled()) {
            try {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP,
                        android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Throwable ignore) {
            }
        }
    }

    /** карточка вида плеера: миниатюра + название + описание */
    private class StyleCard extends FrameLayout {

        final int style;
        private final RadioButton radioButton;
        private final RectF rect = new RectF();
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float selectedProgress;

        StyleCard(Context context, int style) {
            super(context);
            this.style = style;
            setWillNotDraw(false);
            setPadding(dp(12), dp(12), dp(12), dp(12));

            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(2));

            final PengramPlayerMockView mock = new PengramPlayerMockView(context, style);
            mock.setAccent(Theme.getColor(Theme.key_featuredStickers_addButton));
            addView(mock, LayoutHelper.createFrame(74, 112, Gravity.LEFT | Gravity.CENTER_VERTICAL));

            final LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    Gravity.LEFT | Gravity.CENTER_VERTICAL, 86, 0, 44, 0));

            final TextView title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setText(getString(PengramConfig.getPlayerStyleName(style)));
            column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            final TextView description = new TextView(context);
            description.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            description.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            description.setText(getString(PengramConfig.getPlayerStyleInfo(style)));
            column.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

            radioButton = new RadioButton(context);
            radioButton.setSize(dp(20));
            radioButton.setColor(Theme.getColor(Theme.key_radioBackground), Theme.getColor(Theme.key_radioBackgroundChecked));
            addView(radioButton, LayoutHelper.createFrame(22, 22, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 2, 0));

            setOnClickListener(v -> {
                if (PengramConfig.getPlayerStyle() == style) {
                    return;
                }
                PengramConfig.setPlayerStyle(style);
                updateState(true);
                pop(this);
            });
        }

        void setSelected(boolean selected, boolean animated) {
            radioButton.setChecked(selected, animated);
            final float target = selected ? 1f : 0f;
            if (!animated) {
                selectedProgress = target;
                invalidate();
                return;
            }
            animate().cancel();
            final float from = selectedProgress;
            final android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(from, target);
            animator.addUpdateListener(a -> {
                selectedProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.setDuration(200);
            animator.setInterpolator(CubicBezierInterpolator.DEFAULT);
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            rect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
            borderPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            borderPaint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(rect, dp(14), dp(14), borderPaint);
            if (selectedProgress > 0) {
                borderPaint.setStyle(Paint.Style.STROKE);
                borderPaint.setColor(ColorUtils.setAlphaComponent(
                        Theme.getColor(Theme.key_featuredStickers_addButton), (int) (255 * selectedProgress)));
                rect.inset(dp(1), dp(1));
                canvas.drawRoundRect(rect, dp(13), dp(13), borderPaint);
            }
        }
    }

    /** карточка анимации: сам текст и анимируется */
    private class AnimCard extends FrameLayout {

        final int anim;
        private final RectF rect = new RectF();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float selectedProgress;

        AnimCard(Context context, int anim) {
            super(context);
            this.anim = anim;
            setWillNotDraw(false);

            final PengramLyricsView preview = new PengramLyricsView(context);
            preview.setColors(Theme.getColor(Theme.key_featuredStickers_addButton),
                    Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            preview.setPreview(getString(R.string.PengramLyricsSample), anim, 17);
            addView(preview, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.TOP, 0, 8, 0, 0));

            final TextView name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            name.setGravity(Gravity.CENTER);
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            name.setText(getString(PengramConfig.getLyricsAnimName(anim)));
            addView(name, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 4, 0, 4, 12));

            setOnClickListener(v -> {
                PengramConfig.setLyricsAnim(anim);
                updateState(true);
                pop(this);
            });
        }

        void setSelected(boolean selected, boolean animated) {
            final float target = selected ? 1f : 0f;
            if (!animated) {
                selectedProgress = target;
                invalidate();
                return;
            }
            final android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(selectedProgress, target);
            animator.addUpdateListener(a -> {
                selectedProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.setDuration(200);
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            rect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            canvas.drawRoundRect(rect, dp(14), dp(14), paint);
            if (selectedProgress > 0) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(ColorUtils.setAlphaComponent(
                        Theme.getColor(Theme.key_featuredStickers_addButton), (int) (255 * selectedProgress)));
                rect.inset(dp(1), dp(1));
                canvas.drawRoundRect(rect, dp(13), dp(13), paint);
            }
        }
    }

    /** маленькая плашка: вариант фона или формы обложки */
    private class ChipView extends View {

        static final int KIND_BG = 0;
        static final int KIND_SHAPE = 1;

        final int kind;
        final int index;
        private final RectF rect = new RectF();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private LinearGradient gradient;
        private float selectedProgress;

        ChipView(Context context, int kind, int index) {
            super(context);
            this.kind = kind;
            this.index = index;
            setOnClickListener(v -> {
                if (kind == KIND_BG) {
                    PengramConfig.setPlayerBg(index);
                } else {
                    PengramConfig.setCoverShape(index);
                }
                updateState(true);
                pop(this);
            });
        }

        void setSelected(boolean selected, boolean animated) {
            final float target = selected ? 1f : 0f;
            if (!animated) {
                selectedProgress = target;
                invalidate();
                return;
            }
            final android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(selectedProgress, target);
            animator.addUpdateListener(a -> {
                selectedProgress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.setDuration(200);
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getMeasuredWidth();
            final int h = getMeasuredHeight();
            final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
            rect.set(0, 0, w, h);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            canvas.drawRoundRect(rect, dp(14), dp(14), paint);

            final float inset = dp(10);
            rect.set(inset, inset, w - inset, h - inset - dp(12));
            if (kind == KIND_BG) {
                switch (index) {
                    case PengramConfig.PLAYER_BG_DARK:
                        paint.setColor(0xFF101014);
                        canvas.drawRoundRect(rect, dp(8), dp(8), paint);
                        break;
                    case PengramConfig.PLAYER_BG_THEME:
                        paint.setColor(Theme.getColor(Theme.key_windowBackgroundGray));
                        canvas.drawRoundRect(rect, dp(8), dp(8), paint);
                        paint.setColor(accent);
                        canvas.drawCircle(rect.centerX(), rect.centerY(), Math.min(rect.width(), rect.height()) * 0.22f, paint);
                        break;
                    case PengramConfig.PLAYER_BG_GRADIENT:
                    case PengramConfig.PLAYER_BG_COVER:
                    default: {
                        if (gradient == null) {
                            gradient = new LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                                    new int[]{ColorUtils.blendARGB(accent, 0xFF000000, 0.2f), 0xFF101018},
                                    null, Shader.TileMode.CLAMP);
                        }
                        paint.setShader(gradient);
                        canvas.drawRoundRect(rect, dp(8), dp(8), paint);
                        paint.setShader(null);
                        if (index == PengramConfig.PLAYER_BG_COVER) {
                            paint.setColor(0x33FFFFFF);
                            canvas.drawCircle(rect.centerX(), rect.centerY(), Math.min(rect.width(), rect.height()) * 0.3f, paint);
                        }
                        break;
                    }
                }
            } else {
                paint.setColor(ColorUtils.setAlphaComponent(accent, 200));
                final float size = Math.min(rect.width(), rect.height()) * 0.8f;
                final RectF shape = new RectF(rect.centerX() - size / 2, rect.centerY() - size / 2,
                        rect.centerX() + size / 2, rect.centerY() + size / 2);
                if (index == PengramConfig.COVER_SHAPE_CIRCLE) {
                    canvas.drawCircle(shape.centerX(), shape.centerY(), size / 2, paint);
                } else if (index == PengramConfig.COVER_SHAPE_SQUARE) {
                    canvas.drawRect(shape, paint);
                } else {
                    canvas.drawRoundRect(shape, dp(8), dp(8), paint);
                }
            }

            // подпись
            paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            paint.setTextSize(dp(11));
            paint.setTextAlign(Paint.Align.CENTER);
            final String label = kind == KIND_BG
                    ? getString(PengramConfig.getPlayerBgName(index)).toString()
                    : getString(PengramConfig.getCoverShapeName(index)).toString();
            final String shortLabel = label.length() > 14 ? label.substring(0, 13) + "…" : label;
            canvas.drawText(shortLabel, w / 2f, h - dp(6), paint);
            paint.setTextAlign(Paint.Align.LEFT);

            if (selectedProgress > 0) {
                rect.set(dp(1), dp(1), w - dp(1), h - dp(1));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(ColorUtils.setAlphaComponent(accent, (int) (255 * selectedProgress)));
                canvas.drawRoundRect(rect, dp(13), dp(13), paint);
            }
        }
    }

    @Override
    public boolean onFragmentCreate() {
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (fragmentView != null) {
            updateState(false);
        }
    }
}
