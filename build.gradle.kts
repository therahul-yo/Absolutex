plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt)
}

// One detekt pass over every module's sources, applied only at the root.
//
// The alternative — applying detekt per module — creates one task per module *per build
// variant*, each re-parsing the same shared sources, and drags in detekt's Android
// integration, which needs the SDK. A single root task is roughly an order of magnitude
// less work and keeps CI device-free and SDK-free.
//
// The cost is that this runs without type resolution, so rules needing the compiler's
// type information stay off. That is an acceptable trade: the rules we actually care
// about here are naming, complexity and style, none of which need types.
detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(file("config/detekt/detekt.yml"))
    baseline.set(file("config/detekt/baseline.xml"))
    // Report paths become repo-relative, so a finding reads the same locally and in CI.
    basePath.set(layout.projectDirectory)
    // projectDir does not force a subproject to configure, so this stays cheap and
    // survives a module being added without anyone remembering to edit this list.
    source.setFrom(subprojects.map { it.projectDir.resolve("src") })
    failOnSeverity = dev.detekt.gradle.extensions.FailOnSeverity.Error
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    reports {
        sarif.required = true        // uploaded to GitHub code scanning by .github/workflows/ci.yml
        html.required = true         // the human-readable artifact on a failed run
        checkstyle.required = false  // nothing consumes it
        markdown.required = false
    }
}
