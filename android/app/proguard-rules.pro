# Unifest ProGuard / R8 Rules

# Preserve line numbers and source file attributes for actionable crash stack traces in Crashlytics
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions

# Capacitor Core & Plugin Bridge
-keep public class com.getcapacitor.** { *; }
-keep public class * extends com.getcapacitor.Plugin { *; }
-keepclassmembers class * extends com.getcapacitor.Plugin {
    @com.getcapacitor.PluginMethod public *;
    @com.getcapacitor.annotation.PluginMethod public *;
    @com.getcapacitor.annotation.PermissionCallback public *;
}
-keepclassmembers class com.getcapacitor.Bridge { *; }

# Unifest Application & Custom Plugins
-keep public class com.vahin.unifest.** { *; }
-keepclassmembers class com.vahin.unifest.** { *; }

# Firebase Crashlytics & Analytics
-keepattributes *Annotation*,InnerClasses
-dontwarn com.google.firebase.**
-keep class com.google.firebase.** { *; }

# Google Mobile Ads (AdMob)
-keep public class com.google.android.gms.ads.** {
   public *;
}
-keep public class com.google.ads.** {
   public *;
}
-keep class com.google.android.gms.ads.identifier.** { *; }
-dontwarn com.google.android.gms.ads.**

# OkHttp & Coroutines
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class okhttp3.** { *; }
