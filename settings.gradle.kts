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

rootProject.name = "daily-care-companion"

// Pure-Kotlin decision logic. It is its own build so it can be compiled and
// tested without the Android SDK: ./gradlew -p core test
includeBuild("core")

include(":app")
