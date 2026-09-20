plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies {
    // Plain Kotlin/JVM, which is the payoff of moving HttpCall here in #48: the cloud lane's
    // logic unit-tests on the JVM in milliseconds with no emulator and no Android SDK, the
    // same argument :core:model and :source:api are built on.
    implementation(project(":remote:core"))
    testImplementation(libs.junit)
    // The 300 MB proof opens a real ZIP through CoreComicSource, whose public API names these.
    testImplementation(project(":core:model"))
    testImplementation(project(":source:api"))
}
