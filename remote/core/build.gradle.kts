plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core:model"))
    implementation(project(":source:api"))
    testImplementation(libs.junit)
    // runTest for the suspending opener; test-only, off the release classpath (Apache-2.0).
    testImplementation(libs.kotlinx.coroutines.test)
}
