#!/usr/bin/env python3
"""Пингвин должен выглядеть живым, а не плоской наклейкой.

Плоская заливка `0.42 + 0.66 * diff` не давала материала: спина и пузо
отражали одинаково, силуэт сливался с фоном, под лапками не было тени, а
глаза стояли как нарисованные. Проверяем три вещи, которые читаются глазом:
объёмный свет с френелем, контактная тень под лапками и микродвижения взгляда.
"""

from pathlib import Path

root = Path(__file__).resolve().parent.parent
view = (root / 'TMessagesProj/src/main/java/org/telegram/ui/Components/PengramPenguinView.java').read_text()

# --- свет: полусфера, мягкий ключевой, тёплый отскок, френель ---------------
assert 'vec3 ambient = mix(ground, sky, hemi);' in view
assert 'float hemi = n.y * 0.5 + 0.5;' in view
assert 'float diff = clamp((dot(n, keyDir) + 0.30) / 1.30, 0.0, 1.0);' in view
assert 'float fill = clamp(dot(n, normalize(vec3(-0.78, -0.10, 0.52))) * 0.5 + 0.5, 0.0, 1.0);' in view
assert 'float fres = pow(1.0 - clamp(dot(n, viewDir), 0.0, 1.0), 3.0);' in view
assert 'color += sky * fres * 0.55;' in view

# --- разная матовость: чёрное глянцевее белого -----------------------------
assert 'float lum = dot(vColor, vec3(0.299, 0.587, 0.114));' in view
assert 'pow(clamp(dot(n, halfV), 0.0, 1.0), mix(52.0, 14.0, lum))' in view
assert 'GLES20.glUniform1f(uTime, time);' in view

# --- микроворс перьев вместо пластика --------------------------------------
assert 'float feather = sin(vPos.x * 46.0 + sin(vPos.z * 31.0)) * sin(vPos.y * 38.0 + 1.7);' in view
assert 'color *= 1.0 + feather * 0.014;' in view

# --- контактная тень -------------------------------------------------------
assert 'private static final String SHADOW_VERTEX_SHADER' in view
assert 'private static final String SHADOW_FRAGMENT_SHADER' in view
assert 'private void drawContactShadow(float idleBob)' in view
assert 'drawContactShadow(idleBob);' in view
assert 'final float spread = 1f + lift * 1.6f;' in view           # в прыжке шире
assert 'final float fade = 0.46f * (1f - Math.min(0.55f, lift * 1.4f));' in view
assert 'shadowMesh = new Mesh(shadow);' in view
# тень рисуется до тела, иначе она перекрывала бы лапки
assert view.index('drawContactShadow(idleBob);') < view.index('drawMesh(bodyMesh);')

# --- взгляд: редкие саккады, а не вечно распахнутые глаза ------------------
assert 'private float eyeMoveTimer = 1.2f;' in view
assert 'eyeTargetX = ((float) Math.random() - 0.5f) * 0.034f;' in view
assert 'eyeTargetY = ((float) Math.random() - 0.5f) * 0.020f;' in view
assert 'eyeX += (eyeTargetX - eyeX) * eyeK;' in view
assert 'Matrix.translateM(model, 0, eyeX, eyeY, 0);' in view

print('penguin alive: hemisphere light, fresnel, feather grain, contact shadow, eye saccades: OK')
