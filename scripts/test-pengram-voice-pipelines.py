#!/usr/bin/env python3
"""Голосовые пути после мержа 13.1.1: каждый источник записи обязан пропускать PCM
через PengramVoiceChanger, а пер-источник-гейты — быть включёнными по умолчанию.

Регресс, которую ловит тест: рекордеры V2 (AudioCapture / RoundVideoCodecRecorder)
работали в обход recordRunnable, поэтому изменение голоса «не работало нигде».
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel: str) -> str:
    path = ROOT / rel
    assert path.is_file(), f"нет файла: {rel}"
    return path.read_text(encoding="utf-8")


def check(cond: bool, message: str) -> None:
    if not cond:
        raise AssertionError(message)
    print(f"ok: {message}")


# 1. Новый голосовой рекордер 13.1.1 (V2): хук SOURCE_VOICE после AudioRecord.read,
#    до RMS/кодирования — обработанный PCM уходит и в амплитуду, и в Opus.
audio_capture = read("TMessagesProj/src/main/java/org/telegram/utils/recorder/audio/AudioCapture.java")
read_pos = audio_capture.find("audioRecord.read(chunk")
hook_pos = audio_capture.find("processForSource(chunk, bytesRead, SAMPLE_RATE", read_pos)
rms_pos = audio_capture.find("long sum = 0L;", hook_pos)
check(read_pos > 0 and hook_pos > read_pos, "AudioCapture: чтение найдено")
check(hook_pos > 0, "AudioCapture: хук SOURCE_VOICE после read")
check(rms_pos > hook_pos, "AudioCapture: хук до расчёта RMS (амплитуда от обработанного PCM)")
check(
    audio_capture.find("PengramVoiceChanger.isEnabled()", read_pos) > 0,
    "AudioCapture: хук под global-гардом isEnabled()",
)

# 2. Круговой рекордер V2: хук SOURCE_ROUND до queueInputBuffer + finishSource на release.
round_rec = read("TMessagesProj/src/main/java/org/telegram/utils/recorder/round/RoundVideoCodecRecorder.java")
r_read = round_rec.find("audioRecord.read(")
r_hook = round_rec.find("processForSource(buffer, bytesRead, AUDIO_SAMPLE_RATE", r_read)
r_queue = round_rec.find("queueInputBuffer(index, 0, framesRead", r_hook)
check(r_read > 0 and r_hook > r_read, "RoundVideoCodecRecorder: хук SOURCE_ROUND после read")
check(r_queue > r_hook, "RoundVideoCodecRecorder: хук до queueInputBuffer (PCM в MediaCodec)")
check(
    "finishSource(PengramVoiceChanger.SOURCE_ROUND)" in round_rec,
    "RoundVideoCodecRecorder: finishSource(SOURCE_ROUND) на release",
)

# 3. Старт V2-записи сбрасывает состояние эффекта — зеркало legacy-стартов.
media = read("TMessagesProj/src/main/java/org/telegram/messenger/MediaController.java")
v2_start = media.find("private void startRecordingV2(")
v2_body = media[v2_start:media.find("private AudioRecorderV2Controller.Listener", v2_start)]
check("PengramVoiceChanger.reset()" in v2_body, "MediaController: reset() на старте V2-записи")

# 4. Legacy-путь не потерян (fallback/старые устройства).
legacy_hook = media.find("processForSource(buffer, len, sampleRate, PengramVoiceChanger.SOURCE_VOICE)")
check(legacy_hook > 0, "MediaController: legacy recordRunnable-хук на месте")

# 5. Старый кружковый путь (InstantCameraView) цел.
legacy_round = read("TMessagesProj/src/main/java/org/telegram/ui/Components/InstantCameraView.java")
check("processForSource(byteBuffer, readResult," in legacy_round, "InstantCameraView: legacy SOURCE_ROUND-хук на месте")
check("finishSource(org.telegram.messenger.PengramVoiceChanger.SOURCE_ROUND)" in legacy_round, "InstantCameraView: finishSource на месте")

# 6. Звонки/войс: нативный call-site и proguard-keep JNI-класса.
native = read("TMessagesProj/jni/voip/webrtc/modules/audio_device/audio_device_buffer.cc")
check("ProcessPengramMicrophone(rec_buffer_" in native, "webrtc: call-site ProcessPengramMicrophone в DeliverRecordedData")
check('GetStaticMethodID(global, "processCallAudio"' in native, "webrtc: JNI-имена processCallAudio")
proguard = read("TMessagesProj/proguard-rules.pro")
keep = proguard.find("-keep class org.telegram.messenger.AudioEffectBridge")
keep_block = proguard[keep:keep + 300]
check(
    keep >= 0
    and "public static boolean isCallAudioEnabled();" in keep_block
    and "public static void processCallAudio(java.nio.ByteBuffer, int, int);" in keep_block,
    "proguard: обе JNI-методы AudioEffectBridge fully kept (R8 minify включен)",
)

# 7. Пер-источник-гейты: всё включено по умолчанию (AC: работает «нигде» = везде).
config = read("TMessagesProj/src/main/java/org/telegram/messenger/PengramConfig.java")
check('isVoiceMessagesEnabled() { return getBool(KEY_VOICE_MESSAGES, true); }' in config, "дефолт SOURCE_VOICE = true")
check('isVoiceRoundEnabled() { return getBool(KEY_VOICE_ROUND, true); }' in config, "дефолт SOURCE_ROUND = true")
check('isVoiceCallsEnabled() { return getBool(KEY_VOICE_CALLS, true); }' in config, "дефолт SOURCE_CALL = true")

# 8. UI-строки соответствуют дефолтам (без расхождений отображения).
settings = read("TMessagesProj/src/main/java/org/telegram/ui/PengramSettingsActivity.java")
check('check(PengramConfig.KEY_VOICE_ROUND, true,' in settings, "UI: строка кружочков def=true")
check('check(PengramConfig.KEY_VOICE_CALLS, true,' in settings, "UI: строка звонков def=true")

print("ALL OK: голосовые пути всех источников проходят через PengramVoiceChanger")
