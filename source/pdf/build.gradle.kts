plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "com.absolutex.source.pdf"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                // ANDROID_STL=none: libpdfium.so links its own C++ runtime statically and
                // exports a pure C API, so this wrapper needs no STL — same as :source:libarchive.
                // -Os for the same reason as there: this bridge marshals arguments, the work
                // happens inside PDFium. JNIEXPORT keeps the entry points visible under
                // -fvisibility=hidden (verified: the object exports only the six native methods).
                arguments += listOf("-DANDROID_STL=none", "-DCMAKE_BUILD_TYPE=Release")
                cFlags += listOf("-Os", "-fvisibility=hidden")
            }
        }
    }
    ndkVersion = libs.versions.ndk.get()
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
}
dependencies {
    // Deliberately no :source:api or :core:model dependency — see RenderedPageSource.kt for
    // the two changes those modules need before a PDF can be modelled as a ComicSource.
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
