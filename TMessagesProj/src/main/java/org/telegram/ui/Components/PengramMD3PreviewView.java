package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PengramMD3;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/**
 * Pengram: живое превью слоя Material 3.
 *
 * Рисуем маленький список чатов «как будет»: строка поиска, три строки диалогов
 * и кнопка написать. Всё читает те же настройки, что и настоящий экран, поэтому
 * превью меняется сразу при переключении тумблеров — отдельных картинок нет.
 */
public class PengramMD3PreviewView extends View {

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final Theme.ResourcesProvider resourcesProvider;

    public PengramMD3PreviewView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        titlePaint.setTypeface(AndroidUtilities.bold());
        titlePaint.setTextSize(dp(12));
        textPaint.setTextSize(dp(11));
        linePaint.setStrokeWidth(Math.max(1, dp(0.5f)));
    }

    private int color(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(196));
    }

    public void update() {
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final boolean md3 = PengramMD3.dialogs();
        final int width = getMeasuredWidth();
        final int surface = color(Theme.key_windowBackgroundWhite);
        final int accent = color(Theme.key_featuredStickers_addButton);
        final int textColor = color(Theme.key_windowBackgroundWhiteBlackText);
        final int hintColor = color(Theme.key_windowBackgroundWhiteGrayText);

        // «экран» телефона
        final int pad = dp(14);
        rect.set(pad, dp(8), width - pad, getMeasuredHeight() - dp(8));
        fillPaint.setColor(surface);
        canvas.drawRoundRect(rect, dp(18), dp(18), fillPaint);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setColor(ColorUtils.setAlphaComponent(textColor, 28));
        canvas.drawRoundRect(rect, dp(18), dp(18), linePaint);
        linePaint.setStyle(Paint.Style.FILL);

        final int left = pad + dp(10);
        final int right = width - pad - dp(10);

        // строка поиска
        final boolean searchMD3 = PengramMD3.searchBar();
        final float searchHeight = dp(searchMD3 ? 30 : 26);
        rect.set(left, dp(18), right, dp(18) + searchHeight);
        fillPaint.setColor(searchMD3
                ? PengramMD3.searchBarColor(resourcesProvider, Theme.isCurrentThemeDark())
                : ColorUtils.blendARGB(surface, textColor, 0.06f));
        canvas.drawRoundRect(rect, searchHeight / 2f, searchHeight / 2f, fillPaint);
        textPaint.setColor(hintColor);
        canvas.drawText(getString(R.string.Search), left + dp(14), rect.centerY() + dp(4), textPaint);
        fillPaint.setColor(ColorUtils.setAlphaComponent(hintColor, 150));
        canvas.drawCircle(right - dp(16), rect.centerY(), dp(4), fillPaint);

        // три строки чатов: непрочитанный, закреплённый, обычный
        float y = rect.bottom + dp(10);
        final float rowHeight = dp(42);
        for (int a = 0; a < 3; ++a) {
            final boolean unread = a == 0;
            final boolean pinned = a == 1;
            final int inset = md3 ? dp(4) : 0;
            if (md3 && PengramMD3.tonalRows() && (unread || pinned)) {
                rect.set(left - dp(6) + inset, y, right + dp(6) - inset, y + rowHeight);
                fillPaint.setColor(unread
                        ? PengramMD3.unreadContainer(resourcesProvider)
                        : PengramMD3.pinnedContainer(resourcesProvider));
                final float radius = Math.min(PengramMD3.rowRadius(), rowHeight / 2f);
                canvas.drawRoundRect(rect, radius, radius, fillPaint);
            }
            // аватар
            fillPaint.setColor(ColorUtils.blendARGB(accent, surface, a * 0.22f));
            canvas.drawCircle(left + dp(14), y + rowHeight / 2f, dp(13), fillPaint);
            // имя и сообщение
            titlePaint.setColor(textColor);
            canvas.drawText(previewTitle(a), left + dp(34), y + dp(17), titlePaint);
            textPaint.setColor(hintColor);
            canvas.drawText(previewText(a), left + dp(34), y + dp(31), textPaint);
            if (unread) {
                final float badgeWidth = dp(18);
                rect.set(right - badgeWidth, y + dp(12), right, y + dp(12) + dp(16));
                fillPaint.setColor(accent);
                canvas.drawRoundRect(rect, dp(8), dp(8), fillPaint);
                textPaint.setColor(color(Theme.key_chats_actionIcon));
                canvas.drawText("3", rect.centerX() - dp(3), rect.centerY() + dp(4), textPaint);
            }
            if (!PengramMD3.hideDividers() && a != 2) {
                linePaint.setColor(ColorUtils.setAlphaComponent(textColor, 26));
                canvas.drawLine(left + dp(34), y + rowHeight, right, y + rowHeight, linePaint);
            }
            y += rowHeight + (md3 ? dp(3) : 0);
        }

        // кнопка «написать»
        final float fabSize = dp(34);
        rect.set(right - fabSize, getMeasuredHeight() - dp(20) - fabSize, right, getMeasuredHeight() - dp(20));
        fillPaint.setColor(accent);
        final float fabRadius = PengramMD3.fab() ? dp(11) : fabSize / 2f;
        canvas.drawRoundRect(rect, fabRadius, fabRadius, fillPaint);
        fillPaint.setColor(color(Theme.key_chats_actionIcon));
        canvas.drawRect(rect.centerX() - dp(6), rect.centerY() - dp(1), rect.centerX() + dp(6), rect.centerY() + dp(1), fillPaint);
        canvas.drawRect(rect.centerX() - dp(1), rect.centerY() - dp(6), rect.centerX() + dp(1), rect.centerY() + dp(6), fillPaint);
    }

    private String previewTitle(int index) {
        switch (index) {
            case 0: return getString(R.string.PengramMD3PreviewChat1);
            case 1: return getString(R.string.PengramMD3PreviewChat2);
            default: return getString(R.string.PengramMD3PreviewChat3);
        }
    }

    private String previewText(int index) {
        switch (index) {
            case 0: return getString(R.string.PengramMD3PreviewLine1);
            case 1: return getString(R.string.PengramMD3PreviewLine2);
            default: return getString(R.string.PengramMD3PreviewLine3);
        }
    }
}
