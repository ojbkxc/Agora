# ============================================================================
# kotlinx.serialization
# ============================================================================
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.newoether.agora.**$$serializer { *; }
-keepclassmembers class com.newoether.agora.** { *** Companion; }
-keepclasseswithmembers class com.newoether.agora.** { kotlinx.serialization.KSerializer serializer(...); }

# ============================================================================
# Room
# ============================================================================
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class *
-keepclassmembers @androidx.room.Dao class * { *; }
-keepclassmembers class * { @androidx.room.* <methods>; }
-dontwarn androidx.room.paging.**

# ============================================================================
# JNI bridge classes — native methods and class names must be preserved.
# Rust/C++ JNI side looks up classes and methods by their original names.
# ============================================================================
-keep class com.newoether.agora.util.RustShell { *; }
-keep class com.newoether.agora.util.RustShell$* { *; }
-keep class com.newoether.agora.util.RustCrypto { *; }
-keep class com.newoether.agora.util.RustCrypto$* { *; }
-keep class com.newoether.agora.api.RustProvider { *; }
-keep class com.newoether.agora.api.RustProvider$* { *; }
-keep class com.newoether.agora.api.RustEmbeddingClient { *; }
-keep class com.newoether.agora.api.RustEmbeddingClient$* { *; }
-keep class com.newoether.agora.api.LlamaEngine { *; }
-keep class com.newoether.agora.api.LlamaChatEngine { *; }
-keep class com.newoether.agora.api.LlamaChatEngine$* { *; }
-keep class com.newoether.agora.api.ChatTemplateMessage { *; }
-keepclasseswithmembernames class * { native <methods>; }

# ============================================================================
# OkHttp & Okio
# ============================================================================
-dontwarn okhttp3.**
-dontwarn okio.**

# ============================================================================
# DataStore
# ============================================================================
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite { <fields>; }

# ============================================================================
# JSch (SSH/SFTP)
# ============================================================================
-keep class com.jcraft.jsch.** { *; }
-keep class com.github.mwiede.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn com.github.mwiede.jsch.**

# ============================================================================
# Compose
# ============================================================================
-dontwarn androidx.compose.**

# ============================================================================
# Coroutines — keep internal continuations
# ============================================================================
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ============================================================================
# Coil
# ============================================================================
-dontwarn coil.**

# ============================================================================
# Media3 / ExoPlayer
# ============================================================================
-dontwarn androidx.media3.**

# ============================================================================
# Lottie
# ============================================================================
-dontwarn com.airbnb.lottie.**

# ============================================================================
# JLaTeXMath
# ============================================================================
-keep class ru.noties.jlatexmath.** { *; }
-dontwarn ru.noties.jlatexmath.**

# ============================================================================
# Multiplatform Markdown Renderer
# ============================================================================
-keep class com.mikepenz.markdown.** { *; }
-dontwarn com.mikepenz.markdown.**
-keep class org.jetbrains.markdown.** { *; }
