package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/**
 * Pengram: плашка «ваш номер не будет показан» в карточке контакта.
 *
 * Вместо галочки, которую легко задеть по дороге к «Готово», здесь спокойная
 * строка-состояние: видно, что номер остаётся при вас, и видно, куда идти,
 * если нужно иначе. Нажатие уводит в настройки форка — чтобы решение
 * принималось осознанно, а не одним случайным тапом над кнопкой отправки.
 */
public class PengramSharePhoneBadge extends FrameLayout {

    private final TextView title;
    private final TextView subtitle;
    private final ImageView icon;
    private final LinearLayout container;

    public PengramSharePhoneBadge(Context context) {
        super(context);
        setPadding(dp(12), dp(4), dp(12), dp(4));

        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setPadding(dp(14), dp(12), dp(14), dp(12));

        icon = new ImageView(context);
        icon.setImageResource(R.drawable.msg_secret);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        container.addView(icon, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(AndroidUtilities.bold());
        title.setText(getString(R.string.PengramPhoneHiddenTitle));
        texts.addView(title);

        subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setLineSpacing(dp(1), 1f);
        texts.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        container.addView(texts, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1f));
        addView(container, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        updateColors();
    }

    /** имя собеседника делает фразу человеческой, а не канцелярской */
    public void setUserName(CharSequence name) {
        if (name == null || name.length() == 0) {
            subtitle.setText(getString(R.string.PengramPhoneHiddenInfoShort));
        } else {
            subtitle.setText(AndroidUtilities.replaceTags(
                    LocaleController.formatString(R.string.PengramPhoneHiddenInfo, name)));
        }
    }

    public void updateColors() {
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        final int background = Theme.getColor(Theme.key_windowBackgroundWhite);
        final GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(14));
        // мягкая подложка оттенком акцента: заметно, но не кричит красным
        shape.setColor(ColorUtils.blendARGB(background, accent, 0.10f));
        container.setBackground(shape);
        icon.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
    }
}
