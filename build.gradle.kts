// Plugin aliases are applied per-module rather than declared `apply false` here, so that
// configuring `:core` never forces resolution of the Android Gradle Plugin classpath.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
