plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chat.effect.gif.glide"
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

    api("com.github.bumptech.glide:glide:5.0.5")
    implementation("androidx.vectordrawable:vectordrawable-animated:1.1.0")
}

apply(from = "jitpack.gradle")
