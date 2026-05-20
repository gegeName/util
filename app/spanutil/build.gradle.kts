plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.lhj.spanutil"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation("com.google.android.material:material:1.13.0")
    api("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    api("com.squareup.okhttp3:okhttp:5.3.2")
    api("com.github.bumptech.glide:glide:5.0.5")
    api("com.caverock:androidsvg-aar:1.4")
    api("com.github.yyued:SVGAPlayer-Android:2.6.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}