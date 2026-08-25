pluginManagement {
    repositories {
        // Central + the plugin portal first: the pure-JVM `:core` module resolves
        // entirely from them, so a JVM-only CI job never needs to reach Google's repo.
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "Khaata"

// Pure-JVM domain + money engine. No Android dependencies, runs on any JDK.
include(":core")

// Android application. Requires the Android SDK.
// JVM-only environments (domain-test CI, sandboxes without the Android SDK) opt out with:
//     ./gradlew :core:test -Pkhaata.androidModule=false
// This flag alone keeps `:app` out of the module graph. To also keep the root
// `build.gradle.kts` plugins block from ever touching Google's Maven (AGP is
// published there and nowhere else), pass the matching system property too:
//     ./gradlew :core:test -Pkhaata.androidModule=false -Dkhaata.androidModule=false
if (providers.gradleProperty("khaata.androidModule").orNull != "false") {
    include(":app")
}
