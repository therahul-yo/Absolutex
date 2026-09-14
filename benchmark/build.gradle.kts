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
    buildTypes {
        create("benchmark") {
            isDebuggable = false
            // The test APK still has to install. Without a signing config a non-debug build
            // type produces an unsigned APK and the install fails before anything runs.
            signingConfig = signingConfigs.getByName("debug")
            // :app's benchmark type is initWith(release); this keeps variant matching on the
            // release side for any dependency that has no benchmark variant of its own.
            matchingFallbacks += listOf("release")
        }
    }
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
