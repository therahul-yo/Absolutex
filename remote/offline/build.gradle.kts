plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies {
    // Plain Kotlin/JVM, the same argument as :remote:cloud: copying bytes to a file needs
    // nothing from Android, so this unit-tests in milliseconds against a temp directory with
    // no emulator and no SDK. Where the directory lives is an Android question and belongs to
    // the layer that calls this, not to the copy engine.
    implementation(project(":remote:core"))
    testImplementation(libs.junit)
}
