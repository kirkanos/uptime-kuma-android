# R8 rules for the release build (full mode, see gradle.properties).
#
# Most libraries ship their own consumer rules and need nothing here:
#   - Hilt / Dagger, androidx.hilt-work: generated components and @HiltWorker factories
#   - WorkManager: keeps ListenableWorker subclasses by name (persisted in its database)
#   - Room: keeps RoomDatabase subclasses (`*_Impl` is looked up by name)
#   - Glance / app widgets, services, receivers, activities: kept via the merged manifest
#   - Navigation Compose with @Serializable routes and kotlinx.serialization: the serialization
#     library ships R8 rules for `Companion.serializer()` / `$$serializer` lookups
#   - OkHttp / Okio: ship rules for their optional platform integrations
#   - UnifiedPush connector 3.x: no consumer rules, but it only uses manifest components,
#     Parcelable CREATORs (kept by AGP defaults) and Tink's `subtle` classes (no reflection).
# The rules below cover what is not covered by those, plus belt-and-braces for our own DTOs.

# ---- Debugging aids -------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- kotlinx.serialization ------------------------------------------------
# Our DTOs, navigation routes and stored push alerts are decoded via generated serializers; keep
# them and their companions in our own packages (library rules cover the generic cases).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class net.kirkanos.uptimekuma.**$$serializer { *; }
-keepclassmembers class net.kirkanos.uptimekuma.** {
    *** Companion;
}
-keepclasseswithmembers class net.kirkanos.uptimekuma.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class net.kirkanos.uptimekuma.** {
    public static ** INSTANCE;
    public static ** Companion;
}

# ---- Socket.IO / Engine.IO client (io.socket) -----------------------------
# Emitter callbacks and transports are wired by name/reflection in places; the library is small,
# keep it whole. org.json comes from the Android framework (the jar is excluded in build.gradle.kts).
-keep class io.socket.** { *; }
-keep interface io.socket.** { *; }
-dontwarn io.socket.**
-dontwarn org.json.**

# ---- OkHttp / Okio --------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Tink (UnifiedPush Web Push decryption) -------------------------------
# Optional compile-time annotations / providers referenced by Tink that are not on Android.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# ---- Kotlin coroutines ----------------------------------------------------
-dontwarn kotlinx.coroutines.**
