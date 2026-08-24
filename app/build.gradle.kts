import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Optional local configuration for services that need credentials.
 *
 * The app builds and runs fully without this file: every value below has a safe default that
 * selects a local or no-op implementation. Nothing here is committed — see `secrets.properties`
 * in .gitignore and the setup notes in docs/SETUP.md.
 */
val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(key: String, default: String = ""): String =
    (secrets.getProperty(key) ?: System.getenv(key) ?: default)

android {
    namespace = "ai.labs32.khaata"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.labs32.khaata"
        // 24 covers the overwhelming majority of active Indian Android devices while still
        // allowing modern APIs; java.time is available below 26 via core library desugaring.
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "ai.labs32.khaata.KhaataTestRunner"
        vectorDrawables.useSupportLibrary = true

        // AdMob's manifest placeholder. The Google-published sample app id is used by default so
        // debug builds show test ads and never touch a real ad account.
        manifestPlaceholders["admobAppId"] =
            secret("ADMOB_APP_ID", "ca-app-pub-3940256099942544~3347511713")

        buildConfigField(
            "String",
            "ADMOB_BANNER_UNIT_ID",
            "\"${secret("ADMOB_BANNER_UNIT_ID", "ca-app-pub-3940256099942544/6300978111")}\"",
        )
        buildConfigField(
            "String",
            "ADMOB_INTERSTITIAL_UNIT_ID",
            "\"${secret("ADMOB_INTERSTITIAL_UNIT_ID", "ca-app-pub-3940256099942544/1033173712")}\"",
        )
        buildConfigField(
            "String",
            "ADMOB_REWARDED_UNIT_ID",
            "\"${secret("ADMOB_REWARDED_UNIT_ID", "ca-app-pub-3940256099942544/5224354917")}\"",
        )

        // Empty by default, which leaves cloud AI unconfigured and the toggle disabled.
        buildConfigField("String", "CLOUD_AI_ENDPOINT", "\"${secret("CLOUD_AI_ENDPOINT")}\"")
        buildConfigField("String", "CLOUD_AI_MODEL", "\"${secret("CLOUD_AI_MODEL")}\"")
        buildConfigField("String", "CLOUD_AI_API_KEY", "\"${secret("CLOUD_AI_API_KEY")}\"")

        buildConfigField("String", "PRIVACY_POLICY_URL", "\"${secret("PRIVACY_POLICY_URL", "https://example.invalid/khaata/privacy")}\"")
        buildConfigField("String", "TERMS_URL", "\"${secret("TERMS_URL", "https://example.invalid/khaata/terms")}\"")
        buildConfigField("String", "SUPPORT_EMAIL", "\"${secret("SUPPORT_EMAIL", "support@example.invalid")}\"")

        ksp {
            // Exports the schema so migrations can be written against a real diff and verified
            // with MigrationTestHelper rather than by hand.
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.incremental", "true")
        }
    }

    signingConfigs {
        create("release") {
            // Wired from an untracked keystore.properties. Absent locally, the release build
            // still assembles unsigned so CI and contributors are not blocked.
            val keystoreProperties = Properties().apply {
                val file = rootProject.file("keystore.properties")
                if (file.exists()) file.inputStream().use { load(it) }
            }
            val storePath = keystoreProperties.getProperty("storeFile")
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            buildConfigField("boolean", "VERBOSE_LOGGING", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Gates every diagnostic log statement. Financial values are never logged in either
            // build type; this additionally silences routine lifecycle logging in release.
            buildConfigField("boolean", "VERBOSE_LOGGING", "false")

            val keystoreConfigured = rootProject.file("keystore.properties").exists()
            signingConfig = if (keystoreConfigured) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time on API < 26.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
        disable += setOf("GradleDependency", "ObsoleteLintCustomCheck")
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    implementation(project(":core"))

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.play.billing.ktx)
    implementation(libs.play.services.ads)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.androidx.room.testing)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.truth)
    kspAndroidTest(libs.hilt.compiler)
}
