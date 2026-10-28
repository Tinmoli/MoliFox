// 负责配置核心模块依赖、测试和功能验收任务。

import java.time.Duration

plugins { kotlin("jvm") }

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
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
    args(rootProject.file("docs/validation/encyclopedia-online.json").absolutePath)
    timeout.set(Duration.ofSeconds(30))
}

tasks.register<JavaExec>("networkDiagnosticsSmoke") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.molifox.core.NetworkDiagnosticsSmokeKt")
    systemProperty("java.net.useSystemProxies", "true")
    args(rootProject.file("docs/validation/network-diagnostics.json").absolutePath)
    timeout.set(Duration.ofMinutes(2))
}

tasks.register("writeGameSmokeClasspath") {
    group = "verification"
    description = "Export the real-game harness classpath for the project-local Linux check"
    dependsOn(tasks.testClasses)
    doLast {
        rootProject.file(".work/build-tmp/game-smoke-classpath.txt").apply {
            parentFile.mkdirs()
            writeText(sourceSets.test.get().runtimeClasspath.asPath)
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
                "docs/validation/real-game-${System.getProperty("os.name").lowercase().substringBefore(' ')}-${providers.gradleProperty("gameLoader").getOrElse("Vanilla").lowercase()}.json"
            )
            .absolutePath,
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
            .dir(".work/tests/launcher-core-onlineSmoke/profile")
            .asFile
            .absolutePath
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
        rootProject.file("docs/validation/content-online.json").absolutePath,
    )
}

tasks.register<JavaExec>("mrpackSmoke") {
    group = "verification"
    description =
        "Opt-in public mrpack round trip and local HMCL parser check; never starts a GUI or game"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.molifox.core.MrpackOnlineSmokeKt")
    systemProperty("java.net.useSystemProxies", "true")
    systemProperty("stdout.encoding", "UTF-8")
    systemProperty("stderr.encoding", "UTF-8")
    systemProperty("molifox.mrpack.hmcl", providers.gradleProperty("hmclReference").getOrElse(""))
    systemProperty(
        "molifox.mrpack.report",
        rootProject.file("docs/validation/mrpack-online.json").absolutePath,
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
        rootProject.file("docs/validation/reported-issues-online.json").absolutePath,
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
        rootProject.file("docs/validation/account-online.json").absolutePath,
    )
}
