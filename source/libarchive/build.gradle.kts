plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "com.absolutex.source.libarchive"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                // Size over micro-optimisation: page decode dwarfs archive extract cost.
                // JNIEXPORT marks the entry points default-visible, so -fvisibility=hidden
                // strips libarchive's internals without hiding the bridge.
                arguments += listOf("-DANDROID_STL=none", "-DCMAKE_BUILD_TYPE=Release")
                // Hardening, mirrored in src/main/cpp/CMakeLists.txt (source of truth for
                // non-Gradle builds): -Os keeps the .so small, hidden visibility strips
                // libarchive internals, protector+FORTIFY+format checks blunt memory bugs.
                cFlags += listOf(
                    "-Os",
                    "-fvisibility=hidden",
                    "-fstack-protector-strong",
                    "-D_FORTIFY_SOURCE=2",
                    "-Wformat", "-Werror=format-security",
                    "-Wall", "-Wextra",
                )
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
    implementation(project(":core:model"))
    implementation(project(":source:api"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
