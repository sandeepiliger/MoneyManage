package ai.labs32.khaata.feature.settings

/**
 * Attribution for the third-party libraries shipped in the release APK.
 *
 * Maintained by hand rather than generated, because the generator plugin pulls in a Play Services
 * dependency purely to render a list — and the list only changes when a dependency does.
 *
 * **This must be updated whenever `gradle/libs.versions.toml` gains or drops a runtime
 * dependency.** Test-only libraries (JUnit, Truth, MockK, Turbine, Robolectric, Espresso) are
 * deliberately absent: they are not in the shipped artifact, so attributing them here would
 * misstate what the app contains.
 */
object ThirdPartyNotices {

    data class Notice(
        val library: String,
        val owner: String,
        val licence: String,
    )

    private const val APACHE_2 = "Apache License 2.0"

    val ENTRIES: List<Notice> = listOf(
        Notice("AndroidX (Core, AppCompat, Activity, Lifecycle, Navigation)", "The Android Open Source Project", APACHE_2),
        Notice("Jetpack Compose (UI, Foundation, Animation, Material 3)", "The Android Open Source Project", APACHE_2),
        Notice("Material Icons Extended", "The Android Open Source Project", APACHE_2),
        Notice("AndroidX Room", "The Android Open Source Project", APACHE_2),
        Notice("AndroidX DataStore", "The Android Open Source Project", APACHE_2),
        Notice("AndroidX Paging", "The Android Open Source Project", APACHE_2),
        Notice("AndroidX WorkManager", "The Android Open Source Project", APACHE_2),
        Notice("AndroidX Biometric", "The Android Open Source Project", APACHE_2),
        Notice("AndroidX Security Crypto", "The Android Open Source Project", APACHE_2),
        Notice("AndroidX Browser, DocumentFile, SplashScreen", "The Android Open Source Project", APACHE_2),
        Notice("Kotlin Standard Library", "JetBrains s.r.o. and Kotlin Programming Language contributors", APACHE_2),
        Notice("kotlinx.coroutines", "JetBrains s.r.o.", APACHE_2),
        Notice("kotlinx.serialization", "JetBrains s.r.o.", APACHE_2),
        Notice("Dagger and Hilt", "Google LLC", APACHE_2),
        Notice("javax.inject", "The JSR-330 Expert Group", APACHE_2),
        Notice("Google Play Billing Library", "Google LLC", "Android Software Development Kit License"),
        Notice("Google Mobile Ads SDK", "Google LLC", "Android Software Development Kit License"),
    )
}
