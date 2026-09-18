pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "UbuntuForAndroid"

include(":app")
include(":core-ui")
include(":core-data")
include(":core-proot")
include(":core-rootfs")
include(":core-vnc")
include(":feature-onboarding")
include(":feature-terminal")
include(":feature-desktop")
include(":feature-appstore")
