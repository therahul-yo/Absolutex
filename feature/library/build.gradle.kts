plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.absolutex.feature.library"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
    // Robolectric reads this module's own strings now that a label resolves one (formatSize's
    // unknown-size dash), the same way core:data, core:gpu, feature:reader and the widget do.
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    // For SortKey, so the library screens and the index agree on what "sort by date" means.
    implementation(project(":core:scan"))
    // NaturalOrder: "Issue 2" must precede "Issue 10" in the library exactly as it does in a book.
    implementation(project(":source:api"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    // Icon set for the settings action in the top bar. The *core* set, as :feature:remote
    // already uses — it is in the APK either way, and the extended set is not worth one glyph.
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    // NavigationSuiteScaffold (§7): one declaration adapts between bottom bar, rail and drawer
    // across phone/tablet/foldable. Already in the catalog; core:ui dropped it when nothing used it.
    implementation(libs.compose.material3.adaptive.nav)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // RoomLibraryFeedTest renders labels from a real Room table on the JVM, mirroring :core:data.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
}
