package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
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
import org.telegram.ui.Components.PengramDialogPreviewView;
import org.telegram.ui.Components.PengramFontPreviewView;
import org.telegram.ui.Components.PengramLyricsView;
import org.telegram.ui.Components.PengramPlayerMockView;
import org.telegram.ui.Components.PengramTabsMockView;

/**
 * Pengram: «Конструктор» — одно место, где собирается весь внешний вид.
 * Каждая карточка показывает схемку того, что настраивается, и ведёт в редактор,
 * где элементы двигаются пальцем и прячутся.
 */
public class PengramConstructorActivity extends BaseFragment {

    private static final int KIND_DIALOGS = 0;
    private static final int KIND_CHAT_MENU = 1;
    private static final int KIND_SETTINGS = 2;
    private static final int KIND_TABS = 3;
    private static final int KIND_PLAYER = 4;
    private static final int KIND_CHAT_BUTTONS = 5;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.PengramConstructor));
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

        final TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        hint.setText(getString(R.string.PengramConstructorInfo));
        hint.setPadding(dp(20), dp(14), dp(20), dp(4));
        content.addView(hint);

        // ——— главный редактор: целый экран Telegram, который можно перестраивать ———
        addCard(content, KIND_CHAT_BUTTONS, R.string.PengramConstructorEditor, R.string.PengramConstructorEditorInfo,
                () -> presentFragment(new PengramLayoutEditorActivity()));

        addCard(content, KIND_CHAT_BUTTONS, R.string.PengramDeleteEffectHeader, R.string.PengramDeleteEffectPreview,
                () -> presentFragment(new PengramDeleteEffectActivity()));

        // ——— живая мастерская: всё меняется прямо на макете, без галочек ———
        addSectionTitle(content, getString(R.string.PengramConstructorLive));

        final PengramTabsMockView tabsMock = new PengramTabsMockView(context);
        addLiveCard(content, getString(R.string.PengramConstructorLiveTabs), tabsMock, null);

        final PengramFontPreviewView fontPreview = new PengramFontPreviewView(context);
        final TextView fontValue = addLiveCard(content, getString(R.string.PengramConstructorLiveFont), fontPreview,
                () -> fontName(PengramConfig.appFont));
        fontPreview.setOnChanged(() -> {
            if (fontValue != null) {
                fontValue.setText(fontName(PengramConfig.appFont));
            }
        });

        final PengramDialogPreviewView dialogPreview = new PengramDialogPreviewView(context);
        final TextView dialogValue = addLiveCard(content, getString(R.string.PengramConstructorLiveDialogs), dialogPreview,
                () -> getString(PengramConfig.getDialogAvatarShapeName(PengramConfig.getDialogAvatarShape())));
        dialogPreview.setOnClickListener(v -> {
            PengramConfig.setDialogAvatarShape((PengramConfig.getDialogAvatarShape() + 1) % PengramConfig.DIALOG_AVATAR_COUNT);
            dialogPreview.invalidate();
            if (dialogValue != null) {
                dialogValue.setText(getString(PengramConfig.getDialogAvatarShapeName(PengramConfig.getDialogAvatarShape())));
            }
        });

        final PengramPlayerMockView playerMock = new PengramPlayerMockView(context, PengramConfig.getPlayerStyle());
        playerMock.setAccent(Theme.getColor(Theme.key_featuredStickers_addButton));
        final TextView playerValue = addLiveCard(content, getString(R.string.PengramConstructorLivePlayer), playerMock,
                () -> getString(PengramConfig.getPlayerStyleName(PengramConfig.getPlayerStyle())), 86, 130, Gravity.CENTER_HORIZONTAL);
        playerMock.setOnClickListener(v -> {
            final int next = (PengramConfig.getPlayerStyle() + 1) % PengramConfig.PLAYER_STYLE_COUNT;
            PengramConfig.setPlayerStyle(next);
            playerMock.setStyle(next);
            if (playerValue != null) {
                playerValue.setText(getString(PengramConfig.getPlayerStyleName(next)));
            }
        });

        final PengramLyricsView lyricsPreview = new PengramLyricsView(context);
        lyricsPreview.setColors(Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        lyricsPreview.setPreview(getString(R.string.PengramLyricsSample), PengramConfig.getLyricsAnim(), 18);
        final TextView lyricsValue = addLiveCard(content, getString(R.string.PengramConstructorLiveLyrics), lyricsPreview,
                () -> getString(PengramConfig.getLyricsAnimName(PengramConfig.getLyricsAnim())), LayoutHelper.MATCH_PARENT, 56, Gravity.LEFT);
        lyricsPreview.setOnClickListener(v -> {
            final int next = (PengramConfig.getLyricsAnim() + 1) % PengramConfig.LYRICS_ANIM_COUNT;
            PengramConfig.setLyricsAnim(next);
            lyricsPreview.setPreview(getString(R.string.PengramLyricsSample), next, 18);
            if (lyricsValue != null) {
                lyricsValue.setText(getString(PengramConfig.getLyricsAnimName(next)));
            }
        });

        addSectionTitle(content, getString(R.string.PengramConstructorEditors));

        addCard(content, KIND_DIALOGS, R.string.PengramConstructorDialogs, R.string.PengramConstructorDialogsInfo,
                () -> presentFragment(new PengramChatLookActivity()));
        addCard(content, KIND_CHAT_MENU, R.string.PengramConstructorChatMenu, R.string.PengramConstructorChatMenuInfo,
                () -> presentFragment(new PengramMenuItemsActivity(PengramMenuItemsActivity.MODE_MENU)));
        addCard(content, KIND_CHAT_BUTTONS, R.string.PengramConstructorChatButtons, R.string.PengramConstructorChatButtonsInfo,
                () -> presentFragment(new PengramMenuItemsActivity(PengramMenuItemsActivity.MODE_CHAT)));
        addCard(content, KIND_SETTINGS, R.string.PengramConstructorSettings, R.string.PengramConstructorSettingsInfo,
                () -> presentFragment(new PengramMenuItemsActivity(PengramMenuItemsActivity.MODE_SETTINGS)));
        addCard(content, KIND_TABS, R.string.PengramConstructorTabs, R.string.PengramConstructorTabsInfo,
                () -> presentFragment(new PengramSettingsActivity(PengramSettingsActivity.SECTION_CHATS)));
        addCard(content, KIND_PLAYER, R.string.PengramConstructorPlayer, R.string.PengramConstructorPlayerInfo,
                () -> presentFragment(new PengramPlayerStyleActivity()));

        fragmentView = scrollView;
        return fragmentView;
    }

    private static CharSequence fontName(int font) {
        switch (font) {
            case PengramConfig.FONT_SYSTEM: return getString(R.string.PengramFontSystem);
            case PengramConfig.FONT_SERIF: return getString(R.string.PengramFontSerif);
            case PengramConfig.FONT_MONOSPACE: return getString(R.string.PengramFontMono);
            default: return getString(R.string.PengramFontDefault);
        }
    }

    private void addSectionTitle(LinearLayout parent, CharSequence text) {
        final TextView title = new TextView(getContext());
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        title.setText(text);
        title.setPadding(dp(20), dp(16), dp(20), dp(6));
        parent.addView(title);
    }

    /** карточка с настоящим макетом внутри: трогаешь макет — настройка меняется */
    private TextView addLiveCard(LinearLayout parent, CharSequence title, View mock, java.util.concurrent.Callable<CharSequence> valueProvider) {
        return addLiveCard(parent, title, mock, valueProvider, LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT);
    }

    private TextView addLiveCard(LinearLayout parent, CharSequence title, View mock, java.util.concurrent.Callable<CharSequence> valueProvider,
                                 int widthDp, int heightDp, int gravity) {
        final Context context = getContext();
        final LinearLayout card = new LinearLayout(context) {
            private final RectF rect = new RectF();
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                rect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
                paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                canvas.drawRoundRect(rect, dp(14), dp(14), paint);
            }
        };
        card.setWillNotDraw(false);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(10), 0, dp(10));

        final TextView caption = new TextView(context);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        caption.setTypeface(AndroidUtilities.bold());
        caption.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        caption.setText(title);
        caption.setPadding(dp(16), 0, dp(16), 0);
        card.addView(caption);

        TextView value = null;
        if (valueProvider != null) {
            value = new TextView(context);
            value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            value.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            value.setPadding(dp(16), dp(2), dp(16), 0);
            try {
                value.setText(valueProvider.call());
            } catch (Exception ignore) {
            }
            card.addView(value);
        }

        card.addView(mock, LayoutHelper.createLinear(widthDp, heightDp, gravity, 0, 8, 0, 0));
        parent.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 6, 12, 0));
        return value;
    }

    private void addCard(LinearLayout parent, int kind, int titleRes, int descRes, Runnable action) {
        final Context context = getContext();
        final FrameLayout card = new FrameLayout(context) {
            private final RectF rect = new RectF();
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                rect.set(0, 0, getMeasuredWidth(), getMeasuredHeight());
                paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                canvas.drawRoundRect(rect, dp(14), dp(14), paint);
            }
        };
        card.setWillNotDraw(false);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setOnClickListener(v -> {
            v.animate().cancel();
            v.setScaleX(0.97f);
            v.setScaleY(0.97f);
            v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
            action.run();
        });

        final MiniPreview preview = new MiniPreview(context, kind);
        card.addView(preview, LayoutHelper.createFrame(76, 62, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        card.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, 88, 0, 8, 0));

        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setText(getString(titleRes));
        column.addView(title);

        final TextView desc = new TextView(context);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        desc.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        desc.setText(getString(descRes));
        column.addView(desc, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        parent.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 8, 12, 0));
    }

    /** схемка того, что настраивается: список, меню, настройки, вкладки, плеер */
    private static class MiniPreview extends View {

        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        MiniPreview(Context context, int kind) {
            super(context);
            this.kind = kind;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getMeasuredWidth();
            final int h = getMeasuredHeight();
            final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
            final int base = Theme.getColor(Theme.key_windowBackgroundGray);
            final int line = ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), 110);

            rect.set(0, 0, w, h);
            paint.setColor(base);
            canvas.drawRoundRect(rect, dp(10), dp(10), paint);

            switch (kind) {
                case KIND_DIALOGS: {
                    for (int a = 0; a < 3; ++a) {
                        final float top = dp(8) + a * dp(17);
                        paint.setColor(a == 0 ? accent : line);
                        final int radius = PengramConfig.getDialogAvatarShape() == PengramConfig.DIALOG_AVATAR_SQUARE
                                ? 0 : PengramConfig.getDialogAvatarShape() == PengramConfig.DIALOG_AVATAR_ROUNDED ? dp(3) : dp(6);
                        rect.set(dp(8), top, dp(20), top + dp(12));
                        canvas.drawRoundRect(rect, radius, radius, paint);
                        paint.setColor(line);
                        rect.set(dp(24), top + dp(2), w - dp(10), top + dp(6));
                        canvas.drawRoundRect(rect, dp(2), dp(2), paint);
                        rect.set(dp(24), top + dp(8), w - dp(20), top + dp(11));
                        canvas.drawRoundRect(rect, dp(2), dp(2), paint);
                    }
                    break;
                }
                case KIND_CHAT_MENU: {
                    paint.setColor(line);
                    rect.set(dp(8), dp(8), w - dp(8), dp(16));
                    canvas.drawRoundRect(rect, dp(3), dp(3), paint);
                    paint.setColor(accent);
                    for (int a = 0; a < 3; ++a) {
                        canvas.drawCircle(w - dp(13), dp(10) + a * dp(3), dp(1.1f), paint);
                    }
                    for (int a = 0; a < 3; ++a) {
                        paint.setColor(a == 1 ? accent : line);
                        rect.set(dp(26), dp(22) + a * dp(11), w - dp(8), dp(30) + a * dp(11));
                        canvas.drawRoundRect(rect, dp(3), dp(3), paint);
                    }
                    break;
                }
                case KIND_CHAT_BUTTONS: {
                    paint.setColor(line);
                    rect.set(dp(8), dp(8), w - dp(8), h - dp(18));
                    canvas.drawRoundRect(rect, dp(5), dp(5), paint);
                    paint.setColor(accent);
                    canvas.drawCircle(w - dp(16), h - dp(26), dp(7), paint);
                    canvas.drawCircle(w - dp(16), h - dp(10), dp(7), paint);
                    break;
                }
                case KIND_SETTINGS: {
                    for (int a = 0; a < 4; ++a) {
                        paint.setColor(a == 0 ? accent : line);
                        rect.set(dp(8), dp(8) + a * dp(13), dp(18), dp(18) + a * dp(13));
                        canvas.drawRoundRect(rect, dp(3), dp(3), paint);
                        paint.setColor(line);
                        rect.set(dp(22), dp(11) + a * dp(13), w - dp(10), dp(15) + a * dp(13));
                        canvas.drawRoundRect(rect, dp(2), dp(2), paint);
                    }
                    break;
                }
                case KIND_TABS: {
                    paint.setColor(line);
                    rect.set(dp(8), dp(8), w - dp(8), h - dp(20));
                    canvas.drawRoundRect(rect, dp(5), dp(5), paint);
                    for (int a = 0; a < 4; ++a) {
                        paint.setColor(a == 1 ? accent : line);
                        final float cx = dp(14) + a * ((w - dp(28)) / 3f);
                        canvas.drawCircle(cx, h - dp(11), dp(4), paint);
                    }
                    break;
                }
                case KIND_PLAYER:
                default: {
                    paint.setColor(accent);
                    rect.set(dp(10), dp(8), w - dp(10), dp(36));
                    canvas.drawRoundRect(rect, dp(6), dp(6), paint);
                    paint.setColor(line);
                    rect.set(dp(10), dp(41), w - dp(10), dp(45));
                    canvas.drawRoundRect(rect, dp(2), dp(2), paint);
                    paint.setColor(accent);
                    canvas.drawCircle(w / 2f, h - dp(10), dp(6), paint);
                    paint.setColor(line);
                    canvas.drawCircle(w / 2f - dp(16), h - dp(10), dp(3.5f), paint);
                    canvas.drawCircle(w / 2f + dp(16), h - dp(10), dp(3.5f), paint);
                    break;
                }
            }
        }
    }
}
