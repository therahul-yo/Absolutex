plugins {
    alias(libs.plugins.android.test)
}
android {
    namespace = "com.absolutex.benchmark"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { create("benchmark") { isDebuggable = false } }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
}
dependencies {
    implementation(libs.androidx.benchmark.macro)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
    implementation(libs.junit)
}
