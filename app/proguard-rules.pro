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
