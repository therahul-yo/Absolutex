plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "com.absolutex.core.media"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
    // Robolectric stands up the observer shell (ContentResolver/Handler) without a device.
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
dependencies {
    implementation(project(":core:scan"))
    implementation(project(":source:api"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
