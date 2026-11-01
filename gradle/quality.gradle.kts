// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 静态检查独立运行，避免把分析器及其编译器依赖带进启动器发行包。

import org.gradle.api.attributes.Bundling
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile

val ktlintClasspath = configurations.create("qualityKtlint")
val detektClasspath = configurations.create("qualityDetekt")
val detektPlugins = configurations.create("qualityDetektPlugins")
listOf(ktlintClasspath, detektClasspath).forEach {
    it.attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
}
val localKtlintJar = providers.gradleProperty("qualityKtlintJar")
val localDetektJar = providers.gradleProperty("qualityDetektJar")

dependencies {
    add(ktlintClasspath.name, "com.pinterest.ktlint:ktlint-cli:1.7.1")
    add(detektClasspath.name, "dev.detekt:detekt-cli:2.0.0-alpha.6")
    add(detektPlugins.name, "dev.detekt:detekt-rules-ktlint-wrapper:2.0.0-alpha.6")
    add(detektPlugins.name, "io.nlopez.compose.rules:detekt:0.6.8")
}

val qualitySourcePatterns =
    listOf(
        "launcher-core/src/**/*.kt",
        "desktop-app/src/**/*.kt",
        "launcher-bootstrap/src/**/*.kt",
        "multiplayer-core/src/**/*.kt",
        "tests/**/*.kt",
        "*.gradle.kts",
        "*/build.gradle.kts",
        "gradle/**/*.gradle.kts",
    )
val qualityExcludedPatterns =
    listOf(
        ".dev/**",
        ".gradle/**",
        ".minecraft/**",
        "tests/.tmp/**",
        "MoliFox/**",
        "**/build/**",
    )
val qualitySources =
    fileTree(projectDir) {
        qualitySourcePatterns.forEach(::include)
        qualityExcludedPatterns.forEach(::exclude)
    }
val qualityReports = layout.buildDirectory.dir("reports/quality")
val styleToolClasspath =
    if (localKtlintJar.isPresent) {
        files(
            localKtlintJar.get(),
        )
    } else {
        ktlintClasspath
    }
val styleToolClasses = layout.buildDirectory.dir("quality/classes")
val compileStyleTool =
    tasks.register<JavaCompile>("compileStyleTool") {
        source(fileTree("tests/tools/style") { include("*.java") })
        classpath = styleToolClasspath
        destinationDirectory.set(styleToolClasses)
        options.encoding = "UTF-8"
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.release.set(17)
    }

fun registerKtlintTask(
    taskName: String,
    formatSources: Boolean,
) = tasks.register<JavaExec>(taskName) {
    group = "verification"
    description =
        if (formatSources) {
            "Format Kotlin sources using the repository rules"
        } else {
            "Check all Kotlin formatting"
        }
    dependsOn(compileStyleTool)
    classpath = files(styleToolClasses, styleToolClasspath)
    mainClass.set("KotlinStyleMain")
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "-Dlogback.configurationFile=${file("config/quality-logback.xml").absolutePath}",
    )
    workingDir(projectDir)
    inputs.files(qualitySources, file(".editorconfig"))
    doFirst {
        val reports = qualityReports.get().asFile.apply { mkdirs() }
        val sources = reports.resolve("sources.txt")
        sources.writeText(
            qualitySources.files.sortedBy { it.path }.joinToString("\n") { it.absolutePath },
        )
        args =
            listOf(
                projectDir.absolutePath,
                sources.absolutePath,
                reports.resolve("$taskName.json").absolutePath,
                formatSources.toString(),
            )
    }
}

registerKtlintTask("ktlintCheck", false)
registerKtlintTask("ktlintFormat", true)

allprojects {
    tasks.configureEach {
        if (name.startsWith("compile") && name.endsWith("Kotlin")) {
            mustRunAfter(rootProject.tasks.named("ktlintFormat"))
        }
    }
}

tasks.named("ktlintCheck") { mustRunAfter("ktlintFormat") }

val styleToolTest =
    tasks.register<JavaExec>("styleToolTest") {
        group = "verification"
        description = "Verify formatting safety and idempotence using local fixtures"
        dependsOn(compileStyleTool)
        classpath = files(styleToolClasses, styleToolClasspath)
        mainClass.set("KotlinStyleTest")
        systemProperty("logback.configurationFile", file("config/quality-logback.xml").absolutePath)
        args(file("tests/.tmp/quality-tool").absolutePath)
    }

fun registerDetektTask(taskName: String, createBaseline: Boolean) = tasks.register<JavaExec>(taskName) {
    group = "verification"
    description = "Analyze Kotlin quality against the explicit historical baseline"
    mustRunAfter("ktlintFormat")
    classpath = if (localDetektJar.isPresent) files(localDetektJar.get()) else detektClasspath
    mainClass.set("dev.detekt.cli.Main")
    workingDir(projectDir)
    inputs.files(qualitySources, file("config/detekt.yml"))
    doFirst {
        val reports = qualityReports.get().asFile.apply { mkdirs() }
        args =
            listOf(
                "--input",
                listOf(
                    "launcher-core/src",
                    "desktop-app/src",
                    "launcher-bootstrap/src",
                    "multiplayer-core/src",
                    "tests/launcher-core",
                    "tests/desktop-app",
                    "tests/multiplayer-core",
                ).joinToString(File.pathSeparator),
                "--config",
                file("config/detekt.yml").absolutePath,
                "--plugins",
                detektPlugins.files.joinToString(File.pathSeparator) { it.absolutePath },
                "--baseline",
                file("config/detekt-baseline.xml").absolutePath,
                "--build-upon-default-config",
                "--base-path",
                projectDir.absolutePath,
                "--fail-on-severity",
                "Warning",
                "--report",
                "sarif:${reports.resolve("detekt.sarif")}",
            ) + if (createBaseline) listOf("--create-baseline") else emptyList()
    }
}

registerDetektTask("detekt", false)
registerDetektTask("detektBaseline", true)

tasks.register("qualityCheck") {
    group = "verification"
    description = "Run Kotlin formatting and static analysis"
    dependsOn("ktlintCheck", "detekt", styleToolTest)
}
