pluginManagement {
    repositories {
        /*maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }*/
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
        /*maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }*/
        google()
        mavenCentral()
        // SVGAPlayer-Android 发布在 jitpack
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "My Application"
include(":app")
include(":app:mylibrary")
include(":app:pagingutil")
include(":app:spanutil")
include(":app:glidespan")
include(":app:svgspan")
include(":app:svgaspan")
include(":app:shapeview")
include(":app:statelayout")
include(":app:effect-core")
include(":app:effect-svga")
include(":app:effect-mp4-vap")
include(":app:effect-gif-glide")
