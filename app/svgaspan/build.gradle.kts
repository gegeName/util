plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lhj.svgaspan"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    api(project(":app:spanutil"))
    implementation("com.github.yyued:SVGAPlayer-Android:2.6.1") {
        exclude("org.jetbrains.kotlin", "kotlin-android-extensions-runtime")
    }
}

apply(from = "jitpack.gradle")
