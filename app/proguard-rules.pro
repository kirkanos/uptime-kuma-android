# ---- Debugging aids -------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- kotlinx.serialization ------------------------------------------------
# Keep serializers and the generated companion objects of @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class net.kirkanos.uptimekuma.**$$serializer { *; }
-keepclassmembers class net.kirkanos.uptimekuma.** {
    *** Companion;
}
-keepclasseswithmembers class net.kirkanos.uptimekuma.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Serializable enums / objects
-keep class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
    public static ** Companion;
}

# ---- Socket.IO / Engine.IO client (io.socket) -----------------------------
# Uses reflection for its JSON (org.json) layer and emitter callbacks.
-keep class io.socket.** { *; }
-keep interface io.socket.** { *; }
-dontwarn io.socket.**
-keep class org.json.** { *; }
-dontwarn org.json.**

# ---- OkHttp / Okio --------------------------------------------------------
# OkHttp ships consumer rules; these cover the optional platform integrations.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---- Kotlin coroutines ----------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
