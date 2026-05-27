# ============================================================
# ProGuard / R8 rules for Groq Voice Typer
# ============================================================

# --- OkHttp ---
# OkHttp platform adapter uses reflection for TLS/SSL
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep OkHttp's public API and internal platform classes
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.** { *; }

# --- Kotlin Coroutines ---
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# --- AndroidX Security / Tink ---
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# --- Compose ---
# Keep Compose runtime stability metadata
-keep class androidx.compose.runtime.** { *; }

# --- App classes ---
# Keep JSON deserialization structures to prevent R8 from stripping field names
-keep class com.groq.voicetyper.CommandResult { *; }
-keep class com.groq.voicetyper.offline.ModelAssetManager$DownloadProgress { *; }
-keep class com.groq.voicetyper.offline.ModelAssetManager$DownloadState { *; }
-keep class com.groq.voicetyper.offline.OfflineTranscriber$EngineState { *; }
-keep class com.groq.voicetyper.RecordingState { *; }

# --- Sherpa ONNX ---
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class com.k2fsa.sherpa.onnx.** {
    native <methods>;
}

# --- ONNX Runtime ---
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }


# --- General ---
# Keep annotations
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses,EnclosingMethod
