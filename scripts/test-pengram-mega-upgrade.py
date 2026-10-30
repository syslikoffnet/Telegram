#!/usr/bin/env python3
"""
Static & algorithmic regression suite for:
1. Link Assistant Mega-Upgrade (10 platforms, quality selector, audio-only M4A/MP3,
   HLS MPEG-TS remuxer, and incoming chat '⬇ Смотреть тут' inline viewer).
2. Ghost Mode — Automatic Read on Reply.
3. Voice Changer — 6 Procedural Background Ambience modes + ducking + preview.
"""

from pathlib import Path
import hashlib
import math
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


config = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java")
resolver = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramLinkResolver.java")
assistant = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramLinkAssistant.java")
mux = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramVideoMux.java")
preview = read("TMessagesProj/src/main/java/org/telegram/ui/Components/PengramLinkPreviewSheet.java")
enter_view = read("TMessagesProj/src/main/java/org/telegram/ui/Components/ChatActivityEnterView.java")
cell = read("TMessagesProj/src/main/java/org/telegram/ui/Cells/ChatMessageCell.java")
chat_act = read("TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java")
msgs_ctrl = read("TMessagesProj/src/main/java/org/telegram/messenger/MessagesController.java")
send_hlp = read("TMessagesProj/src/main/java/org/telegram/messenger/SendMessagesHelper.java")
voice = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramVoiceChanger.java")
voice_prof = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramVoiceProfile.java")
settings = read("TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java")


def unescape_java_str(raw: str) -> str:
    out = []
    i = 0
    while i < len(raw):
        if raw[i] == "\\" and i + 1 < len(raw):
            nxt = raw[i + 1]
            if nxt == "u" and i + 5 < len(raw):
                out.append(chr(int(raw[i + 2 : i + 6], 16)))
                i += 6
                continue
            out.append(nxt)
            i += 2
        else:
            out.append(raw[i])
            i += 1
    return "".join(out)


URL_STOP_MATCH = re.search(r'private static final String URL_STOP = "((?:\\.|[^"\\])*)";', resolver)
assert URL_STOP_MATCH, "URL_STOP not found"
URL_STOP_VAL = unescape_java_str(URL_STOP_MATCH.group(1))


def extract_java_pattern(src: str, field_name: str) -> re.Pattern:
    m = re.search(
        rf"private static final Pattern {field_name} = Pattern\.compile\((.*?)\);",
        src,
        re.DOTALL,
    )
    assert m, f"Pattern {field_name} not found"
    expr = m.group(1)
    expr = re.sub(r",\s*Pattern\.CASE_INSENSITIVE\s*$", "", expr.strip())
    tokens = re.findall(r'"((?:\\.|[^"\\])*)"|\b(URL_STOP)\b', expr)
    assembled = "".join(URL_STOP_VAL if ident == "URL_STOP" else unescape_java_str(lit) for lit, ident in tokens)
    return re.compile(assembled, re.IGNORECASE)


PATTERNS = {
    "YOUTUBE": extract_java_pattern(resolver, "YOUTUBE"),
    "TIKTOK": extract_java_pattern(resolver, "TIKTOK"),
    "INSTAGRAM": extract_java_pattern(resolver, "INSTAGRAM"),
    "TWITTER": extract_java_pattern(resolver, "TWITTER"),
    "PINTEREST": extract_java_pattern(resolver, "PINTEREST"),
    "VK_VIDEO": extract_java_pattern(resolver, "VK"),
    "RUTUBE": extract_java_pattern(resolver, "RUTUBE"),
    "REDDIT": extract_java_pattern(resolver, "REDDIT"),
    "SOUNDCLOUD": extract_java_pattern(resolver, "SOUNDCLOUD"),
    "YANDEX_MUSIC": extract_java_pattern(resolver, "YANDEX"),
}

POSITIVE_URLS = [
    ("INSTAGRAM", "https://www.instagram.com/reel/C8abcDEF_12/?igsh=xyz"),
    ("INSTAGRAM", "https://instagram.com/reels/C8abcDEF_12/"),
    ("INSTAGRAM", "https://www.instagram.com/p/C8abcDEF_12/?img_index=1"),
    ("INSTAGRAM", "https://www.instagram.com/share/reel/BAJ123xyz/"),
    ("INSTAGRAM", "instagr.am/p/C8abcDEF_12/"),
    ("TWITTER", "https://x.com/elonmusk/status/1850123456789012345?s=20"),
    ("TWITTER", "https://twitter.com/i/status/1850123456789012345"),
    ("TWITTER", "https://fxtwitter.com/someone/status/1850123456789012345"),
    ("TWITTER", "https://vxtwitter.com/someone/status/1850123456789012345"),
    ("TWITTER", "https://fixupx.com/someone/status/1850123456789012345"),
    ("TWITTER", "https://t.co/AbCdEf1234"),
    ("PINTEREST", "https://www.pinterest.com/pin/123456789012345678/"),
    ("PINTEREST", "https://ru.pinterest.com/pin/cool-design--123456789012345678/"),
    ("PINTEREST", "https://pin.it/3AbCdEfGh"),
    ("VK_VIDEO", "https://vk.com/clip-12345678_456239017"),
    ("VK_VIDEO", "https://vk.com/video-12345678_456239017"),
    ("VK_VIDEO", "https://vkvideo.ru/video-12345678_456239017"),
    ("VK_VIDEO", "https://vkvideo.ru/clip123456_789012"),
    ("VK_VIDEO", "https://vk.com/clips?z=clip-123456_789012"),
    ("VK_VIDEO", "https://vk.com/clips/?z=video-123456_789012"),
    ("RUTUBE", "https://rutube.ru/shorts/0123456789abcdef0123456789abcdef/"),
    ("RUTUBE", "https://rutube.ru/video/0123456789abcdef0123456789abcdef/"),
    ("RUTUBE", "https://rutube.ru/video/private/0123456789abcdef0123456789abcdef/?p=SecretKey1"),
    ("RUTUBE", "https://rutube.ru/yappy/0123456789abcdef0123456789abcdef/"),
    ("REDDIT", "https://www.reddit.com/r/nextfuckinglevel/comments/1abc2de/amazing_trick/"),
    ("REDDIT", "https://www.reddit.com/r/pics/s/AbCdEfGh12"),
    ("REDDIT", "https://redd.it/1abc2de"),
    ("REDDIT", "https://v.redd.it/z9y8x7w6v5u4"),
    ("SOUNDCLOUD", "https://soundcloud.com/porter-robinson/shelter"),
    ("SOUNDCLOUD", "https://m.soundcloud.com/artist-name/song-title?si=abc"),
    ("SOUNDCLOUD", "https://on.soundcloud.com/AbCdEfGh1234"),
    ("YANDEX_MUSIC", "https://music.yandex.ru/album/1234567/track/76543210"),
    ("YANDEX_MUSIC", "https://music.yandex.ru/track/76543210"),
    ("YANDEX_MUSIC", "https://music.yandex.kz/users/someone/playlists/3/track/76543210"),
]

for expected_name, sample in POSITIVE_URLS:
    matched = [name for name, pat in PATTERNS.items() if pat.search(sample)]
    assert matched == [expected_name], f"Expected {[expected_name]} for {sample}, got {matched}"

NEGATIVE_URLS = [
    "https://www.instagram.com/explore/",
    "https://www.instagram.com/some_profile/",
    "https://x.com/home",
    "https://x.com/settings/profile",
    "https://www.pinterest.com/ideas/",
    "https://vk.com/feed",
    "https://vk.com/im",
    "https://rutube.ru/feeds/top/",
    "https://www.reddit.com/r/funny/",
    "https://soundcloud.com/discover",
    "https://soundcloud.com/you/likes",
    "https://music.yandex.ru/chart",
]

for neg in NEGATIVE_URLS:
    matched = [name for name, pat in PATTERNS.items() if pat.search(neg)]
    assert not matched, f"Non-media URL {neg} falsely matched {matched}"

# Verify Yandex Music MD5 sign formula matches PengramLinkResolver
path_sample = "/s/v1/storage/track_file.mp3"
s_sample = "abcdef012345"
expected_md5 = hashlib.md5(("XGRlBW9FXlekgbPrRHuSiA" + path_sample[1:] + s_sample).encode()).hexdigest()
assert len(expected_md5) == 32
assert '"XGRlBW9FXlekgbPrRHuSiA" + path.substring(1) + s' in resolver

# 1. Link Assistant Mega-Upgrade checks
for key in (
    "KEY_LINK_PLAT_YOUTUBE",
    "KEY_LINK_PLAT_TIKTOK",
    "KEY_LINK_PLAT_INSTAGRAM",
    "KEY_LINK_PLAT_TWITTER",
    "KEY_LINK_PLAT_PINTEREST",
    "KEY_LINK_PLAT_VK",
    "KEY_LINK_PLAT_RUTUBE",
    "KEY_LINK_PLAT_REDDIT",
    "KEY_LINK_PLAT_SOUNDCLOUD",
    "KEY_LINK_PLAT_YANDEX",
    "KEY_LINK_AUDIO_CHIP",
    "KEY_LINK_INCOMING_WATCH",
    "KEY_LINK_INCOMING_OUTGOING",
):
    assert key in config, f"Missing {key} in PengramConfig"
    assert key in settings, f"Missing {key} in PengramSettingsActivity"
assert "KEY_LINK_QUALITY" in config and "BTN_LINK_QUALITY_MODE" in settings
assert "KEY_LINK_DEFAULT_FORMAT" in config and "BTN_LINK_DEFAULT_FORMAT" in settings

assert "remuxToMp4(" in mux and "extractAudioToM4a(" in mux
assert "class TsSample" in mux and "TS_PACKET_SIZE = 188" in mux
assert "downloadHlsStream(" in resolver and "selectBestHlsVariant(" in resolver
assert "ACTION_SEND_AUDIO" in assistant and "deliverAudio(" in assistant and "sendAudioFile(" in assistant
assert "watchInline(" in assistant and "openWatchedMediaOnUi(" in assistant
assert "onSendAudioOnly(" in preview and "PengramLinkPreviewSendAudioChip" in preview
assert "PengramLinkAssistant.findSupportedLink(" in enter_view
assert "pengramDrawWatchPill(" in cell and "checkPengramWatchMotionEvent(" in cell
assert "didPressPengramWatch(" in chat_act and "didLongPressPengramWatch(" in chat_act

# 2. Ghost Mode — Automatic Read on Reply checks
assert 'KEY_GHOST_READ_ON_REPLY = "ghostReadOnReply"' in config
assert "isGhostReadOnReply()" in config
assert "pengramGhostDeferredReads" in msgs_ctrl
assert "pengramMarkDialogReadOnReply(" in msgs_ctrl
assert "completeReadTask(task, true)" in msgs_ctrl
assert send_hlp.count("pengramMarkDialogReadOnReply(") >= 2, "Both sendMessage entrypoints must trigger read-on-reply"
assert "KEY_GHOST_READ_ON_REPLY" in settings

# 3. Voice Changer — 6 Background Ambience modes + ducking + preview + profile export
for amb in (
    "AMBIENCE_OFF = 0",
    "AMBIENCE_SUBWAY = 1",
    "AMBIENCE_STREET = 2",
    "AMBIENCE_RAIN = 3",
    "AMBIENCE_OFFICE = 4",
    "AMBIENCE_RADIO = 5",
    "AMBIENCE_CLUB = 6",
):
    assert amb in voice, f"Missing {amb} in PengramVoiceChanger"

assert "class AmbienceEngine" in voice
assert "renderAmbiencePreviewChunk(" in voice
assert "PengramVoiceAmbiencePlayer.stop();" in settings
assert '"ambience"' in voice_prof and '"ambienceLevel"' in voice_prof and '"ambienceDuck"' in voice_prof

# 4. Localization completeness & <= 150-char shadow guard
en_strings = {n.get("name"): (n.text or "") for n in ET.parse(ROOT / "TMessagesProj/src/main/res/values/strings.xml").iter("string")}
ru_strings = {n.get("name"): (n.text or "") for n in ET.parse(ROOT / "TMessagesProj/src/main/res/values-ru/strings.xml").iter("string")}
for info_key in ("PengramLinkAssistantInfo", "PengramLinkPreviewInfo", "PengramLinkPhotosInfo", "PengramLinkFallbackInfo"):
    assert len(en_strings[info_key]) <= 150, f"EN {info_key} too long: {len(en_strings[info_key])}"
    assert len(ru_strings[info_key]) <= 150, f"RU {info_key} too long: {len(ru_strings[info_key])}"

print("Pengram mega-upgrade (10-platform Link Assistant, Watch Here, Audio-only, Quality, Ghost Read-on-Reply, 6x Voice Ambience): ALL OK")
