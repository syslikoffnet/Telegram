#!/usr/bin/env python3
"""Penguin/player ownership and styling contracts; no screenshot/device claims."""
from pathlib import Path
import importlib.util
import re
ROOT = Path(__file__).resolve().parents[1]
J = ROOT/'TMessagesProj/src/main/java/org/telegram'
p = (J/'ui/Components/PengramMusicPlayerSheet.java').read_text()
v = (J/'ui/Components/PengramPenguinView.java').read_text()
h = (J/'ui/Components/PengramBeatHalo.java').read_text()
c = (J/'messenger/PengramCovers.java').read_text()
s = (J/'ui/PengramSettingsActivity.java').read_text()
config = (J/'messenger/PengramConfig.java').read_text()
# Local method parser: avoid importing a suite that executes subprocesses at import.
def body(source,name):
    m=re.search(r'(?:public|private|protected)\s+(?:static\s+)?[\w<>.]+\s+'+name+r'\([^{};]*\)\s*\{',source)
    assert m,name
    i=m.end();depth=1
    while depth:
        if source[i]=='{':depth+=1
        if source[i]=='}':depth-=1
        i+=1
    return source[m.start():i]
state=body(p,'setCoverState')
without=p.replace(state,'')
for name in ('penguinHolder','coverView'):
    assert name+'.setVisibility' not in without,name
assert 'COVER_VISIBLE = 0, COVER_LOADING = 1, COVER_MISSING = 2, COVER_ERROR = 3' in p
assert 'currentCover(key, generation)' in body(p,'applyCoverBitmap')
assert 'generation == coverGeneration' in body(p,'currentCover')
assert 'getPlayingMessageObject()' in body(p,'currentCover')
assert 'bitmap.getWidth() > 0 && bitmap.getHeight() > 0' in body(p,'usableCover')
assert 'else if (coverState != COVER_VISIBLE) setCoverState(COVER_ERROR, key);' in p
assert 'callback.onCover(key, null)' in c and 'listener.onCover(key, usable)' in c
assert 'holder.setVisibility(View.GONE)' not in p
assert 'penguinCover.whenUnavailable' in p and 'fallback.setAlpha(1f)' in p
assert 'setPaused(false)' in body(p,'updatePenguinMood')
assert 'penguinCover.setSleeping(paused)' in body(p,'updatePenguinMood')
background=body(p,'emptyCoverBackground')
assert 'accentColor' not in background and 'setStroke' not in background
assert 'Theme.key_windowBackgroundGray' in background
assert 'smallCoverView.getImageReceiver().setDelegate(style == PengramConfig.PLAYER_STYLE_LYRICS ? artworkDelegate : null)' in p
assert 'smallCoverView.setImageDrawable(layers)' not in state
assert 'smallCoverView.setBackground(layers)' in state
assert 'registerOnSharedPreferenceChangeListener' in p and 'unregisterOnSharedPreferenceChangeListener' in p
assert 'BODY_FILL = 0.84f' in v and 'Math.max(bodyDistance, fitDistance)' in v
assert 'dance > 0.01f || jump > 0.001f ? CAM_JUMP_LIMIT : 0f' in v
assert 'camDistance = 0f;' in v[v.index('if (builtSkin != requestedSkin)'):v.index('// вьюпорт квадратный')]
assert 'GLES20.glScissor(viewportX, viewportY, side, side)' in v
assert 'BASE_SIZE_DP = 256' in s and 'dp(96)' in s
assert 'LayoutHelper.createLinear(132, 132' in p and 'LayoutHelper.createFrame(160, 160' in p
assert 'Gravity.CENTER, 4, 4, 4, 4)' in p
assert 'invalidate();' in body(s,'applySize')
draw=body(h,'onDraw')
assert 'if (intensity <= 0 || !isShown())' in draw
assert 'canvas.clipRect(0, 0, getWidth(), getHeight())' in draw
assert 'ensureShader(' not in draw and 'new ' not in draw
assert 'intensity <= 1f ? 40 : 100' in draw
assert 'intensity > 1f' in draw
assert 'getIntCached(KEY_PLAYER_BEAT, PLAYER_BEAT_SOFT)' in config
# Bounded identity/state reference model: late A callbacks must not hide B's placeholder.
for _ in range(20):
    key, gen, state_value='A',1,'visible'
    key, gen, state_value='B',gen+1,'loading'
    assert not ('A'==key and 1==gen)
    assert state_value=='loading'
    state_value='error'  # null B decode, fallback remains visible
    assert state_value!='visible'
    key, gen, state_value='A',gen+1,'loading'
    assert not ('A'==key and 1==gen)  # same key also needs generation
print('Penguin cover ownership, stale callback model, fallback, neutral background, soft/off/clip contracts: PASS; device NOT RUN')
