# ============================================================================
# kotlinx.serialization
# ============================================================================
-keepattributes *Annotation*, InnerClasses, RuntimeVisibleAnnotations, AnnotationDefault, Signature
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.newoether.agora.**$$serializer { *; }
-keepclassmembers class com.newoether.agora.** { *** Companion; }
-keepclasseswithmembers class com.newoether.agora.** { kotlinx.serialization.KSerializer serializer(...); }

# Explicitly keep @SerialName annotations on all @Serializable classes so R8
# does not strip the field-name mapping metadata. Without this, R8 can remove
# the annotation and the generated serializer falls back to Kotlin property
# names (which may be obfuscated), producing JSON the Rust side can't parse.
-keepclassmembers @kotlinx.serialization.Serializable class com.newoether.agora.** {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers @kotlinx.serialization.Serializable class com.newoether.agora.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

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
# Keep ALL Rust* classes (providers, custom variants, shared types) because:
#  1. native methods must not be renamed;
#  2. RustSharedTypes data classes are @Serializable and their JSON field
#     names map to Rust-side structs — R8 must not touch their fields or
#     the generated $$serializer companions, or chat generation crashes.
# ============================================================================
-keep class com.newoether.agora.util.RustShell { *; }
-keep class com.newoether.agora.util.RustShell$* { *; }
-keep class com.newoether.agora.util.RustCrypto { *; }
-keep class com.newoether.agora.util.RustCrypto$* { *; }
-keep class com.newoether.agora.api.RustProvider { *; }
-keep class com.newoether.agora.api.RustProvider$* { *; }
-keep class com.newoether.agora.api.RustEmbeddingClient { *; }
-keep class com.newoether.agora.api.RustEmbeddingClient$* { *; }
-keep class com.newoether.agora.api.RustOpenAiProvider { *; }
-keep class com.newoether.agora.api.RustAnthropicProvider { *; }
-keep class com.newoether.agora.api.RustGeminiProvider { *; }
-keep class com.newoether.agora.api.RustOllamaProvider { *; }
-keep class com.newoether.agora.api.RustCustomOpenAiProvider { *; }
-keep class com.newoether.agora.api.RustCustomAnthropicProvider { *; }
-keep class com.newoether.agora.api.RustCustomGeminiProvider { *; }

# RustSharedTypes.kt — every @Serializable data class (RustProviderConfig,
# RustChatMessage, RustStreamEvent, RustStreamEventData, RustGenerationError,
# RustToolCallRequest, RustModelListResponse, ...) is the JSON wire format
# between Kotlin and Rust; keep fields + serializers untouched.
-keep class com.newoether.agora.api.RustSharedTypes { *; }
-keep class com.newoether.agora.api.RustSharedTypes$* { *; }
-keep class com.newoether.agora.api.RustProviderConfig { *; }
-keep class com.newoether.agora.api.RustChatMessage { *; }
-keep class com.newoether.agora.api.RustStreamEvent { *; }
-keep class com.newoether.agora.api.RustStreamEventData { *; }
-keep class com.newoether.agora.api.RustGenerationError { *; }
-keep class com.newoether.agora.api.RustToolCallRequest { *; }
-keep class com.newoether.agora.api.RustModelListResponse { *; }
-keepclasseswithmembernames class * { native <methods>; }

# ============================================================================
# App model data classes — @Serializable, used by JSON import/export and
# must keep field names to avoid breaking persisted/conversation data.
# ============================================================================
-keep @kotlinx.serialization.Serializable class com.newoether.agora.** { *; }
-keepclassmembers class com.newoether.agora.** {
    *** Companion;
}
-keepclasseswithmembers class com.newoether.agora.** {
    kotlinx.serialization.KSerializer serializer(...);
}

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
