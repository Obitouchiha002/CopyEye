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

# Recognised text must never reach a crash report. Stripping the chatty levels is cheaper
# than auditing every call site.
#
# Log.i is deliberately kept: the only things logged at that level are scan timings and
# counts — never screen content — and they are what lets someone measure the app's latency
# on their own phone from a release build. See core/state/ScanTiming.kt.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
