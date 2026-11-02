# SMBJ
-keep class com.hierynomus.** { *; }
-keep class com.hierynomus.smbj.** { *; }
-dontwarn com.hierynomus.**

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# JmDNS (LAN Discovery)
-keep class javax.jmdns.** { *; }
-keep interface javax.jmdns.** { *; }
-dontwarn javax.jmdns.**
-keep class java.net.InetAddress { *; }

# SpeechRecognizer
-keep class android.speech.** { *; }

# App - Speech to Subtitle
-keep class com.swiftplayer.player.SpeechToSubtitleManager { *; }
-keep class com.swiftplayer.player.SpeechToSubtitleManager$* { *; }
-keep class com.swiftplayer.player.AudioCaptureService { *; }
-keep class com.swiftplayer.player.AudioCaptureService$* { *; }
-keep class com.swiftplayer.player.LiveSubtitleView { *; }
-keep class com.swiftplayer.smb.LanDiscoveryService { *; }
-keep class com.swiftplayer.smb.LanDiscoveryService$* { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}

# SMBJ transitive dependencies - not available on Android
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn net.engio.mbassy.**

# Bouncy Castle (used by smbj)
-dontwarn org.bouncycastle.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-dontwarn com.bumptech.glide.**

# OkHttp / Okio (Emby HTTP client + Media3 OkHttp data source)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# org.json (Android built-in)
-keep class org.json.** { *; }

# Emby data layer (reflection-free, but keep model field names stable)
-keep class com.swiftplayer.data.emby.** { *; }
-keep class com.swiftplayer.data.model.MediaEntry { *; }
