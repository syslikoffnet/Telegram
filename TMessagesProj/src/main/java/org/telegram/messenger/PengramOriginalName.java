package org.telegram.messenger;

import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import org.telegram.tgnet.TLRPC;

/**
 * Pengram: второе имя человека в его профиле.
 *
 * Записанного в контакты человека Telegram везде показывает так, как записали
 * вы. Настоящее имя при этом никуда не девается — оно просто не на виду: оно
 * лежит в телефонной книге, в истории профиля Pengram или хотя бы в @нике.
 * Жест по имени в профиле показывает эту вторую версию и возвращает обратно.
 */
public final class PengramOriginalName {

    /** как переключать: ничем, нажатием, свайпом или и тем и другим */
    public static final String KEY_MODE = "originalName";
    public static final int MODE_OFF = 0;
    public static final int MODE_TAP = 1;
    public static final int MODE_SWIPE = 2;
    public static final int MODE_BOTH = 3;

    /** откуда взяли второе имя — чтобы честно подписать, что показываем */
    public static final int SOURCE_PHONEBOOK = 0;
    public static final int SOURCE_HISTORY = 1;
    public static final int SOURCE_USERNAME = 2;

    private PengramOriginalName() {
    }

    public static int mode() {
        return PengramConfig.getIntCached(KEY_MODE, MODE_TAP);
    }

    public static void setMode(int mode) {
        PengramConfig.setIntValue(KEY_MODE, mode);
    }

    public static boolean enabled() {
        return mode() != MODE_OFF;
    }

    public static boolean tapEnabled() {
        final int mode = mode();
        return mode == MODE_TAP || mode == MODE_BOTH;
    }

    public static boolean swipeEnabled() {
        final int mode = mode();
        return mode == MODE_SWIPE || mode == MODE_BOTH;
    }

    public static class Result {
        public final String name;
        public final int source;

        Result(String name, int source) {
            this.name = name;
            this.source = source;
        }
    }

    /**
     * Второе известное имя или null, если второго нет.
     *
     * Ищем по очереди: как человек записан в телефонной книге, каким его имя
     * видели раньше (история профилей Pengram) и, если больше нечего показать,
     * его @ник.
     */
    public static Result other(int account, TLRPC.User user) {
        if (user == null) {
            return null;
        }
        final String shown = clean(ContactsController.formatName(user.first_name, user.last_name));

        final String phonebook = clean(fromPhoneBook(account, user));
        if (!TextUtils.isEmpty(phonebook) && !phonebook.equalsIgnoreCase(shown)) {
            return new Result(phonebook, SOURCE_PHONEBOOK);
        }

        final String history = clean(fromHistory(user.id));
        if (!TextUtils.isEmpty(history) && !history.equalsIgnoreCase(shown)) {
            return new Result(history, SOURCE_HISTORY);
        }

        final String username = UserObject.getPublicUsername(user);
        if (!TextUtils.isEmpty(username)) {
            return new Result("@" + username, SOURCE_USERNAME);
        }
        return null;
    }

    private static String clean(String value) {
        return value == null ? null : value.trim();
    }

    /** имя из телефонной книги устройства: как человек записан у вас */
    private static String fromPhoneBook(int account, TLRPC.User user) {
        try {
            final ContactsController controller = ContactsController.getInstance(account);
            if (controller == null || controller.contactsBook == null) {
                return null;
            }
            for (ContactsController.Contact contact : controller.contactsBook.values()) {
                if (contact == null) {
                    continue;
                }
                boolean mine = contact.user != null && contact.user.id == user.id;
                if (!mine && !TextUtils.isEmpty(user.phone) && contact.shortPhones != null) {
                    for (String phone : contact.shortPhones) {
                        if (!TextUtils.isEmpty(phone) && user.phone.endsWith(phone)) {
                            mine = true;
                            break;
                        }
                    }
                }
                if (mine) {
                    return ContactsController.formatName(contact.first_name, contact.last_name);
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    /** самое раннее имя, которое Pengram успел увидеть до переименования */
    private static String fromHistory(long userId) {
        try {
            final java.util.ArrayList<PengramProfileHistory.Change> changes = PengramProfileHistory.getChanges(userId);
            // список идёт от свежих к старым — нам нужно самое первое имя
            for (int i = changes.size() - 1; i >= 0; i--) {
                final PengramProfileHistory.Change change = changes.get(i);
                if (change != null && !TextUtils.isEmpty(change.name)) {
                    return change.name;
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    public static int sourceTextRes(int source) {
        switch (source) {
            case SOURCE_PHONEBOOK:
                return R.string.PengramOriginalNamePhonebook;
            case SOURCE_HISTORY:
                return R.string.PengramOriginalNameHistory;
            default:
                return R.string.PengramOriginalNameUsername;
        }
    }

    // ------------------------------------------------------------ жест

    /**
     * Повесить жест на имя в профиле.
     *
     * Нажатие и свайп включаются раздельно, поэтому обработчик всегда
     * спрашивает настройку заново: переключили в настройках — заработало сразу,
     * без перезахода в профиль.
     */
    public static void attach(View view, Runnable onToggle) {
        if (view == null || onToggle == null) {
            return;
        }
        final int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
        final GestureDetector detector = new GestureDetector(view.getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return enabled();
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (!tapEnabled()) {
                    return false;
                }
                onToggle.run();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (!swipeEnabled() || e1 == null || e2 == null) {
                    return false;
                }
                final float dx = e2.getX() - e1.getX();
                if (Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(e2.getY() - e1.getY())) {
                    onToggle.run();
                    return true;
                }
                return false;
            }
        });
        view.setOnTouchListener((v, event) -> {
            if (!enabled()) {
                return false;
            }
            return detector.onTouchEvent(event);
        });
    }
}
