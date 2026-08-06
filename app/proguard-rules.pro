# ML Kit ships native models reached through reflection; without this the recogniser
# fails to load in a shrunk release build.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# kotlinx.serialization keeps its generated serializers on the class itself.
-keepclassmembers class com.copyeye.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.copyeye.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Recognised text must never reach a crash report. Stripping the log calls that could
# carry it is cheaper than auditing every call site.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
