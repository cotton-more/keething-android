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
    }
}

rootProject.name = "keepass-bar-android"

include(
    ":core:domain",
    ":core:totp",
    ":core:search",
    ":core:kdbx",
    ":core:vault",
    ":app",
)
