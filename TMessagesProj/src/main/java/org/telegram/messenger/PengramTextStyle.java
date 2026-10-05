package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pengram: автоматический стиль отправляемого текста.
 * Всё, что вы отправляете, оформляется выбранным стилем — как если бы вы
 * каждый раз вручную выделяли текст и выбирали «жирный», «курсив» и т.д.
 * Стиль «широкий» работает иначе: он заменяет символы на полноширинные.
 */
public class PengramTextStyle {

    /** название стиля для настроек */
    public static int getNameRes(int style) {
        switch (style) {
            case PengramConfig.SEND_STYLE_BOLD: return R.string.PengramSendStyleBold;
            case PengramConfig.SEND_STYLE_ITALIC: return R.string.PengramSendStyleItalic;
            case PengramConfig.SEND_STYLE_MONO: return R.string.PengramSendStyleMono;
            case PengramConfig.SEND_STYLE_STRIKE: return R.string.PengramSendStyleStrike;
            case PengramConfig.SEND_STYLE_UNDERLINE: return R.string.PengramSendStyleUnderline;
            case PengramConfig.SEND_STYLE_SPOILER: return R.string.PengramSendStyleSpoiler;
            case PengramConfig.SEND_STYLE_QUOTE: return R.string.PengramSendStyleQuote;
            case PengramConfig.SEND_STYLE_WIDE: return R.string.PengramSendStyleWide;
            default: return R.string.PengramSendStyleOff;
        }
    }

    public static final int[] ALL_STYLES = new int[] {
            PengramConfig.SEND_STYLE_OFF,
            PengramConfig.SEND_STYLE_BOLD,
            PengramConfig.SEND_STYLE_ITALIC,
            PengramConfig.SEND_STYLE_MONO,
            PengramConfig.SEND_STYLE_STRIKE,
            PengramConfig.SEND_STYLE_UNDERLINE,
            PengramConfig.SEND_STYLE_SPOILER,
            PengramConfig.SEND_STYLE_QUOTE,
            PengramConfig.SEND_STYLE_WIDE
    };

    /** как будет выглядеть пример текста с этим стилем (для превью в настройках) */
    public static CharSequence preview(int style, String sample) {
        if (style == PengramConfig.SEND_STYLE_WIDE) {
            return toWide(sample);
        }
        return sample;
    }

    /** полноширинные символы: "Привет 42" → "Ｐｒｉｖｅｔ ４２" (ASCII), остальное без изменений */
    public static String toWide(String text) {
        return toWide(text, null);
    }

    /** то же самое, но куски из {@code skip} (ссылки, ники) остаются как есть */
    public static String toWide(String text, ArrayList<int[]> skip) {
        if (text == null || text.length() == 0) {
            return text;
        }
        final StringBuilder sb = new StringBuilder(text.length());
        for (int a = 0; a < text.length(); ++a) {
            if (inRanges(skip, a)) {
                sb.append(text.charAt(a));
                continue;
            }
            final char c = text.charAt(a);
            if (c == ' ') {
                sb.append('\u3000');                      // полноширинный пробел
            } else if (c > 0x20 && c < 0x7F) {
                sb.append((char) (c - 0x20 + 0xFF00));    // полноширинные формы ASCII
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static TLRPC.MessageEntity createEntity(int style) {
        switch (style) {
            case PengramConfig.SEND_STYLE_BOLD: return new TLRPC.TL_messageEntityBold();
            case PengramConfig.SEND_STYLE_ITALIC: return new TLRPC.TL_messageEntityItalic();
            case PengramConfig.SEND_STYLE_MONO: return new TLRPC.TL_messageEntityCode();
            case PengramConfig.SEND_STYLE_STRIKE: return new TLRPC.TL_messageEntityStrike();
            case PengramConfig.SEND_STYLE_UNDERLINE: return new TLRPC.TL_messageEntityUnderline();
            case PengramConfig.SEND_STYLE_SPOILER: return new TLRPC.TL_messageEntitySpoiler();
            case PengramConfig.SEND_STYLE_QUOTE: return new TLRPC.TL_messageEntityBlockquote();
            default: return null;
        }
    }

    /** результат применения стиля */
    public static class Styled {
        public final String text;
        public final ArrayList<TLRPC.MessageEntity> entities;

        Styled(String text, ArrayList<TLRPC.MessageEntity> entities) {
            this.text = text;
            this.entities = entities;
        }
    }

    /**
     * Применяет выбранный стиль ко всему тексту сообщения.
     * @return null, если ничего менять не надо
     */
    /**
     * Куски текста, которые Telegram сам превращает в ссылки: сами ссылки,
     * @упоминания, #хештеги, $кештеги, команды ботам и почта.
     * Оформлять их выбранным стилем нельзя: жирная или моноширинная ссылка и
     * выглядит чужеродно, и до перезахода в чат показывается не как ссылка.
     */
    private static final Pattern SPECIAL = Pattern.compile(
            "(@[A-Za-z0-9_]{2,32})" +
            "|([#$][\\w\u0400-\u04FF]{1,64})" +
            "|(/[A-Za-z0-9_]{1,64})" +
            "|([A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,})" +
            "|(\\+[0-9][0-9\\-()\\s]{6,20})");

    /** границы ссылок, ников и прочего, что трогать не надо */
    public static ArrayList<int[]> protectedRanges(String text, ArrayList<TLRPC.MessageEntity> entities) {
        final ArrayList<int[]> ranges = new ArrayList<>();
        if (text == null || text.length() == 0) {
            return ranges;
        }
        try {
            if (AndroidUtilities.WEB_URL != null) {
                final Matcher m = AndroidUtilities.WEB_URL.matcher(text);
                while (m.find()) {
                    ranges.add(new int[]{m.start(), m.end()});
                }
            }
            final Matcher m = SPECIAL.matcher(text);
            while (m.find()) {
                ranges.add(new int[]{m.start(), m.end()});
            }
        } catch (Throwable ignore) {
        }
        // то, что пользователь уже оформил вручную ссылкой, тоже не трогаем
        if (entities != null) {
            for (TLRPC.MessageEntity e : entities) {
                if (e instanceof TLRPC.TL_messageEntityTextUrl || e instanceof TLRPC.TL_messageEntityUrl
                        || e instanceof TLRPC.TL_messageEntityMention || e instanceof TLRPC.TL_messageEntityMentionName
                        || e instanceof TLRPC.TL_messageEntityEmail || e instanceof TLRPC.TL_messageEntityPhone
                        || e instanceof TLRPC.TL_messageEntityBotCommand || e instanceof TLRPC.TL_messageEntityHashtag
                        || e instanceof TLRPC.TL_messageEntityCashtag || e instanceof TLRPC.TL_messageEntityCustomEmoji) {
                    ranges.add(new int[]{e.offset, e.offset + e.length});
                }
            }
        }
        return merge(ranges, text.length());
    }

    /** объединяем пересекающиеся куски и приводим их к границам текста */
    private static ArrayList<int[]> merge(ArrayList<int[]> ranges, int length) {
        final ArrayList<int[]> result = new ArrayList<>();
        if (ranges.isEmpty()) {
            return result;
        }
        Collections.sort(ranges, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return a[0] - b[0];
            }
        });
        for (int[] range : ranges) {
            final int start = Math.max(0, Math.min(length, range[0]));
            final int end = Math.max(0, Math.min(length, range[1]));
            if (end <= start) {
                continue;
            }
            if (!result.isEmpty() && start <= result.get(result.size() - 1)[1]) {
                final int[] last = result.get(result.size() - 1);
                last[1] = Math.max(last[1], end);
            } else {
                result.add(new int[]{start, end});
            }
        }
        return result;
    }

    private static boolean inRanges(ArrayList<int[]> ranges, int index) {
        if (ranges == null) {
            return false;
        }
        for (int a = 0; a < ranges.size(); ++a) {
            if (index >= ranges.get(a)[0] && index < ranges.get(a)[1]) {
                return true;
            }
        }
        return false;
    }

    public static Styled apply(String text, ArrayList<TLRPC.MessageEntity> entities) {
        final int style = PengramConfig.getSendTextStyle();
        if (style == PengramConfig.SEND_STYLE_OFF || text == null) {
            return null;
        }
        final String trimmed = text.trim();
        if (trimmed.length() == 0) {
            return null;
        }
        // команды ботам и «/start» оставляем как есть — иначе бот их не поймёт
        if (trimmed.charAt(0) == '/') {
            return null;
        }
        // ссылки, ники, хештеги и команды оставляем нетронутыми
        final ArrayList<int[]> skip = protectedRanges(text, entities);

        if (style == PengramConfig.SEND_STYLE_WIDE) {
            // полноширинные символы занимают ровно один char, смещения сущностей не ломаются
            return new Styled(toWide(text, skip), entities);
        }
        final ArrayList<TLRPC.MessageEntity> result = entities != null ? new ArrayList<>(entities) : new ArrayList<>();
        // цитата — рамка вокруг всего сообщения, дробить её на куски бессмысленно
        final ArrayList<int[]> pieces = style == PengramConfig.SEND_STYLE_QUOTE
                ? single(0, text.length())
                : gaps(skip, text.length());
        if (pieces.isEmpty()) {
            return null;
        }
        final Class<?> type = createEntity(style) == null ? null : createEntity(style).getClass();
        if (type == null) {
            return null;
        }
        boolean added = false;
        for (int[] piece : pieces) {
            // пробелы по краям в оформление не берём — иначе жирный «хвост» перед ссылкой
            while (piece[0] < piece[1] && Character.isWhitespace(text.charAt(piece[0]))) {
                piece[0]++;
            }
            while (piece[1] > piece[0] && Character.isWhitespace(text.charAt(piece[1] - 1))) {
                piece[1]--;
            }
            if (piece[1] <= piece[0]) {
                continue;
            }
            boolean exists = false;
            for (int a = 0; a < result.size(); ++a) {
                final TLRPC.MessageEntity e = result.get(a);
                if (e != null && e.getClass() == type && e.offset == piece[0] && e.length == piece[1] - piece[0]) {
                    exists = true; // уже оформлено вручную — второй раз не надо
                    break;
                }
            }
            if (exists) {
                continue;
            }
            if (style == PengramConfig.SEND_STYLE_MONO && !added) {
                // внутри моноширинного блока другие стили не живут
                result.clear();
            }
            final TLRPC.MessageEntity entity = createEntity(style);
            entity.offset = piece[0];
            entity.length = piece[1] - piece[0];
            result.add(entity);
            added = true;
        }
        if (!added) {
            return null;
        }
        return new Styled(text, result);
    }

    private static ArrayList<int[]> single(int start, int end) {
        final ArrayList<int[]> list = new ArrayList<>(1);
        list.add(new int[]{start, end});
        return list;
    }

    /** куски текста между защищёнными участками */
    private static ArrayList<int[]> gaps(ArrayList<int[]> skip, int length) {
        final ArrayList<int[]> list = new ArrayList<>();
        int from = 0;
        for (int a = 0; a < skip.size(); ++a) {
            final int[] range = skip.get(a);
            if (range[0] > from) {
                list.add(new int[]{from, range[0]});
            }
            from = Math.max(from, range[1]);
        }
        if (from < length) {
            list.add(new int[]{from, length});
        }
        return list;
    }
}
