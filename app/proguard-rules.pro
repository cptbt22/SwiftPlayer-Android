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

# SMBJ / MBassador：SMBClient 通过 MBassador 发布连接事件。
# MBassador 在运行时读取 @Handler 注解，并反射创建 ReflectiveHandlerInvocation。
# R8 若混淆注解成员或删除 invocation 默认值，会在 TCP 连接前抛出
# "The provided handler invocation did not specify ..."，必须完整保留其运行时元数据。
-keep class net.engio.mbassy.** { *; }
-keep interface net.engio.mbassy.** { *; }
-keep enum net.engio.mbassy.** { *; }
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler <methods>;
}
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-dontwarn net.engio.mbassy.**

# SMBJ 的其他可选依赖在 Android 上不使用
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**

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
