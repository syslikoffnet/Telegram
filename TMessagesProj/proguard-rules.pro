-keep public class com.google.android.gms.* { public *; }
-keepnames @com.google.android.gms.common.annotation.KeepName class *
-keepclassmembernames class * {
    @com.google.android.gms.common.annotation.KeepName *;
}

-keep @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * { @androidx.annotation.Keep *; }

-keep class org.webrtc.* { *; }
-keep class org.webrtc.audio.* { *; }
-keep class org.webrtc.voiceengine.* { *; }
# Telegram core is preserved below. Do not keep this whole package here:
# PengramAI/PengramConfig/etc. live in it and must be eligible for R8 renaming.
-keep class org.telegram.messenger.camera.* { *; }
-keep class org.telegram.messenger.secretmedia.* { *; }
-keep class org.telegram.messenger.support.* { *; }
-keep class org.telegram.messenger.support.* { *; }
-keep class org.telegram.messenger.time.* { *; }
-keep class org.telegram.messenger.video.* { *; }
-keep class org.telegram.messenger.voip.* { *; }
-keep class org.telegram.SQLite.** { *; }
-keep class org.telegram.tgnet.ConnectionsManager { *; }
-keep class org.telegram.tgnet.NativeByteBuffer { *; }
-keep class org.telegram.tgnet.RequestTimeDelegate { *; }
-keep class org.telegram.tgnet.RequestDelegate { *; }
-keep class org.telegram.ui.Stories.recorder.FfmpegAudioWaveformLoader { *; }
-keep class androidx.mediarouter.app.MediaRouteButton { *; }
-keepclassmembers class ** {
    @android.webkit.JavascriptInterface <methods>;
}

# https://developers.google.com/ml-kit/known-issues#android_issues
-keep class com.google.mlkit.nl.languageid.internal.LanguageIdentificationJni { *; }

# Huawei Services
-keep class com.huawei.hianalytics.**{ *; }
-keep class com.huawei.updatesdk.**{ *; }
-keep class com.huawei.hms.**{ *; }

# Don't warn about checkerframework and Kotlin annotations
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**

-keep class io.nano.tex.** {*;}

-keep class org.telegram.tgnet.** { *; }

# JLatexMath: macro/atom classes are loaded reflectively by Class.forName
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**

# Keep upstream Telegram/JNI/reflection contracts intact, but let R8 rename,
# shrink and optimize our Pengram* classes. This is obfuscation, NOT encryption.
# Do not add -dontoptimize here: it disables R8's optimizer for the whole app.
-dontwarn **
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions
# Keep line numbers for private retracing, but do not reveal original Java
# source filenames in production stack traces or decompiler metadata.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep upstream Telegram/JNI/reflection contracts intact; rename Pengram*
# classes in every package, including org.telegram.messenger.
-keep class !**.Pengram**, ** { *; }
-keepclassmembers class !**.Pengram**, ** { *; }

# Both PengramBackgroundService and PengramAntiCrash are plain Java helpers,
# not Android components or JNI entry points. Do not keep their public names.
# Put renamed fork-only classes into a short package, not the descriptive
# org.telegram.* hierarchy. Kept upstream classes remain in their packages.
-repackageclasses 'p'
