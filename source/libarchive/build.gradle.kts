plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "com.absolutex.source.libarchive"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk { abiFilters += "arm64-v8a" }
    }
    // TODO(phase2): externalNativeBuild + CMakeLists wiring libarchive. Not yet present — see gate report.
    ndkVersion = libs.versions.ndk.get()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":source:api"))
}
