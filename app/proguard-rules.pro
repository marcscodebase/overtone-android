# Add project specific ProGuard rules here.
# Keep kotlinx.serialization generated classes
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.musicplayer.**$$serializer { *; }
-keepclassmembers class com.musicplayer.** {
    *** Companion;
}
-keepclasseswithmembers class com.musicplayer.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Jellyfin SDK models & serializers under R8
-keep,includedescriptorclasses class org.jellyfin.sdk.model.** { *; }
-dontwarn org.jellyfin.sdk.**

# Keep jump3r (pure-Java LAME fallback encoder) clean under R8
-dontwarn de.sciss.jump3r.**
-dontwarn javax.sound.sampled.**

# Keep embedded WireGuard userspace JNI bridge and native JSON DTOs under R8
-keep class com.musicplayer.core.network.wireguard.WireGuardNativeBridge { *; }
-keep class com.musicplayer.core.network.wireguard.NativeStartResult { *; }
-keep class com.musicplayer.core.network.wireguard.NativeStatusResult { *; }
-keep class com.musicplayer.core.network.wireguard.NativeDialTestResult { *; }
