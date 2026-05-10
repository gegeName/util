plugins {
    id("com.android.library")
    `maven-publish`
}

android {
    namespace = "com.simple.mylibrary"
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

    buildFeatures {
        viewBinding = true
        dataBinding = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("com.google.android.material:material:1.13.0")
    api("androidx.constraintlayout:constraintlayout:2.2.1")
    api("androidx.recyclerview:recyclerview:1.4.0")
    api("androidx.paging:paging-runtime-ktx:3.5.0")
    api("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    api("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")

    api("com.squareup.retrofit2:retrofit:3.0.0")
    api("com.squareup.retrofit2:converter-gson:3.0.0")
    api("com.squareup.okhttp3:logging-interceptor:5.3.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

// ─── JitPack 发布配置 ───────────────────────────────────────────────────────
// JitPack 服务器执行： ./gradlew :app:mylibrary:publishToMavenLocal -PVERSION_NAME=<tag>
// 本地验证： ./gradlew :app:mylibrary:publishToMavenLocal
// 使用方坐标（多模块仓库注意点号）： com.github.<USER>.<REPO>:mylibrary:<TAG>
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.gegeName"
                artifactId = "mylibrary"
                version = (findProperty("VERSION_NAME") as? String) ?: "0.1.0-LOCAL"

                pom {
                    name.set("mylibrary")
                    description.set("TODO: 一句话描述这个库做什么")
                    url.set("https://github.com/gegeName/mylibrary")    // TODO

                    licenses {
                        license {
                            name.set("The Apache Software License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("gegeName")
                            name.set("util")
                        }
                    }
                    scm {
                        url.set("https://github.com/gegeName/mylibrary")
                    }
                }
            }
        }
    }
}
