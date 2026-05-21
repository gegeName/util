plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lhj.spanutil"
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
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.github.bumptech.glide:glide:5.0.5")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation("com.github.yyued:SVGAPlayer-Android:2.6.1")
}

apply(from = "jitpack.gradle")
