// Plugin aliases are applied per-module rather than declared `apply false` here, so that
// configuring `:core` never forces resolution of the Android Gradle Plugin classpath.
//
// `kotlin-android` is the one exception: it is backed by the exact same Kotlin Gradle Plugin
// artifact as `kotlin-jvm` above. When `:core` requests `kotlin-jvm` and `:app` separately
// requests `kotlin-android` in the same build, without a shared root-level declaration Gradle
// cannot tell the two apart and fails with "the plugin is already on the classpath with an
// unknown version" the moment both subprojects are configured together. Declaring it here costs
// nothing for a JVM-only build: like `kotlin-jvm`, it resolves from Maven Central and the Gradle
// Plugin Portal, never from Google's Maven, so `:core:test -Pkhaata.androidModule=false` is
// unaffected.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
