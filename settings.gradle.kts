pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
    plugins {
        id("com.android.application") version "8.5.0"
        id("com.android.library") version "8.5.0"
        id("org.jetbrains.kotlin.android") version "1.9.0"
        id("com.google.devtools.ksp") version "1.9.0-1.0.13" apply false
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libxposed API 102 is published to Maven Central; the legacy xposed.info repo is gone.
    }
}
rootProject.name = "贴吧TS"
include(":app")
