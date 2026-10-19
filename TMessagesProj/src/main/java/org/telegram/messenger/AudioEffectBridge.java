package org.telegram.messenger;

import java.nio.ByteBuffer;

/** Minimal stable JNI entry point. The actual effect engine may be renamed by R8. */
public final class AudioEffectBridge {
    private AudioEffectBridge() {}

    /** Called before allocating a direct ByteBuffer for each WebRTC packet. */
    public static boolean isCallAudioEnabled() {
        // No JNI buffer for the default OFF call; gain-only is a valid opt-in.
        return PengramConfig.isVoiceCallsEnabled()
                && PengramVoiceChanger.isEnabledFor(PengramVoiceChanger.SOURCE_CALL);
    }

    public static void processCallAudio(ByteBuffer buffer, int length, int rate) {
        PengramVoiceChanger.processCallAudio(buffer, length, rate);
    }
}
