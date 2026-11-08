// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 静态检查独立运行，避免把分析器及其编译器依赖带进启动器发行包。

import org.gradle.api.Action
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile

val ktlintClasspath = configurations.create("qualityKtlint")
val detektClasspath = configurations.create("qualityDetekt")
val detektPlugins = configurations.create("qualityDetektPlugins")
listOf(ktlintClasspath, detektClasspath).forEach {
    it.attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    it.attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
    // Kotlin Multiplatform 模块同时发布 sourcesElements；只声明 runtime 仍可能选到源码 jar。
    // 明确只取 library 变体，避免 detekt 类路径里出现 *-sources.jar 导致找不到类。
    it.attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
}
val localKtlintJar = providers.gradleProperty("qualityKtlintJar")
val localDetektJar = providers.gradleProperty("qualityDetektJar")

dependencies {
    add(ktlintClasspath.name, "com.pinterest.ktlint:ktlint-cli:1.7.1")
    add(detektClasspath.name, "dev.detekt:detekt-cli:2.0.0-alpha.6")
    // detekt 2.0 alpha 的 IntelliJ 编译器依赖使用未重定位的 kotlinx.coroutines，
    // 但其 analysis-api 只传递了 IntelliJ 重定位版本；显式补上运行依赖，避免启动时
    // NoClassDefFoundError: kotlinx/coroutines/CoroutineScope。
    add(detektClasspath.name, "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
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

// ktlint 包装源码不再入库（开发工具只在本机保留），先找仓库目录、再找本机 .dev 目录；
// 两处都没有时跳过 ktlint 任务，而不是让门禁因为缺文件直接失败。
val styleToolSource =
    listOf(file("tools/style"), file(".dev/tooling/style")).firstOrNull { it.isDirectory }
val styleToolAvailable = styleToolSource != null

val compileStyleTool =
    tasks.register<JavaCompile>("compileStyleTool") {
        onlyIf { styleToolAvailable }
        source(fileTree(styleToolSource ?: file("tools/style")) { include("*.java") })
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
    onlyIf { styleToolAvailable }
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

if (!styleToolAvailable) {
    logger.lifecycle(
        "ktlint 包装源码未找到（tools/style 与 .dev/tooling/style 都不存在），ktlintCheck/ktlintFormat 将跳过。",
    )
}

allprojects {
    tasks.configureEach {
        if (name.startsWith("compile") && name.endsWith("Kotlin")) {
            mustRunAfter(rootProject.tasks.named("ktlintFormat"))
        }
    }
}

// 打包任务会在项目根目录写入 dist/MoliFox.jar；ktlint 以项目根为工作目录，
// 先跑打包会让 Gradle 把该产物误判为 ktlint 的隐式输入。显式排序避免同次调用失败。
tasks.named("ktlintCheck") {
    mustRunAfter("ktlintFormat", ":desktop-app:universalJar")
}

val styleToolTest =
    tasks.register<JavaExec>("styleToolTest") {
        group = "verification"
        description = "Verify formatting safety and idempotence using local fixtures"
        onlyIf { styleToolAvailable }
        dependsOn(compileStyleTool)
        classpath = files(styleToolClasses, styleToolClasspath)
        mainClass.set("KotlinStyleTest")
        systemProperty("logback.configurationFile", file("config/quality-logback.xml").absolutePath)
        args(file("tests/.tmp/quality-tool").absolutePath)
    }

fun registerDetektTask(
    taskName: String,
    createBaseline: Boolean,
) = tasks.register<JavaExec>(taskName) {
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

data class DetektUnusedModule(
    val taskSuffix: String,
    val projectPath: String,
    val compileTask: String,
    val runtimeConfiguration: String,
    val sourcePaths: List<String>,
    val classPaths: List<String>,
)

val detektUnusedModules =
    listOf(
        DetektUnusedModule(
            taskSuffix = "LauncherCore",
            projectPath = ":launcher-core",
            compileTask = ":launcher-core:classes",
            runtimeConfiguration = "runtimeClasspath",
            sourcePaths = listOf("launcher-core/src/main/kotlin"),
            classPaths = listOf("classes/kotlin/main", "classes/java/main"),
        ),
        DetektUnusedModule(
            taskSuffix = "DesktopApp",
            projectPath = ":desktop-app",
            compileTask = ":desktop-app:classes",
            runtimeConfiguration = "runtimeClasspath",
            sourcePaths = listOf("desktop-app/src/main/kotlin"),
            classPaths = listOf("classes/kotlin/main", "classes/java/main"),
        ),
        DetektUnusedModule(
            taskSuffix = "LauncherBootstrap",
            projectPath = ":launcher-bootstrap",
            compileTask = ":launcher-bootstrap:classes",
            runtimeConfiguration = "runtimeClasspath",
            sourcePaths = listOf("launcher-bootstrap/src/main/kotlin"),
            classPaths = listOf("classes/kotlin/main", "classes/java/main"),
        ),
        DetektUnusedModule(
            taskSuffix = "MultiplayerCore",
            projectPath = ":multiplayer-core",
            compileTask = ":multiplayer-core:jvmMainClasses",
            runtimeConfiguration = "jvmRuntimeClasspath",
            sourcePaths =
                listOf(
                    "multiplayer-core/src/commonMain/kotlin",
                    "multiplayer-core/src/jvmMain/kotlin",
                ),
            classPaths = listOf("classes/kotlin/jvm/main", "classes/java/jvmMain"),
        ),
    )

// Gradle 9 禁止在任务执行期解析依赖配置（会报 "without an exclusive lock"），
// 但各模块的 runtimeClasspath 由插件在 afterEvaluate 才注册，根项目配置期也拿不到。
// 因此在任务图确定后、执行开始前（whenReady 仍属配置阶段，持锁）完成解析并缓存，
// doFirst 只读取缓存结果。仅在对应任务真正进入本次构建时才解析，避免拖慢无关构建。
val resolvedAnalysisClasspaths = mutableMapOf<String, FileCollection>()
gradle.taskGraph.whenReady(
    // Kotlin DSL 下 whenReady 的 Action/Closure 重载有歧义，显式 SAM 构造。
    object : Action<TaskExecutionGraph> {
        override fun execute(graph: TaskExecutionGraph) {
            detektUnusedModules.forEach { module ->
                if (graph.hasTask(":detektUnused${module.taskSuffix}")) {
                    val target = project(module.projectPath)
                    resolvedAnalysisClasspaths[module.projectPath] =
                        files(
                            target.configurations.getByName(module.runtimeConfiguration).files,
                            module.classPaths.map {
                                target.layout.buildDirectory
                                    .dir(it)
                                    .get()
                                    .asFile
                            },
                        )
                }
            }
        }
    },
)

val detektUnusedTasks =
    detektUnusedModules.map { module ->
        tasks.register<JavaExec>("detektUnused${module.taskSuffix}") {
            group = "verification"
            description = "Find newly introduced unused production code in ${module.projectPath}"
            dependsOn(module.compileTask)
            classpath = if (localDetektJar.isPresent) files(localDetektJar.get()) else detektClasspath
            mainClass.set("dev.detekt.cli.Main")
            workingDir(projectDir)
            inputs.files(module.sourcePaths.map(::file), file("config/detekt-unused.yml"))
            doFirst {
                val reports = qualityReports.get().asFile.apply { mkdirs() }
                val analysisClasspath =
                    resolvedAnalysisClasspaths.getValue(module.projectPath).filter(File::exists)
                args =
                    listOf(
                        "--input",
                        module.sourcePaths.joinToString(File.pathSeparator),
                        "--config",
                        file("config/detekt-unused.yml").absolutePath,
                        // 不传 --disable-default-rulesets：detekt 2.0 alpha 里该开关会把
                        // config 中显式激活的规则一起关掉，整个检查变成空转；
                        // 改由 config/detekt-unused.yml 逐个关闭默认规则集，只留 Unused*。
                        "--analysis-mode",
                        "full",
                        "--classpath",
                        analysisClasspath.joinToString(File.pathSeparator) { it.absolutePath },
                        "--jdk-home",
                        System.getProperty("java.home"),
                        "--jvm-target",
                        "17",
                        "--base-path",
                        projectDir.absolutePath,
                        "--fail-on-severity",
                        "Warning",
                        "--report",
                        "sarif:${reports.resolve("detekt-unused-${module.taskSuffix}.sarif")}",
                    )
            }
        }
    }

tasks.register("detektUnused") {
    group = "verification"
    description = "Run full-analysis unused-code checks per production module"
    dependsOn(detektUnusedTasks)
}

tasks.register("qualityCheck") {
    group = "verification"
    description = "Run Kotlin formatting and static analysis"
    dependsOn("ktlintCheck", "detekt", "detektUnused", styleToolTest)
}
