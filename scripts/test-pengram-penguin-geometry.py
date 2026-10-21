#!/usr/bin/env python3
"""Source contracts + independent numeric checks, not Android/GL acceptance."""
import math
import re
from pathlib import Path
root = Path(__file__).resolve().parent.parent
ui = root/'TMessagesProj/src/main/java/org/telegram/ui'
s = (ui/'PengramSettingsActivity.java').read_text()
v = (ui/'Components/PengramPenguinView.java').read_text()
assert 'private static final float CAM_FILL = 0.82f;' in v
assert 'contentBottom - CAM_MARGIN' in v
assert 'Math.min(CONTENT_BOTTOM,' in v
assert 'accessoryMesh.radiusXZ' in v
assert 'contentDepth + (fitHeight / CAM_FILL' in v
assert 'final int requestedSkin = skin;' in v
assert 'buildAccessory(requestedSkin);' in v
assert 'builtSkin = requestedSkin;' in v
assert 'camDistance = 0f;' in v[v.index('if (builtSkin != requestedSkin)'):v.index('// вьюпорт квадратный')]
assert 'for (int i = 1; i < array.length; i += 9)' in v
assert 'LayoutHelper.createFrame(penguinSize, penguinSize' not in s
assert 'new LinearLayout.LayoutParams(size, size)' in s
assert 'new LinearLayout.LayoutParams(penguinSize, penguinSize)' in s
assert 'dp(BASE_SIZE_DP * requestedPenguinPercent / 100f)' in s
assert 'updatePenguinSize(width);' in s
update = s[s.index('private void updatePenguinSize('):s.index('/** маленькая «таблетка»',s.index('private void updatePenguinSize('))]
assert not re.search(r'\bnew\b|getString\(|setLayoutParams\(|PengramConfig\.', update)
assert 'penguinContainer.forceLayout();' in update
assert 'whenUnavailable' in s and 'fallbackLogo.setAlpha(1f);' in s
assert 'pausedByScreen || pausedByWindow || pausedByViewport' in v
assert 'getGlobalVisibleRect(visibleRect)' in v
assert 'removeOnScrollChangedListener(scrollListener)' in v
assert 'previous = null;' in v
assert 'previous.join();' in v and 'previous.join(500)' not in v
assert 'if (ownsGL) releaseGLLocked();' in v
assert 'if (finished || !isAlive()) releaseSurface();' in v
assert 'surfaceReleased.compareAndSet(false, true)' in v
assert 'egl.eglSwapBuffers' in v and 'GL_LINK_STATUS' in v
# Density matrix: the container owns pixels exactly once, even after live resize.
cases = 0
for density in (1, 1.5, 2, 3, 4):
    dp = lambda x: math.ceil(x*density)
    for width, height in ((320,640),(640,320),(640,960),(240,300),(80,200)):
        for percent in (70,100,150):
            requested=dp(224*percent/100)
            maximum=min(max(1,dp(width)-dp(32)),max(1,dp(height)-dp(240)))
            actual=min(max(dp(96),requested),maximum)
            assert 0 < actual <= maximum
            # Compact is always 72dp, not density-squared 72dp.
            assert dp(72) <= 72*density+1
            cases+=1
# Analytic camera envelope only: no claim about transformed dance/flip geometry.
for top in (1.18,1.5,1.9,2.2):
    bottom=-1.11-.035
    high=top+.26+.035
    distance=.75+(high-bottom)/(.82*2*math.tan(math.radians(17)))
    for z in (0,.35,.75):
        fraction=(high-bottom)/(2*(distance-z)*math.tan(math.radians(17)))
        assert fraction <= .82+1e-10
print(f'penguin geometry: {cases} density/size cases + static camera envelopes PASS; animated/device acceptance NOT RUN')
