# =============================================================================================
# R8 / ProGuard configuration
#
# Release builds run with full-mode R8 (android.enableR8.fullMode=true). Most of what follows
# exists because reflection-based libraries cannot be seen by static analysis; nothing here is a
# blanket "keep everything" rule, which would defeat the point of shrinking at all.
# =============================================================================================

# ---- Diagnostics ----------------------------------------------------------------------------
# Line numbers are kept so a crash report points at a real line, and the source file name is
# renamed rather than kept so it leaks nothing about the project layout.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Logging --------------------------------------------------------------------------------
# Verbose logging is already compiled out via BuildConfig.VERBOSE_LOGGING, but stripping the
# calls outright means even an accidentally-added Log.d cannot survive into release. Warnings and
# errors are kept: a crash with no context is far harder to fix.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ---- Kotlin ---------------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }

# Coroutines' internals are looked up reflectively by the debug agent and by the dispatcher.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.flow.**

# ---- kotlinx.serialization ------------------------------------------------------------------
# Serializers are generated as companion objects and resolved by name at runtime, so they are
# invisible to R8's reachability analysis. Without these, backup export produces an empty file
# and import fails with a confusing serializer-not-found error.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# The domain models and backup envelope are all serialized.
-keep,includedescriptorclasses class ai.labs32.khaata.core.model.** { *; }
-keep,includedescriptorclasses class ai.labs32.khaata.core.backup.** { *; }
-keep,includedescriptorclasses class ai.labs32.khaata.core.money.Money { *; }
-keep,includedescriptorclasses class ai.labs32.khaata.core.money.MoneySerializer { *; }
-keep,includedescriptorclasses class ai.labs32.khaata.core.common.*Serializer { *; }

# ---- Room -----------------------------------------------------------------------------------
# Entities are instantiated reflectively by generated code, and converters are resolved by
# signature.
-keep class ai.labs32.khaata.core.database.entity.** { *; }
-keep class ai.labs32.khaata.core.database.Converters { *; }
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# ---- Hilt / Dagger --------------------------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-dontwarn dagger.hilt.**

# ---- WorkManager ----------------------------------------------------------------------------
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends androidx.work.ListenableWorker { public <init>(...); }

# ---- Play Billing ---------------------------------------------------------------------------
-keep class com.android.billingclient.api.** { *; }
-dontwarn com.android.billingclient.**

# ---- Google Mobile Ads ----------------------------------------------------------------------
-keep class com.google.android.gms.ads.** { *; }
-dontwarn com.google.android.gms.**

# ---- Compose --------------------------------------------------------------------------------
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }

# ---- Desugaring -----------------------------------------------------------------------------
-dontwarn java.lang.invoke.**
-dontwarn build.IgnoreJava8API

# ---- Biometric / security -------------------------------------------------------------------
-keep class androidx.biometric.** { *; }
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**
