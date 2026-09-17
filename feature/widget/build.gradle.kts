plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.absolutex.feature.widget"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
    // Robolectric stands up the provider and Room on the JVM; resources stay on for the provider-XML test.
    testOptions { unitTests { isIncludeAndroidResources = true } }
}
dependencies {
    implementation(project(":core:data"))
    // Compose BOM alignment, matching the other Compose feature modules.
    implementation(platform(libs.compose.bom))
    implementation(libs.glance.appwidget) {
        // Glance lists WorkManager only for actionRunCallback, which this widget never uses
        // (refresh is a broadcast; updatePeriodMillis=0). Measured in PR #19: WorkManager drags
        // in a protobuf runtime and its own Room job database — the bulk of the widget's dex cost.
        exclude(group = "androidx.work")
    }
    implementation(libs.hilt.android) // EntryPointAccessors only; Hilt codegen stays in :app.
    implementation(libs.kotlinx.coroutines.android)

    // Room on the JVM via Robolectric, mirroring :core:data: DAO tests without a device.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    // Renders Glance compositions to a node tree on the JVM; the tap-intent regression above shipped green without it.
    testImplementation(libs.glance.appwidget.testing)
}
