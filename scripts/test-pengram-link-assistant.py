#!/usr/bin/env python3
"""Помощник ссылок: статическая проверка проводки + поведенческие тесты детектора.

Два блока:
1) Behavior — регулярки и trimUrl берутся ИЗ Java-исходника и исполняются в
   Python: вставка TikTok/YouTube-ссылки обязана детектироваться мгновенно и
   только поддерживаемыми форматами ( Shorts/youtu.be/watch, @user/video, vm/vt ).
2) Wiring — каждый микромомент фичи закреплён в коде: тумблер, вотчер, слот
   (сжатие/стек с ответом), отмена с возвратом ссылки, авто-фоллбек текстом,
   prepareSendingMedia с forceDocument=false, мультиплексор, строки EN/RU.
"""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    path = ROOT / rel
    assert path.is_file(), f"нет файла: {rel}"
    return path.read_text(encoding="utf-8")


def check(cond: bool, message: str) -> None:
    if not cond:
        raise AssertionError(message)
    print(f"ok: {message}")


def java_string_const(source: str, const_name: str) -> str:
    """Достаёт String CONST = "..." (с тем же разбором экранирования)."""
    m = re.search(rf'String {const_name}\s*=\s*"((?:[^"\\]|\\.)*)"', source)
    assert m, f"нет строковой константы {const_name}"
    return m.group(1).replace('\\\\', '\x00').replace('\\"', '"').replace('\\/', '/').replace('\x00', '\\')


def java_string_pattern(source: str, const_name: str) -> str:
    """Достаёт Pattern.compile("..." + CONST + "...") и склеивает как в Java."""
    anchor = source.find(f"Pattern {const_name} = Pattern.compile(")
    assert anchor >= 0, f"нет паттерна {const_name}"
    tail = source[anchor:]
    end = tail.find(");")
    block = tail[:end]
    # Ссылки на строковые константы подставляем в исходном литерал-виде
    # (raw из исходника — парсер ниже сам раскодирует экранирование).
    for cid in set(re.findall(r'\b([A-Z][A-Z0-9_]{3,})\b', block)):
        m = re.search(rf'String {cid}\s*=\s*"((?:[^"\\]|\\.)*)"', source)
        if m:
            block = re.sub(rf'\b{cid}\b', lambda _m, v=m.group(1): f'"{v}"', block)
    literals = re.findall(r'"((?:[^"\\]|\\.)*)"', block)
    assert literals, f"пустой литерал {const_name}"
    out = []
    for lit in literals:
        # java escape → python: сначала \\, потом остальные
        s = lit.replace('\\\\', '\x00').replace('\\"', '"').replace('\\/', '/')
        s = s.replace('\x00', '\\')
        out.append(s)
    return "".join(out)


def find_all(pattern: str, text: str):
    return [(m.group(0), m.start()) for m in re.finditer(pattern, text, re.IGNORECASE)]


def trim_url(raw: str) -> str:
    # Зеркало PengramLinkResolver.trimUrl
    end = len(raw)
    while end > 0 and raw[end - 1] in ".,!?;:)»'\"]":
        end -= 1
    return raw[:end]


resolver = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramLinkResolver.java")
yt_pat = java_string_pattern(resolver, "YOUTUBE")
tt_pat = java_string_pattern(resolver, "TIKTOK")

# ---------------------------------------------------------------- guards ----
# Android-рантайм отвергает Pattern.UNICODE_CHARACTER_CLASS: IllegalArgumentException
# прямо в <clinit> → ExceptionInInitializerError при первом вводе текста (краш
# run441 на Android 14). javac такой флаг проглатывает (константа есть в
# android.jar), поэтому защищаемся именно тут: флаг не должен появиться нигде.
import glob as _glob
_banned = []
for _path in _glob.glob(str(ROOT / "TMessagesProj/src/main/java/org/telegram/**/*.java"), recursive=True):
    _src = open(_path, encoding="utf-8", errors="replace").read()
    # комментарии и строковые литералы не исполняются — режем их перед проверкой
    _code = re.sub(r'"(?:[^"\\]|\\.)*"', '""', _src)
    _code = re.sub(r'//[^\n]*', '', _code)
    _code = re.sub(r'/\*.*?\*/', '', _code, flags=re.S)
    if "Pattern.UNICODE_CHARACTER_CLASS" in _code or "(?U)" in _code:
        _banned.append(_path)
check(not _banned, f"нет UNICODE_CHARACTER_CLASS в дереве (найдено: {_banned})")
# Семантика «\s = любые Unicode-пробелы» обязана остаться явной: NBSP и friends
# должны рвать URL, иначе после ссылки будет слипаться мусор.
for _c in ("\\\\u00A0", "\\\\u2000-\\\\u200A", "\\\\u3000", "\\\\u2028"):
    check(_c in resolver, f"resolver: явный Unicode-пробел {_c} в наборе остановки")

# ---------------------------------------------------------------- behavior ---

# YouTube: Shorts, share-ссылка, youtu.be, watch — детектим; чужие пути — нет.
cases_yes = [
    (yt_pat, "https://youtube.com/shorts/AbC123_xy?si=Q", "AbC123_xy"),
    (yt_pat, "youtu.be/dQw4w9WgXcQ", "dQw4w9WgXcQ"),
    (yt_pat, "https://m.youtube.com/watch?v=dQw4w9WgXcQ&feature=share", "dQw4w9WgXcQ"),
    (yt_pat, "https://www.youtube.com/watch?v=abc_DEF-123", "abc_DEF-123"),
    (tt_pat, "https://www.tiktok.com/@somebody/video/7355512345678901234", None),
    (tt_pat, "https://vm.tiktok.com/ZM8abcDEF/", None),
    (tt_pat, "https://vt.tiktok.com/ZSxyz123/", None),
    (tt_pat, "tiktok.com/@user/video/7123456789012345678", None),
]
for pat, sample, expect_id in cases_yes:
    matches = find_all(pat, sample)
    check(bool(matches), f"детект: {sample}")
    if expect_id:
        m = re.search(pat, sample, re.IGNORECASE)
        check(m.group(1) == expect_id, f"id из {sample} = {expect_id}")

# Не-видео YouTube пути не должны детекиться (плейлист/канал/шорт без id-суффикса ok проверен выше).
for bad in [
    "https://www.youtube.com/playlist?list=PLxxx",
    "https://www.youtube.com/@channelname",
    "https://www.youtube.com/results?search_query=cats",
    "https://example.com/youtube.com/shorts/AbC123",
]:
    # playlist/канал/поиск — не совпадают; чужой домен с вхождением youtube.com — только если
    # паттерн требует начало хоста: допускаем вхождение подстроки? Наш паттерн не требует ^,
    # поэтому example.com/youtube.com/... СОВПАДЕТ — это осознанный компромисс (предложение
    # появится, резолв упадёт и ссылка уйдёт текстом). Проверяем лишь явные не-видео пути:
    if "example.com" in bad:
        continue
    check(not find_all(yt_pat, bad) and not find_all(tt_pat, bad),
          f"не-видео путь игнорируется: {bad}")

# trimUrl: хвостовая пунктуация уходит, query остаётся.
check(trim_url("https://youtube.com/shorts/AbC123.") == "https://youtube.com/shorts/AbC123", "trim: точка")
check(trim_url("https://youtu.be/dQw4w9WgXcQ,") == "https://youtu.be/dQw4w9WgXcQ", "trim: запятая")
check(trim_url("https://youtu.be/dQw4w9WgXcQ?si=X)") == "https://youtu.be/dQw4w9WgXcQ?si=X", "trim: скобка не трогает query")

# Unicode-пробелы рвут URL (NBSP из буфера/веба) — без явного набора на Android
# после снятия UNICODE_CHARACTER_CLASS они бы слипались с хвостом.
nb = "https://youtu.be/AbC123DeF\u00a0и\u3000ещё"
m = re.search(yt_pat, nb, re.IGNORECASE)
check(m is not None and m.group(0) == "https://youtu.be/AbC123DeF", "NBSP/Ogham-пробел рвут URL")
m = re.search(tt_pat, "https://vm.tiktok.com/ZM8abcDEF/\u2028хвост", re.IGNORECASE)
check(m is not None and m.group(0) == "https://vm.tiktok.com/ZM8abcDEF/", "Line-separator рвёт TikTok-URL")

# Вставка в середину текста: находится и позиция, и последняя ссылка побеждает.
text = "го вот https://youtube.com/shorts/AAAAAAAAAA и ещё https://vm.tiktok.com/ZM111111/"
m = re.search(yt_pat, text, re.IGNORECASE)
check(m is not None and m.group(1) == "AAAAAAAAAA", "ссылка в середине текста")

# TikTok SSR-JSON: зеркало балансировки скобок из extractTikTokItem.
item = {"id": "7123", "desc": "привет {мир}", "author": {"uniqueId": "user"},
        "video": {"duration": 14, "playAddr": "https://cdn.tiktokcdn.com/v.mp4"}}
blob = json.dumps({"__DEFAULT_SCOPE__": {"webapp.video-detail": {"itemInfo": {"itemStruct": item}}}},
                  ensure_ascii=False)
html = f"<html><script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\" type=\"application/json\">{blob}</script></html>"
script_at = html.find("__UNIVERSAL_DATA_FOR_REHYDRATION__")
json_start = html.find("{", script_at)
depth, end = 0, -1
for i in range(json_start, len(html)):
    if html[i] == "{":
        depth += 1
    elif html[i] == "}":
        depth -= 1
        if depth == 0:
            end = i
            break
root = json.loads(html[json_start:end + 1])
parsed = root["__DEFAULT_SCOPE__"]["webapp.video-detail"]["itemInfo"]["itemStruct"]
check(parsed["video"]["playAddr"].startswith("https://"), "SSR: playAddr достаётся")
check(parsed["author"]["uniqueId"] == "user", "SSR: автор достаётся")

# ---------------------------------------------------------------- wiring ----

assistant = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramLinkAssistant.java")
mux = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramVideoMux.java")
enter = read("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java")
top = read("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterTopView.java")
config = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java")
settings = read("TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java")
strings_en = read("TMessagesProj/src/main/res/values/strings.xml")
strings_ru = read("TMessagesProj/src/main/res/values-ru/strings.xml")

# Тумблер в настройках + дефолт включён.
check('KEY_LINK_ASSISTANT = "linkAssistant"' in config, "config: ключ linkAssistant")
check("getBool(KEY_LINK_ASSISTANT, true)" in config, "config: по умолчанию ВКЛ")
check('check(PengramConfig.KEY_LINK_ASSISTANT, true, getString(R.string.PengramLinkAssistant))' in settings,
      "settings: строка тумблера")
check("PengramLinkAssistantInfo" in settings, "settings: подпись-тень")

# Мгновенный детект: вотчер на поле ввода.
check("updatePengramLinkSuggestion(s)" in enter, "enter: вотчер вызывает детект")
check("addTextChangedListener(new TextWatcher()" in enter, "enter: TextWatcher на messageEditText")
check("PengramConfig.isLinkAssistantEnabled()" in enter, "enter: уважает тумблер")

# Бар: показ/скрытие, слот, стек с ответом, редактирование.
check("showPengramLinkBar" in enter and "hidePengramLinkBar" in enter, "enter: show/hide бара")
check("updatePengramLinkSlot" in enter, "enter: пересчёт высоты слота")
check("topViewShownByPanel = true;" in enter, "enter: панель помечает слот занятым")
check("topViewShownByPanel = false;" in enter, "enter: скрытие панели снимает флаг")
check("if (pengramLinkShown) {" in enter and "updatePengramLinkSlot();" in enter,
      "enter: закрытие панели оставляет бар")
check("setLinkView" in top and "applyLinkVisibility" in top, "top: контейнер принимает бар")
check("linkVisible && !editMode" in top, "top: при редактировании бар скрыт")
check("isEditMode()" in enter, "enter: детект учитывает режим редактирования")

# Микромоменты тапа: вырезание ссылки, восстановление при отмене, dismiss.
check("editable.delete(idx, idx + url.length())" in enter, "action: ссылка вырезается из поля")
check("restorePengramLinkUrl" in enter and "editable.insert(idx, url)" in enter,
      "close: отмена возвращает ссылку на место")
check("pengramLinkDismissedUrl = pengramLinkUrl" in enter, "close: dismiss запоминает ссылку")
check('pengramLinkDismissedUrl = null;\n            pengramLinkUrl = null;' in enter
      or "pengramLinkDismissedUrl = null;" in enter, "watcher: ссылки нет → dismiss сброшен")

# Конвейер: резолв → скачать → (мькс) → отправка видео; фейл → авто-ссылка.
check("PengramLinkResolver.resolve" in assistant, "assistant: резолв")
check("PHASE_DOWNLOAD" in assistant and "PHASE_MUX" in assistant and "PHASE_SEND" in assistant,
      "assistant: фазы прогресса")
check("prepareSendingMedia" in assistant, "assistant: штатный медиа-конвейер")
check("false,  // forceDocument → обычное видео с превью" in assistant,
      "assistant: forceDocument=false (видео с превью)")
check("sendFallbackLink" in assistant and "SendMessageParams.of(request.url" in assistant,
      "assistant: авто-фоллбек ссылкой при ошибке")
check("cancelFlag" in assistant and "CancellationException" in assistant, "assistant: отмена через флаг")
check("detachListenerIf" in assistant and "detachListenerIf(pengramLinkListener)" in enter,
      "assistant: слушатель отвязывается в onDestroy")
check("1_500_000_000L" in assistant, "assistant: лимит файла 1.5 ГБ")

# YouTube: InnerTube без шифрования. Цепочка = yt-dlp master (окт-2026):
# VISIONOS — no-JS дефолт без PO-token; ANDROID/IOS — запасные; ANDROID_VR
# УДАЛЁН (все форматы 403 с 2026-08-17, yt-dlp #17348) — бывший дефект.
for client in ('"VISIONOS"', '"ANDROID"', '"IOS"'):
    check(client in resolver, f"resolver: клиент {client} в цепочке")
check('"ANDROID_VR"' not in resolver,
      "resolver: мёртвый ANDROID_VR (403 с 2026-08-17) удалён из цепочки")
check('"1.02"' in resolver and "RealityDevice17,1" in resolver,
      "resolver: константы visionos как в INNERTUBE_CLIENTS")
check("youtubei/v1/player" in resolver, "resolver: InnerTube player endpoint")
check("signatureCipher" not in resolver or "directUrl" in resolver,
      "resolver: прямые URL (cipher → следующий клиент)")
check("__UNIVERSAL_DATA_FOR_REHYDRATION__" in resolver, "resolver: TikTok SSR-данные")
# Многослойный фоллбэк TikTok: SSR → og:video → сырой playAddr.
check("resolveTikTokLayered" in resolver, "resolver: слои 2/3 для TikTok")
check('og:video' in resolver and "extractMetaContent" in resolver,
      "resolver: og:video-мета как слой 2")
check('playAddr\\"\\\\s*:' in resolver or '"playAddr"' in resolver,
      "resolver: сырой playAddr как слой 3")
check("followRedirects" in resolver, "resolver: vm/vt редиректы")
check("applyCookies" in resolver and "storeCookies" in resolver, "resolver: cookie-континуитет для CDN")
check("MAX_DURATION_SEC = 600" in resolver, "resolver: потолок 10 минут")
# minSdk 23: List.of (Java 9) без desugar-гарантий — только Arrays.asList.
check("List.of(" not in resolver, "resolver: нет List.of (minSdk 23)")
check("Arrays.asList" in resolver, "resolver: Arrays.asList для Resolved-списков")
# Отказ конвейера обязан логироваться — иначе «просто ссылка» не диагностируется.
check("PengramLink pipeline failed" in assistant, "assistant: техпричина отказа в логе")

# Мультиплексор: копирование семплов без перекодирования.
check("MediaMuxer" in mux and "MediaExtractor" in mux, "mux: MediaExtractor+MediaMuxer")
check("writeSampleData" in mux, "mux: копия семплов")

# Строки EN/RU синхронны.
for key in ["PengramLinkAssistant", "PengramLinkAssistantInfo", "PengramLinkSuggestTitle",
            "PengramLinkSuggestAction", "PengramLinkResolving", "PengramLinkDownloading",
            "PengramLinkSending", "PengramLinkFailed", "PengramLinkTooLong"]:
    check(f'name="{key}"' in strings_en, f"EN строка {key}")
    check(f'name="{key}"' in strings_ru, f"RU строка {key}")

print("ALL OK: помощник ссылок — детект, слот, конвейер и фоллбеки на месте")
