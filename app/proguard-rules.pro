# Add project specific ProGuard rules here.
# Keep kotlinx.serialization generated classes
-keepattributes *Annotation*, InnerClasses
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
