#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""run413 — четыре задачи:
1. Служебное (keep-alive) уведомление Pengram получает единственный
   незаменяемый значок со звёздами — его нельзя выбрать в настройках,
   им нельзя перепутать с обычными уведомлениями.
2. Жидкое стекло: дефолты «под iPhone» (плотнее/ярче) и преломление
   включено по умолчанию (миграция lite_mode7, PRESET_MEDIUM/HIGH).
3. Пингвин в Настройки → Pengram: средний размер (был слишком большой).
4. Остров «трёх точек»: пункты получают гарантированный контрастный цвет
   от фактического фона — текст виден при любом разрешении темы.
"""
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
java = root / 'TMessagesProj/src/main/java/org/telegram'

wrapper = (java / 'ui/Components/PengramChatMenuWrapper.java').read_text()
service = (java / 'messenger/NotificationsService.java').read_text()
notif_icon = (java / 'messenger/PengramNotificationIcon.java').read_text()
notifs = (java / 'messenger/NotificationsController.java').read_text()
icon = (root / 'TMessagesProj/src/main/res/drawable/pengram_notify_keepalive.xml').read_text()
config = (java / 'messenger/PengramConfig.java').read_text()
lite = (java / 'messenger/LiteMode.java').read_text()
settings = (java / 'ui/PengramSettingsActivity.java').read_text()

# --- 4: остров — гарантированный контраст пунктов ---------------------------
# цвет каждого пункта (назад, пункты меню, быстрые действия) считается
# от фактического фона страницы
assert 'applyContrastColors(closeItem)' in wrapper  # run421: одна кнопка «Назад/закрыть»
assert 'applyContrastColors(item)' in wrapper
assert 'applyContrastColors(quick)' in wrapper
# run421: закрытие+пункты+быстрые = три вызова + определение
assert wrapper.count('applyContrastColors(') == 4  # определение + 3 вызова
assert 'Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, resourcesProvider)' in wrapper
assert 'luminance / 1000 < 140 ? 0xFFFFFFFF : 0xFF1C1C1E' in wrapper
assert 'item.setSubtextColor(fg)' in wrapper
assert 'item.setColors(fg, fg)' in wrapper
# фон страницы и текст считаются от одного ключа с одним провайдером
# run419: единственный источник — pageBackground(); контраст берёт оттуда же
assert wrapper.count('Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, resourcesProvider)') == 1
assert 'final int bg = pageBackground();' in wrapper

# --- 1: уникальный незаменяемый значок keep-alive ---------------------------
assert 'setSmallIcon(R.drawable.pengram_notify_keepalive)' in service
assert 'PengramNotificationIcon.drawable()' not in service
# значок не попал в список выбираемых (иначе его можно было бы перепутать)
assert 'pengram_notify_keepalive' not in notif_icon
assert 'COUNT = 5' in notif_icon
# обычная(ые) доставка по-прежнему использует выбираемый значок
assert notifs.count('PengramNotificationIcon.drawable()') == 2
# сама иконка: большая звезда + две искры, чисто белая
assert icon.count('<path') == 3
assert 'M19.6,2.5' in icon          # искра сверху справа
assert 'M5.2,16' in icon            # искра снизу слева
assert icon.count('#FFFFFFFF') == 3

# --- 2: жидкое стекло «под iPhone» и включённое по умолчанию ----------------
assert 'getIntCached(KEY_GLASS_OPACITY, 56)' in config      # был 42
assert 'getIntCached(KEY_GLASS_THICKNESS, 20)' in config    # был 16
assert 'getIntCached(KEY_GLASS_INTENSITY, 120)' in config   # был 92
assert 'getIntCached(KEY_GLASS_GLOW, 130)' in config        # был 100
# границы диапазонов не тронуты — настройки по-прежнему работают
assert 'Math.max(20, Math.min(90' in config
assert 'Math.max(4, Math.min(30' in config
# преломление по умолчанию: MEDIUM/HIGH пресеты содержат флаг
assert '); // 466527' in lite   # PRESET_MEDIUM + FLAG_LIQUID_GLASS
assert '); // 524287' in lite   # PRESET_HIGH + FLAG_LIQUID_GLASS
low = lite[lite.index('PRESET_LOW = ('):lite.index('// 198684')]
assert 'FLAG_LIQUID_GLASS' not in low  # слабые устройства не грузим
# обновляющиеся профили получают флаг один раз (lite_mode7)
assert 'preferences.contains("lite_mode7") && preferences.contains("lite_mode6")' in lite
assert 'getInt("lite_mode6", defaultValue) | FLAG_LIQUID_GLASS' in lite
assert 'value = preferences.getInt("lite_mode7", defaultValue);' in lite
assert 'putInt("lite_mode7", value)' in lite
# ползунки оповещают все стеклянные поверхности
assert 'org.telegram.ui.Components.PengramGlassSkin.notifyChanged();' in settings
assert 'org.telegram.ui.MainTabsActivity.refreshPengramGlass();' in settings

# --- 3: пингвин — средний размер --------------------------------------------
assert 'private static final int BASE_SIZE_DP = 150;' in settings  # был 224, потом 180, стал 150
assert '70, PengramConfig.getPenguinSize(), 120' in settings        # максимум 150→120
assert 'Math.max(70, Math.min(120, getIntCached("penguinSize", 100))' in config
assert 'Math.max(70, Math.min(120, percent))' in config             # сеттер в тех же границах
# формула и живое обновление шапки не тронуты
assert 'BASE_SIZE_DP * PengramConfig.getPenguinSize() / 100f' in settings
assert 'headerView.applySize();' in settings
# компактная шапка корня (72dp) не тронута
assert 'final int size = dp(72);' in settings
# старое сохранённое 150% ужимается до 120% → 180dp, а не 336dp
assert 'Math.min(150, getIntCached("penguinSize"' not in config

print('run413: ALL OK')
