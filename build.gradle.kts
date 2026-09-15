plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    // Phase 4 (verified by running the build): do NOT apply kotlin.android in modules.
    // AGP 9+ ships built-in Kotlin support and applying it is a hard error
    // ("no longer required since AGP 9.0", https://kotl.in/gradle/agp-built-in-kotlin).
    // The kotlin { jvmToolchain(21) } blocks are served by AGP itself.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
