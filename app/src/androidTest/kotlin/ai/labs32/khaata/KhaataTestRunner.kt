package ai.labs32.khaata

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Swaps in a Hilt-aware Application for instrumentation tests.
 *
 * Without this, a test that injects anything gets the real [KhaataApplication], whose startup work
 * — seeding categories, scheduling WorkManager jobs — runs before the test does. Referenced from
 * `app/build.gradle.kts` as `testInstrumentationRunner`.
 */
class KhaataTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(classLoader, HiltTestApplication::class.java.name, context)
}
