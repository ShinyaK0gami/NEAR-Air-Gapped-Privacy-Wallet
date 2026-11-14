pluginManagement {
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

rootProject.name = "nearair"

include(":cold-storage")
project(":cold-storage").projectDir = file("app")

include(":public-wallet")
project(":public-wallet").projectDir = file("public-wallet")

