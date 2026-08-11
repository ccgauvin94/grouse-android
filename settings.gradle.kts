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
        // Native roam transport (.aar + jna), published from the
        // grouse-roam-core repo's maven-repo branch (raw.githubusercontent).
        maven { url = uri("https://raw.githubusercontent.com/ccgauvin94/grouse-roam-core/maven-repo") }
    }
}
rootProject.name = "grouse-android"
include(":app")
