package org.telegram.messenger;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

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
        if (text == null || text.length() == 0) {
            return text;
        }
        final StringBuilder sb = new StringBuilder(text.length());
        for (int a = 0; a < text.length(); ++a) {
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
        if (style == PengramConfig.SEND_STYLE_WIDE) {
            // полноширинные символы занимают ровно один char, смещения сущностей не ломаются
            return new Styled(toWide(text), entities);
        }
        final TLRPC.MessageEntity entity = createEntity(style);
        if (entity == null) {
            return null;
        }
        entity.offset = 0;
        entity.length = text.length();
        final ArrayList<TLRPC.MessageEntity> result = entities != null ? new ArrayList<>(entities) : new ArrayList<>();
        for (int a = 0; a < result.size(); ++a) {
            final TLRPC.MessageEntity e = result.get(a);
            if (e != null && e.getClass() == entity.getClass() && e.offset == 0 && e.length == entity.length) {
                return null; // уже оформлено вручную — второй раз не надо
            }
        }
        if (style == PengramConfig.SEND_STYLE_MONO) {
            // внутри моноширинного блока другие стили не живут
            result.clear();
        }
        result.add(entity);
        return new Styled(text, result);
    }
}
