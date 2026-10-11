# Offline voice conversion: integration gate

Pengram's built-in cartoon/armor modes are streaming DSP, **not** pretrained
character voices. They run in the existing 8–96 kHz microphone path for voice
messages, round videos and opt-in calls. The voice-studio animation is not a
microphone monitor. No remote service receives microphone audio.

## Model availability (reviewed October 2026)

- [KoeAI/LLVC](https://github.com/KoeAI/LLVC) publishes MIT-licensed CPU
  streaming **code**. Its `download_models.py` obtains checkpoints separately
  from Hugging Face. The code license alone does not establish redistribution
  permission for any named character/performer checkpoint, nor prove real-time
  performance on a low-end Android device.
- [StreamVC](https://github.com/yuval-reshef/StreamVC) has no trained checkpoint
  to share and does not implement the paper's streaming inference.
- [BadloVoice](https://github.com/abuzar310/badlovoice) offers Android UI and
  DSP but describes a separate Python/FastAPI backend for RVC; this is not an
  autonomous, ready-to-integrate in-call Android inference engine.

Therefore **no character-model weights or unused inference runtime are bundled
in the APK**. Do not advertise the original DSP presets as exact character
clones. Choosing a library requires a model with documented distribution
rights, a reproducible mobile build and real-device measurements first.

## Acceptance gate before enabling optional offline neural processing

1. Verify the checkpoint's provenance and redistribution permission separately
   from the engine's license; include an attribution record and hashes. Limit
   model size, reject unknown formats and do not deserialize arbitrary Java or
   Python objects in the app. A generic `.pth` is not executable by this APK.
2. Run inference off the microphone callback on a bounded preallocated queue.
   Normalize PCM/sample rate at the boundary; preserve packet order and length,
   and measure *end-to-end* p95/p99 delay including model lookahead/resampling.
   No main-thread I/O or model loading while recording.
3. Benchmark at 8, 16 and 48 kHz with the actual Bluetooth/call pathways on
   the weakest supported device. If the model cannot sustain the packet rate,
   use the selected original DSP preset for a whole ordinary session, not
   alternating late neural packets with dry speech. Never expose unmodified
   audio when the selected mode is Anonymous: use its existing mute-on-overload
   rule rather than the ordinary dry fallback.
4. Gate inference per device/session only after a cold-start and sustained-load
   test; test calls, voice notes and round videos on physical phones. Default
   remains the low-cost DSP path until these conditions are met.

This is an engineering gate, not a claim that a licensed model or working
on-device neural conversion is already included.
