package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewTreeObserver;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawableRenderNode;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;

/** Захватываем только отдельный нижний слой: поверхность не может захватить саму себя. */
public final class PengramSurfaceGlass implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private final View target;
    private final View sourceView;
    private Drawable original;
    private final Theme.ResourcesProvider resources;
    private final java.util.concurrent.Callable<Boolean> applicable;
    private final int[] sourcePosition = new int[2];
    private final int[] targetPosition = new int[2];
    private final Runnable refresh = this::refresh;
    private final SheenOverlay sheen = new SheenOverlay();
    private Recorder recorder;
    private BlurredBackgroundDrawable drawable;
    private int signature = Integer.MIN_VALUE;
    private int width, height;
    private boolean enabled;

    public PengramSurfaceGlass(View target, View sourceView, Theme.ResourcesProvider resources,
                               java.util.concurrent.Callable<Boolean> applicable) {
        this.target = target;
        this.sourceView = sourceView;
        this.resources = resources;
        this.applicable = applicable;
        original = target.getBackground();
        target.addOnAttachStateChangeListener(this);
        if (target.isAttachedToWindow()) onViewAttachedToWindow(target);
    }

    private void setTargetBackground(Drawable background) {
        final int left = target.getPaddingLeft(), top = target.getPaddingTop();
        final int right = target.getPaddingRight(), bottom = target.getPaddingBottom();
        target.setBackground(background);
        target.setPadding(left, top, right, bottom);
    }

    public void setOriginalBackground(Drawable background) {
        original = background;
        if (!enabled) setTargetBackground(original);
    }

    public boolean isRequested() { return wanted(); }

    private boolean wanted() {
        try {
            return PengramConfig.isSettingsSurfaceGlass() && applicable.call();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void refresh() {
        final boolean want = wanted();
        final int next = PengramGlassSkin.signature(PengramGlassSkin.SURFACE_HEADER);
        if (want == enabled && signature == next && width == target.getWidth() && height == target.getHeight()) return;
        signature = next;
        enabled = want;
        sheen.effect.configure(want, resources);
        width = target.getWidth();
        height = target.getHeight();
        if (!want || width <= 0 || height <= 0) {
            drawable = null;
            if (recorder != null) recorder.close();
            recorder = null;
            setTargetBackground(original);
            return;
        }
        if (recorder != null) recorder.close();
        // Изолируем API 31-классы от верификатора старых Android. Там остаётся
        // тот же провайдер и настоящий downsampled bitmap blur, а не заливка.
        recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && target.isHardwareAccelerated()
                ? new NodeRecorder() : new BitmapRecorder();
        capture();
        final BlurredBackgroundDrawableViewFactory factory = new BlurredBackgroundDrawableViewFactory(recorder.source());
        factory.setLiquidGlassEffectAllowed(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && org.telegram.messenger.LiteMode.isEnabled(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS));
        drawable = factory.create(target, PengramGlassSkin.provider(PengramGlassSkin.SURFACE_HEADER, resources));
        PengramGlassSkin.applyToDrawable(drawable);
        drawable.setRadius(AndroidUtilities.dp(22));
        sheen.setBounds(0, 0, width, height);
        setTargetBackground(drawable);
    }

    private void capture() {
        if (recorder == null || width <= 0 || height <= 0) return;
        sourceView.getLocationInWindow(sourcePosition);
        target.getLocationInWindow(targetPosition);
        final Canvas canvas = recorder.begin(width, height);
        final int save = canvas.save();
        canvas.translate(sourcePosition[0] - targetPosition[0], sourcePosition[1] - targetPosition[1]);
        sourceView.draw(canvas);
        canvas.restoreToCount(save);
        recorder.end();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && drawable instanceof BlurredBackgroundDrawableRenderNode) {
            ((BlurredBackgroundDrawableRenderNode) drawable).invalidateDisplayList();
        }
    }

    @Override public boolean onPreDraw() {
        if (wanted() != enabled || width != target.getWidth() || height != target.getHeight()) refresh();
        if (enabled && target.isShown()) capture();
        return true;
    }

    @Override public void onViewAttachedToWindow(View view) {
        target.getViewTreeObserver().addOnPreDrawListener(this);
        target.getOverlay().add(sheen);
        PengramGlassSkin.register(refresh);
    }

    @Override public void onViewDetachedFromWindow(View view) {
        if (target.getViewTreeObserver().isAlive()) target.getViewTreeObserver().removeOnPreDrawListener(this);
        PengramGlassSkin.unregister(refresh);
        target.getOverlay().remove(sheen);
        setTargetBackground(original);
        drawable = null;
        if (recorder != null) recorder.close();
        recorder = null;
        enabled = false;
        signature = Integer.MIN_VALUE;
    }

    private final class SheenOverlay extends Drawable {
        final PengramGlassSheen effect = new PengramGlassSheen();
        @Override public void draw(Canvas canvas) { effect.draw(canvas, target, getBounds()); }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private interface Recorder {
        BlurredBackgroundSource source();
        Canvas begin(int width, int height);
        void end();
        void close();
    }

    @androidx.annotation.RequiresApi(31)
    private static final class NodeRecorder implements Recorder {
        private final BlurredBackgroundSourceRenderNode source = new BlurredBackgroundSourceRenderNode(null);
        NodeRecorder() { source.setBlur(AndroidUtilities.dp(12)); }
        public BlurredBackgroundSource source() { return source; }
        public Canvas begin(int width, int height) { return source.beginRecording(width, height); }
        public void end() { source.endRecording(); }
        public void close() { source.releaseDisplayList(); }
    }

    private static final class BitmapRecorder implements Recorder {
        private final BlurredBackgroundSourceBitmap source = new BlurredBackgroundSourceBitmap();
        private final Matrix matrix = new Matrix();
        private Bitmap bitmap;
        private Canvas canvas;
        public BlurredBackgroundSource source() { return source; }
        public Canvas begin(int width, int height) {
            final int w = Math.max(1, width / 4), h = Math.max(1, height / 4);
            if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
                close();
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                canvas = new Canvas(bitmap);
                source.setBitmap(bitmap);
                matrix.setScale(width / (float) w, height / (float) h);
                source.setMatrix(matrix);
            }
            bitmap.eraseColor(0);
            canvas.setMatrix(null);
            canvas.scale(w / (float) width, h / (float) height);
            return canvas;
        }
        public void end() { Utilities.stackBlurBitmap(bitmap, 3); }
        public void close() {
            source.setBitmap(null);
            if (bitmap != null) bitmap.recycle();
            bitmap = null;
            canvas = null;
        }
    }
}
