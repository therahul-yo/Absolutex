plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.absolutex.core.ui"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
}
dependencies {
    implementation(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.material3)
    // Phase 4: adaptive-navigation-suite removed — zero adaptive imports repo-wide (grep);
    // ui/material3 stay api (Theme + downstream Modifier/Color flow through public API),
    // tooling-preview stays implementation (compile-only, never leaks).
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)
    // First tests in this module: LanguageOptionsTest is plain JVM — java.util.Locale and XML
    // parsing, no Compose and no Robolectric — so junit alone is enough. Test-only, so the
    // release APK is unchanged.
    testImplementation(libs.junit)
}
