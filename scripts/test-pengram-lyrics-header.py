#!/usr/bin/env python3
"""Строка песни в шапке и текст трека в профиле.

Две жалобы: «анимка зависает» в списке чатов и «текст трека иногда пропадает»
в шапке профиля, хотя трек есть. Причины разные — кадр не запрашивался вовсе
(шапка), поиск текста не запускался для стилей без панели текста, а подпись
песни профиля не перерисовывалась и могла остаться без названия.
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent
java = root / 'TMessagesProj/src/main/java'

view = (java / 'org/telegram/ui/Components/PengramLyricsView.java').read_text()
context = (java / 'org/telegram/ui/Components/FragmentContextView.java').read_text()
sheet = (java / 'org/telegram/ui/Components/PengramMusicPlayerSheet.java').read_text()
pill = (java / 'org/telegram/ui/Components/ProfileMusicView.java').read_text()
profile = (java / 'org/telegram/ui/ProfileActivity.java').read_text()

# --- шапка: кадр запрашивается, пока играет, даже без плавности --------------
assert '|| (tickerMode && playing && !layouts.isEmpty())' in view
assert 'final float wave = 0.5f - 0.5f * (float) Math.cos(phase * Math.PI * 2);' in view
assert 'postInvalidateOnAnimation();' in view
assert 'if ((smooth || tickerMode) && durationMs > 0) {' in view
# экономия «подсветка не сдвинулась» в шапке не применяется
assert 'if (moving && playing && !previewMode && !tickerMode && !animated(anim)' in view

# --- шапка: текст догружается, если пришёл позже -----------------------------
assert 'private long pengramLyricsCheckTime;' in context
assert 'now - pengramLyricsCheckTime > 1500' in context
assert 'pengramLyricsCheckTime = now;' in context
assert 'if (PengramLyrics.has(PengramLyrics.keyFor(playing))) {' not in context
# и повтор при не изменившемся треке (включили показ текста уже после старта)
assert '} else if (pengramTicker != null && !pengramTickerShown && isMusic' in context

# --- список чатов: шапку ведёт собственный насос -----------------------------
# В списке чатов события плеера приходят редко, и строка выглядела замершей:
# раз в полсекунды подкидываем свежий прогресс и повторяем поиск текста, пока
# он не показан. Останавливается вместе с вьюхой.
assert 'private final Runnable pengramTickerPump = new Runnable()' in context
assert 'pengramTicker.setProgress(playing.audioProgress);' in context
assert 'AndroidUtilities.runOnUIThread(this, 500);' in context
assert 'private void pengramStartTickerPump()' in context
assert 'private void pengramStopTickerPump()' in context
assert 'pengramStartTickerPump();' in context
# поиск текста из насоса — не чаще раза в 2 секунды: иначе каждый тик
# поднимает токен и выбрасывает ответ, который ещё в пути
assert 'now - pengramPumpCheckedAt >= 2000' in context
assert 'pengramPumpCheckedAt = now;' in context
assert 'pengramStopTickerPump();' in context

# --- плеер: поиск текста не зависит от стиля с видимой панелью --------------
assert 'if (PengramConfig.playerStyleHasLyrics(style) || PengramConfig.isHeaderLyrics()) {' in sheet
assert sheet.count('loadLyrics(false);') >= 2   # updateTitle + toggleLyrics

# --- профиль: подпись песни перерисовывается и не теряет название -----------
assert 'invalidate();\n        // Песня профиля приходит позже самой шапки' not in pill  # комментарий выше вызова
assert 'Песня профиля приходит позже самой шапки' in pill
settext = pill[pill.index('public void setText(CharSequence author'):]
settext = settext[:settext.index('private ProfileActivity.AvatarImageView avatarView;')]
assert 'invalidate();' in settext
# у названия всегда остаётся место, даже если автор длинный
assert 'available * 0.55f' in pill
assert 'available - dp(48)' in pill
assert 'this.title.ellipsize(Math.max(0, available - this.author.getWidth()));' in pill
# позиционирование подписи не зависит от наличия onlineTextView[1]
assert 'if (musicView == null || onlineTextView[1] == null) {' not in profile
assert 'if (musicView == null || actionBar == null) {' in profile
# песня приезжает позже шапки: после её появления позицию пересчитываем сразу,
# иначе alpha подписи остаётся нулевой до первого скролла
assert 'musicView.setVisibility(hasMusic ? View.VISIBLE : View.GONE);' in profile
block = profile[profile.index('musicView.setVisibility(hasMusic ? View.VISIBLE : View.GONE);'):]
assert 'updateMusicPosition();' in block[:400]

print('lyrics header: frames keep coming, late text picked up, profile pill text safe: OK')
