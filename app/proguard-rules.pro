# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.newoether.agora.**$$serializer { *; }
-keepclassmembers class com.newoether.agora.** { *** Companion; }
-keepclasseswithmembers class com.newoether.agora.** { kotlinx.serialization.KSerializer serializer(...); }

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
-keep class com.newoether.agora.api.LlamaEngine { *; }
-keep class com.newoether.agora.api.LlamaChatEngine { *; }

# Lottie
-keep class com.airbnb.lottie.** { *; }

# Coil
-dontwarn coil.**

# Media3 / ExoPlayer
-dontwarn androidx.media3.**

# jlatexmath
-keep class jp.** { *; }
-dontwarn jp.**
