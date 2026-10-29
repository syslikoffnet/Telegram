package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.PengramInlineWebSearch;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.List;

/**
 * Pengram: интерактивное окно быстрого интернет-поиска по префиксу прямо поверх чата.
 * Позволяет мгновенно подставить ответ/факт/ссылку в поле ввода, скопировать текст
 * или открыть полный поиск в выбранном поисковике без потери контекста переписки.
 */
public class PengramWebSearchSheet extends BottomSheet {

    public interface InsertCallback {
        void onInsert(String textToInsert);
    }

    private final int currentAccount;
    private final InsertCallback insertCallback;

    private final EditTextBoldCursor searchField;
    private final ImageView clearBtn;
    private final HorizontalScrollView suggestScroll;
    private final LinearLayout suggestRow;
    private final LinearLayout resultsContainer;
    private final LinearLayout loadingRow;

    private PengramInlineWebSearch.SearchHandle activeHandle;
    private String currentQuery = "";

    private final Runnable debouncedSearchRunnable = () -> runQueryNow(currentQuery);

    public PengramWebSearchSheet(
            Context context,
            int currentAccount,
            String initialQuery,
            @Nullable PengramInlineWebSearch.SearchResponse preloaded,
            @Nullable Theme.ResourcesProvider resourcesProvider,
            InsertCallback insertCallback
    ) {
        super(context, true, resourcesProvider);
        this.currentAccount = currentAccount;
        this.insertCallback = insertCallback;

        fixNavigationBar(getThemedColor(Theme.key_dialogBackground));

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(8), 0, dp(14));

        // 1. Заголовок и кнопка закрытия
        final FrameLayout headerRow = new FrameLayout(context);
        final TextView headerTitle = new TextView(context);
        headerTitle.setText(getString(R.string.PengramWebSearchSheetTitle));
        headerTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        headerTitle.setTypeface(AndroidUtilities.bold());
        headerTitle.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        headerTitle.setSingleLine(true);
        headerRow.addView(headerTitle, LayoutHelper.createFrame(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL, 18, 0, 52, 0));

        final ImageView closeBtn = new ImageView(context);
        closeBtn.setImageResource(R.drawable.ic_ab_close);
        closeBtn.setScaleType(ImageView.ScaleType.CENTER);
        closeBtn.setColorFilter(new PorterDuffColorFilter(
                getThemedColor(Theme.key_dialogTextGray3), PorterDuff.Mode.SRC_IN));
        closeBtn.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(18)));
        closeBtn.setContentDescription(LocaleController.getString(R.string.Close));
        closeBtn.setOnClickListener(v -> dismiss());
        headerRow.addView(closeBtn, LayoutHelper.createFrame(36, 36, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
        root.addView(headerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42));

        // 2. Поле живого поиска внутри окна
        final FrameLayout searchBox = new FrameLayout(context);
        final int fieldBg = ColorUtils.setAlphaComponent(
                getThemedColor(Theme.key_dialogTextGray3), Theme.isCurrentThemeDark() ? 36 : 24);
        searchBox.setBackground(Theme.createRoundRectDrawable(dp(12), fieldBg));

        final ImageView searchIcon = new ImageView(context);
        searchIcon.setImageResource(R.drawable.msg_search);
        searchIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        searchIcon.setColorFilter(new PorterDuffColorFilter(
                getThemedColor(Theme.key_dialogTextGray3), PorterDuff.Mode.SRC_IN));
        searchBox.addView(searchIcon, LayoutHelper.createFrame(20, 20, Gravity.START | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

        searchField = new EditTextBoldCursor(context);
        searchField.setBackground(null);
        searchField.setSingleLine(true);
        searchField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        searchField.setHint(getString(R.string.PengramWebSearchInputHint));
        searchField.setHintTextColor(getThemedColor(Theme.key_dialogTextGray3));
        searchField.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        searchField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        searchField.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        searchField.setCursorSize(dp(19));
        searchField.setCursorWidth(1.6f);
        searchField.setPadding(0, dp(8), 0, dp(8));
        searchBox.addView(searchField, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL, 38, 0, 38, 0));

        clearBtn = new ImageView(context);
        clearBtn.setImageResource(R.drawable.ic_ab_close);
        clearBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        clearBtn.setPadding(dp(6), dp(6), dp(6), dp(6));
        clearBtn.setColorFilter(new PorterDuffColorFilter(
                getThemedColor(Theme.key_dialogTextGray3), PorterDuff.Mode.SRC_IN));
        clearBtn.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(16)));
        clearBtn.setOnClickListener(v -> {
            searchField.setText("");
            AndroidUtilities.showKeyboard(searchField);
        });
        searchBox.addView(clearBtn, LayoutHelper.createFrame(32, 32, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        root.addView(searchBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 16, 4, 16, 6));

        // 3. Горизонтальный ряд автодополнений запроса
        suggestScroll = new HorizontalScrollView(context);
        suggestScroll.setHorizontalScrollBarEnabled(false);
        suggestScroll.setVisibility(android.view.View.GONE);
        suggestRow = new LinearLayout(context);
        suggestRow.setOrientation(LinearLayout.HORIZONTAL);
        suggestRow.setPadding(dp(16), dp(2), dp(16), dp(4));
        suggestScroll.addView(suggestRow, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        root.addView(suggestScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // 4. Индикатор загрузки
        loadingRow = new LinearLayout(context);
        loadingRow.setOrientation(LinearLayout.HORIZONTAL);
        loadingRow.setGravity(Gravity.CENTER_VERTICAL);
        loadingRow.setPadding(dp(20), dp(10), dp(20), dp(10));
        final RadialProgressView spinner = new RadialProgressView(context, resourcesProvider);
        spinner.setSize(dp(18));
        spinner.setProgressColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        loadingRow.addView(spinner, LayoutHelper.createLinear(22, 22, 0, 0, 10, 0));
        final TextView loadingTv = new TextView(context);
        loadingTv.setText(getString(R.string.PengramWebSearchSearchingSub));
        loadingTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        loadingTv.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        loadingRow.addView(loadingTv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        root.addView(loadingRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // 5. Прокручиваемый список карточек (ограничиваем высоту до ~65% экрана)
        final int maxScrollPx = (int) (AndroidUtilities.displaySize.y * 0.62f);
        final ScrollView scrollView = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                final int cappedSpec = MeasureSpec.makeMeasureSpec(maxScrollPx, MeasureSpec.AT_MOST);
                super.onMeasure(widthMeasureSpec, cappedSpec);
            }
        };
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setClipToPadding(false);

        resultsContainer = new LinearLayout(context);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        resultsContainer.setPadding(dp(16), dp(4), dp(16), dp(10));
        scrollView.addView(resultsContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        setCustomView(root);

        currentQuery = initialQuery != null ? initialQuery.trim() : "";
        searchField.setText(currentQuery);
        searchField.setSelection(searchField.length());
        clearBtn.setVisibility(currentQuery.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);

        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                AndroidUtilities.cancelRunOnUIThread(debouncedSearchRunnable);
                AndroidUtilities.hideKeyboard(searchField);
                runQueryNow(searchField.getText() != null ? searchField.getText().toString().trim() : "");
                return true;
            }
            return false;
        });

        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                final String q = s != null ? s.toString().trim() : "";
                clearBtn.setVisibility(q.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
                if (q.equals(currentQuery)) {
                    return;
                }
                currentQuery = q;
                AndroidUtilities.cancelRunOnUIThread(debouncedSearchRunnable);
                if (q.length() >= 2) {
                    AndroidUtilities.runOnUIThread(debouncedSearchRunnable, 300);
                } else {
                    cancelActiveSearch();
                    loadingRow.setVisibility(android.view.View.GONE);
                    suggestScroll.setVisibility(android.view.View.GONE);
                    resultsContainer.removeAllViews();
                }
            }
        });

        if (preloaded != null && preloaded.query.equalsIgnoreCase(currentQuery)) {
            loadingRow.setVisibility(android.view.View.GONE);
            renderResponse(preloaded);
        } else if (currentQuery.length() >= 2) {
            runQueryNow(currentQuery);
        } else {
            loadingRow.setVisibility(android.view.View.GONE);
        }
    }

    private void runQueryNow(String query) {
        cancelActiveSearch();
        final String trimmed = query != null ? query.trim() : "";
        if (trimmed.length() < 2) {
            loadingRow.setVisibility(android.view.View.GONE);
            return;
        }
        final PengramInlineWebSearch.SearchResponse cached = PengramInlineWebSearch.getCached(trimmed);
        if (cached != null) {
            loadingRow.setVisibility(android.view.View.GONE);
            renderResponse(cached);
            return;
        }
        loadingRow.setVisibility(android.view.View.VISIBLE);
        renderBrowserEnginesOnly(trimmed);
        activeHandle = PengramInlineWebSearch.searchAsync(trimmed, currentAccount, response -> {
            loadingRow.setVisibility(android.view.View.GONE);
            renderResponse(response);
        });
    }

    private void renderBrowserEnginesOnly(String query) {
        resultsContainer.removeAllViews();
        addBrowserEnginesSection(query);
    }

    private void renderResponse(PengramInlineWebSearch.SearchResponse resp) {
        if (resp == null) {
            return;
        }
        // Автодополнения запроса
        suggestRow.removeAllViews();
        if (!resp.suggestions.isEmpty()) {
            suggestScroll.setVisibility(android.view.View.VISIBLE);
            for (String sug : resp.suggestions) {
                final TextView chip = buildSmallChip(sug, false);
                chip.setOnClickListener(v -> {
                    haptic(v);
                    searchField.setText(sug);
                    searchField.setSelection(searchField.length());
                    AndroidUtilities.cancelRunOnUIThread(debouncedSearchRunnable);
                    runQueryNow(sug);
                });
                suggestRow.addView(chip, LayoutHelper.createLinear(
                        LayoutHelper.WRAP_CONTENT, 28, 0, 0, 6, 0));
            }
        } else {
            suggestScroll.setVisibility(android.view.View.GONE);
        }

        resultsContainer.removeAllViews();

        // 1. Главная карточка мгновенного ответа
        if (resp.instant != null) {
            addHeroInstantCard(resp.instant);
        }

        // 2. Список веб-результатов
        final List<PengramInlineWebSearch.WebHit> hits = resp.webHits;
        if (!hits.isEmpty()) {
            final TextView secTitle = new TextView(getContext());
            secTitle.setText(getString(R.string.PengramWebSearchWebResults));
            secTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            secTitle.setTypeface(AndroidUtilities.bold());
            secTitle.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
            resultsContainer.addView(secTitle, LayoutHelper.createLinear(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 8, 4, 6));

            for (int i = 0; i < hits.size(); i++) {
                final PengramInlineWebSearch.WebHit hit = hits.get(i);
                // Не дублируем карточку один-в-один, если это единственный идентичный заголовок без новых деталей
                if (i == 0 && resp.instant != null
                        && !TextUtils.isEmpty(resp.instant.url)
                        && resp.instant.url.equalsIgnoreCase(hit.url)
                        && hits.size() > 1) {
                    continue;
                }
                addWebHitCard(hit);
            }
        } else if (resp.instant == null) {
            final TextView emptyHint = new TextView(getContext());
            emptyHint.setText(getString(R.string.PengramWebSearchNoDirectHits));
            emptyHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            emptyHint.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
            resultsContainer.addView(emptyHint, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 6, 4, 8));
        }

        // 3. Кнопки быстрого открытия полного поиска в браузере
        addBrowserEnginesSection(resp.query);
    }

    private void addHeroInstantCard(PengramInlineWebSearch.InstantAnswer instant) {
        final Context ctx = getContext();
        final int accent = getThemedColor(Theme.key_windowBackgroundWhiteBlueText);
        final LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(Theme.createRoundRectDrawable(
                dp(14), ColorUtils.setAlphaComponent(accent, Theme.isCurrentThemeDark() ? 32 : 20)));

        // Бейдж источника
        final LinearLayout badgeRow = new LinearLayout(ctx);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setGravity(Gravity.CENTER_VERTICAL);

        final TextView badge = new TextView(ctx);
        final String badgeText = !TextUtils.isEmpty(instant.sourceName)
                ? (getString(R.string.PengramWebSearchInstantBadge) + " · " + instant.sourceName)
                : getString(R.string.PengramWebSearchInstantBadge);
        badge.setText(badgeText);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        badge.setTypeface(AndroidUtilities.bold());
        badge.setTextColor(accent);
        badgeRow.addView(badge, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        card.addView(badgeRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        // Заголовок ответа
        final TextView titleTv = new TextView(ctx);
        titleTv.setText(instant.title);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        titleTv.setTypeface(AndroidUtilities.bold());
        titleTv.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        card.addView(titleTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Краткое определение / подзаголовок
        if (!TextUtils.isEmpty(instant.subtitle) && !instant.subtitle.equalsIgnoreCase(instant.sourceName)) {
            final TextView subTv = new TextView(ctx);
            subTv.setText(instant.subtitle);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            subTv.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
            card.addView(subTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        // Полная выжимка
        if (!TextUtils.isEmpty(instant.summary) && !instant.summary.equalsIgnoreCase(instant.title)) {
            final TextView sumTv = new TextView(ctx);
            sumTv.setText(instant.summary);
            sumTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            sumTv.setLineSpacing(dp(2), 1f);
            sumTv.setMaxLines(7);
            sumTv.setEllipsize(TextUtils.TruncateAt.END);
            sumTv.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            card.addView(sumTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        }

        // Кнопки действий (горизонтальный скролл для любых экранов)
        final HorizontalScrollView actionsScroll = new HorizontalScrollView(ctx);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout actionsRow = new LinearLayout(ctx);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);

        if (!TextUtils.isEmpty(instant.shortInsert)) {
            final String shortLabel = instant.shortInsert.length() <= 22
                    ? LocaleController.formatString(R.string.PengramWebSearchInsertExact, instant.shortInsert)
                    : getString(R.string.PengramWebSearchInsertShort);
            final TextView insertShortBtn = buildSmallChip(shortLabel, true);
            insertShortBtn.setOnClickListener(v -> {
                haptic(v);
                if (insertCallback != null) {
                    insertCallback.onInsert(instant.shortInsert);
                }
                dismiss();
            });
            actionsRow.addView(insertShortBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0, 0, 6, 0));
        }

        if (!TextUtils.isEmpty(instant.altInsert)
                && !instant.altInsert.equalsIgnoreCase(instant.shortInsert)) {
            final TextView insertAltBtn = buildSmallChip(instant.altInsert, false);
            insertAltBtn.setOnClickListener(v -> {
                haptic(v);
                if (insertCallback != null) {
                    insertCallback.onInsert(instant.altInsert);
                }
                dismiss();
            });
            actionsRow.addView(insertAltBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0, 0, 6, 0));
        }

        if (!TextUtils.isEmpty(instant.summaryInsert)
                && !instant.summaryInsert.equalsIgnoreCase(instant.shortInsert)
                && !instant.summaryInsert.equalsIgnoreCase(instant.altInsert)) {
            final boolean primaryIfNoShort = TextUtils.isEmpty(instant.shortInsert);
            final TextView insertSumBtn = buildSmallChip(
                    primaryIfNoShort ? getString(R.string.PengramWebSearchInsertShort) : getString(R.string.PengramWebSearchInsertFullSentence),
                    primaryIfNoShort
            );
            insertSumBtn.setOnClickListener(v -> {
                haptic(v);
                if (insertCallback != null) {
                    insertCallback.onInsert(instant.summaryInsert);
                }
                dismiss();
            });
            actionsRow.addView(insertSumBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0, 0, 6, 0));
        }

        final String copyPayload = !TextUtils.isEmpty(instant.summary)
                ? (instant.title + " — " + instant.summary)
                : instant.title;
        final TextView copyBtn = buildSmallChip(getString(R.string.PengramWebSearchCopy), false);
        copyBtn.setOnClickListener(v -> {
            haptic(v);
            copyWithToast(copyPayload);
        });
        actionsRow.addView(copyBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0, 0, 6, 0));

        if (!TextUtils.isEmpty(instant.url)) {
            final TextView openBtn = buildSmallChip(getString(R.string.PengramWebSearchOpenArticle) + " ↗", false);
            openBtn.setOnClickListener(v -> {
                haptic(v);
                Browser.openUrl(ctx, instant.url);
                dismiss();
            });
            actionsRow.addView(openBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30));
        }

        actionsScroll.addView(actionsRow, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        card.addView(actionsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        resultsContainer.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));
    }

    private void addWebHitCard(PengramInlineWebSearch.WebHit hit) {
        final Context ctx = getContext();
        final LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        final int cardBg = ColorUtils.setAlphaComponent(
                getThemedColor(Theme.key_dialogTextGray3), Theme.isCurrentThemeDark() ? 24 : 15);
        card.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                dp(12), cardBg, ColorUtils.setAlphaComponent(getThemedColor(Theme.key_listSelector), 42)));

        if (!TextUtils.isEmpty(hit.domain)) {
            final TextView domainTv = new TextView(ctx);
            domainTv.setText(hit.domain);
            domainTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            domainTv.setTypeface(AndroidUtilities.bold());
            domainTv.setTextColor(0xFF19A974);
            domainTv.setSingleLine(true);
            card.addView(domainTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        final TextView titleTv = new TextView(ctx);
        titleTv.setText(hit.title);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        titleTv.setTypeface(AndroidUtilities.bold());
        titleTv.setMaxLines(2);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        titleTv.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        card.addView(titleTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        if (!TextUtils.isEmpty(hit.snippet)) {
            final TextView snippetTv = new TextView(ctx);
            snippetTv.setText(hit.snippet);
            snippetTv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            snippetTv.setMaxLines(3);
            snippetTv.setEllipsize(TextUtils.TruncateAt.END);
            snippetTv.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
            card.addView(snippetTv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        }

        final HorizontalScrollView rowScroll = new HorizontalScrollView(ctx);
        rowScroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout actions = new LinearLayout(ctx);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        final String cleanFact = PengramInlineWebSearch.extractCleanSentenceFromSnippet(hit.snippet);
        final TextView insTextBtn = buildSmallChip(getString(R.string.PengramWebSearchInsertFact), false);
        insTextBtn.setOnClickListener(v -> {
            haptic(v);
            if (insertCallback != null) {
                final String payload = !TextUtils.isEmpty(cleanFact) ? cleanFact : hit.title;
                insertCallback.onInsert(payload);
            }
            dismiss();
        });
        actions.addView(insTextBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 26, 0, 0, 6, 0));

        if (!TextUtils.isEmpty(hit.url)) {
            final TextView insLinkBtn = buildSmallChip(getString(R.string.PengramWebSearchInsertLink), false);
            insLinkBtn.setOnClickListener(v -> {
                haptic(v);
                if (insertCallback != null) {
                    insertCallback.onInsert(hit.url);
                }
                dismiss();
            });
            actions.addView(insLinkBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 26, 0, 0, 6, 0));
        }

        final TextView copyBtn = buildSmallChip(getString(R.string.PengramWebSearchCopy), false);
        copyBtn.setOnClickListener(v -> {
            haptic(v);
            final String text = !TextUtils.isEmpty(hit.snippet)
                    ? (hit.title + "\n" + hit.snippet + "\n" + hit.url)
                    : (hit.title + "\n" + hit.url);
            copyWithToast(text);
        });
        actions.addView(copyBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 26));

        rowScroll.addView(actions, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        card.addView(rowScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        card.setOnClickListener(v -> {
            if (!TextUtils.isEmpty(hit.url)) {
                haptic(v);
                Browser.openUrl(ctx, hit.url);
                dismiss();
            }
        });

        resultsContainer.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 7));
    }

    private void addBrowserEnginesSection(String query) {
        if (TextUtils.isEmpty(query)) {
            return;
        }
        final Context ctx = getContext();
        final TextView title = new TextView(ctx);
        title.setText(getString(R.string.PengramWebSearchOpenInBrowser));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        resultsContainer.addView(title, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 4, 8, 4, 6));

        final HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        final LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);

        final int preferred = PengramConfig.getInlineWebSearchEngine();
        final int[] order = new int[]{
                preferred,
                PengramConfig.WEB_ENGINE_GOOGLE,
                PengramConfig.WEB_ENGINE_YANDEX,
                PengramConfig.WEB_ENGINE_DUCKDUCKGO,
                PengramConfig.WEB_ENGINE_BRAVE
        };
        final boolean[] added = new boolean[PengramConfig.WEB_ENGINE_COUNT];
        for (int eng : order) {
            if (eng < 0 || eng >= PengramConfig.WEB_ENGINE_COUNT || added[eng]) {
                continue;
            }
            added[eng] = true;
            final boolean isPrimary = (eng == preferred);
            final TextView chip = buildSmallChip(PengramConfig.getInlineWebSearchEngineName(eng) + " ↗", isPrimary);
            chip.setOnClickListener(v -> {
                haptic(v);
                Browser.openUrl(ctx, PengramInlineWebSearch.buildBrowserSearchUrl(query, eng));
                dismiss();
            });
            row.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 0, 0, 6, 0));
        }

        final TextView wikiChip = buildSmallChip("Wikipedia ↗", false);
        wikiChip.setOnClickListener(v -> {
            haptic(v);
            Browser.openUrl(ctx, PengramInlineWebSearch.buildWikipediaSearchUrl(query));
            dismiss();
        });
        row.addView(wikiChip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30));

        scroll.addView(row, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        resultsContainer.addView(scroll, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));
    }

    private TextView buildSmallChip(String text, boolean accentFilled) {
        final TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tv.setTypeface(AndroidUtilities.bold());
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setPadding(dp(11), 0, dp(11), 0);
        final int accent = getThemedColor(Theme.key_windowBackgroundWhiteBlueText);
        if (accentFilled) {
            tv.setTextColor(0xFFFFFFFF);
            tv.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                    dp(15), accent, ColorUtils.blendARGB(accent, Color.BLACK, 0.16f)));
        } else {
            final int base = getThemedColor(Theme.key_dialogTextGray3);
            tv.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            tv.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                    dp(15),
                    ColorUtils.setAlphaComponent(base, Theme.isCurrentThemeDark() ? 38 : 26),
                    ColorUtils.setAlphaComponent(base, 64)));
        }
        return tv;
    }

    private void copyWithToast(String text) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        AndroidUtilities.addToClipboard(text);
        if (container != null) {
            BulletinFactory.of(container, resourcesProvider)
                    .createSimpleBulletin(R.raw.info, getString(R.string.PengramWebSearchCopied))
                    .show();
        }
    }

    private void haptic(android.view.View v) {
        if (PengramConfig.isVibrationEnabled() && v != null) {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    private void cancelActiveSearch() {
        if (activeHandle != null) {
            activeHandle.cancel();
            activeHandle = null;
        }
    }

    @Override
    public void dismissInternal() {
        AndroidUtilities.cancelRunOnUIThread(debouncedSearchRunnable);
        cancelActiveSearch();
        super.dismissInternal();
    }
}
