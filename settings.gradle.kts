rootProject.name = "benefits"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":connector:extensions:benefits-policy")
include(":connector:extensions:benefits-privacy")
include(":connector:extensions:benefits-audit")
include(":connector:runtime")
include(":experiments:harness:e3")
