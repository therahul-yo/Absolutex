pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*|com\\.google.*|androidx.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google { content { includeGroupByRegex("com\\.android.*|com\\.google.*|androidx.*") } }
        mavenCentral()
    }
}

rootProject.name = "Absolutex"

include(":app")
include(":core:model")
include(":core:ui")
include(":core:data")
include(":core:decode")
include(":source:api")
include(":source:libarchive")
include(":source:pdf")
include(":feature:reader")
include(":benchmark")
