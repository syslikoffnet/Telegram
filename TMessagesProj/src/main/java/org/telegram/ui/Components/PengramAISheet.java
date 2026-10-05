package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PengramAI;
import org.telegram.messenger.PengramAIClient;
import org.telegram.messenger.PengramAIRoles;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.List;

/**
 * Pengram: окно разговора с нейросетью.
 *
 * Показывает, кого спрашиваем и в какой роли, и печатает ответ по мере того,
 * как он приходит. Готовый текст можно скопировать или сразу положить в поле
 * ввода — обычной строкой или цитатой, как выбрано в настройках.
 */
public class PengramAISheet extends BottomSheet {

    public interface OnInsert {
        void insert(CharSequence text, boolean asQuote);
    }

    private final TextView answerView;
    private final TextView statusView;
    private final ScrollView scrollView;
    private final LinearLayout buttons;

    private final StringBuilder answer = new StringBuilder();
    private boolean finished;

    public PengramAISheet(Context context, Theme.ResourcesProvider resourcesProvider,
                          CharSequence source, PengramAIRoles.Role role,
                          List<PengramAIClient.Turn> turns, OnInsert onInsert) {
        super(context, false, resourcesProvider);
        setApplyBottomPadding(false);

        final PengramAI.Service service = PengramAI.active();
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(10));

        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setText(role != null ? role.title : getString(R.string.PengramAITitle));
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusView.setTextColor(getThemedColor(Theme.key_dialogTextGray3));
        statusView.setText(service == null ? getString(R.string.PengramAINoService) : service.summary());
        root.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 10));

        // исходный текст показываем, только если не включено «показывать один ответ»
        if (!PengramAI.isOnlyAnswer() && !TextUtils.isEmpty(source)) {
            final TextView sourceView = new TextView(context);
            sourceView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            sourceView.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
            sourceView.setMaxLines(4);
            sourceView.setEllipsize(TextUtils.TruncateAt.END);
            sourceView.setText(source);
            sourceView.setPadding(dp(12), dp(8), dp(12), dp(8));
            sourceView.setBackground(Theme.createRoundRectDrawable(dp(10),
                    Theme.multAlpha(getThemedColor(Theme.key_dialogTextBlack), 0.06f)));
            root.addView(sourceView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));
        }

        answerView = new TextView(context);
        answerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        answerView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        answerView.setTextIsSelectable(true);
        answerView.setText(getString(R.string.PengramAIThinking));

        scrollView = new ScrollView(context);
        scrollView.addView(answerView, new FrameLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setVisibility(View.GONE);
        root.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        addButton(context, getString(R.string.Copy), () -> {
            AndroidUtilities.addToClipboard(answer.toString());
            if (containerView instanceof android.widget.FrameLayout) {
                BulletinFactory.of((android.widget.FrameLayout) containerView, resourcesProvider)
                        .createSimpleBulletin(R.raw.copy, getString(R.string.TextCopied)).show();
            }
        });
        if (onInsert != null) {
            addButton(context, getString(R.string.PengramAIInsert), () -> {
                onInsert.insert(answer.toString(), PengramAI.isAsQuote());
                dismiss();
            });
        }

        final FrameLayout contentRoot = new FrameLayout(context);
        contentRoot.addView(root, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                Math.min(AndroidUtilities.displaySize.y * 0.6f / AndroidUtilities.density, 420f), Gravity.TOP));
        setCustomView(contentRoot);

        request(role, turns);
    }

    private void addButton(Context context, CharSequence text, Runnable action) {
        final TextView button = new TextView(context);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        button.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        button.setBackground(Theme.AdaptiveRipple.filledRect(
                getThemedColor(Theme.key_featuredStickers_addButton), 8));
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
        button.setText(text);
        button.setOnClickListener(v -> action.run());
        buttons.addView(button, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f,
                buttons.getChildCount() == 0 ? 0 : 8, 0, 0, 0));
    }

    private void request(PengramAIRoles.Role role, List<PengramAIClient.Turn> turns) {
        final PengramAI.Service service = PengramAI.active();
        PengramAIClient.ask(service, role == null ? null : role.prompt, turns, PengramAI.isStream(),
                new PengramAIClient.Listener() {
                    @Override
                    public void onChunk(String text) {
                        if (answer.length() == 0) {
                            answerView.setText("");
                        }
                        answer.append(text);
                        answerView.setText(answer.toString());
                        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                    }

                    @Override
                    public void onDone(String text) {
                        if (finished) {
                            return;
                        }
                        finished = true;
                        if (!TextUtils.isEmpty(text)) {
                            answer.setLength(0);
                            answer.append(text);
                            answerView.setText(text);
                        }
                        statusView.setText(service == null ? "" : service.summary());
                        buttons.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onError(String message) {
                        finished = true;
                        answerView.setText(message);
                        answerView.setTextColor(getThemedColor(Theme.key_text_RedBold));
                    }
                });
    }
}
