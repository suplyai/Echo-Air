# Keep BLE library classes
-keep class com.kkmcn.kbeaconlib2.** { *; }

# Keep model classes serialized with kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Kotlinx serialization
-keepclassmembers class **$$serializer {
    <fields>;
    <methods>;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# ML Kit
-keep class com.google.mlkit.** { *; }

# Strip android.util.Log.d/v/i in release (v0.6.0). Our own code uses
# Timber and no tree is planted in release, so all Timber.* calls are
# no-ops — but the vendored kbeaconlib2 library has ~88 raw
# android.util.Log calls that bypass Timber and would otherwise write
# MAC addresses + GATT chatter to logcat in production.
# -assumenosideeffects lets R8 treat the listed methods as
# side-effect-free and remove them at minify time. Log.w / Log.e are
# deliberately not listed — genuine warnings and errors should still
# surface for post-incident debugging via `adb logcat *:W`.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
