package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.annotation.SuppressLint;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import android.util.Log;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedLinearLayout;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.glass.GlassTabView;

import java.util.HashSet;
import java.util.Set;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.ListAnimator;
import me.vkryl.android.util.ClickHelper;

@SuppressLint("ViewConstructor")
public class MainTabsLayout extends AnimatedLinearLayout {

    private final Theme.ResourcesProvider resourcesProvider;

    public MainTabsLayout(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
    }

    private static final float[] PASS_TEXT_SIZES_DP = {12f, 12f, 10f};
    private static final int[] PASS_PADDINGS_DP = {16, 8, 4};

    private int maxWidthPx;

    public void setMaxWidth(int maxWidthPx) {
        if (this.maxWidthPx != maxWidthPx) {
            this.maxWidthPx = maxWidthPx;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        final int height = MeasureSpec.getSize(heightMeasureSpec);
        final int tabHeight = height - getPaddingTop() - getPaddingBottom();

        if (maxWidthPx > 0 && width > maxWidthPx) {
            width = maxWidthPx;
        }

        final int maxTotalWidthForTabs = width - getPaddingLeft() - getPaddingRight();
        final int minTotalWidthForTabs = Math.min(dp(320), maxTotalWidthForTabs);

        int chosenPass = PASS_TEXT_SIZES_DP.length - 1;
        float lastMeasuredTextSize = -1;
        for (int pass = 0; pass < PASS_TEXT_SIZES_DP.length; pass++) {
            if (PASS_TEXT_SIZES_DP[pass] != lastMeasuredTextSize) {
                measureTabTexts(PASS_TEXT_SIZES_DP[pass]);
                lastMeasuredTextSize = PASS_TEXT_SIZES_DP[pass];
            }
            final int padding = dp(PASS_PADDINGS_DP[pass]);
            float total = 0;
            for (int a = 0, N = getChildCount(); a < N; a++) {
                if (!isViewVisible(getChildAt(a))) continue;
                final float withMargin = tabsTextWidth[a] + padding * 2;
                total += withMargin;
            }
            final boolean fits = total <= maxTotalWidthForTabs;
            if (fits || pass == PASS_TEXT_SIZES_DP.length - 1) {
                chosenPass = pass;
                break;
            }
        }

        applyPassTextSize(chosenPass);

        final int tabPadding = dp(PASS_PADDINGS_DP[chosenPass]);
        final int maxTabTextWidthIfEq = (maxTotalWidthForTabs / Math.max(1, visibleChildCount)) - tabPadding * 2;

        float totalWidth = 0;
        int totalWeight = 0;
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (!isViewVisible(child)) {
                tabsTextWidth[a] = tabsTextWidthWithMargin[a] = 0;
                tabsWeight[a] = 0;
                continue;
            }

            tabsTextWidthWithMargin[a] = tabsTextWidth[a] + tabPadding * 2;
            tabsWeight[a] = tabsTextWidthWithMargin[a] > (maxTabTextWidthIfEq + tabPadding * 2) ? 0 : 1;

            totalWidth += tabsTextWidthWithMargin[a];
            totalWeight += tabsWeight[a];
        }

        if (totalWeight == 0) {
            for (int a = 0, N = getChildCount(); a < N; a++) {
                tabsWeight[a] = isViewVisible(getChildAt(a)) ? 1 : 0;
            }
            totalWeight = visibleChildCount;
        }

        if (totalWidth > maxTotalWidthForTabs) {
            final float m = maxTotalWidthForTabs / totalWidth;
            for (int a = 0, N = getChildCount(); a < N; a++) {
                tabsTextWidthWithMargin[a] *= m;
            }
        } else if (totalWidth < minTotalWidthForTabs) {
            final float growW = minTotalWidthForTabs - totalWidth;
            final float growP = growW / totalWeight;

            for (int a = 0, N = getChildCount(); a < N; a++) {
                tabsTextWidthWithMargin[a] += growP * tabsWeight[a];
            }
        }

        int l = 0;
        for (int a = 0, N = getChildCount(); a < N; a++) {
            if (!isViewVisible(getChildAt(a))) {
                continue;
            }

            tabsWidth[a] = Math.round(tabsTextWidthWithMargin[a]);
            tabsLeftPos[a] = l;
            l += tabsWidth[a];
        }
        setMeasuredDimension(l + getPaddingLeft() + getPaddingRight(), height);
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            child.measure(
                MeasureSpec.makeMeasureSpec(tabsWidth[a], MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(tabHeight, MeasureSpec.EXACTLY));
        }

        calculateTotalSizesAfterMeasure();
    }

    public interface Tab {
        float measureTextWidth();
        default float measureTextWidth(float textSizeDp) { return measureTextWidth(); }
        default void setTextSizeDp(float textSizeDp) {}
    }



    // fills tabsTextWidth[] and return visible child count;

    private float[] tabsTextWidth;
    private float[] tabsTextWidthWithMargin;
    private int[] tabsWeight;
    private int[] tabsWidth;

    private int[] tabsLeftPos;


    private int visibleChildCount;
    private int biggestTabTextWidth;

    private void measureTabTexts(float textSizeDp) {
        final int childCount = getChildCount();
        if (tabsTextWidth == null || tabsTextWidth.length < childCount) {
            tabsTextWidth = new float[childCount];
            tabsTextWidthWithMargin = new float[childCount];
            tabsWeight = new int[childCount];
            tabsLeftPos = new int[childCount];
            tabsWidth = new int[childCount];
        }

        float maxTabWidthF = 0;
        int index = 0;

        for (int a = 0; a < childCount; a++) {
            final View child = getChildAt(a);
            if (!isViewVisible(child)) {
                tabsTextWidth[a] = -1;
                continue;
            }

            final float tabWidth;
            if (child instanceof MainTabsLayout.Tab) {
                tabWidth = ((MainTabsLayout.Tab) child).measureTextWidth(textSizeDp);
            } else {
                tabWidth = 0;
            }

            tabsTextWidth[a] = tabWidth;
            maxTabWidthF = Math.max(maxTabWidthF, tabWidth);
            index++;
        }

        biggestTabTextWidth = (int) Math.ceil(maxTabWidthF);
        visibleChildCount = index;
    }

    private void applyPassTextSize(int pass) {
        final float textSizeDp = PASS_TEXT_SIZES_DP[pass];
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (child instanceof MainTabsLayout.Tab) {
                ((MainTabsLayout.Tab) child).setTextSizeDp(textSizeDp);
            }
        }
    }

    @Override
    protected void setChildVisibilityFactor(View view, float factor) {
        final float s = lerp(0.7f, 1f, factor);
        view.setAlpha(factor);
        view.setScaleX(s);
        view.setScaleY(s);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        checkVisualWidth();
        if (pengramGlass) {
            // ширина вкладок меняется вместе с размером панели, поэтому пятно
            // подсветки каждый раз подтягиваем к настоящему центру вкладки —
            // но не мешаем анимации, пока она идёт
            final View selected = findSelectedTab();
            final boolean animating = glassGlowAnimator != null && glassGlowAnimator.isRunning();
            if (selected != null && !animating) {
                final float target = selected.getX() + selected.getWidth() / 2f;
                if (glassGlowX <= 0f || Math.abs(target - glassGlowX) > 1f) {
                    glassGlowX = target;
                    invalidate();
                }
            }
        }
    }

    @Override
    protected void onItemsChanged() {
        super.onItemsChanged();
        checkVisualWidth();
    }

    private void checkVisualWidth() {
        for (int a = 0, N = getEntriesCount(); a < N; a++) {
            final ListAnimator.Entry<Holder> entry = getEntry(a);
            final float width = entry.getRectF().width();
            ((GlassTabView) entry.item.view).setVisualWidth(width);
        }
    }








    public void setTabSelected(View tab, boolean animated) {
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (child instanceof GlassTabView) {
                ((GlassTabView) child).setSelected(child == tab, animated);
            }
        }
    }

    private View findSelectedTab() {
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }

            if (child instanceof GlassTabView) {
                if (((GlassTabView) child).isTabSelected()) {
                    return child;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------- liquid glass

    /** «жидкое стекло»: объём, блик и отклик на нажатие поверх размытия */
    private boolean pengramGlass;
    private View pengramGlassSelectedTab;

    private float glassGlowX;
    private float glassSheenT = -1f;
    private boolean glassSheenForward = true;
    private ValueAnimator glassSheenAnimator;
    private ValueAnimator glassGlowAnimator;

    private boolean glassPressDown;
    private long glassPressStart;
    private long glassPressRelease;
    private float glassPressX, glassPressY;

    private final Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path glassPath = new Path();
    private final RectF glassRect = new RectF();
    private final Rect glassPadding = new Rect();
    private final Matrix glassMatrix = new Matrix();
    private LinearGradient glassAmbient;
    private LinearGradient glassRim;
    private LinearGradient glassSheen;
    private RadialGradient glassGlow;
    private float glassShaderSheenWidth;
    private int glassShaderWidth, glassShaderHeight;
    private boolean glassShaderDark;

    public boolean isPengramGlass() {
        return pengramGlass;
    }

    /** включено ли оформление; вызывается из MainTabsActivity по настройке */
    public void setPengramGlass(boolean enabled) {
        if (pengramGlass == enabled) {
            return;
        }
        pengramGlass = enabled;
        if (!enabled) {
            glassSheenT = -1f;
            glassPressDown = false;
        } else {
            final View selected = findSelectedTab();
            pengramGlassSelectedTab = selected;
            glassGlowX = selected == null ? getWidth() / 2f : selected.getX() + selected.getWidth() / 2f;
        }
        invalidate();
    }

    /**
     * Подсветка следует за выбранной вкладкой, а при смене вкладки по полосе
     * проезжает блик. Вызывается из MainTabsActivity.selectTab().
     */
    public void setPengramSelectedTab(@Nullable View tab, boolean animated) {
        if (!pengramGlass) {
            return;
        }
        if (tab == pengramGlassSelectedTab && tab != null) {
            return;
        }
        pengramGlassSelectedTab = tab;
        final float target = tab == null ? getWidth() / 2f : tab.getX() + tab.getWidth() / 2f;
        if (!animated || Math.abs(target - glassGlowX) < 1f) {
            glassGlowX = target;
            invalidate();
            return;
        }
        startGlassSheen(target > glassGlowX);
        if (glassGlowAnimator != null) {
            glassGlowAnimator.cancel();
        }
        glassGlowAnimator = ValueAnimator.ofFloat(glassGlowX, target);
        glassGlowAnimator.setDuration(340);
        glassGlowAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        glassGlowAnimator.addUpdateListener(a -> {
            glassGlowX = (float) a.getAnimatedValue();
            invalidate();
        });
        glassGlowAnimator.start();
    }

    private void startGlassSheen(boolean forward) {
        glassSheenForward = forward;
        glassSheenT = 0f;
        if (glassSheenAnimator != null) {
            glassSheenAnimator.cancel();
        }
        glassSheenAnimator = ValueAnimator.ofFloat(0f, 1f);
        glassSheenAnimator.setDuration(640);
        glassSheenAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        glassSheenAnimator.addUpdateListener(a -> {
            glassSheenT = (float) a.getAnimatedValue();
            invalidate();
        });
        glassSheenAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                glassSheenT = -1f;
                invalidate();
            }
        });
        glassSheenAnimator.start();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        // аниматоры живут дольше вида при быстром пересоздании панели
        if (glassSheenAnimator != null) {
            glassSheenAnimator.cancel();
            glassSheenAnimator = null;
        }
        if (glassGlowAnimator != null) {
            glassGlowAnimator.cancel();
            glassGlowAnimator = null;
        }
        glassSheenT = -1f;
        glassPressDown = false;
        glassPressRelease = 0;
        super.onDetachedFromWindow();
    }

    private float glassPressT() {
        final long now = SystemClock.uptimeMillis();
        if (glassPressDown) {
            return Math.min(1f, (now - glassPressStart) / 150f);
        }
        if (glassPressRelease == 0) {
            return 0f;
        }
        return Math.max(0f, 1f - (now - glassPressRelease) / 260f);
    }

    private void ensureGlassShaders(int w, int h) {
        final boolean dark = Theme.isCurrentThemeDark();
        if (glassShaderWidth == w && glassShaderHeight == h && glassShaderDark == dark) {
            return;
        }
        glassShaderWidth = w;
        glassShaderHeight = h;
        glassShaderDark = dark;
        final float sheenWidth = Math.max(1f, w * 0.42f);
        glassShaderSheenWidth = sheenWidth;
        // Свет и тень стекла зависят от темы: на светлой панели блик почти
        // невидим, если рисовать его той же силой, что и на тёмной.
        glassAmbient = new LinearGradient(0, 0, 0, h, dark ? new int[]{
                Theme.multAlpha(0xffffffff, 0.12f),
                Theme.multAlpha(0xffffffff, 0.02f),
                Theme.multAlpha(0xff000000, 0.04f)
        } : new int[]{
                Theme.multAlpha(0xffffffff, 0.30f),
                Theme.multAlpha(0xffffffff, 0.07f),
                Theme.multAlpha(0xff000000, 0.03f)
        }, new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP);
        glassRim = new LinearGradient(0, 0, 0, h, dark ? new int[]{
                Theme.multAlpha(0xffffffff, 0.34f),
                Theme.multAlpha(0xffffffff, 0.08f),
                Theme.multAlpha(0xffffffff, 0.03f)
        } : new int[]{
                Theme.multAlpha(0xffffffff, 0.85f),
                Theme.multAlpha(0xff000000, 0.04f),
                Theme.multAlpha(0xff000000, 0.06f)
        }, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        glassSheen = new LinearGradient(-sheenWidth / 2f, 0, sheenWidth / 2f, 0, new int[]{
                Theme.multAlpha(0xffffffff, 0f),
                Theme.multAlpha(0xffffffff, dark ? 0.16f : 0.26f),
                Theme.multAlpha(0xffffffff, dark ? 0.02f : 0.05f),
                Theme.multAlpha(0xffffffff, 0f)
        }, new float[]{0f, 0.45f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        glassGlow = new RadialGradient(0, 0, 1f, new int[]{
                Theme.multAlpha(0xffffffff, dark ? 0.20f : 0.34f),
                Theme.multAlpha(0xffffffff, dark ? 0.06f : 0.10f),
                Theme.multAlpha(0xffffffff, 0f)
        }, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
    }

    /**
     * Прямоугольник самого стекла. Фон панели рисуется с внутренним отступом,
     * и полоса бликов обязана совпадать с ним, иначе поверх пилюли появляется
     * светлый кант, висящий в воздухе.
     */
    private boolean glassGeometry() {
        final int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            return false;
        }
        ensureGlassShaders(w, h);
        glassRect.set(0, 0, w, h);
        final Drawable background = getBackground();
        if (background != null) {
            glassPadding.set(0, 0, 0, 0);
            background.getPadding(glassPadding);
            if (glassPadding.left + glassPadding.right < w
                    && glassPadding.top + glassPadding.bottom < h) {
                // RectF.inset с четырьмя аргументами есть не на всех API —
                // считаем края сами, это ровно то же самое
                glassRect.set(glassPadding.left, glassPadding.top,
                        w - glassPadding.right, h - glassPadding.bottom);
            }
        }
        glassPath.reset();
        glassPath.addRoundRect(glassRect, glassRect.height() / 2f, glassRect.height() / 2f,
                Path.Direction.CW);
        return true;
    }

    private void drawGlassBack(Canvas canvas) {
        if (!glassGeometry()) {
            return;
        }
        final int w = getWidth(), h = getHeight();
        final float radius = glassRect.height() / 2f;

        final int save = canvas.save();
        canvas.clipPath(glassPath);

        // объём стекла: сверху светлее, к низу — лёгкая тень
        glassPaint.setShader(glassAmbient);
        glassPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(glassRect, glassPaint);

        // световое пятно под выбранной вкладкой: мягкий эллипс, а не полоса
        // от самого верха — иначе стекло выглядит подсвеченным с одной стороны
        final float glowRadius = Math.max(1f, glassRect.width() * 0.17f);
        glassMatrix.reset();
        glassMatrix.setScale(glowRadius, Math.max(1f, glassRect.height() * 0.75f));
        glassMatrix.postTranslate(glassGlowX, glassRect.centerY());
        glassGlow.setLocalMatrix(glassMatrix);
        glassGlowPaint.setShader(glassGlow);
        glassGlowPaint.setAlpha((int) (255 * (0.75f + 0.25f * glassPressT())));
        canvas.drawRect(glassRect, glassGlowPaint);

        // отклик на касание: свет расходится из точки нажатия
        final float pressT = glassPressT();
        if (pressT > 0.01f) {
            glassMatrix.reset();
            final float pressRadius = AndroidUtilities.dp(42) * (0.6f + 0.4f * pressT);
            glassMatrix.setScale(pressRadius, pressRadius);
            glassMatrix.postTranslate(glassPressX, glassPressY);
            glassGlow.setLocalMatrix(glassMatrix);
            glassGlowPaint.setShader(glassGlow);
            glassGlowPaint.setAlpha((int) (52 * pressT));
            canvas.drawRect(glassRect, glassGlowPaint);
        }

        glassGlowPaint.setShader(null);
        canvas.restoreToCount(save);

        // тонкий кант по верхней кромке — стекло «толщиной» в 1dp
        glassPaint.setShader(glassRim);
        glassPaint.setStyle(Paint.Style.STROKE);
        glassPaint.setStrokeWidth(AndroidUtilities.dp(1));
        glassRect.inset(AndroidUtilities.dp(0.5f), AndroidUtilities.dp(0.5f));
        canvas.drawRoundRect(glassRect, radius - AndroidUtilities.dp(0.5f),
                radius - AndroidUtilities.dp(0.5f), glassPaint);
        glassPaint.setStyle(Paint.Style.FILL);
        glassPaint.setShader(null);
    }

    private void drawGlassSheen(Canvas canvas) {
        if (glassSheenT < 0f || glassSheenT > 1f) {
            return;
        }
        if (!glassGeometry()) {
            return;
        }
        final int w = getWidth(), h = getHeight();
        final float band = glassShaderSheenWidth;
        final float from = glassSheenForward ? -band * 1.5f : w + band * 1.5f;
        final float to = glassSheenForward ? w + band * 1.5f : -band * 1.5f;
        final float x = from + (to - from) * glassSheenT;

        glassMatrix.reset();
        glassMatrix.setTranslate(x, glassRect.centerY());
        glassMatrix.postSkew(-0.42f, 0, 0, glassRect.centerY());
        glassSheen.setLocalMatrix(glassMatrix);

        final int save = canvas.save();
        canvas.clipPath(glassPath);
        glassSheenPaint.setShader(glassSheen);
        canvas.drawRect(glassRect, glassSheenPaint);
        canvas.restoreToCount(save);
    }

    private final Runnable restoreDrawSelector = () -> setSkipDrawSelector(false);

    private boolean drawCustomSelector;
    private void setSkipDrawSelector(boolean skipDrawSelector) {
        drawCustomSelector = skipDrawSelector;
        if (drawCustomSelector) {
            selectorPaint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider), 0.09f));
        }
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }

            if (child instanceof GlassTabView) {
                ((GlassTabView) child).setSkipDrawSelector(skipDrawSelector);
            }
        }
        invalidate();
    }







    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (pengramGlass) {
            drawGlassBack(canvas);
            if (glassPressDown || glassPressT() > 0f) {
                postInvalidateOnAnimation();
            }
        }
        if (drawCustomSelector) {
            final float x = animatedLongSelectedViewCenterX + animatedLongSelectedViewOffsetX;
            final float sWidth = getInterpolatedWidthByX(x, this);
            final float sHeight = getHeight() - getPaddingTop() - getPaddingBottom();

            canvas.drawRoundRect(
                    x - sWidth / 2f, (getHeight() - sHeight) / 2f,
                    x + sWidth / 2f, (getHeight() + sHeight) / 2f,
                    sHeight / 2f, sHeight / 2f, selectorPaint);
        }

        super.dispatchDraw(canvas);

        if (pengramGlass) {
            drawGlassSheen(canvas);
        }
    }


    final Paint selectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final SpringAnimation scaleX = new SpringAnimation(this, DynamicAnimation.SCALE_X, 1f);
    final SpringAnimation scaleY = new SpringAnimation(this, DynamicAnimation.SCALE_Y, 1f);

    final SpringAnimation selectedTabPositionOffsetX = new SpringAnimation(this, new FloatPropertyCompat<MainTabsLayout>("selectedTabPositionOffsetX") {
        @Override
        public float getValue(MainTabsLayout object) {
            return object.animatedLongSelectedViewOffsetX;
        }

        @Override
        public void setValue(MainTabsLayout object, float value) {
            object.animatedLongSelectedViewOffsetX = value;
            object.invalidate();
        }
    });
    final SpringAnimation selectedTabPositionX = new SpringAnimation(this, new FloatPropertyCompat<MainTabsLayout>("selectedTabPositionX") {
        @Override
        public float getValue(MainTabsLayout object) {
            return object.animatedLongSelectedViewCenterX;
        }

        @Override
        public void setValue(MainTabsLayout object, float value) {
            object.animatedLongSelectedViewCenterX = value;
            object.invalidate();
        }
    });

    {
        selectedTabPositionOffsetX.setSpring(new SpringForce(1)
            .setStiffness(SpringForce.STIFFNESS_MEDIUM)
            .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY));
        scaleX.setSpring(new SpringForce(1f)
            .setStiffness(250)
            .setDampingRatio(0.25f));
        scaleY.setSpring(new SpringForce(1f)
            .setStiffness(250)
            .setDampingRatio(0.25f));
        selectedTabPositionX.setSpring(new SpringForce(1f)
            .setStiffness(SpringForce.STIFFNESS_MEDIUM)
            .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY));
    }

    private float animatedLongSelectedViewCenterX;
    private float animatedLongSelectedViewOffsetX;

    private boolean isInLongPress;
    private float lastLongSelectedViewCenterX;
    private float lastLongSelectedViewWidth;
    private View lastLongSelectedView;




    public static View findChildUnder(ViewGroup parent, float x, float y) {
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            View child = parent.getChildAt(i);

            if (child.getVisibility() != View.VISIBLE) continue;

            if (x >= child.getLeft() && x <= child.getRight()
                    && y >= child.getTop() && y <= child.getBottom()) {
                return child;
            }
        }
        return null;
    }

    private void checkLongMove(float x_, float y, boolean start, boolean end) {
        final float x = clampXToChildrenCenters(x_, this);
        final View found = findNearestVisibleChildByX(x, this);
        if (start) {
            View selected = findSelectedTab();
            if (selected != null) {
                animatedLongSelectedViewCenterX = selected.getX() + selected.getWidth() / 2f;
                animatedLongSelectedViewOffsetX = animatedLongSelectedViewCenterX - x;
                selectedTabPositionOffsetX.animateToFinalPosition(0);
                if (selected != found && found != null) {
                    found.performClick();
                }
            }
            selectedTabPositionX.cancel();
        }

        if (!end) {
            animatedLongSelectedViewCenterX = x;
            invalidate();
        }

        if (found != null) {
            lastLongSelectedView = found;
            setTabSelected(found, true);

            if (end) {
                final float vw = found.getWidth();
                final float cx = found.getX() + vw / 2f;
                if (lastLongSelectedViewWidth != vw || lastLongSelectedViewCenterX != cx) {
                    selectedTabPositionX.animateToFinalPosition(cx);
                }
            }
        }
    }

    private final Set<View> tabsWithIgnoreClick = new HashSet<>();
    public void addTabToIgnoreClick(View v) {
        tabsWithIgnoreClick.add(v);
    }

    private final BoolAnimator animatorIsScaled = new BoolAnimator(0, (a, factor, c, g) -> {
        setScaleX(lerp(1, 1.019f, factor));
        setScaleY(lerp(1, 1.019f, factor));
    }, CubicBezierInterpolator.EASE_OUT_QUINT, 380);

    private final ClickHelper clickHelper = new ClickHelper(new ClickHelper.Delegate() {
        @Override
        public boolean needClickAt(View view, float x, float y) {
            lastLongSelectedView = null;
            final View found = findChildUnder(MainTabsLayout.this, x, y);
            return found != null && !tabsWithIgnoreClick.contains(found);
        }

        @Override
        public void onClickAt(View view, float x, float y) {
        }

        @Override
        public boolean needLongPress(float x, float y) {
            return true;
        }

        @Override
        public boolean needCancelTouchBySlopMove() {
            return false;
        }


        @Override
        public boolean onLongPressRequestedAt(View view, float x, float y) {
            checkPivot(view, x, y);
            isInLongPress = true;
            AndroidUtilities.cancelRunOnUIThread(restoreDrawSelector);
            setSkipDrawSelector(true);
            checkLongMove(x, y, true, false);
            invalidate();
            longTouchStart();
            return true;
        }

        @Override
        public void onLongPressMove(View view, MotionEvent e, float x, float y, float startX, float startY) {
            checkPivot(view, x, y);
            checkLongMove(x, y, false, false);
            invalidate();
        }

        @Override
        public long getLongPressDuration() {
            return ClickHelper.Delegate.super.getLongPressDuration() * 750 / 1000;
        }

        @Override
        public void onLongPressFinish(View view, float x, float y) {
            checkPivot(view, x, y);
            checkLongMove(x, y, false, true);
            isInLongPress = false;
            AndroidUtilities.runOnUIThread(restoreDrawSelector, 450);
            if (lastLongSelectedView != null) {
                lastLongSelectedView.performClick();
            }
            lastLongSelectedView = null;
            invalidate();
            longTouchEnd();
        }

        @Override
        public void onLongPressCancelled(View view, float x, float y) {
            checkPivot(view, x, y);
            checkLongMove(x, y, false, true);
            isInLongPress = false;
            AndroidUtilities.runOnUIThread(restoreDrawSelector, 450);
            lastLongSelectedView = null;
            invalidate();
            longTouchEnd();
        }

        private void longTouchStart() {
            animatorIsScaled.setValue(true, true);

            /*
            if (!scaleX.isRunning()) {
                scaleX.setStartVelocity(-0.45f);
                scaleY.setStartVelocity(-0.45f);
            }
            scaleX.animateToFinalPosition(1.012f);
            scaleY.animateToFinalPosition(1.012f);
            */
        }

        private void longTouchEnd() {
            animatorIsScaled.setValue(false, true);

            /*
            if (!scaleX.isRunning()) {
                scaleX.setStartVelocity(0.25f);
                scaleY.setStartVelocity(0.25f);
            }
            scaleX.animateToFinalPosition(1f);
            scaleY.animateToFinalPosition(1f);
            */
        }
    });

    @Override
    public void setScaleY(float scaleY) {
        super.setScaleY(scaleY);
        checkLayerType();
    }

    @Override
    public void setScaleX(float scaleX) {
        super.setScaleX(scaleX);
        checkLayerType();
    }

    private void checkLayerType() {
        final int layerType = Math.abs(getScaleX() - 1f) < 0.0001f && Math.abs(getScaleY() - 1f) < 0.0001f ?
            View.LAYER_TYPE_NONE : View.LAYER_TYPE_HARDWARE;

        if (getLayerType() != layerType) {
            setLayerType(layerType, null);
            invalidate();
        }
    }


    private void checkPivot(View view, float x, float y) {
        float w = view.getWidth();
        float h = view.getHeight();

        if (w <= 0f || h <= 0f) {
            return;
        }

        float cx = w * 0.5f;
        float cy = h * 0.5f;

        float dx = x - cx;
        float dy = y - cy;

        float halfW = w * 0.5f;
        float halfH = h * 0.5f;

        float nx = dx / halfW;
        float ny = dy / halfH;

        float r = (float) Math.sqrt(nx * nx + ny * ny);

        float pivotX;
        float pivotY;

        if (r > 1e-4f) {
            float mappedR = 1.5f * r / (r + 0.5f);

            float scale = mappedR / r;
            pivotX = cx + dx * scale;
            pivotY = cy + dy * scale;
        } else {
            pivotX = cx;
            pivotY = cy;
        }

        pivotX = lerp(cx, pivotX, 1f);
        pivotY = lerp(cy, pivotY, 3f);

        view.setPivotX(pivotX);
        view.setPivotY(pivotY);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (pengramGlass) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    glassPressX = ev.getX();
                    glassPressY = ev.getY();
                    glassPressDown = true;
                    glassPressRelease = 0;
                    glassPressStart = SystemClock.uptimeMillis();
                    postInvalidateOnAnimation();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (glassPressDown) {
                        glassPressDown = false;
                        glassPressRelease = SystemClock.uptimeMillis();
                        postInvalidateOnAnimation();
                    }
                    break;
            }
        }
        clickHelper.onTouchEvent(this, ev);
        return super.dispatchTouchEvent(ev);
    }


    private static float clampXToChildrenCenters(float x, ViewGroup parent) {
        if (parent == null || parent.getChildCount() == 0) {
            return x;
        }

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        boolean found = false;

        for (int i = 0; i < parent.getChildCount(); i++) {
            View view = parent.getChildAt(i);
            if (view == null || view.getVisibility() != View.VISIBLE) {
                continue;
            }

            float centerX = view.getX() + view.getWidth() * 0.5f;

            if (centerX < min) min = centerX;
            if (centerX > max) max = centerX;

            found = true;
        }

        if (!found) {
            return x;
        }

        if (x < min) return min;
        if (x > max) return max;
        return x;
    }

    @Nullable
    private static View findNearestVisibleChildByX(float x, ViewGroup parent) {
        if (parent == null || parent.getChildCount() == 0) {
            return null;
        }

        View nearest = null;
        float nearestDistance = Float.MAX_VALUE;

        for (int i = 0; i < parent.getChildCount(); i++) {
            View view = parent.getChildAt(i);
            if (view == null || view.getVisibility() != View.VISIBLE) {
                continue;
            }

            float centerX = view.getX() + view.getWidth() * 0.5f;
            float distance = Math.abs(centerX - x);

            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = view;
            }
        }

        return nearest;
    }

    private static float getInterpolatedWidthByX(float x, ViewGroup parent) {
        if (parent == null || parent.getChildCount() == 0) {
            return 0f;
        }

        View left = null;
        View right = null;

        for (int i = 0; i < parent.getChildCount(); i++) {
            View view = parent.getChildAt(i);
            if (view == null || view.getVisibility() != View.VISIBLE) {
                continue;
            }

            float centerX = view.getX() + view.getWidth() * 0.5f;

            if (centerX <= x && (left == null || centerX > getCenterX(left))) {
                left = view;
            }

            if (centerX >= x && (right == null || centerX < getCenterX(right))) {
                right = view;
            }
        }

        if (left == null && right == null) {
            return 0f;
        }

        if (left == null) {
            return right.getWidth();
        }

        if (right == null) {
            return left.getWidth();
        }

        float leftX = getCenterX(left);
        float rightX = getCenterX(right);

        if (left == right || leftX == rightX) {
            return left.getWidth();
        }

        float ratio = (x - leftX) / (rightX - leftX);
        return lerp(left.getWidth(), right.getWidth(), ratio);
    }

    private static float getCenterX(View v) {
        return v.getX() + v.getWidth() * 0.5f;
    }
}
