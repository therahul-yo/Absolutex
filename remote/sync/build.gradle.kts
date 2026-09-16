plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.absolutex.remote.sync"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
    // Robolectric stands up android.jar resources so org.json parses for real on the JVM.
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
dependencies {
    // Zero shipped dependencies: HTTP is HttpURLConnection, JSON is org.json (both platform).
    // JUnit + Robolectric are test-only and already used by :core:data — nothing new ships.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
