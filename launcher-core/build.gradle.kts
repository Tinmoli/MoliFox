// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责配置核心模块依赖、测试和功能验收任务。

import java.time.Duration
import java.util.Properties

plugins { kotlin("jvm") }

tasks.processResources {
    from(rootProject.file("assets/backgrounds/default-backgrounds.zip")) { into("META-INF/molifox") }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Microsoft OAuth Client ID 不进仓库：优先环境变量，其次本机 local.properties。
val microsoftClientId =
    providers
        .environmentVariable("MOLIFOX_MS_CLIENT_ID")
        .orElse(providers.environmentVariable("MICROSOFT_CLIENT_ID"))
        .orElse(
            providers.provider {
                val local = rootProject.file("local.properties")
                if (!local.isFile) {
                    ""
                } else {
                    Properties()
                        .apply { local.inputStream().use(::load) }
                        .getProperty("microsoft.client.id", "")
                }
            },
        )
val microsoftIdentityDir = layout.buildDirectory.dir("generated/microsoftIdentity")
val writeMicrosoftIdentity =
    tasks.register("writeMicrosoftIdentity") {
        val destination = microsoftIdentityDir
        val clientId = microsoftClientId
        inputs.property("microsoftClientId", clientId)
        outputs.dir(destination)
        doLast {
            val file = destination.get().asFile.resolve("microsoft-identity.properties")
            file.parentFile.mkdirs()
            file.writeText("microsoft.client.id=" + clientId.get().trim() + "\n")
        }
    }
sourceSets.main { resources.srcDir(writeMicrosoftIdentity.map { microsoftIdentityDir }) }
// 测试源码统一放在仓库根的 tests/，模块内不再保留 src/test。
sourceSets.test {
    kotlin.srcDirs(rootProject.file("tests/launcher-core/test/kotlin"))
    resources.srcDir(rootProject.file("tests/launcher-core/test/resources"))
}

dependencies {
    implementation("com.materialkolor:material-color-utilities:4.0.5")
    // 动图背景解码（GIF/APNG/动态 WebP）；纯 Kotlin、Apache-2.0，不引入原生运行库。
    implementation("io.github.yuroyami:imagekodec:0.3.0")
    // 资源目录模型把 JsonObject 暴露在公开签名里，界面层需要能引用 Gson 的解析异常类型。
    api("com.google.code.gson:gson:2.13.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tomlj:tomlj:1.1.1")
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

tasks.register<JavaExec>("encyclopediaSmoke") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.molifox.core.EncyclopediaOnlineSmoke")
    args(rootProject.file("build/validation/encyclopedia-online.json").absolutePath)
    timeout.set(Duration.ofSeconds(30))
}

tasks.register<JavaExec>("networkDiagnosticsSmoke") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.molifox.core.NetworkDiagnosticsSmokeKt")
    systemProperty("java.net.useSystemProxies", "true")
    args(rootProject.file("build/validation/network-diagnostics.json").absolutePath)
    timeout.set(Duration.ofMinutes(2))
}

tasks.register("writeGameSmokeClasspath") {
    group = "verification"
    description = "Export the real-game harness classpath for the project-local Linux check"
    dependsOn(tasks.testClasses)
    doLast {
        rootProject.file(".dev/work/build-tmp/game-smoke-classpath.txt").apply {
            parentFile.mkdirs()
            writeText(
                sourceSets.test
                    .get()
                    .runtimeClasspath.asPath,
            )
        }
    }
}

tasks.register<JavaExec>("gameSmoke") {
    group = "verification"
    description =
        "Opt-in real Minecraft install and main-menu acceptance; requires visual review and a desktop"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.molifox.core.RealGameSmokeKt")
    systemProperty("stdout.encoding", "UTF-8")
    systemProperty("stderr.encoding", "UTF-8")
    systemProperty("java.net.useSystemProxies", "true")
    systemProperty(
        "molifox.game.version",
        providers.gradleProperty("gameVersion").getOrElse("1.20.1"),
    )
    systemProperty(
        "molifox.game.loader",
        providers.gradleProperty("gameLoader").getOrElse("Vanilla"),
    )
    systemProperty(
        "molifox.game.loaderVersion",
        providers.gradleProperty("gameLoaderVersion").getOrElse(""),
    )
    systemProperty("molifox.game.seedRoot", providers.gradleProperty("gameSeedRoot").getOrElse(""))
    systemProperty("molifox.game.javaPath", providers.gradleProperty("gameJavaPath").getOrElse(""))
    systemProperty(
        "molifox.game.downloadSource",
        providers.gradleProperty("gameDownloadSource").getOrElse("official"),
    )
    systemProperty(
        "molifox.game.windowMode",
        providers.gradleProperty("gameWindowMode").getOrElse("custom"),
    )
    systemProperty(
        "molifox.game.report",
        rootProject
            .file(
                "build/validation/real-game-${System.getProperty(
                    "os.name",
                ).lowercase().substringBefore(
                    ' ',
                )}-${providers.gradleProperty("gameLoader").getOrElse("Vanilla").lowercase()}.json",
            ).absolutePath,
    )
}

tasks.register<JavaExec>("onlineSmoke") {
    group = "verification"
    description = "Read-only connectivity checks against game, loader, and Modrinth APIs"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.molifox.core.OnlineSmokeKt")
    systemProperty("java.net.useSystemProxies", "true")
    systemProperty("stdout.encoding", "UTF-8")
    systemProperty("stderr.encoding", "UTF-8")
    args(
        rootProject.layout.projectDirectory
            .dir("tests/.tmp/launcher-core-onlineSmoke/profile")
            .asFile
            .absolutePath,
    )
}

tasks.register<JavaExec>("contentSmoke") {
    group = "verification"
    description = "Opt-in public Modrinth hash/version/update check; never executes downloaded mods"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.molifox.core.ContentOnlineSmokeKt")
    systemProperty("java.net.useSystemProxies", "true")
    systemProperty("stdout.encoding", "UTF-8")
    systemProperty("stderr.encoding", "UTF-8")
    systemProperty(
        "molifox.content.report",
        rootProject.file("build/validation/content-online.json").absolutePath,
    )
}

tasks.register<JavaExec>("reportedIssuesSmoke") {
    group = "verification"
    description = "Read-only external profile search and official skin CDN acceptance"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.molifox.core.ReportedIssuesSmokeKt")
    systemProperty("java.net.useSystemProxies", "true")
    systemProperty("stdout.encoding", "UTF-8")
    systemProperty("stderr.encoding", "UTF-8")
    systemProperty(
        "molifox.reference.games",
        providers.gradleProperty("referenceGameRoot").getOrElse(""),
    )
    systemProperty(
        "molifox.reference.config",
        providers.gradleProperty("referenceLauncherConfig").getOrElse(""),
    )
    systemProperty(
        "molifox.issues.report",
        rootProject.file("build/validation/reported-issues-online.json").absolutePath,
    )
}

tasks.register<JavaExec>("accountSmoke") {
    group = "verification"
    description =
        "Explicit interactive Microsoft login, restart/refresh and local sign-out acceptance"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.molifox.core.AccountOnlineSmokeKt")
    timeout.set(Duration.ofMinutes(17))
    systemProperty("java.net.useSystemProxies", "true")
    systemProperty("stdout.encoding", "UTF-8")
    systemProperty("stderr.encoding", "UTF-8")
    systemProperty(
        "molifox.account.report",
        rootProject.file("build/validation/account-online.json").absolutePath,
    )
}
