plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chat.pagingutil"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
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

kotlin {
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_8)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_8)
    }
}

dependencies {
    // 内部实现
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.material:material:1.12.0")

    // 出现在 public API 签名(基类 / 函数参数 / 返回值),必须 api
    api("androidx.recyclerview:recyclerview:1.4.0")
    api("androidx.paging:paging-runtime-ktx:3.5.0")
    api("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    // PagingStateLayout extends StateLayout,业务方使用时需直接持有 StateLayout 类型
    api(project(":app:statelayout"))
}

apply(from = "jitpack.gradle")
