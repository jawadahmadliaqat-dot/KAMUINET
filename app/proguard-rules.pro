# KamuiNet Production R8/ProGuard Rules

# 1. WebRTC - Native code and JNI stability
-keep class org.webrtc.** { *; }
-keep interface org.webrtc.** { *; }
-keep class org.webrtc.audio.** { *; }
-keep class org.webrtc.voiceengine.** { *; }
-dontwarn org.webrtc.**

# 2. Supabase & KotlinX Serialization
-keepattributes *Annotation*, EnclosingMethod, Signature
-keepclassmembers class ** {
    @kotlinx.serialization.Serializable *;
    @kotlinx.serialization.SerialName *;
}
-keep class io.github.jan.supabase.** { *; }
-keep interface io.github.jan.supabase.** { *; }

# 3. Ktor & OkHttp
-keep class io.ktor.** { *; }
-keep class okhttp3.** { *; }
-dontwarn io.ktor.**
-dontwarn okhttp3.**

# 4. Hilt & Dagger
-keep class dagger.hilt.** { *; }
-keep interface dagger.hilt.** { *; }
-keep class * extends androidx.lifecycle.ViewModel
-keep class * implements dagger.hilt.internal.GeneratedComponent
-keep class * implements dagger.hilt.internal.UnsafeCasts.UnsafeCast
-keep class * implements dagger.hilt.codegen.OriginatingElement

# 5. Firebase (if any remaining)
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# 6. KamuiNet Models & Internal Logic
# We only keep models that are serialized for Supabase/DB.
# Everything else should be obfuscated.
-keep class com.kamuinet.client.data.** { *; }

# Aggressive Obfuscation for internal logic
-repackageclasses 'com.kamuinet.client.internal'
-allowaccessmodification
-overloadaggressively

# Remove Log calls in production for better security
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
