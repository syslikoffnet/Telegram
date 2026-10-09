package org.telegram.messenger;

import java.nio.ByteBuffer;

/** Minimal stable JNI entry point. The actual effect engine may be renamed by R8. */
public final class AudioEffectBridge {
    private AudioEffectBridge() {}

    public static void processCallAudio(ByteBuffer buffer, int length, int rate) {
        PengramVoiceChanger.processCallAudio(buffer, length, rate);
    }
}
