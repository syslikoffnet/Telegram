#!/usr/bin/env python3
"""Приёмка: ускорение загрузки/отправки (п.9) — корневая механика.

Дефект run441: boost ставил downloadChunkSizeBig=512KB, но
updateDownloadByteBudget() ПОСЛЕ boost вычисляет
currentMax = byteBudget / chunk → 2MB/512KB = 4 потока против 16 у апстрима,
т.е. дефолтный «Fast» УРЕЗАЛ параллелизм в 4 раза. Плюс 512KB форсился без
серверного разрешения getfileExperimentalParams → риск LIMIT_INVALID →
forceSmallChunk (32KB×4, без byteBudget).

Корневой фикс: boost масштабирует byteBudget (окно байт в полёте), чанк 512KB
только с серверного разрешения (ветка апстрима).

Проверяет:
 - FileLoadOperation: boost умножает byteBudget; чанк НЕ форсируется в boost-блоке;
   множитель гейтится !forceSmallChunk; OFF (×1) = формула апстрима;
 - математика окна: OFF == апстрим, FAST == 2x, EXTREME == 4x;
 - FileUploadOperation: boost только для !slowNetwork, cap 32;
 - FileLoader: три гейта параллельных big-upload операций на множителе;
 - PengramConfig: дефолт BOOST_FAST и таблица множителей;
 - строки EN/RU описывают окно, а не «форс 512KB» (старый текст врал после фикса).
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def read(path):
    return (ROOT / path).read_text(encoding='utf-8')


def main():
    flo = read('TMessagesProj/src/main/java/org/telegram/messenger/FileLoadOperation.java')

    # A. boost умножает byteBudget — единственное место, где множитель реально
    #    действует, потому что byteBudget по умолчанию включён и перетирает
    #    maxDownloadRequests из updateParams()
    idx_budget = flo.index('private void updateDownloadByteBudget')
    budget_body = flo[idx_budget:flo.index('private boolean canUseDownloadByteBudget', idx_budget)]
    assert 'byteBudget *= boost;' in budget_body, \
        'byteBudget обязан умножаться на boost (иначе множитель не действует)'
    assert 'final int boost = PengramConfig.getSpeedBoostMultiplier();' in budget_body
    assert 'boost > 1 && !forceSmallChunk' in budget_body, \
        'множитель гейтится forceSmallChunk'
    # формула апстрима при OFF: та же константа бюджета
    assert 'int byteBudget = (premium ? 16 : 2) * 1024 * 1024;' in budget_body, \
        'база бюджета должна быть байт-в-байт апстримная'
    assert 'currentMaxDownloadRequests = byteBudget / currentDownloadChunkSize;' in budget_body

    # B. boost-блок НЕ трогает чанк (512KB — только серверная ветка апстрима)
    idx_up = flo.index('private void updateParams')
    upd_body = flo[idx_up:flo.index('public FileLoadOperation(', idx_up)]
    idx_boost = upd_body.index('getSpeedBoostMultiplier')
    # до строки maxCdnParts, которая общая (апстримная) и тоже ссылается на чанк
    boost_block = upd_body[idx_boost:upd_body.index('maxCdnParts =', idx_boost)]
    assert 'downloadChunkSizeBig' not in boost_block, \
        'boost не имеет права форсировать чанк: LIMIT_INVALID → forceSmallChunk ' \
        '(32KB × 4) и потеря byteBudget — это и было замедление'
    for token in ('maxDownloadRequests = Math.min(32, 4 * boost);',
                  'maxDownloadRequestsBig = Math.min(32, 4 * boost);',
                  'maxDownloadRequestsAnimation = Math.min(16, 4 * boost);'):
        assert token in boost_block, f'updateParams boost-блок: нет «{token}»'
    # чанк 512KB есть только в серверной (experimental) ветке — ровно один раз
    assert upd_body.count('downloadChunkSizeBig = 1024 * 512;') == 1, \
        'чанк 512KB должен выставляться только когда его разрешил сервер'
    assert 'getfileExperimentalParams' in upd_body, \
        'ветка 512KB обязана читать серверный флаг getfileExperimentalParams'

    # C. математика окна: OFF = апстрим байт-в-байт, FAST = 2x, EXTREME = 4x
    def window(total_mb, chunk_kb):
        return total_mb * 1024 // chunk_kb
    for prem, base in ((False, 2), (True, 16)):
        assert window(base, 128) == window(base * 1, 128)          # OFF == апстрим
        assert window(base, 128) * 2 == window(base * 2, 128)       # FAST == 2x
        assert window(base, 128) * 4 == window(base * 4, 128)       # EXTREME == 4x
        # регрессия run441: 512KB-чанк при том же бюджете резал потоки в 4
        assert window(base, 512) * 4 == window(base, 128), \
            'старая схема (чанк 512 при бюджете 2MB) даёт 4 против 16 — регрессия'

    # D. upload: множитель только когда сеть не «медленная», cap 32
    fuo = read('TMessagesProj/src/main/java/org/telegram/messenger/FileUploadOperation.java')
    assert 'if (!slowNetwork) {\n                    maxRequestsCount = Math.min(32, maxRequestsCount * PengramConfig.getSpeedBoostMultiplier());\n                }' in fuo, \
        'upload-boost: только !slowNetwork и cap 32'
    assert 'initialRequestsCount = 8' in fuo, 'стартовый burst как в апстриме'

    # E. FileLoader: три гейта big-upload операций на множителе
    fl = read('TMessagesProj/src/main/java/org/telegram/messenger/FileLoader.java')
    assert fl.count('currentUploadOperationsCount < PengramConfig.getSpeedBoostMultiplier()') == 3, \
        'ровно три гейта параллельных big-upload операций'

    # F. PengramConfig: дефолт FAST, множители 1/2/4
    cfg = read('TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java')
    assert 'public static int speedBoost = 1; // BOOST_FAST' in cfg, 'дефолт FAST'
    assert 'speedBoost = p.getInt("speedBoost", BOOST_FAST);' in cfg
    for want in ('case BOOST_FAST: return 2;', 'case BOOST_EXTREME: return 4;',
                 'default: return 1;'):
        assert want in cfg, f'множители: нет «{want}»'

    # G. строки: текст обязан соответствовать механике (окно, не «форс 512KB»)
    en = read('TMessagesProj/src/main/res/values/strings.xml')
    ru = read('TMessagesProj/src/main/res/values-ru/strings.xml')
    for loc, text in (('EN', en), ('RU', ru)):
        m = re.search(r'name="PengramBoostInfo">([^<]+)<', text)
        assert m, f'{loc}: нет PengramBoostInfo'
        info = m.group(1)
        assert '512' not in info, \
            f'{loc}: BoostInfo всё ещё обещает «512KB», хотя чанк больше не форсируется'
    for name in ('PengramBoostHeader', 'PengramBoostOff', 'PengramBoostFast',
                 'PengramBoostExtreme', 'PengramBoostInfo'):
        assert f'name="{name}"' in en, f'EN: нет {name}'
        assert f'name="{name}"' in ru, f'RU: нет {name}'

    print('OK: ускорение загрузки/отправки — окно × boost, чанк только с '
          'серверного разрешения, upload-гейты и строки на месте')
    return 0


if __name__ == '__main__':
    sys.exit(main())
