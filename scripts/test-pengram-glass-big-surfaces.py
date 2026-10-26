#!/usr/bin/env python3
"""Приёмка: жидкое стекло и MD3 в больших местах (после полного отката к мастеру).

Проверяет, что обвязка стекла действительно в дереве, настройки выведены,
а путь отправки остался байт-в-байт мастерским (регрессий «мигающего текста»).
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASE = 'build-master-20261023-1345'
SEND_FILES = [
    'TMessagesProj/src/main/java/org/telegram/ui/TextMessageEnterTransition.java',
    'TMessagesProj/src/main/java/org/telegram/ui/MessageEnterTransitionContainer.java',
    'TMessagesProj/src/main/java/org/telegram/ui/recyclerview/ChatListItemAnimator.java',
]
# ChatMessageCell и MessageObject обновлены апстримом13.1.1: байт-паритет с
# мастер-тегом заменён маркерной контрактурой — золотые патчи Pengram и новые
# строки13.1.1 обязаны присутствовать в файле (любая потеря при будущих мержах = фейл).
SEND_MARKER_FILES = {
    'TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java':
        'scripts/testdata/send_gold_chat_message_cell.txt',
    'TMessagesProj/src/main/java/org/telegram/messenger/MessageObject.java':
        'scripts/testdata/send_gold_message_object.txt',
}


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def main():
    # A. Классы стекла восстановлены и содержат ключевые механизмы
    skin = read('TMessagesProj/src/main/java/org/telegram/ui/Components/PengramGlassSkin.java')
    for token in ('SURFACE_TAB_BAR', 'SURFACE_INPUT', 'SURFACE_HEADER',
                  'notifyChanged', 'signature(', 'applyLens', 'refractionAllowed'):
        assert token in skin, f'PengramGlassSkin: нет {token}'
    surface = read('TMessagesProj/src/main/java/org/telegram/ui/Components/PengramSurfaceGlass.java')
    for token in ('PengramGlassSheen', 'BlurredBackgroundDrawable', 'onPreDraw', 'Recorder'):
        assert token in surface, f'PengramSurfaceGlass: нет {token}'
    sheen = read('TMessagesProj/src/main/java/org/telegram/ui/Components/PengramGlassSheen.java')
    assert 'LinearGradient' in sheen and 'isGlassSheenEnabled' in sheen

    # B. Конфиг: четыре ключа, сеттеры оповещают слушателей
    cfg = read('TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java')
    for key in ('KEY_SETTINGS_SURFACE_GLASS', 'KEY_INPUT_GLASS', 'KEY_HEADER_GLASS', 'KEY_CHAT_HEADER_GLASS'):
        assert key in cfg, f'PengramConfig: нет {key}'
    assert cfg.count('PengramGlassSkin.notifyChanged()') >= 4, 'сеттеры стекла не шлют notifyChanged'

    # C. Шапка любого экрана (ActionBar): линза, блик, корректное выключение
    ab = read('TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ActionBar.java')
    for token in ('applyPengramGlassLens', 'clearGlass', 'pengramSheen.draw'):
        assert token in ab, f'ActionBar: нет {token}'

    # D. Чат: шапка чата и панель ввода пересоздаются по сигнатуре
    chat = read('TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java')
    for token in ('refreshPengramSurfaces', 'resetPengramSurfaceSignatures',
                  'PengramGlassSkin.register', 'PengramGlassSkin.unregister',
                  'isChatHeaderGlass', 'isInputGlass'):
        assert token in chat, f'ChatActivity: нет {token}'

    # E. Список чатов: шапка и верхняя панель
    dlg = read('TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java')
    for token in ('refreshPengramHeaderGlass', 'isChatListHeaderGlass',
                  'PengramGlassSkin.register', 'PengramGlassSkin.unregister'):
        assert token in dlg, f'DialogsActivity: нет {token}'

    # F. Панель ввода чата: sheen и переключаемое стекло
    cic = read('TMessagesProj/src/main/java/org/telegram/ui/Components/chat/ChatInputViewsContainer.java')
    for token in ('setPengramGlass', 'pengramSheen.draw', 'drawFallback'):
        assert token in cic, f'ChatInputViewsContainer: нет {token}'
    tp = read('TMessagesProj/src/main/java/org/telegram/ui/Components/DialogsActivityTopPanelLayout.java')
    assert 'setPengramGlass' in tp and 'pengramSheen.draw' in tp

    # G. MD3: живое обновление и тональные шапки шторок
    md3 = read('TMessagesProj/src/main/java/org/telegram/messenger/PengramMD3.java')
    for token in ('notifyChanged', 'styleSheetHeader', 'watch('):
        assert token in md3, f'PengramMD3: нет {token}'
    assert 'styleSheetHeader(' in read(
        'TMessagesProj/src/main/java/org/telegram/ui/Components/PengramChatMusicSheet.java')

    # H. Настройки: три рабочих тумблера; строка поверхности — только после проводки
    st = read('TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java')
    for key in ('KEY_INPUT_GLASS', 'KEY_HEADER_GLASS', 'KEY_CHAT_HEADER_GLASS'):
        assert f'PengramConfig.{key}' in st, f'Настройки: нет строки {key}'
    assert 'KEY_SETTINGS_SURFACE_GLASS, false' not in st, (
        'строка настройки.surfaceGlass выведена без проводки (плеер/меню) — мёртвый тумблер')
    for token in ('PengramGlassSkin.notifyChanged', 'PengramMD3.notifyChanged', 'PengramGlassSharedInfo'):
        assert token in st, f'Настройки: нет {token}'

    # I. Строки есть и в en, и в ru
    en = read('TMessagesProj/src/main/res/values/strings.xml')
    ru = read('TMessagesProj/src/main/res/values-ru/strings.xml')
    for key in ('PengramInputGlass', 'PengramHeaderGlass', 'PengramChatHeaderGlass', 'PengramGlassSharedInfo'):
        assert f'name="{key}"' in en, f'en strings: нет {key}'
        assert f'name="{key}"' in ru, f'ru strings: нет {key}'

    # J. Путь отправки не тронут: байт-в-байт мастерский
    for path in SEND_FILES:
        res = subprocess.run(['git', 'diff', '--quiet', BASE, '--', path], cwd=ROOT)
        assert res.returncode == 0, f'файл отправки отличается от мастера: {path}'
    for path, mf in SEND_MARKER_FILES.items():
        text = (ROOT / path).read_text(encoding='utf-8')
        missing = [l for l in (ROOT / mf).read_text(encoding='utf-8').splitlines()
                   if l.strip() and not l.startswith('#') and l not in text]
        assert not missing, f'{path}: потеряны маркеры {mf}: {missing[:3]}'

    print('Pengram glass/MD3 big-surface wiring: OK')


if __name__ == '__main__':
    try:
        main()
    except AssertionError as exc:
        print(f'FAIL: {exc}')
        sys.exit(1)
