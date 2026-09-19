plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
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
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    // api, not implementation: HttpCall is in this module's public API — SyncModule provides
    // it and the clients take it as a constructor parameter — so every consumer compiling
    // against :remote:sync needs it on their compile classpath. :app in particular never
    // names HttpCall itself, but Hilt generates its component there against that binding.
    api(project(":remote:core"))
    implementation(libs.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    // Still zero shipped third-party dependencies: HTTP is HttpURLConnection, JSON is org.json
    // (both platform); Hilt/Room/DataStore/coroutines already ship in the app (see apkanalyzer
    // note in the wiring PR). JUnit + Robolectric + MockWebServer are test-only.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockwebserver3)
    testImplementation(libs.kotlinx.coroutines.test)
}
