package org.telegram.messenger;

import org.json.JSONException;
import org.json.JSONObject;

/** A shareable DSP effect profile, not a neural voice/identity model. */
public final class PengramVoiceProfile {
    private static final String FORMAT = "pengram.voice.effect.v1";
    private final int pitch, formant, echo, drive, robot, mix, gainDb, overdrive, ambience, ambienceLevel;
    private final boolean ambienceDuck;

    private PengramVoiceProfile(int pitch, int formant, int echo, int drive, int robot, int mix, int gainDb, int overdrive, int ambience, int ambienceLevel, boolean ambienceDuck) {
        this.pitch = pitch;
        this.formant = formant;
        this.echo = echo;
        this.drive = drive;
        this.robot = robot;
        this.mix = mix;
        this.gainDb = gainDb;
        this.overdrive = overdrive;
        this.ambience = ambience;
        this.ambienceLevel = ambienceLevel;
        this.ambienceDuck = ambienceDuck;
    }

    public static PengramVoiceProfile current() {
        return new PengramVoiceProfile(PengramConfig.getVoiceChangerPitch(),
                PengramConfig.getVoiceFormant(), PengramConfig.getVoiceEcho(),
                PengramConfig.getVoiceStudioDrive(), PengramConfig.getVoiceStudioRobot(),
                PengramConfig.getVoiceStudioMix(), PengramConfig.getVoiceGainDb(),
                PengramConfig.getVoiceGainDrive(), PengramConfig.getVoiceAmbience(),
                PengramConfig.getVoiceAmbienceLevel(), PengramConfig.isVoiceAmbienceDuck());
    }

    public String toJson() throws JSONException {
        return new JSONObject().put("format", FORMAT).put("pitch", pitch)
                .put("formant", formant).put("echo", echo).put("drive", drive)
                .put("robot", robot).put("mix", mix).put("gainDb", gainDb)
                .put("overdrive", overdrive).put("ambience", ambience)
                .put("ambienceLevel", ambienceLevel).put("ambienceDuck", ambienceDuck).toString(2);
    }

    /** Validate *all* fields before touching any microphone or persistent setting. */
    public static PengramVoiceProfile parse(String text) throws JSONException {
        if (text == null || text.length() > 8192) throw new JSONException("Invalid effect profile size");
        JSONObject json = new JSONObject(text);
        if (!FORMAT.equals(json.optString("format"))) throw new JSONException("Unsupported effect profile");
        return new PengramVoiceProfile(number(json, "pitch", -12, 12),
                number(json, "formant", -6, 6), number(json, "echo", 0, 5),
                number(json, "drive", 0, 6), number(json, "robot", 0, 6),
                number(json, "mix", 0, 100), json.has("gainDb")
                        ? number(json, "gainDb", 0, PengramConfig.VOICE_GAIN_MAX_DB) : 0,
                json.has("overdrive") ? number(json, "overdrive", 0, 100) : 0,
                json.has("ambience") ? number(json, "ambience", 0, 6) : 0,
                json.has("ambienceLevel") ? number(json, "ambienceLevel", 5, 100) : 24,
                !json.has("ambienceDuck") || json.optBoolean("ambienceDuck", true));
    }

    private static int number(JSONObject json, String key, int min, int max) throws JSONException {
        Object raw = json.get(key);
        if (!(raw instanceof Number)) throw new JSONException("Invalid " + key);
        double value = ((Number) raw).doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < min || value > max) {
            throw new JSONException("Out of range " + key);
        }
        return (int) value;
    }

    /** Local settings only; imported profiles never change microphone permissions or source switches. */
    public void apply() {
        PengramConfig.setVoiceChangerPitch(pitch);
        PengramConfig.setIntValue(PengramConfig.KEY_VOICE_FORMANT, formant);
        PengramConfig.setIntValue(PengramConfig.KEY_VOICE_ECHO, echo);
        PengramConfig.setIntValue(PengramConfig.KEY_VOICE_STUDIO_DRIVE, drive);
        PengramConfig.setIntValue(PengramConfig.KEY_VOICE_STUDIO_ROBOT, robot);
        PengramConfig.setIntValue(PengramConfig.KEY_VOICE_STUDIO_MIX, mix);
        PengramConfig.setVoiceGainPreset(PengramConfig.VOICE_GAIN_CUSTOM);
        PengramConfig.setVoiceGainDb(gainDb);
        PengramConfig.setVoiceGainDrive(overdrive);
        PengramConfig.setVoiceAmbience(ambience);
        PengramConfig.setVoiceAmbienceLevel(ambienceLevel);
        PengramConfig.setVoiceAmbienceDuck(ambienceDuck);
        PengramConfig.setVoiceChangerMode(PengramVoiceChanger.MODE_CUSTOM);
        PengramVoiceChanger.reset();
    }
}
