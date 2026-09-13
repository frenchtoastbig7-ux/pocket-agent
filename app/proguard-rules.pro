# Conservative rules: the Anthropic SDK and Jackson use reflection and annotations for (de)serialization,
# so keep them whole and let R8 shrink everything else (Compose, AndroidX, Kotlin stdlib, OkHttp).
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.jvm.internal.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# Optional dependencies of the SDK's JSON-schema helpers and OkHttp that aren't on Android.
-dontwarn com.github.victools.**
-dontwarn io.swagger.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn java.lang.invoke.**
# JVM-only reflection types referenced by Jackson's Java 7 support and the SDK's structured-output helpers.
-dontwarn java.beans.**
-dontwarn java.lang.reflect.AnnotatedType
