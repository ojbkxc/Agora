# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.lxseek.chat.**$$serializer { *; }
-keepclassmembers class com.lxseek.chat.** { *** Companion; }
-keepclasseswithmembers class com.lxseek.chat.** { kotlinx.serialization.KSerializer serializer(...); }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# DataStore
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite { <fields>; }

# JSch (SSH/SFTP)
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Compose
-dontwarn androidx.compose.**

# JNI native methods (llama.cpp / proot)
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.lxseek.chat.api.LlamaEngine { *; }
-keep class com.lxseek.chat.api.LlamaChatEngine { *; }

# Lottie
-keep class com.airbnb.lottie.** { *; }

# Coil
-dontwarn coil.**

# Media3 / ExoPlayer
-dontwarn androidx.media3.**

# jlatexmath
-keep class jp.** { *; }
-dontwarn jp.**

# TTS — prevent R8 from obfuscating UtteranceProgressListener callbacks
-keep class com.lxseek.chat.util.TtsManager { *; }
-keep class com.lxseek.chat.util.TtsManager$* { *; }
