# kotlinx.serialization models (library ships its own rules; these keep saved history and routes intact).
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep,includedescriptorclasses class com.vknxworks.shutterctrl.**$$serializer { *; }
-keepclassmembers class com.vknxworks.shutterctrl.** {
    *** Companion;
}
-keepclasseswithmembers class com.vknxworks.shutterctrl.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Google-free: Guava's listenablefuture jar is excluded from the build (see app/build.gradle.kts).
# AndroidX's concurrent-futures still names its interface in classes ShutterCtrl never uses.
-dontwarn com.google.common.util.concurrent.ListenableFuture

# Release builds drop all logging: ShutterCtrl's own code doesn't log, and any Log call that sneaks in later
# (or one in a library) is stripped.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
    public static boolean isLoggable(java.lang.String, int);
}
