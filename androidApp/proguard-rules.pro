# W9-10: keep rules for a FUTURE minified release build.
# Minify is currently OFF (see docs/release.md); these rules are committed
# now so enabling later is a one-line flip proven by a release smoke run.

# kotlinx.serialization: keep generated serializers and their companion fields.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }

# Keep serializable model companions referenced reflectively.
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Compose / AndroidX navigation: keep signatures referenced from generated code.
-keep class androidx.navigation.** { *; }
-dontwarn androidx.navigation.**

# OkHttp / Ktor CIO engine internals referenced via ServiceLoader.
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
