# Microphone boost presets (voice messages and voice chats)

Settings → Pengram → Media → Voice studio → Microphone boost. Select Original (0 dB), Clear (+9 dB), Loud (+18 dB), Battle (+30 dB), or Custom (initially 0 dB). Each of the four boost slots has its **own saved 0–30 dB value**: select it, then use the slider. Moving the slider while Original is selected switches to Custom. Preset selection and dB values survive restart and are included in the Pengram settings backup.

Boost is applied to PCM16 before encoding, with the same selected level in voice messages and calls/group voice chats. The latter must be explicitly enabled using the **Calls and group voice chats** source switch (off by default). Video messages are independently opt-in. Boost also works with the voice effect set to Off or an effect mix of 0%; with effects enabled it is applied after the effect and before the peak limiter. Default Original with voice effect Off preserves the original audio and bypasses the WebRTC JNI buffer.

The effect profile JSON contains an optional `gainDb` (0–30) alongside the existing effect parameters; old profiles without it import with 0 dB. Import selects the Custom boost slot and does **not** enable the call source switch. Do not present the JSON as a neural voice model.

The limiter caps outgoing digital samples at 30,000/32,768; it does not guarantee a distortion-free signal when input is already saturated, nor override device AGC/noise suppression, microphone sensitivity, Opus processing, network effects, or the listener's physical playback volume. At high gain, background noise is amplified too. Start at a moderate preset and avoid live feedback from a nearby speaker.

Host PCM regression: `python3 scripts/test-voice-studio.py` (JDK required); Android compile/preflight and ARM64 APK run in GitHub Actions. A real-device check of voice notes and group calls, including a headset/Bluetooth mic, is still recommended.
