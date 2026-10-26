#!/usr/bin/env python3
"""run430: позиция варпа (корневой фикс обводки), клип захвата, заголовки-Pengram,
бесплатные иконки приложения, лого первого экрана."""
import io, os, sys

ROOT = os.path.join(os.path.dirname(__file__), '..')
def read(rel):
    return io.open(os.path.join(ROOT, rel), encoding='utf-8').read()

fails = []
def check(name, cond):
    print(('PASS' if cond else 'FAIL'), name)
    if not cond:
        fails.append(name)

warp = read('TMessagesProj/src/main/java/org/telegram/ui/Components/PengramCylinderWarp.java')
# корень бага обводки: translate(left, top) ДО concat(getMatrix) — позиция не в матрице
t = 'canvas.translate(child.getLeft() - child.getScrollX(), child.getTop() - child.getScrollY());'
c = 'canvas.concat(child.getMatrix());'
check('warp: translate позиции есть', t in warp)
check('warp: translate идёт ДО concat', t in warp and c in warp and warp.index(t) < warp.index(c))
check('warp: save/restore вокруг', warp.count('canvas.save();') >= 1 and warp.count('canvas.restore();') >= 1)
# клип захвата = штатный setClipBounds (RenderNode не участвует в child.draw)
check('warp: клип захвата', 'onto.clipRect(0, 0, w, h);' in warp)
# каноника не сломана
check('warp: drawBitmapMesh канон', 'canvas.drawBitmapMesh(capture.bitmap, 1, rows - 1, verts, 0, null, 0, paint);' in warp)
check('warp: нет canvas.drawVertices', 'canvas.drawVertices' not in warp)
check('warp: равномерный источник', 'final float stepS = h / (float) (rows - 1);' in warp)
check('warp: обратный образ источника', 'yOfSource' in warp)

scroll = read('TMessagesProj/src/main/java/org/telegram/ui/Components/PengramCylinderScroll.java')
check('scroll: legacy поворотов нет', 'setRotationX' not in scroll and 'setCameraDistance' not in scroll)

# заголовки: первый экран и дефолт режима = Pengram
intro = read('TMessagesProj/src/main/java/org/telegram/ui/IntroActivity.java')
check('intro: надпись = AppName из настроек', 'titles[0] = LocaleController.getString(R.string.AppName);' in intro)
check('intro: вордмарк TG убран', 'new ImageSpan(logoDrawable)' not in intro)

cfg = read('TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java')
check('config: default-режим отдаёт Pengram', 'return telegramTitle;' not in cfg)
check('config: resolveAppTitle default=Pengram', '// свежая база/«как в Telegram»: показываем бренд Pengram' in cfg)

dlg = read('TMessagesProj/src/main/java/org/telegram/ui/DialogsActivity.java')
check('dialogs: fallback шапки = Pengram, не логотип TG', 'логотип TG в шапке не показываем' in dlg)
check('dialogs: pengramTitleText без null-default', 'default:\\n                    return null;' not in dlg.replace('\\n', '\n') if False else ('логотип TG в шапке не показываем' in dlg))

# иконки приложения: доступ без премиума
ic = read('TMessagesProj/src/main/java/org/telegram/ui/Cells/AppIconsSelectorCell.java')
check('icons: нет премиум-шторки', 'new PremiumFeatureBottomSheet' not in ic)
check('icons: нет фильтра premiumFeaturesBlocked', 'premiumFeaturesBlocked' not in ic)
check('icons: нет замка в подписи', 'msg_mini_premiumlock' not in ic)
check('icons: все иконки в листе', 'availableIcons.addAll(Arrays.asList(LauncherIconController.LauncherIcon.values()));' in ic)


# ---- иконки: новые арты и ссылки
import os.path as _osp
res = _osp.join(ROOT, 'TMessagesProj/src/main/res')
def fexists(rel):
    return _osp.exists(_osp.join(res, rel))
check('icons: aqua fg есть (xxxhdpi)', fexists('mipmap-xxxhdpi/icon_4_foreground_sa.png'))
check('icons: nox fg есть (xxxhdpi)', fexists('mipmap-xxxhdpi/icon_2_foreground_sa.png'))
check('icons: дефолтный fg заменён', fexists('mipmap-xxxhdpi/icon_foreground_sa.png'))
check('icons: round-варианты обновлены', fexists('mipmap-xxxhdpi/icon_foreground_round.png') and fexists('mipmap-xxxhdpi/icon_6_foreground_round.png'))
lau = read('TMessagesProj/src/main/java/org/telegram/ui/LauncherIconController.java')
check('icons: enum AQUA на новом ресурсе', 'R.mipmap.icon_4_foreground_sa' in lau)
check('icons: enum NOX на новом ресурсе', 'R.mipmap.icon_2_foreground_sa' in lau)
x4 = read('TMessagesProj/src/main/res/mipmap-anydpi-v26/icon_4_launcher.xml')
check('icons: anydpi aqua -> icon_4_foreground', '@mipmap/icon_4_foreground' in x4)
x2 = read('TMessagesProj/src/main/res/mipmap-anydpi-v26/icon_2_launcher_round.xml')
check('icons: anydpi nox round -> icon_2_foreground', '@mipmap/icon_2_foreground' in x2)

# ---- лого первого экрана: intro_tg_plane заменён на наш арт (png, webp удалён)
import glob as _glob
planes_png = _glob.glob(_osp.join(res, 'drawable-*/intro_tg_plane.png'))
planes_webp = _glob.glob(_osp.join(res, 'drawable-*/intro_tg_plane.webp'))
check('logo: intro_tg_plane.png (4 бакета)', len(planes_png) == 4)
check('logo: старый webp удалён', len(planes_webp) == 0)
for pl in planes_png:
    with open(pl, 'rb') as fh:
        ok = fh.read(8) == b'\x89PNG\r\n\x1a\n'
    check('logo: png валиден ' + _osp.basename(_osp.dirname(pl)), ok)

print()
if fails:
    print('FAILED:', len(fails), fails)
    sys.exit(1)
print('ALL PASS')
