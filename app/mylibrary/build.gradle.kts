plugins {
    id("com.android.library")
}

android {
    namespace = "com.simple.mylibrary"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
        dataBinding = true
    }
}

dependencies {
    // 基础(只在内部用,不暴露到公共 API)
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("com.google.android.material:material:1.13.0")
    api(project(":app:statelayout"))
    // 在 public API 签名里出现 → 必须 api,业务方继承时能拿到类型
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    // ApiErrorHandler 用到 retrofit2.HttpException;HttpResult / 网络层 public API
    api("com.squareup.retrofit2:retrofit:3.0.0")
}

apply(from = "jitpack.gradle")
