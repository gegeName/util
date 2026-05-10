/*
 * JitPack 发布脚本，仅用于本模块。
 *
 * ── 工作原理 ────────────────────────────────────────────────────────────────
 * JitPack（https://jitpack.io）按需从 GitHub 拉 tag/commit 构建。
 * 服务器执行的命令大致是：
 *   ./gradlew :app:mylibrary:publishToMavenLocal
 * 然后把 ~/.m2/repository 下产出的 aar/pom/sources/javadoc 收走对外提供。
 * 因此本脚本只配 maven-publish，不需要登录凭据，也不需要签名。
 *
 * ── 使用方 ──────────────────────────────────────────────────────────────────
 *   // settings.gradle.kts
 *   dependencyResolutionManagement {
 *       repositories { maven { url = uri("https://jitpack.io") } }
 *   }
 *   // build.gradle.kts
 *   implementation("com.github.<GITHUB_USER>:<REPO_NAME>:<TAG>")
 *   // 多模块仓库时 artifactId 要写模块名：
 *   implementation("com.github.<GITHUB_USER>.<REPO_NAME>:mylibrary:<TAG>")
 *
 * ── 发布步骤 ────────────────────────────────────────────────────────────────
 * 1) 把代码推到 GitHub。
 * 2) 打 tag 并推送：
 *      git tag 0.1.0 && git push origin 0.1.0
 *    （或在 GitHub Releases 页面发一个 Release）
 * 3) 打开 https://jitpack.io/#<GITHUB_USER>/<REPO_NAME>，点 "Get it" 触发首次构建；
 *    构建日志在页面上能直接看，绿色对勾即成功。
 *
 * ── 本地验证 ────────────────────────────────────────────────────────────────
 *   ./gradlew :app:mylibrary:publishToMavenLocal
 *   # 产物在 ~/.m2/repository/com/github/<GITHUB_USER>/<REPO_NAME>/<VERSION>/
 *
 * ── 启用方式 ────────────────────────────────────────────────────────────────
 * 在 app/mylibrary/build.gradle.kts 末尾加：
 *   apply(from = "jitpack.gradle.kts")
 */

import com.android.build.gradle.LibraryExtension

apply(plugin = "maven-publish")

// ─── 坐标 ───────────────────────────────────────────────────────────────────
// JitPack 的 groupId 固定是 com.github.<GITHUB_USER>
// JitPack 服务器会用 -PVERSION_NAME=<tag> 覆盖版本，本地构建时用下面默认值
val publishGroupId = "com.github.gegeName"          // TODO: 替换为你的 GitHub 用户名/组织
val publishArtifactId = "mylibrary"
val publishVersion = (findProperty("VERSION_NAME") as? String) ?: "0.1.0-LOCAL"

// ─── 让 AGP release variant 同时打 sources / javadoc jar ─────────────────────
extensions.configure<LibraryExtension>("android") {
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// ─── publishing 配置 ────────────────────────────────────────────────────────
// 必须 afterEvaluate，等 AGP 生成 components["release"] 之后再 from(...)
afterEvaluate {
    extensions.configure<PublishingExtension>("publishing") {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                groupId = publishGroupId
                artifactId = publishArtifactId
                version = publishVersion

                pom {
                    name.set(publishArtifactId)
                    description.set("TODO: 一句话描述这个库做什么")
                    url.set("https://github.com/gegeName/$publishArtifactId")    // TODO

                    licenses {
                        license {
                            name.set("The Apache Software License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("gegeName")           // TODO
                            name.set("util")
                        }
                    }
                    scm {
                        url.set("https://github.com/gegeName/$publishArtifactId") // TODO
                    }
                }
            }
        }
        // 不配 repositories：JitPack 只用 publishToMavenLocal，发到 ~/.m2/repository 即可
    }
}
