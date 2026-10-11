# Recognizable voice models — actual feasibility and acceptance gate

**Current state:** Pengram has real-time microphone DSP effects, but does **not**
contain neural voice-conversion inference or weights of any celebrity or
character. An effect that changes pitch or adds echo is not a recognizable
person's voice. The synthetic presets added as character substitutes have been
removed. Do not label effects with the names of real people or characters.

## Projects examined (October 2026)

- [ouor/RVC-Android](https://github.com/ouor/RVC-Android) runs ONNX-based RVC
  **offline on Android**, but converts a finished recording (up to 60 seconds),
  not the live microphone packets of a Telegram call. It needs three external
  models (synthesizer, HuBERT and RMVPE), Android 12+, arm64 and `largeHeap`.
  Its repository has no detected software license; do not copy its code or
  present batch conversion as live call conversion.
- [KoeAI/LLVC](https://github.com/KoeAI/LLVC) provides MIT-licensed streaming
  CPU code. Checkpoints are downloaded separately from Hugging Face. This does
  not supply a verified, redistributable voice of a named public figure, nor
  prove that the full conversion pipeline works on a low-end Android phone.
- [StreamVC](https://github.com/yuval-reshef/StreamVC) publishes an unofficial
  implementation, but its README says it has no trained checkpoint to share
  and the paper's streaming inference is not fully implemented.
- [rabebsdiri45/tts-spongebob-voice-clone](https://github.com/rabebsdiri45/tts-spongebob-voice-clone)
  describes **text-to-speech**, not conversion of a live speaker; its MIT code
  label alone does not grant rights to the performance recordings or prove the
  exported model can run on Android in real time.

No usable, verified-redistributable celebrity voice package was found in these
sources. Repository license != consent to redistribute a person's vocal likeness
or a separately hosted model. None of these projects can be honestly pasted
into Pengram as a ready-to-use, low-latency in-call celebrity voice.

## Implementation gate for an actual voice model

1. Obtain an identifiable voice model and auditable rights for its weights and
   voice data, not merely a similarly named GitHub repository. Verify its format
   and integrity; never run Python `.pth` deserialization in the Android app.
2. Benchmark end-to-end, *including* content encoder, pitch extractor, synthesis,
   resampling, buffer scheduling and model lookahead. The microphone callback
   must not block for inference or allocate unbounded memory. Test 8/16/48 kHz,
   Bluetooth, calls and voice/round recordings on real weak phones.
3. Offer a real voice option only after the model passes the test. If unavailable
   or too slow, show that plainly and don't silently replace the chosen celebrity
   with a cartoon filter or unmodified audio. Existing Anonymous mode must keep
   its mute-on-error guarantee.
4. Document synthetic-voice use and the provenance/permissions of each packaged
   voice. The existing DSP modes remain separate effects.

This document is a feasibility record, **not** an implemented inference engine.
