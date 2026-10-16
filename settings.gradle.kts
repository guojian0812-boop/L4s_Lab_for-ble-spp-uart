pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}
rootProject.name = "Look4Sat"
include(":app")
include(
    ":core:data",
    ":core:domain",
    ":core:presentation"
)
include(
    ":feature:map",
    ":feature:passes",
    ":feature:radar",
    ":feature:satellites",
    ":feature:settings",
    ":feature:status"
)
