package org.telegram.messenger;

import java.nio.ByteBuffer;

/** Minimal stable JNI entry point. The actual effect engine may be renamed by R8. */
public final class AudioEffectBridge {
    private AudioEffectBridge() {}

    /** Called before allocating a direct ByteBuffer for each WebRTC packet. */
    public static boolean isCallAudioEnabled() {
        // On by default (sources opt-out, not opt-in); gain-only is a valid state.
        return PengramConfig.isVoiceCallsEnabled()
                && PengramVoiceChanger.isEnabledFor(PengramVoiceChanger.SOURCE_CALL);
    }

    public static void processCallAudio(ByteBuffer buffer, int length, int rate) {
        PengramVoiceChanger.processCallAudio(buffer, length, rate);
    }
}
