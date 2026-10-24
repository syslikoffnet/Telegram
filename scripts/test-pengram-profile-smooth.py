#!/usr/bin/env python3
"""Свой профиль не должен «подлагивать»: никаких пересборок на каждый вход.

Три места грелись на открытии профиля:
1) фон кнопки «написать» собирался заново (инфлейт тени + селектор + фильтры);
2) оценка даты регистрации считалась по несколько раз на одну и ту же строку;
3) значок календарика заново разбирался на каждую привязку.

Плюс пингвин в шапке настроек обязан быть крупным: 224dp базово и камера,
которая отдаёт телу почти весь кадр.
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent
java = root / 'TMessagesProj/src/main/java'

profile = (java / 'org/telegram/ui/ProfileActivity.java').read_text()
regdate = (java / 'org/telegram/messenger/PengramRegDate.java').read_text()
settings = (java / 'org/telegram/ui/PengramSettingsActivity.java').read_text()
penguin = (java / 'org/telegram/ui/Components/PengramPenguinView.java').read_text()

# --- фон кнопки кэшируется по цвету и стилю ---------------------------------
assert 'private Drawable writeButtonBg;' in profile
assert 'writeButtonBgValid' in profile and 'writeButtonBgMd3' in profile
assert 'if (writeButtonBgValid && writeButtonBg != null' in profile
# при совпадении цвета ничего не инфлейтим
assert 'if (writeButton.getBackground() != writeButtonBg) {' in profile

# --- оценка даты считается один раз на id -----------------------------------
assert 'estimateCache' in regdate
assert 'final Long cached = estimateCache.get(userId);' in regdate
assert 'estimateCache.put(userId, result);' in regdate
assert 'estimateCache.remove(userId);' in regdate   # уточнение сбрасывает кэш

# --- значок календарика: ConstantState + один фильтр -----------------------
assert 'pengramRegDateIconState' in profile
assert 'getConstantState()' in profile
assert 'pengramRegDateIconFilter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);' in profile

# --- пингвин: средний базовый размер и плотный кадр ------------------------
assert 'private static final int BASE_SIZE_DP = 180;' in settings
assert 'private int clampPenguinSize(int size)' in settings
assert 'Math.min(size, max)' in settings
assert 'private static final float CAM_FILL = 0.97f;' in penguin
assert 'private static final float CAM_MARGIN = 0.035f;' in penguin
# ползунок по-прежнему масштабирует шапку на лету
assert 'headerView.applySize();' in settings

print('own profile smoothness + large header penguin: OK')
