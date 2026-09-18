plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.absolutex.core.thumbnails"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
    // Robolectric needs android.jar resources to stand up Bitmap/ImageDecoder under the JVM.
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":source:api"))
    implementation(libs.kotlinx.coroutines.android)

    // Bitmap decode, cache and pipeline tests without a device.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    // Remote-backed pipeline loads: CoreComicSource already IS a ComicSource, so the pipeline
    // takes it with zero production changes — this edge exists so tests can prove it.
    // Test-only, off the release classpath.
    testImplementation(project(":remote:core"))
}
