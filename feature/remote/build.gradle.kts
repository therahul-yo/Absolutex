plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}
android {
    namespace = "com.absolutex.feature.remote"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
    // Robolectric stands up android.jar resources so stringResource resolves on the JVM.
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":remote:core"))
    implementation(project(":remote:sync"))
    implementation(project(":remote:ftp"))
    implementation(project(":remote:smb"))
    // Direct use: the SMB connection tester maps smbj's own exception types.
    // Same Apache-2.0 artifact and version the :remote:smb module uses.
    implementation(libs.smbj)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.datastore.preferences)
    // Real in-process FTP server for the probe's behavioural mapping (same trio the
    // :remote:ftp module's own wire tests use). Test-only, off the release classpath.
    testImplementation(libs.ftpserver.core)
    testImplementation(libs.slf4j.api.ftp.test)
}
