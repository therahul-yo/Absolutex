plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "com.absolutex.core.gpu"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
    // Robolectric needs android.jar resources to stand up Bitmap/Matrix under the JVM.
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
