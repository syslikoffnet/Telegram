#!/usr/bin/env python3
"""Приёмка: анимация входа в чат (тумблер, по умолчанию выкл) + защита от чёрного кадра стекла.

Тумблер обязан существовать, быть выключен по базе, быть выведен в настройках
и включать/выключать реальный код перехода (а не быть мёртвой строкой).
Первый кадр стекла не должен рисоваться, пока сцена не захвачена источником —
иначе на тёмных обоях видна чёрная вспышка при входе в чат.
Путь отправки остаётся байт-в-байт мастерским.
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE = 'build-master-20261023-1345'
SEND_FILES = [
    'TMessagesProj/src/main/java/org/telegram/ui/TextMessageEnterTransition.java',
    'TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java',
    'TMessagesProj/src/main/java/org/telegram/ui/MessageEnterTransitionContainer.java',
    'TMessagesProj/src/main/java/org/telegram/ui/recyclerview/ChatListItemAnimator.java',
    'TMessagesProj/src/main/java/org/telegram/messenger/MessageObject.java',
]


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def main():
    # A. Конфиг: ключ ровно с этим именем, по умолчанию false, есть сеттер
    cfg = read('TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java')
    assert 'KEY_CHAT_ENTER_ANIM = "chatEnterAnim"' in cfg, 'нет ключа chatEnterAnim'
    assert 'isChatEnterAnim() { return getBool(KEY_CHAT_ENTER_ANIM, false); }' in cfg, \
        'по умолчанию обязано быть false'
    assert 'setChatEnterAnim(boolean value)' in cfg, 'нет сеттера'

    # B. Настройки: строка есть, привязана к конфигу, id не занят
    st = read('TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java')
    assert 'R.string.PengramChatEnterAnim' in st, 'строка настроек не выведена'
    assert 'PengramConfig::isChatEnterAnim' in st, 'геттер не привязан'
    assert 'setChatEnterAnim(!PengramConfig.isChatEnterAnim())' in st, 'переключение не привязано'
    assert st.count('BTN_EXTRA_BASE + 19') == 1, 'id BTN_EXTRA_BASE + 19 должен быть ровно один'

    # C. Строки EN и RU на месте
    en = read('TMessagesProj/src/main/res/values/strings.xml')
    ru = read('TMessagesProj/src/main/res/values-ru/strings.xml')
    for name in ('PengramChatEnterAnim', 'PengramChatEnterAnimInfo'):
        assert f'name="{name}"' in en, f'EN: нет {name}'
        assert f'name="{name}"' in ru, f'RU: нет {name}'

    # D. Переход чата: progress-хук читает тумблер, сброс идёт в конце перехода
    chat = read('TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java')
    for token in ('onTransitionAnimationProgress', 'applyPengramEnterAnim',
                  'resetPengramEnterAnim', 'pengramEnterAnimApplied',
                  'PengramConfig.isChatEnterAnim()', 'EASE_OUT_QUINT'):
        assert token in chat, f'ChatActivity: нет {token}'
    end_pos = chat.find('public void onTransitionAnimationEnd')
    assert end_pos != -1, 'нет onTransitionAnimationEnd'
    assert 'resetPengramEnterAnim()' in chat[end_pos:end_pos + 800], \
        'сброс анимации должен вызываться в конце перехода'
    # тумблер выключен по базе → вне перехода флаг не должен оставаться
    assert 'if (pengramEnterAnimApplied) {' in chat[end_pos:end_pos + 800]

    # E. Первый кадр стекла: hasContent у источников + guard в drawable
    iface = read('TMessagesProj/src/main/java/org/telegram/ui/Components/blur3/source/BlurredBackgroundSource.java')
    assert 'default boolean hasContent()' in iface, 'нет hasContent в интерфейсе'
    src = read('TMessagesProj/src/main/java/org/telegram/ui/Components/blur3/source/BlurredBackgroundSourceRenderNode.java')
    assert 'renderNode.hasDisplayList()' in src and 'underSource.hasContent()' in src, \
        'RenderNode-источник не сообщает содержимое'
    wrap = read('TMessagesProj/src/main/java/org/telegram/ui/Components/blur3/source/BlurredBackgroundSourceWrapped.java')
    assert 'sourceInternal != null && sourceInternal.hasContent()' in wrap, 'Wrapped не делегирует'
    bmp = read('TMessagesProj/src/main/java/org/telegram/ui/Components/blur3/source/BlurredBackgroundSourceBitmap.java')
    assert 'bitmap != null && !bitmap.isRecycled()' in bmp, 'Bitmap не сообщает содержимое'
    draw = read('TMessagesProj/src/main/java/org/telegram/ui/Components/blur3/drawable/BlurredBackgroundDrawableRenderNode.java')
    assert 'Color.alpha(backgroundColor) != 255 && !source.hasContent()' in draw, \
        'нет guard первого кадра в drawable'
    # guard стоит после записи display list — содержимое подтянется без переигрыша
    guard_pos = draw.find('!source.hasContent()')
    record_pos = draw.find('updateDisplayList()')
    assert 0 < record_pos < guard_pos, 'guard должен идти после записи display list'

    # F. Поисковый индекс содержит новую строку
    idx = read('TMessagesProj/src/main/java/org/telegram/ui/PengramSearchIndex.java')
    assert 'R.string.PengramChatEnterAnim' in idx, 'поисковый индекс не перегенерирован'

    # G. Путь отправки байт-в-байт мастерский (регрессий «мигающего текста» нет)
    for path in SEND_FILES:
        res = subprocess.run(['git', 'diff', '--quiet', BASE, '--', path], cwd=ROOT)
        assert res.returncode == 0, f'{path} отличается от мастера'

    print('OK: тумблер входа в чат (по базе выкл) в дереве и обвязан; '
          'первый кадр стекла защищён; отправка = мастер')
    return 0


if __name__ == '__main__':
    sys.exit(main())
