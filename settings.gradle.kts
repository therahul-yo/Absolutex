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
include(":core:gpu")
include(":core:scan")
include(":core:thumbnails")
include(":source:api")
include(":source:libarchive")
include(":source:pdf")
include(":remote:core")
include(":remote:smb")
include(":feature:library")
include(":feature:reader")
include(":feature:settings")
include(":feature:remote")
include(":feature:widget")
include(":benchmark")
include(":remote:ftp")
include(":remote:sync")
include(":remote:cloud")
