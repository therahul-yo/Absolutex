plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.absolutex.feature.reader"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
    // ReaderViewModel touches a real Context (cacheDir, string resources); Robolectric is what
    // lets its open/cancel-race tests construct one on the JVM (see :core:data for the same need).
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:scan"))
    implementation(project(":core:decode"))
    implementation(project(":core:gpu"))
    implementation(project(":source:api"))
    implementation(project(":source:epub"))
    implementation(project(":source:folder"))
    implementation(project(":source:libarchive"))
    implementation(project(":source:pdf"))
    implementation(project(":core:thumbnails"))
    // The RemoteBookOpener interface only: plain JVM, no transports/UI. Hilt binds the real
    // implementation from :feature:remote, wherever that lands in the app's own graph.
    implementation(project(":remote:core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
