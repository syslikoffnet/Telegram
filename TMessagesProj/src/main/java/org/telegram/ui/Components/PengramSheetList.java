package org.telegram.ui.Components;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;

/**
 * Pengram: прокручиваемый список строк внутри нижней шторки.
 *
 * <p>Шторка ({@code BottomSheet.setCustomView}) растягивает содержимое по
 * высоте и обрезает всё, что не влезло. Списки выбора устройства длинные
 * (реальная модель + популярные + «своё», плюс версия системы), и на обычном
 * телефоне последние строки — включая «Своё» — оказывались за нижней границей
 * и были недоступны: высоту было нечем прокрутить. Здесь содержимое ограничено
 * двумя третями экрана, а остальное уезжает в прокрутку.
 */
public class PengramSheetList extends ScrollView {

    /** контейнер, в который добавляются строки */
    public final LinearLayout content;

    public PengramSheetList(Context context) {
        super(context);
        setVerticalScrollBarEnabled(false);
        setClipToPadding(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(6));
        addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
    }

    /** строка во всю ширину; {@code heightDp <= 0} — высоту считает сама строка */
    public void addRow(View row, int heightDp) {
        content.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                heightDp > 0 ? AndroidUtilities.dp(heightDp) : LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int cap = Math.max(AndroidUtilities.dp(180),
                (int) (AndroidUtilities.displaySize.y * 0.62f));
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
    }
}
