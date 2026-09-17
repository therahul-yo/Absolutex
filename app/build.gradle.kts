plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.absolutex"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.absolutex"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            isMinifyEnabled = true       // R8 full mode is the AGP default
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            // R8 and resource shrinking are inherited from release, on purpose. With them off,
            // cold start measured the unshrunk dex: opening it alone was 38 ms of bindApplication,
            // so every startup number overstated what ships. It also means the minified app —
            // the thing users get — actually runs on a device on every benchmark, which is the
            // only place a missing keep rule shows up. The benchmark APK drives the app through
            // UiAutomator by package and text only, so shrinking cannot break the harness.
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:scan"))
    implementation(project(":core:decode"))
    implementation(project(":source:api"))
    implementation(project(":source:libarchive"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:library"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:widget")) // manifest merge for the widget receiver

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Phase 4: navigation-compose removed — no NavHost/NavController import anywhere in
    // app/src (verified by grep); hilt-navigation-compose is KEPT, it provides hiltViewModel.
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.profileinstaller)   // §3: baseline profiles are mandatory
}
