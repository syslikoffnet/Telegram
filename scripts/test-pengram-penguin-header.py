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
assert 'final int size = dp(96);' in settings                # пингвин, а не точка
# компактная шапка — та же центрированная композиция, что и большая:
# ни пингвин, ни ссылки не должны уезжать влево
# Готовые px нельзя передавать в LayoutHelper: он снова применяет density.
assert 'new LinearLayout.LayoutParams(size, size)' in settings
assert 'penguinParams.gravity = Gravity.CENTER_HORIZONTAL;' in settings
assert 'column.addView(createTitle' not in settings
assert 'addView(createLinks(context, 28), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,\n                    LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 0));' in settings
assert 'bubble = createBubble(context, Gravity.CENTER);' in settings
# ползунок размера по-прежнему относится к большому превью
assert 'if (compact) {' in settings

# --- шапка занимает всю ширину строки ---------------------------------------
# Строка списка отдаёт полностью кастомной вьюхе WRAP_CONTENT, поэтому шапка
# сжималась по содержимому и прилипала к левому краю: и пингвин, и «канал» с
# «создателем» уезжали влево. Ширина строки берётся целиком.
assert 'protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {' in settings
assert 'MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)' in settings
assert 'setGravity(Gravity.CENTER_HORIZONTAL);' in settings
assert 'links.setGravity(Gravity.CENTER_HORIZONTAL);' in settings

# --- рендер: квадратный вьюпорт и проекция 1:1 ------------------------------
assert 'final int side = Math.min(viewWidth, viewHeight);' in view
assert 'final int viewportX = (viewWidth - side) / 2;' in view
assert 'final int viewportY = (viewHeight - side) / 2;' in view
assert 'GLES20.glViewport(viewportX, viewportY, side, side);' in view
assert 'Matrix.perspectiveM(projection, 0, CAM_FOV_DEG, 1f, 1f, 14f);' in view
assert 'float aspect = surfaceHeight == 0' not in view       # старый расчёт убран

# --- размер ограничен обоими измерениями ------------------------------------
assert 'final int maxHeight = height > 0 ? Math.max(1, height - dp(240)) : size;' in settings
assert 'Math.min(maxWidth, maxHeight)' in settings

print('penguin header: full row width, centred content, square viewport, size capped: OK')
