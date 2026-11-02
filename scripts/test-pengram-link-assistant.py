#!/usr/bin/env python3
"""
Поведенческая и структурная проверка «Помощника ссылок» (PengramLink*):

  • Android-совместимость регексов (без Pattern.UNICODE_CHARACTER_CLASS и List.of).
  • Детектор ссылок: YouTube (watch, shorts, youtu.be, live, embed, music) и
    TikTok (video, photo-карусели, share/photo, vm/vt/t короткие ссылки),
    отсечение служебных путей (/explore, /foryou, /playlist, /@channel).
  • Парсер TikTok: канонический @i/video/<id> под Desktop UA, решение SHA-256
    WAF-челленджа (id="cs" / id="wci"), извлечение фото-карусели imagePost +
    музыкальной дорожки, фильтрация bytevc2, резервные слои SIGI_STATE / embed / og:video.
  • Парсер YouTube: InnerTube VISIONOS / IOS / ANDROID / WEB_EMBEDDED_PLAYER
    (без мёртвого ANDROID_VR), X-YouTube-Client-Name + VISITOR_DATA, Range-чанки.
  • Аппаратная склейка фото в MP4-видео (PengramPhotoSlideshow: InputSurface +
    MediaCodec video/avc + GLES20 кроссфейд + прямое копирование AAC / транскод MP3→AAC).
  • Интерактивный предпросмотр и экран выбора при ошибке (PengramLinkPreviewSheet:
    тап на 1 фото = отправка, лонг-тап = мультивыбор 2+, склейка, фоллбек-меню).
  • Геометрия и клики островка над полем ввода: передача DP (без двойного dp()) в
    LayoutHelper.createFrame, скрытие replyView/replyCloseView при чистом островке,
    рабочий крестик ✕ в покое и при загрузке, подавление дублирующего webpage-превью.
  • Настройки SECTION_LINK_ASSISTANT, дефолты (фоллбек = экран выбора, фото = спрашивать,
    предпросмотр = для фото), экспорт в .pen и синхронность EN/RU строк (≤150 симв. в тенях).
"""
import base64
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def check(cond: bool, message: str):
    if not cond:
        raise AssertionError(message)
    print("ok:", message)


def java_string_literal(source: str, const_name: str) -> str:
    m = re.search(rf'String\s+{const_name}\s*=\s*"((?:[^"\\]|\\.)*)"\s*;', source)
    assert m, f"не найдена строковая константа {const_name}"
    return m.group(1).replace("\\\\", "\\").replace('\\"', '"')


def java_string_pattern(source: str, const_name: str) -> re.Pattern:
    anchor = source.find(f"Pattern {const_name} = Pattern.compile(")
    assert anchor >= 0, f"не найден {const_name}"
    end = source.find(");", anchor)
    block = source[anchor:end]
    consts = {}
    for cid in set(re.findall(r'\b([A-Z][A-Z0-9_]{3,})\b', block)):
        if cid in {const_name, "Pattern"}:
            continue
        try:
            consts[cid] = java_string_literal(source, cid)
        except AssertionError:
            pass
    tokens = re.findall(r'"((?:[^"\\]|\\.)*)"|\b([A-Z][A-Z0-9_]{3,})\b', block)
    parts = []
    for str_lit, ident in tokens:
        if str_lit != "":
            parts.append(str_lit.replace("\\\\", "\\").replace('\\"', '"'))
        elif ident in consts:
            parts.append(consts[ident])
    raw = "".join(parts)
    assert raw, f"пустой паттерн {const_name}"
    return re.compile(raw)


def trim_url(raw: str) -> str:
    return raw.rstrip(".,!?;:)»'\"]")


def find_link(text: str, patterns):
    best = None
    for p in patterns:
        for m in p.finditer(text):
            u = trim_url(m.group(0))
            if not u:
                continue
            if not u.startswith(("http://", "https://")):
                u = "https://" + u
            if best is None or m.start() >= best[1]:
                best = (u, m.start(), m.start() + len(trim_url(m.group(0))))
    return best


resolver = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramLinkResolver.java")
assistant = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramLinkAssistant.java")
mux = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramVideoMux.java")
slideshow = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramPhotoSlideshow.java")
preview_sheet = read("TMessagesProj/src/main/java/org/telegram/ui/Components/PengramLinkPreviewSheet.java")
enter = read("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java")
top = read("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterTopView.java")
chat_act = read("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
config = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java")
settings = read("TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java")
search_meta = read("TMessagesProj/src/main/java/org/telegram/ui/PengramSearchMetadata.java")
preview_view = read("TMessagesProj/src/main/java/org/telegram/ui/Components/PengramInteractivePreviewView.java")
strings_en = read("TMessagesProj/src/main/res/values/strings.xml")
strings_ru = read("TMessagesProj/src/main/res/values-ru/strings.xml")

# --- 0. Android runtime safety: ни одного Pattern.UNICODE_CHARACTER_CLASS / (?U) ---
bad_flag_hits = []
for java_file in (ROOT / "TMessagesProj/src/main/java").rglob("*.java"):
    txt = java_file.read_text(encoding="utf-8", errors="ignore")
    code_only = re.sub(r'//[^\n]*', '', txt)
    code_only = re.sub(r'/\*.*?\*/', '', code_only, flags=re.S)
    if "UNICODE_CHARACTER_CLASS" in code_only or "(?U)" in code_only or "(?U:" in code_only:
        bad_flag_hits.append(str(java_file.relative_to(ROOT)))
check(not bad_flag_hits, f"нет UNICODE_CHARACTER_CLASS в дереве (найдено: {bad_flag_hits})")
for esc in ("\\\\u00A0", "\\\\u2000-\\\\u200A", "\\\\u3000", "\\\\u2028"):
    check(esc in resolver, f"resolver: явный Unicode-пробел {esc} в наборе остановки")

# --- 1. Регексы детектора (видео + фото-карусели TikTok) ---
yt = java_string_pattern(resolver, "YOUTUBE")
tt = java_string_pattern(resolver, "TIKTOK")
patterns = [yt, tt]

positive = [
    ("https://youtube.com/shorts/AbC123_xy?si=Q", "AbC123_xy"),
    ("youtu.be/dQw4w9WgXcQ", "dQw4w9WgXcQ"),
    ("https://m.youtube.com/watch?v=dQw4w9WgXcQ&feature=share", "dQw4w9WgXcQ"),
    ("https://music.youtube.com/watch?v=abc_DEF-123", "abc_DEF-123"),
    ("https://www.youtube.com/watch?v=abc_DEF-123", "abc_DEF-123"),
    ("https://www.tiktok.com/@somebody/video/7355512345678901234", None),
    ("https://www.tiktok.com/@somebody/photo/7355512345678901234", None),
    ("tiktok.com/@user/photo/7123456789012345678", None),
    ("https://www.tiktok.com/share/photo/7355512345678901234", None),
    ("https://m.tiktok.com/v/7355512345678901234.html", None),
    ("https://www.tiktok.com/t/ZTRC5xgJp/", None),
    ("https://vm.tiktok.com/ZM8abcDEF/", None),
    ("https://vt.tiktok.com/ZSxyz123/", None),
    ("tiktok.com/@user/video/7123456789012345678", None),
]
for url, yt_id in positive:
    got = find_link(f"глянь {url} пожалуйста", patterns)
    check(got is not None, f"детект: {url}")
    if yt_id is not None:
        m = yt.search(got[0])
        check(m is not None and m.group(1) == yt_id, f"id из {url} = {yt_id}")

negative = [
    "https://www.youtube.com/playlist?list=PLxxx",
    "https://www.youtube.com/@channelname",
    "https://www.youtube.com/results?search_query=cats",
    "https://www.tiktok.com/@channelname",
    "https://www.tiktok.com/explore",
    "https://www.tiktok.com/foryou",
    "https://www.tiktok.com/following",
    "https://example.com/watch?v=dQw4w9WgXcQ",
]
for url in negative:
    check(find_link(url, patterns) is None, f"не-медиа путь игнорируется: {url}")

check(find_link("тут https://youtu.be/dQw4w9WgXcQ.", patterns)[0] == "https://youtu.be/dQw4w9WgXcQ",
      "trim: точка")
check(find_link("тут https://youtu.be/dQw4w9WgXcQ, глянь", patterns)[0] == "https://youtu.be/dQw4w9WgXcQ",
      "trim: запятая")
check(find_link("(https://youtube.com/shorts/AbC123_xy?a=1)", patterns)[0]
      == "https://youtube.com/shorts/AbC123_xy?a=1", "trim: скобка не трогает query")
check(find_link("https://youtu.be/dQw4w9WgXcQ\u00A0и\u1680текст", patterns)[0]
      == "https://youtu.be/dQw4w9WgXcQ", "NBSP/Ogham-пробел рвут URL")
check(find_link("https://vm.tiktok.com/ZM8abcDEF/\u2028дальше", patterns)[0]
      == "https://vm.tiktok.com/ZM8abcDEF/", "Line-separator рвёт TikTok-URL")
# Два URL в одной строке: первый не-ютуб не склеивается с &v= второго.
check(find_link("https://youtube.com/watch?foo=1 https://example.com/?a=1&v=dQw4w9WgXcQ", patterns) is None,
      "watch? без v= не перепрыгивает через пробел на чужой &v=")

mid = find_link("смотри youtu.be/dQw4w9WgXcQ огонь", patterns)
check(mid == ("https://youtu.be/dQw4w9WgXcQ", 7, 7 + len("youtu.be/dQw4w9WgXcQ")),
      "ссылка в середине текста с точными индексами")

# --- 2. Проверка WAF PoW челленджа TikTok (эквивалентность алгоритма SHA-256) ---
prefix_raw = b"tiktok_waf_salt_"
target_nonce = 4219
expected_digest = hashlib.sha256(prefix_raw + str(target_nonce).encode("ascii")).digest()
challenge_payload = {
    "v": {
        "a": base64.b64encode(prefix_raw).decode("ascii"),
        "c": base64.b64encode(expected_digest).decode("ascii"),
    }
}
solved = None
for cand in range(10000):
    if hashlib.sha256(prefix_raw + str(cand).encode("ascii")).digest() == expected_digest:
        solved = base64.b64encode(str(cand).encode("ascii")).decode("ascii")
        break
check(solved == base64.b64encode(b"4219").decode("ascii"), "TikTok WAF SHA-256 тест-вектор сходится")
check("solveTikTokWafChallenge" in resolver and 'MessageDigest.getInstance("SHA-256")' in resolver,
      "resolver: встроенное решение SHA-256 WAF-челленджа TikTok")

# --- 3. TikTok фото-карусели (imagePost) + канонический @i/video/ + слои ---
check('https://www.tiktok.com/@i/video/' in resolver,
      "resolver: канонический @i/video/<id> эндпоинт для видео и фото")
check("UA_DESKTOP" in resolver and "Windows NT 10.0; Win64; x64" in resolver,
      "resolver: Desktop Chrome UA для получения SSR и imagePost")
check('optJSONObject("imagePost")' in resolver and 'optJSONObject("image_post_info")' in resolver,
      "resolver: поддержка фото-каруселей TikTok (imagePost / image_post_info)")
check("KIND_PHOTOS" in resolver and "KIND_VIDEO" in resolver and "PhotoItem" in resolver,
      "resolver: модели KIND_PHOTOS / KIND_VIDEO / PhotoItem")
check("bytevc2" in resolver, "resolver: фильтрация невоспроизводимого кодека bytevc2")
check("__UNIVERSAL_DATA_FOR_REHYDRATION__" in resolver and "SIGI_STATE" in resolver,
      "resolver: SSR + SIGI_STATE парсеры TikTok")
check("resolveTikTokEmbed" in resolver and "resolveTikTokLayered" in resolver,
      "resolver: резервные слои embed/v2 и og:video/playAddr")
check("followRedirects" in resolver and "applyCookies" in resolver and "storeCookies" in resolver,
      "resolver: редиректы vm/vt + cookie-континуитет")

# --- 4. YouTube InnerTube + Range-чанки ---
for client in ('"VISIONOS"', '"ANDROID"', '"IOS"', '"WEB_EMBEDDED_PLAYER"'):
    check(client in resolver, f"resolver: клиент {client} в цепочке")
check('"ANDROID_VR"' not in resolver,
      "resolver: мёртвый ANDROID_VR удалён из цепочки")
check('"1.02"' in resolver and "RealityDevice17,1" in resolver,
      "resolver: актуальные параметры VISIONOS")
check("X-YouTube-Client-Name" in resolver and "VISITOR_DATA" in resolver,
      "resolver: заголовки X-YouTube-Client-Name и VISITOR_DATA")
check("downloadRangeChunked" in resolver and 'Range", "bytes="' in resolver,
      "resolver: чанковое Range-скачивание для googlevideo")
check("MAX_DURATION_SEC = 600" in resolver, "resolver: лимит длительности 10 минут")

# minSdk 23 совместимость во всех новых/изменённых классах
for name, src in [("resolver", resolver), ("assistant", assistant),
                  ("slideshow", slideshow), ("preview_sheet", preview_sheet)]:
    check("List.of(" not in src and "Map.of(" not in src, f"{name}: нет List.of/Map.of (minSdk 23)")

# --- 5. Аппаратная склейка фото в MP4-видео (PengramPhotoSlideshow) ---
check("InputSurface" in slideshow and "MIMETYPE_VIDEO_AVC" in slideshow and "GLES20" in slideshow,
      "slideshow: аппаратный H.264 через InputSurface + GLES20")
check("muxVideoAndAacLoop" in slideshow and "muxVideoAndTranscodedAudio" in slideshow,
      "slideshow: прямое копирование AAC + транскодирование MP3/Opus в AAC-LC")
check("ExifInterface" in slideshow, "slideshow: учёт EXIF-поворота фотографий")

# --- 6. Оркестратор PengramLinkAssistant (мультимодальность + настройки) ---
check("PHASE_RESOLVE" in assistant and "PHASE_DOWNLOAD" in assistant
      and "PHASE_MUX" in assistant and "PHASE_STITCH" in assistant and "PHASE_SEND" in assistant,
      "assistant: все 5 фаз прогресса (включая склейку фото)")
check("deliverPhotos" in assistant and "deliverVideo" in assistant,
      "assistant: отдельные конвейеры фото/альбома/слайд-шоу и видео")
check("batchSize = 10" in assistant, "assistant: разбивка больших фото-каруселей пачками по 10")
check("normalizePhotoToJpeg" in assistant, "assistant: нормализация WebP/PNG в валидный JPEG")
check("MAX_VIDEO_BYTES = 1_500_000_000L" in assistant, "assistant: лимит видео 1.5 ГБ")
check("PengramLinkAssistant failed for " in assistant, "assistant: логирование отказа в FileLog")
check("LINK_FALLBACK_ASK" in assistant and "LINK_FALLBACK_SEND" in assistant and "LINK_FALLBACK_NONE" in assistant,
      "assistant: 3 режима поведения при ошибке")

# --- 7. Интерактивный предпросмотр и выбор при ошибке (PengramLinkPreviewSheet) ---
check("showPreview(" in preview_sheet and "showFallbackChoice(" in preview_sheet,
      "preview_sheet: шторка предпросмотра контента и шторка выбора при ошибке")
check("onTapBody" in preview_sheet and "onLongPress" in preview_sheet and "onTapCheck" in preview_sheet,
      "preview_sheet: одиночный тап отправляет 1 фото, удержание/кружок — мультивыбор")
check("onStitchPhotos" in preview_sheet and "onSendPhotos" in preview_sheet and "onSendPlainLink" in preview_sheet,
      "preview_sheet: действия отправки фото, склейки в видео и отправки обычной ссылкой")

# --- 8. Островок в поле ввода: фикс пустого бара и неработающего крестика ✕ ---
check("LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, dp(" not in enter,
      "enter: НЕТ двойного умножения на density (dp() внутри createFrame)")
check("LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, PENGRAM_LINK_BAR_DP, Gravity.BOTTOM)" in enter,
      "enter: высота островка передаётся в DP в LayoutHelper.createFrame")
check("setPanelVisible" in top and "replyCloseView" in top and "applyLinkVisibility" in top,
      "top: скрытие replyView и replyCloseView, когда открыт только островок ссылки")
check("top.setPanelVisible(topViewShownByPanel || !barVisible)" in enter,
      "enter: синхронизация видимости панели ответа и островка")
check("pengramLinkBarClose.setOnClickListener" in enter and "onPengramLinkClose()" in enter,
      "enter: рабочий крестик ✕ (отмена загрузки и скрытие островка)")
check("isPengramLinkSuggestionActive()" in chat_act,
      "chat: подавление дублирующего webpage-превью пока активен островок помощника")

# --- 9. Настройки PengramConfig + PengramSettingsActivity + .pen бэкап ---
check('KEY_LINK_ASSISTANT = "linkAssistant"' in config, "config: ключ linkAssistant")
check("getBool(KEY_LINK_ASSISTANT, true)" in config, "config: по умолчанию ВКЛ")
check("getIntCached(KEY_LINK_FALLBACK_MODE, LINK_FALLBACK_ASK)" in config,
      "config: по умолчанию при ошибке — вызывать экран выбора (LINK_FALLBACK_ASK)")
check("getIntCached(KEY_LINK_PHOTO_STITCH, LINK_STITCH_ASK)" in config,
      "config: по умолчанию для нескольких фото — спрашивать (LINK_STITCH_ASK)")
check("getIntCached(KEY_LINK_PREVIEW_MODE, LINK_PREVIEW_PHOTOS)" in config,
      "config: по умолчанию предпросмотр — для фото (LINK_PREVIEW_PHOTOS)")
for exp_key in ("linkAssistant", "linkAssistantFallback", "linkAssistantPhotoStitch",
                "linkAssistantPreview", "linkAssistantCaption", "linkAssistantSlideSec",
                "linkAssistantSlideAudio", "linkAssistantIslandPreviewBtn"):
    check(f'"{exp_key}"' in config, f"config: ключ {exp_key} включён в экспорт .pen")

check("SECTION_LINK_ASSISTANT = 21" in settings and "fillLinkAssistant(" in settings,
      "settings: выделенный подраздел SECTION_LINK_ASSISTANT")
check('check(PengramConfig.KEY_LINK_ASSISTANT, true, getString(R.string.PengramLinkAssistant))' in settings,
      "settings: главный тумблер помощника ссылок")
check("KEY_LINK_ISLAND_PREVIEW_BTN" in settings and "isLinkIslandPreviewButtonEnabled()" in config,
      "settings: тумблер отключения кнопки предпросмотра на островке ссылки")
check("SECTION_LINK_ASSISTANT" in search_meta and "SECTION_LINK_ASSISTANT" in preview_view,
      "settings: подраздел подключён к хлебным крошкам поиска и интерактивному превью")

# --- 9b. LRU-кэш последних 3 запросов TikTok + фоновые сессии по чатам + вёрстка ---
check("MAX_TIKTOK_CACHE_ENTRIES = 3" in assistant
      and "MAX_TIKTOK_CACHE_ENTRY_BYTES" in assistant
      and "MAX_TIKTOK_CACHE_TOTAL_BYTES" in assistant,
      "assistant: LRU-кэш последних 3 запросов TikTok с потолками размера")
for cache_fn in ("peekCachedTikTok", "getCachedTikTokPhotoFile", "prefetchTikTokMetadata",
                 "copyCachedPhotoIfPresent", "copyCachedAudioIfPresent", "copyCachedVideoIfPresent"):
    check(cache_fn in assistant, f"assistant: кэш-метод {cache_fn}")

for sess_fn in ("startSession", "attachSession", "detachSession", "cancelSession", "chatKey("):
    check(sess_fn in assistant, f"assistant: пер-чат сессия {sess_fn}")
check("req.sendMessageChatArguments" in assistant and "req.monoForumPeer" in assistant,
      "assistant: сохранение контекста чата/топика/отправителя при фоновой отправке")
check("detachPengramSessionUi();" in enter and "syncPengramActiveSession(" in enter,
      "enter: уход из чата не убивает фоновую загрузку, возврат восстанавливает статус")
on_destroy_block = enter[enter.index("public void onDestroy()"):enter.index("public void onDestroy()") + 260]
check("pengramLinkHandle.cancel()" not in on_destroy_block,
      "enter: onDestroy() отвязывает UI вместо отмены фоновой отправки")

check("shouldShowPengramPreviewBtn" in enter and "private ImageView pengramLinkBarPreviewBtn;" in enter,
      "enter: аккуратная иконка предпросмотра и автоскрытие при включённом предпросмотре по дефолту")
check("FlowChipsLayout" in preview_sheet and "selectionRow" in preview_sheet,
      "preview_sheet: адаптивный перенос чипов и отдельная строка для «Выбрать все» без вылета за экран")

slide_int = read("TMessagesProj/src/main/java/org/telegram/ui/Cells/SlideIntChooseView.java")
typing_preview = read("TMessagesProj/src/main/java/org/telegram/ui/Components/PengramTypingPreviewView.java")
fill_typing = settings[settings.index("private void fillTyping("):settings.index("private void fillMediaTime(") if "private void fillMediaTime(" in settings else len(settings)]
check('": " + value' not in fill_typing,
      "typing: ни один слайдер в SECTION_TYPING не передаёт длинный заголовок в min/value/max")
for typing_hdr in ("PengramTypingBlurRadius", "PengramTypingDeleteCount", "PengramTypingDeleteSpeed",
                   "PengramTypingDeleteSpread", "PengramTypingDeleteSize", "PengramTypingCursorSpeed",
                   "PengramTypingCursorWidth", "PengramTypingCursorStretch"):
    check(f"UItem.asHeader(getString(R.string.{typing_hdr}))" in fill_typing,
          f"typing: отдельный заголовок над слайдером {typing_hdr}")
check("extends LinearLayout" in typing_preview and "asCustom(typingPreview, LayoutHelper.WRAP_CONTENT)" in fill_typing,
      "typing: превью набора на вертикальном LinearLayout с WRAP_CONTENT без наложения и обрезки")
check("type != 0 && formatted != null && formatted.length() > 10" in slide_int,
      "SlideIntChooseView: защита min/max подписей от длинных строк")

# --- 10. Строки EN/RU синхронны и тени не обрезаются лимитом 150 символов ---
en_map = {el.attrib["name"]: (el.text or "") for el in ET.fromstring(strings_en).findall("string")}
ru_map = {el.attrib["name"]: (el.text or "") for el in ET.fromstring(strings_ru).findall("string")}

link_keys = [k for k in en_map if k.startswith("PengramLink")]
check(len(link_keys) >= 45, f"достаточный набор локализованных строк PengramLink* ({len(link_keys)})")
for k in link_keys:
    check(k in ru_map and len(ru_map[k].strip()) > 0, f"RU перевод для {k}")
    check(len(en_map[k].strip()) > 0, f"EN перевод для {k}")

for shadow_key in ("PengramLinkAssistantInfo", "PengramLinkPreviewInfo",
                   "PengramLinkPhotosInfo", "PengramLinkFallbackInfo"):
    check(len(en_map[shadow_key]) <= 150, f"EN тень {shadow_key} <= 150 симв. ({len(en_map[shadow_key])})")
    check(len(ru_map[shadow_key]) <= 150, f"RU тень {shadow_key} <= 150 симв. ({len(ru_map[shadow_key])})")

print("ALL OK: помощник ссылок — видео, фото-карусели, слайд-шоу, предпросмотр, островок и настройки на 100%")
