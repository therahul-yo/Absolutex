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
    packaging {
        resources {
            // BouncyCastle arrives via smbj, but these resource tables are for code paths
            // SMB never calls: PQC (picnic) lookup tables and X.509 reviewer messages.
            // ~1.2 MiB of APK weight with zero handshake coverage — see the wiring PR.
            // If smbj ever needs them, the failure is a loud MissingResourceException at
            // handshake time, and the live-handshake test in :remote:smb proves it works.
            excludes += "org/bouncycastle/pqc/**"
            excludes += "org/bouncycastle/x509/CertPathReviewerMessages*.properties"
        }
    }
    buildFeatures { compose = true }
    // Release signing config (§5.1): keystore from env/properties, never committed.
    signingConfigs {
        create("release") {
            val storeFilePath = System.getenv("KEYSTORE_FILE") ?: findProperty("RELEASE_STORE_FILE")?.let { (it as String?)?.let { p -> file(p) } }?.absolutePath
            val storePass = System.getenv("KEYSTORE_PASSWORD") ?: (findProperty("RELEASE_STORE_PASSWORD") as String?)
            val alias = System.getenv("KEY_ALIAS") ?: (findProperty("RELEASE_KEY_ALIAS") as String?)
            val keyPass = System.getenv("KEY_PASSWORD") ?: (findProperty("RELEASE_KEY_PASSWORD") as String?)
            if (storeFilePath != null && storePass != null && alias != null && keyPass != null) {
                storeFile = file(storeFilePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true       // R8 full mode is the AGP default
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            if (release.storeFile != null) signingConfig = release
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
    // Sync wiring (milestone 5): installs SyncModule into the app graph. Inert until the
    // trigger call sites fire — no work starts from the dependency alone.
    implementation(project(":remote:sync"))
    // Remote file access (milestone 5): the servers UI plus the transports it tests and
    // opens, and the RemoteModule binding ReaderViewModel now injects to open a remote book.
    // First time smbj and commons-net enter the release APK — see the size note in the
    // wiring PR; the ceiling decision belongs to the lead, not this dependency.
    implementation(project(":feature:remote"))
    implementation(project(":remote:core"))
    implementation(project(":remote:smb"))
    implementation(project(":remote:ftp"))
    implementation(project(":feature:widget")) // manifest merge for the widget receiver

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Phase 4: navigation-compose removed — no NavHost/NavController import anywhere in
    // app/src (verified by grep); hilt-navigation-compose is KEPT, it provides hiltViewModel.
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.navigation.compose)
    // Shared-element cover flight: the SharedTransitionLayout both the library grid and the
    // reader sheet compose under. BOM-managed.
    implementation(libs.compose.animation)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.profileinstaller)   // §3: baseline profiles are mandatory
}
