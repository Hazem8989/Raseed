
pluginManagement {
    repositories {
        google()
        // Google-hosted Maven Central mirror reduces transient 504s from upstream artifact delivery.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Keep Maven Central available as a fallback if the mirror is temporarily unavailable.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
    }
}

rootProject.name = "TheDailyShop"
include(":app")
