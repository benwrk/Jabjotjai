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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The Polar BLE SDK is published only through JitPack, which builds artifacts from GitHub
        // on request. Polar's group is looked for there and nowhere else, and nothing else is looked
        // for there. A plain content filter would not do the first: "Filters declared using the
        // repository-level content filter are not exclusive" (Gradle 9.9.2 docs, "Filtering
        // repository content", read 2026-11-28).
        exclusiveContent {
            forRepository { maven { url = uri("https://jitpack.io") } }
            filter { includeGroup("com.github.polarofficial") }
        }
    }
}

rootProject.name = "Jabjotjai"
include(":app")
