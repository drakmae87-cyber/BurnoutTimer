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

rootProject.name = "BurnoutTimer"
include(":client-app", ":admin-app", ":data", ":domain", ":policy", ":services")

project(":client-app").projectDir = file("app")
