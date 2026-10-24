#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""run414 — две задачи (видео + замечание):
1. Прокрутка-цилиндр: по умолчанию включён и «сочный» — слайдер
   «Сила цилиндра» (0…100, по умолчанию100 %) управляет радиусом валика
   (1400dp ←480dp) и ужатием краёв (5 % ←15 %). Модель движения не тронута.
2. Цитирование удалённых при включённом пункте «Цитировать удалённое
   в ответе»: цель ответа берётся и из replyToMsg, и из replyQuote.message;
   удалённость подтверждается флагом ИЛИ меткой архива; работают любые
   не-секретные чаты (раньше молча отсекались группы).
"""
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
java = root / 'TMessagesProj/src/main/java/org/telegram'

reply = (java / 'messenger/PengramDeletedReply.java').read_text()
config = (java / 'messenger/PengramConfig.java').read_text()
cyl = (java / 'ui/Components/PengramCylinderScroll.java').read_text()
settings = (java / 'ui/PengramSettingsActivity.java').read_text()
index = (java / 'ui/PengramSearchIndex.java').read_text()
ru = (root / 'TMessagesProj/src/main/res/values-ru/strings.xml').read_text()
en = (root / 'TMessagesProj/src/main/res/values/strings.xml').read_text()

# --- 2: удалённые цитируются при включённом пункте --------------------------
# цель ответа: replyToMsg либо replyQuote.message (decorate вызывается раньше,
# чем sendMessage подставляет quote.message в replyToMsg)
assert 'params.replyToMsg != null' in reply
assert 'params.replyQuote != null ? params.replyQuote.message : null' in reply
# удалённость: флаг объекта ИЛИ метка в архиве PengramHistory
assert 'target.pengramDeleted' in reply
assert 'PengramHistory.isMarkedDeleted(target.currentAccount, params.peer, target.getId())' in reply
# секрет-чаты — единственный оставшийся запрет; групп больше нет
assert 'DialogObject.isEncryptedDialog(params.peer)' in reply
assert 'DialogObject.isUserDialog' not in reply
# гейт настройки и снятие нативного ответа сохранены
assert 'PengramConfig.isDeletedReplyQuote()' in reply
assert 'params.replyToMsg = null;' in reply
assert 'params.replyQuote = null;' in reply
# строки больше не обещают «только личные»
assert 'поэтому в личных чатах Pengram' not in ru
assert 'so in private chats Pengram' not in en
assert 'поэтому Pengram подклеивает его текст' in ru

# --- 1: цилиндр по умолчанию «сочный» и включён -----------------------------
assert 'isCylinderScroll() { return getBool(KEY_CYLINDER_SCROLL, true); }' in config
assert 'KEY_CYLINDER_STRENGTH = "cylinderScrollStrength"' in config
assert 'getIntCached(KEY_CYLINDER_STRENGTH, 100)' in config
assert 'Math.max(0, Math.min(100, percent))' in config   # границы ползунка
assert 'RADIUS_TIGHT_DP = 480f' in cyl                   # максимум силы
assert 'RADIUS_GENTLE_DP = 1400f' in cyl                 # ноль силы — прежняя дуга
assert 'EDGE_SCALE_MIN = 0.05f' in cyl
assert 'EDGE_SCALE_MAX = 0.15f' in cyl
assert 'PengramConfig.getCylinderScrollStrength() / 100f' in cyl
assert 'radiusDp = RADIUS_GENTLE_DP + (RADIUS_TIGHT_DP - RADIUS_GENTLE_DP) * strength' in cyl
assert '1f - k * edgeScale' in cyl
# модель движения (то, что совпало с видео) не тронута
assert 'child.setRotationX(angle)' in cyl
assert 'CAMERA_BOOST = 4f' in cyl
assert 'PengramCylinderScroll.attach(chatListView, PengramConfig::isCylinderScroll)' in \
       (java / 'ui/ChatActivity.java').read_text()
# строка настройки: тумблер + новый ползунок
assert 'tgCheckInfo(BTN_EXTRA_BASE + 65' in settings
assert 'PengramCylinderStrength' in settings
assert 'PengramConfig.getCylinderScrollStrength(), 100' in settings
assert 'PengramConfig.setCylinderScrollStrength(value)' in settings
# EN/RU строка + индекс поиска
assert 'name="PengramCylinderStrength">Сила цилиндра' in ru
assert 'name="PengramCylinderStrength">Cylinder strength' in en
assert 'PengramCylinderStrength' in index
assert 'По умолчанию включён на максимуме' in ru
assert 'On by default at full strength' in en

print('run414: ALL OK')
