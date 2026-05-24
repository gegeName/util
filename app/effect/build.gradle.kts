plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chat.effect"
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")

    // 协程：EffectDownloader / EffectManager 用 CoroutineScope/launch/async/Semaphore
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp：DownloadClient 内部默认实例，业务方可通过 DownloadClient.setSharedClient 注入自己的
    api("com.squareup.okhttp3:okhttp:5.3.2")

    // SVGA：SvgaEffectPlayer
    implementation("com.github.yyued:SVGAPlayer-Android:2.6.1") {
        exclude("org.jetbrains.kotlin", "kotlin-android-extensions-runtime")
    }

    // Glide：GifEffectPlayer
    implementation("com.github.bumptech.glide:glide:5.0.5")

    // 腾讯 VAP（alpha-MP4）：Mp4EffectPlayer
    implementation("io.github.tencent:vap:2.0.28") {
        exclude("org.jetbrains.kotlin", "kotlin-android-extensions-runtime")
    }
}

apply(from = "jitpack.gradle")