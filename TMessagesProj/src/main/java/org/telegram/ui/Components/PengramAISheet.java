package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.text.InputFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.EditText;
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
import java.util.ArrayList;

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

    public interface OnReplace {
        boolean replace(CharSequence text, boolean asQuote);
    }

    private final TextView answerView;
    private final TextView statusView;
    private final ScrollView scrollView;
    private final LinearLayout buttons;
    private final LinearLayout transcript;
    private final EditText followUp;
    private final TextView sendFollowUp;
    private final PengramAI.Service service;
    private final String systemPrompt;
    private final PengramAIRoles.Role selectedRole;
    private final ArrayList<PengramAIClient.Turn> conversation;
    private final int initialTurnCount;

    private final StringBuilder answer = new StringBuilder();
    private boolean finished;

    public PengramAISheet(Context context, Theme.ResourcesProvider resourcesProvider,
                          CharSequence source, PengramAIRoles.Role role,
                          List<PengramAIClient.Turn> turns, OnInsert onInsert, OnReplace onReplace) {
        super(context, false, resourcesProvider);
        setApplyBottomPadding(false);

        service = PengramAI.active();
        systemPrompt = role == null ? null : role.prompt;
        selectedRole = role;
        conversation = new ArrayList<>(turns);
        initialTurnCount = Math.min(2, conversation.size());
        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(10));

        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setText(getString(R.string.PengramAIResultTitle));
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        statusView = new TextView(context);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusView.setTextColor(getThemedColor(Theme.key_dialogTextGray3));
        statusView.setText((service == null ? getString(R.string.PengramAINoService) : service.title) + "  ·  " +
                (role == null ? "" : role.title));
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
        transcript = new LinearLayout(context);
        transcript.setOrientation(LinearLayout.VERTICAL);
        transcript.addView(answerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        scrollView.addView(transcript, new FrameLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        final LinearLayout inputRow = new LinearLayout(context);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        followUp = new EditText(context);
        followUp.setSingleLine(true);
        followUp.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1500)});
        followUp.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        followUp.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        followUp.setHintTextColor(getThemedColor(Theme.key_dialogTextGray3));
        followUp.setHint(getString(R.string.PengramAIFollowUpHint));
        inputRow.addView(followUp, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        sendFollowUp = new TextView(context);
        sendFollowUp.setText(getString(R.string.PengramAIFollowUpSend));
        sendFollowUp.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        sendFollowUp.setBackground(Theme.AdaptiveRipple.filledRect(
                getThemedColor(Theme.key_featuredStickers_addButton), 10));
        sendFollowUp.setPadding(dp(12), dp(10), dp(12), dp(10));
        sendFollowUp.setOnClickListener(v -> continueConversation());
        inputRow.addView(sendFollowUp, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        root.addView(inputRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        sendFollowUp.setEnabled(false);
        sendFollowUp.setAlpha(.5f);

        buttons = new LinearLayout(context);
        buttons.setOrientation(onReplace == null ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        buttons.setVisibility(View.GONE);
        root.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        if (onReplace != null) {
            addButton(context, getString(R.string.PengramAIReplaceDraft), () -> {
                if (onReplace.replace(answer.toString(), PengramAI.isAsQuote())) {
                    dismiss();
                } else {
                    statusView.setText(getString(R.string.PengramAIDraftChanged));
                }
            });
        }
        if (onInsert != null) {
            addButton(context, getString(onReplace == null ? R.string.PengramAIInsert : R.string.PengramAIAppendDraft), () -> {
                onInsert.insert(answer.toString(), PengramAI.isAsQuote());
                dismiss();
            });
        }
        addButton(context, getString(R.string.Copy), () -> {
            AndroidUtilities.addToClipboard(answer.toString());
            if (containerView instanceof android.widget.FrameLayout) {
                BulletinFactory.of((android.widget.FrameLayout) containerView, resourcesProvider)
                        .createSimpleBulletin(R.raw.copy, getString(R.string.TextCopied)).show();
            }
        });

        final FrameLayout contentRoot = new FrameLayout(context);
        contentRoot.addView(root, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                Math.min(AndroidUtilities.displaySize.y * 0.6f / AndroidUtilities.density, 420f), Gravity.TOP));
        setCustomView(contentRoot);

        request();
    }

    private void addButton(Context context, CharSequence text, Runnable action) {
        final TextView button = new TextView(context);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        final boolean primary = buttons.getChildCount() == 0;
        final int accent = getThemedColor(Theme.key_featuredStickers_addButton);
        button.setTextColor(getThemedColor(primary ? Theme.key_featuredStickers_buttonText : Theme.key_dialogTextBlack));
        button.setBackground(Theme.AdaptiveRipple.filledRect(
                primary ? accent : Theme.multAlpha(accent, 0.13f), 10));
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
        button.setText(text);
        button.setOnClickListener(v -> action.run());
        if (buttons.getOrientation() == LinearLayout.VERTICAL) {
            buttons.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44,
                    0, buttons.getChildCount() == 0 ? 0 : 8, 0, 0));
        } else {
            buttons.addView(button, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f,
                    buttons.getChildCount() == 0 ? 0 : 8, 0, 0, 0));
        }
    }

    /** A bounded, in-sheet conversation; no follow-up turns are saved to the chat or disk. */
    private void continueConversation() {
        if (!finished || answer.length() == 0 || service == null) return;
        final String question = followUp.getText().toString().trim();
        if (question.isEmpty()) return;
        followUp.setText("");
        final TextView oldAnswer = new TextView(getContext());
        oldAnswer.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        oldAnswer.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        oldAnswer.setText(answer.toString());
        transcript.addView(oldAnswer, transcript.getChildCount() - 1,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        final TextView questionView = new TextView(getContext());
        questionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        questionView.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        questionView.setText(getString(R.string.PengramAIFollowUpYou) + " " + question);
        transcript.addView(questionView, transcript.getChildCount() - 1,
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 4));
        conversation.add(new PengramAIClient.Turn("assistant",
                answer.substring(0, Math.min(answer.length(), 2000))));
        conversation.add(new PengramAIClient.Turn("user", question));
        while (conversation.size() > 10) conversation.remove(initialTurnCount);
        AndroidUtilities.hideKeyboard(followUp);
        request();
    }

    private void request() {
        finished = false;
        answer.setLength(0);
        answerView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        answerView.setText(getString(R.string.PengramAIThinking));
        buttons.setVisibility(View.GONE);
        sendFollowUp.setEnabled(false);
        sendFollowUp.setAlpha(.5f);
        // Take a snapshot so a later follow-up cannot mutate an in-flight HTTP request.
        PengramAIClient.ask(service, systemPrompt, new ArrayList<>(conversation), PengramAI.isStream(),
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
                        if (finished) return;
                        finished = true;
                        if (!TextUtils.isEmpty(text)) {
                            answer.setLength(0);
                            answer.append(text);
                            answerView.setText(text);
                        }
                        statusView.setText((service == null ? "" : service.title) + "  ·  " +
                                (selectedRole == null ? "" : selectedRole.title));
                        buttons.setVisibility(answer.length() == 0 ? View.GONE : View.VISIBLE);
                        if (answer.length() == 0) {
                            answerView.setText(getString(R.string.PengramAIErrorEmpty));
                        } else {
                            sendFollowUp.setEnabled(true);
                            sendFollowUp.setAlpha(1f);
                        }
                    }

                    @Override
                    public void onError(String message) {
                        finished = true;
                        answer.setLength(0);
                        answerView.setText(message);
                        answerView.setTextColor(getThemedColor(Theme.key_text_RedBold));
                    }
                });
    }
}
