plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.absolutex.remote.ftp"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
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
    // FTP/FTPS wire protocol. Apache-2.0, version pinned in the catalog (see its note).
    implementation(libs.commons.net)
    testImplementation(libs.junit)
    // Real in-process FTP server for CommonsNetFtpTransportRealServerTest — see the
    // libs.versions.toml note on ftpserverCore for why this library over MockFtpServer.
    testImplementation(libs.ftpserver.core)
    testImplementation(libs.mina.core)
    testImplementation(libs.slf4j.api.ftp.test)
}
