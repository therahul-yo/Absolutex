plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "com.absolutex.remote.smb"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    // AGP 9 nests the Kotlin extension inside android; top-level kotlin{} is a build error.
    kotlin { jvmToolchain(21) }
}
dependencies {
    implementation(project(":core:model"))
    implementation(project(":source:api"))
    // SMB2/SMB3 client, Apache-2.0 (Central POM, 0.15.0). Transitives are permissive
    // (slf4j MIT, BouncyCastle, mbassador MIT, asn-one Apache-2.0) — no GPL/AGPL in the tree.
    implementation(libs.smbj)
    testImplementation(libs.junit)
}
