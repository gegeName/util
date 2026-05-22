plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lhj.glidespan"
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
    implementation("com.github.bumptech.glide:glide:5.0.5")
}

apply(from = "jitpack.gradle")
