package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PengramAvatarPlacementView;
import org.telegram.ui.Components.PengramMessagePreviewView;

/**
 * Pengram: «Вид сообщений» — визуальная настройка вместо списка галочек.
 * Сверху живое превью настоящего чата, снизу — аватарку можно перетащить туда,
 * где она должна быть: слева, справа, до ника, после ника или вообще спрятать.
 */
public class PengramChatLookActivity extends BaseFragment {

    private PengramMessagePreviewView preview;
    private PengramAvatarPlacementView placement;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.PengramChatLook));
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

        preview = new PengramMessagePreviewView(context, null);
        content.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        content.addView(header(context, getString(R.string.PengramAvatarPos)));
        content.addView(hint(context, getString(R.string.PengramAvatarPlacementHint)));

        placement = new PengramAvatarPlacementView(context);
        placement.setCallback(position -> {
            if (preview != null) {
                preview.update();
            }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
        });
        content.addView(placement, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        final TextView more = new TextView(context);
        more.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        more.setTypeface(AndroidUtilities.bold());
        more.setGravity(Gravity.CENTER);
        more.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        more.setText(getString(R.string.PengramChatLookMore));
        more.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(12),
                Theme.getColor(Theme.key_featuredStickers_addButton),
                Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        more.setOnClickListener(v -> presentFragment(new PengramSettingsActivity(PengramSettingsActivity.SECTION_CHATS)));
        content.addView(more, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 12, 18, 12, 0));

        fragmentView = scrollView;
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

    @Override
    public void onResume() {
        super.onResume();
        if (preview != null) {
            preview.update();
        }
        if (placement != null) {
            placement.update();
        }
    }

    @Override
    public boolean needDelayOpenAnimation() {
        return true;
    }
}
