// Plugin aliases are applied per-module rather than declared `apply false` here, so that
// configuring `:core` never forces resolution of the Android Gradle Plugin classpath.
//
// `kotlin-android`, `android-application` and `compose-compiler` are declared together as one
// exception. Gradle resolves each subproject's plugin requests into its own classloader scope
// unless a plugin is also declared, unapplied, at a shared ancestor; when `:core` requests
// `kotlin-jvm` and `:app` separately requests `kotlin-android`, that isolation makes Kotlin's
// Android support unable to see AGP's classes and fails constructing `KotlinAndroidTarget` with
// "Could not generate a decorated class ... com/android/build/gradle/api/BaseVariant" the moment
// both subprojects are configured in the same build. Declaring all three here, together, puts
// them in one shared classloader so they can interoperate.
//
// `com.android.application` is published only on Google's Maven, so this group is skipped when
// `-Dkhaata.androidModule=false` is set. Note this is a JVM *system* property, not the Gradle
// *project* property `settings.gradle.kts` reads to decide whether `:app` is even included: the
// `plugins {}` block below runs in a restricted pre-pass with no access to the `Project` instance,
// so `providers.gradleProperty(...)` and `hasProperty(...)` are not available to it, and a plain
// `System.getProperty` read is the only conditional this DSL block accepts. A genuinely JVM-only
// build needs both flags:
//     ./gradlew -Pkhaata.androidModule=false -Dkhaata.androidModule=false :core:test
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    if (System.getProperty("khaata.androidModule") != "false") {
        alias(libs.plugins.android.application) apply false
        alias(libs.plugins.kotlin.android) apply false
        alias(libs.plugins.compose.compiler) apply false
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
