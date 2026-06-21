/*
 * Maven Central（Sonatype OSSRH）发布脚本，仅用于本模块。
 *
 * ── 一次性准备 ───────────────────────────────────────────────────────────────
 * 1) 在 https://issues.sonatype.org 申请 groupId（io.github.<你的 GitHub 用户名>
 *    通常一两小时即审批通过；自有域名需 DNS TXT 验证）。
 * 2) 生成 GPG 密钥并把公钥推送到 keys.openpgp.org / keyserver.ubuntu.com。
 * 3) 在 ~/.gradle/gradle.properties（**不要**放进项目内）填入：
 *
 *      ossrhUsername=<Sonatype 用户名>
 *      ossrhPassword=<Sonatype 密码或 User Token>
 *
 *      # 二选一：CI 推荐 in-memory（ASCII armor 整段私钥，换行用 \n）
 *      signing.keyId=ABCDEF12
 *      signing.password=<GPG passphrase>
 *      signing.key=-----BEGIN PGP PRIVATE KEY BLOCK-----\n...\n-----END PGP PRIVATE KEY BLOCK-----
 *
 *      # 或本机用 keyring 文件
 *      # signing.keyId=ABCDEF12
 *      # signing.password=<GPG passphrase>
 *      # signing.secretKeyRingFile=/Users/you/.gnupg/secring.gpg
 *
 *    CI 也可改用环境变量：OSSRH_USERNAME / OSSRH_PASSWORD / SIGNING_KEY_ID /
 *    SIGNING_KEY / SIGNING_PASSWORD（脚本内部按 property → env 顺序兜底）。
 *
 * ── 常用任务 ────────────────────────────────────────────────────────────────
 *   ./gradlew :ui-foundation:publishToMavenLocal           # 本地验证（~/.m2/repository）
 *   ./gradlew :ui-foundation:publishReleasePublicationToOSSRHRepository
 *                                                       # 发到 OSSRH（version 含 -SNAPSHOT 自动走 snapshot 仓库）
 *
 * ── 发布到 Maven Central（非 SNAPSHOT 版本） ─────────────────────────────────
 * Sonatype 的 staging 关闭 / release 流程没有放进本脚本，去
 *   https://s01.oss.sonatype.org → Staging Repositories
 * 手动 Close → Release，约 10~30 分钟同步到 search.maven.org。
 * 想把 close/release 也自动化，再去根 build.gradle.kts 加
 *   id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
 * （需要在根项目而非模块，所以脚本里没引）
 */

import com.android.build.gradle.LibraryExtension

apply(plugin = "maven-publish")
apply(plugin = "signing")

// ─── 坐标（按需修改）─────────────────────────────────────────────────────────
val publishGroupId = "io.github.lhjgege"   // TODO: 替换为你的 GitHub 用户名
val publishArtifactId = "ui-foundation"
val publishVersion = "0.1.0"

// ─── POM 元数据（Maven Central 强制字段）──────────────────────────────────────
val publishName = publishArtifactId
val publishDescription = "TODO: 一句话描述这个库做什么"
val publishGithubRepo = "lhjgege/$publishArtifactId"        // TODO
val publishUrl = "https://github.com/$publishGithubRepo"
val publishDeveloperId = "lhjgege"                          // TODO
val publishDeveloperName = "util"
val publishDeveloperEmail = "TODO@example.com"

// ─── 凭据：先读 gradle property，再回退环境变量；都没有则空串（仅本地构建会跳过签名）──
fun prop(propertyKey: String, envKey: String = propertyKey.replace('.', '_').uppercase()): String? =
    (findProperty(propertyKey) as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

// ─── 让 AGP release variant 同时打 sources / javadoc jar ─────────────────────
extensions.configure<LibraryExtension>("android") {
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// ─── publishing & signing 配置 ──────────────────────────────────────────────
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
                    name.set(publishName)
                    description.set(publishDescription)
                    url.set(publishUrl)
                    inceptionYear.set("2025")

                    licenses {
                        license {
                            name.set("The Apache Software License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set(publishDeveloperId)
                            name.set(publishDeveloperName)
                            email.set(publishDeveloperEmail)
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/$publishGithubRepo.git")
                        developerConnection.set("scm:git:ssh://github.com:$publishGithubRepo.git")
                        url.set(publishUrl)
                    }
                    issueManagement {
                        system.set("GitHub Issues")
                        url.set("$publishUrl/issues")
                    }
                }
            }
        }

        repositories {
            maven {
                name = "OSSRH"
                val isSnapshot = publishVersion.endsWith("-SNAPSHOT")
                setUrl(
                    if (isSnapshot)
                        "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                    else
                        "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                )
                credentials {
                    username = prop("ossrhUsername", "OSSRH_USERNAME").orEmpty()
                    password = prop("ossrhPassword", "OSSRH_PASSWORD").orEmpty()
                }
            }
        }
    }

    extensions.configure<SigningExtension>("signing") {
        val signingKeyId = prop("signing.keyId")
        val signingKey = prop("signing.key")              // ASCII-armored 私钥（CI 推荐）
        val signingPassword = prop("signing.password")

        when {
            // CI / 无本地 keyring：in-memory key
            signingKeyId != null && signingKey != null && signingPassword != null -> {
                useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
                isRequired = true
            }
            // 本机 keyring：插件自动读 signing.keyId / signing.password / signing.secretKeyRingFile
            signingKeyId != null && signingPassword != null -> {
                isRequired = true
            }
            // 凭据未配齐：跳过签名（仅 publishToMavenLocal 等本地任务可用；远端发布会被 Central 拒收）
            else -> {
                isRequired = false
                logger.warn("[publish.gradle.kts] signing 凭据未配齐，已禁用签名；发到 Maven Central 必须签名！")
            }
        }

        val publishing = extensions.getByName("publishing") as PublishingExtension
        sign(publishing.publications["release"])
    }
}
