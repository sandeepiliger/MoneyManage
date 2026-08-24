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
if (providers.gradleProperty("khaata.androidModule").orNull != "false") {
    include(":app")
}
