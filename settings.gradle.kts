pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "papermc"
        }
    }
}

rootProject.name = "OpenOneBlock"

include(
    "openoneblock-api",
    "openoneblock-core",
    "openoneblock-paper",
    "openoneblock-scripting",
    "openoneblock-protection",
    "openoneblock-persistence-sql",
    "openoneblock-structures-worldedit",
    "openoneblock-integrations",
    "openoneblock-admin-tools",
)
