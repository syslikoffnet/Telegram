package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.PengramConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

/**
 * Pengram: пересылка трека одной кнопкой — с преднастройками.
 *
 * Пересылать музыку обычно приходится в три шага: выделить сообщение, выбрать «Переслать»,
 * вспомнить про галочку «без имени отправителя». Здесь всё решено заранее: в настройках
 * выбирается манера пересылки (от своего имени / с именем отправителя / спрашивать),
 * и дальше кнопка в плеере сразу открывает выбор чата.
 */
public final class PengramTrackForward {

    private PengramTrackForward() {}

    /** Точка входа: кнопка «переслать трек» нажата. */
    public static void start(Context context, Theme.ResourcesProvider resourcesProvider, MessageObject track) {
        if (context == null || track == null) {
            return;
        }
        final int mode = PengramConfig.getTrackForwardMode();
        if (mode == PengramConfig.TRACK_FORWARD_ASK) {
            askMode(context, resourcesProvider, track);
        } else {
            pickChat(track, mode == PengramConfig.TRACK_FORWARD_AS_ME);
        }
    }

    /** Лист с двумя вариантами пересылки и галочкой «запомнить выбор». */
    private static void askMode(Context context, Theme.ResourcesProvider resourcesProvider, MessageObject track) {
        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false, resourcesProvider);
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);

        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        title.setText(getString(R.string.PengramTrackForwardTitle));
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 18, 22, 2));

        final TextView subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        subtitle.setText(track.getMusicTitle(true));
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        layout.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 0, 22, 12));

        final boolean[] remember = new boolean[]{false};
        final BottomSheet[] sheet = new BottomSheet[1];

        layout.addView(option(context, resourcesProvider, R.drawable.msg_user_search,
                getString(R.string.PengramTrackForwardAsMe), getString(R.string.PengramTrackForwardAsMeInfo), v -> {
                    if (remember[0]) {
                        PengramConfig.setTrackForwardMode(PengramConfig.TRACK_FORWARD_AS_ME);
                    }
                    if (sheet[0] != null) sheet[0].dismiss();
                    pickChat(track, true);
                }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        layout.addView(option(context, resourcesProvider, R.drawable.msg_forward,
                getString(R.string.PengramTrackForwardWithAuthor), getString(R.string.PengramTrackForwardWithAuthorInfo), v -> {
                    if (remember[0]) {
                        PengramConfig.setTrackForwardMode(PengramConfig.TRACK_FORWARD_WITH_AUTHOR);
                    }
                    if (sheet[0] != null) sheet[0].dismiss();
                    pickChat(track, false);
                }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final CheckBoxCell rememberCell = new CheckBoxCell(context, 1, resourcesProvider);
        rememberCell.setText(getString(R.string.PengramTrackForwardRemember), "", false, false);
        rememberCell.setOnClickListener(v -> {
            remember[0] = !remember[0];
            rememberCell.setChecked(remember[0], true);
        });
        layout.addView(rememberCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 4, 0, 8));

        builder.setCustomView(layout);
        sheet[0] = builder.create();
        sheet[0].show();
    }

    private static View option(Context context, Theme.ResourcesProvider resourcesProvider, int icon,
                               CharSequence title, CharSequence info, View.OnClickListener listener) {
        final FrameLayout row = new FrameLayout(context);
        row.setBackground(Theme.getSelectorDrawable(false));
        row.setOnClickListener(listener);

        final ImageView iconView = new ImageView(context);
        iconView.setImageResource(icon);
        iconView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_dialogTextBlue2, resourcesProvider), PorterDuff.Mode.SRC_IN));
        row.addView(iconView, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL, 22, 0, 0, 0));

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        row.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL, 62, 10, 20, 10));

        final TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        titleView.setText(title);
        column.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView infoView = new TextView(context);
        infoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        infoView.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, resourcesProvider));
        infoView.setText(info);
        column.addView(infoView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        row.setMinimumHeight(dp(60));
        return row;
    }

    /** Обычный телеграмный выбор чата — но сразу с нужной манерой пересылки. */
    private static void pickChat(MessageObject track, boolean asMe) {
        final BaseFragment lastFragment = LaunchActivity.getLastFragment();
        if (lastFragment == null) {
            return;
        }
        final Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("canSelectTopics", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putBoolean("allowSwitchAccount", true);
        final DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            send(track, dids, asMe);
            fragment.finishFragment();
            return true;
        });
        lastFragment.presentFragment(picker);
    }

    private static void send(MessageObject track, ArrayList<MessagesStorage.TopicKey> dids, boolean asMe) {
        if (track == null || dids == null || dids.isEmpty()) {
            return;
        }
        final int account = UserConfig.selectedAccount;
        final ArrayList<MessageObject> messages = new ArrayList<>();
        messages.add(track);
        for (int a = 0; a < dids.size(); ++a) {
            final long did = dids.get(a).dialogId;
            SendMessagesHelper.getInstance(account).sendMessage(messages, did, asMe, false, true, 0, 0);
            if (PengramConfig.isTrackForwardCaption()) {
                final String caption = caption(track);
                if (!TextUtils.isEmpty(caption)) {
                    SendMessagesHelper.getInstance(account).sendMessage(
                            SendMessagesHelper.SendMessageParams.of(caption, did));
                }
            }
        }
        final BaseFragment lastFragment = LaunchActivity.getLastFragment();
        if (lastFragment != null) {
            AndroidUtilities.runOnUIThread(() -> BulletinFactory.of(lastFragment)
                    .createSimpleBulletin(R.raw.forward, getString(R.string.PengramTrackForwardDone)).show(), 150);
        }
    }

    /** подпись «🎧 Исполнитель — Название», если её включили в настройках */
    private static String caption(MessageObject track) {
        final String title = track.getMusicTitle(true);
        final String author = track.getMusicAuthor(true);
        if (TextUtils.isEmpty(title)) {
            return null;
        }
        if (TextUtils.isEmpty(author)) {
            return "🎧 " + title;
        }
        return "🎧 " + author + " — " + title;
    }

    /** Текущая манера пересылки человеческим языком — для строки настроек. */
    public static CharSequence modeName(int mode) {
        switch (mode) {
            case PengramConfig.TRACK_FORWARD_AS_ME: return getString(R.string.PengramTrackForwardAsMe);
            case PengramConfig.TRACK_FORWARD_WITH_AUTHOR: return getString(R.string.PengramTrackForwardWithAuthor);
            default: return getString(R.string.PengramTrackForwardAsk);
        }
    }

    /** Подпись под кнопкой пересылки — чтобы не гадать, что произойдёт. */
    public static CharSequence buttonHint() {
        return LocaleController.formatString("PengramTrackForwardHint", R.string.PengramTrackForwardHint, modeName(PengramConfig.getTrackForwardMode()));
    }
}
