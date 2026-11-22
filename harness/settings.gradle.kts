pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement {
    repositories { mavenLocal(); mavenCentral(); google(); maven { url = uri("https://jitpack.io") } }
}
rootProject.name = "microg-harness"
