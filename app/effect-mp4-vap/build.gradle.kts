plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chat.effect.mp4.vap"
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

    api("io.github.tencent:vap:2.0.28") {
        exclude("org.jetbrains.kotlin", "kotlin-android-extensions-runtime")
    }
}

apply(from = "jitpack.gradle")
