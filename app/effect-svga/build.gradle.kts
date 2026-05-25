plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chat.effect.svga"
    compileSdk = 35

    defaultConfig {
        minSdk = 21

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
kotlin {
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_8)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_8)
    }
}

dependencies {
    api(project(":app:effect-core"))

    api("com.github.yyued:SVGAPlayer-Android:2.6.1") {
        exclude("org.jetbrains.kotlin", "kotlin-android-extensions-runtime")
    }
}

apply(from = "jitpack.gradle")
