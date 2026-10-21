#!/usr/bin/env python3
"""Шапка пингвина не растягивается ни при каком размере экрана.

Баг: custom-строка списка получала MATCH_PARENT по высоте, шапка расползалась
на весь список, а TextureView масштабировал кадр под свою форму — пингвин
выглядел сплющенным. Проверяем три независимых замка: строка обнимает
содержимое, кадр рисуется в квадратном вьюпорте, размер ограничен и по ширине,
и по высоте экрана.
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent
java = root / 'TMessagesProj/src/main/java'
settings = (java / 'org/telegram/ui/PengramSettingsActivity.java').read_text()
view = (java / 'org/telegram/ui/Components/PengramPenguinView.java').read_text()

# --- строка списка обнимает шапку -------------------------------------------
assert 'items.add(UItem.asFullyCustom(headerViewCompact));' in settings
assert 'items.add(UItem.asFullyCustom(headerView));' in settings
assert 'UItem.asCustom(headerView' not in settings          # больше не MATCH_PARENT
assert 'UItem.asCustom(headerViewCompact' not in settings

# --- корень: компактная шапка, а не вертикальная на пол-экрана ---------------
assert 'headerViewCompact = new PengramHeaderView(getContext(), true);' in settings
assert 'public PengramHeaderView(Context context, boolean compact)' in settings
assert 'private void buildCompact(Context context)' in settings
assert 'private void buildFull(Context context)' in settings
assert 'final int size = dp(88);' in settings                # пингвин, а не точка
assert 'row.setOrientation(HORIZONTAL);' in settings
assert 'column.addView(createTitle(context, Gravity.START)' in settings
assert 'column.addView(bubble' in settings
assert 'addView(createLinks(context, 28)' in settings
# ползунок размера по-прежнему относится к большому превью
assert 'if (compact) {' in settings

# --- рендер: квадратный вьюпорт и проекция 1:1 ------------------------------
assert 'final int side = Math.min(viewWidth, viewHeight);' in view
assert 'final int viewportX = (viewWidth - side) / 2;' in view
assert 'final int viewportY = (viewHeight - side) / 2;' in view
assert 'GLES20.glViewport(viewportX, viewportY, side, side);' in view
assert 'Matrix.perspectiveM(projection, 0, CAM_FOV_DEG, 1f, 1f, 14f);' in view
assert 'float aspect = surfaceHeight == 0' not in view       # старый расчёт убран

# --- размер ограничен обоими измерениями ------------------------------------
assert 'final int maxHeight = height > 0 ? height - dp(240) : size;' in settings
assert 'Math.min(maxWidth, maxHeight)' in settings

print('penguin header: wraps content, square viewport, size capped in both axes: OK')
