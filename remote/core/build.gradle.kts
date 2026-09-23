plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core:model"))
    implementation(project(":source:api"))
    // Retry backoff waits on delay(): cancellable and virtual under runTest. The
    // transports themselves stay blocking (SMBJ/Commons Net are blocking APIs), so this
    // is the suspend policy entry point plus the pure classification they share.
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    // runTest for the suspending opener; test-only, off the release classpath (Apache-2.0).
    testImplementation(libs.kotlinx.coroutines.test)
}
