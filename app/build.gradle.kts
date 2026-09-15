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
            // Minification off for now: frame timing is unaffected by R8, and keeping it on
            // would need a full keep-rule pass for Hilt/Room/Compose before any number could
            // be trusted. TODO(phase9): turn on once R8 rules land, then re-baseline startup —
            // startup numbers from this variant are NOT production-representative.
            isMinifyEnabled = false
            isShrinkResources = false
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
    implementation(project(":core:decode"))
    implementation(project(":source:api"))
    implementation(project(":source:libarchive"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:widget")) // manifest merge for the widget receiver

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Phase 4: navigation-compose removed — no NavHost/NavController import anywhere in
    // app/src (verified by grep); hilt-navigation-compose is KEPT, it provides hiltViewModel.
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.profileinstaller)   // §3: baseline profiles are mandatory
}
