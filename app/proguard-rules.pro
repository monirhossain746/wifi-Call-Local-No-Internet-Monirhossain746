# Add project specific ProGuard rules here.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault

# Gson & JSON Models
-keepattributes *Annotation*
-keep class sun.misc.Unsafe { *; }
-keep class com.android.wificalllocal.network.model.** { *; }
-keep class com.android.wificalllocal.data.local.entity.** { *; }
-keep class com.android.wificalllocal.files.** { *; }

# Room Database
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# WebRTC (io.github.webrtc-sdk:android)
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# NanoHTTPD & WebSocket
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# ZXing
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }

# AdMob & UMP
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.android.ump.** { *; }
-dontwarn com.google.android.gms.ads.**

# Firebase Remote Config & App Control
-keep class com.google.firebase.remoteconfig.** { *; }
-keep class com.android.wificalllocal.control.** { *; }
-dontwarn com.google.firebase.**

# Reflection & ViewBinding
-keepclassmembers class * extends androidx.viewbinding.ViewBinding {
    public static ** inflate(...);
    public static ** bind(...);
}
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
