// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责配置桌面编译、依赖清单、单文件打包与 GUI 验收任务。

import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipFile

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

evaluationDependsOn(":launcher-bootstrap")
evaluationDependsOn(":multiplayer-core")

// 界面与文档资源统一放在仓库根的 assets/；legal 与 brand 由打包任务单独处理，不进类路径。
sourceSets["main"].resources.srcDir(rootProject.file("assets"))
sourceSets["main"].resources.exclude("legal/**", "brand/**")

val bootstrapProject = project(":launcher-bootstrap")
val bootstrapSourceSets = bootstrapProject.extensions.getByType<SourceSetContainer>()
val runtimeManifestFile = layout.buildDirectory.file("portable/deps.json")
val usedIconPaths =
    fileTree("src/main/kotlin")
        .matching { include("**/*.kt") }
        .files
        .flatMap { source ->
            Regex(
                "Icons\\s*\\.\\s*(?:(AutoMirrored)\\s*\\.\\s*)?" +
                    "(Filled|Default|Outlined|Rounded|Sharp|TwoTone)\\s*\\.\\s*(\\w+)",
            ).findAll(source.readText())
                .map { match ->
                    val mirrored = if (match.groupValues[1].isEmpty()) "" else "automirrored/"
                    val style = match.groupValues[2].replace("Default", "Filled").lowercase()
                    val name = match.groupValues[3]
                    "androidx/compose/material/icons/$mirrored$style/${name}Kt*.class"
                }.toList()
        }.distinct()
val extendedIcons =
    configurations.runtimeClasspath.map { configuration ->
        configuration.files.first { it.name.startsWith("material-icons-extended-desktop-") }
    }

/** 清单中的一条运行时依赖：Maven 坐标 + 文件名 + 校验值。 */
data class RuntimeEntry(
    val group: String,
    val artifact: String,
    val version: String,
    val filename: String,
    val sha256: String,
    val size: Long,
)

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** 清单版本号 = 应用版本 + 依赖列表哈希；坐标或校验值一变就换新的缓存目录。 */
fun manifestVersion(
    base: String,
    entries: List<RuntimeEntry>,
): String {
    val canonical =
        entries.sortedWith(compareBy({ it.group }, { it.artifact }, { it.version })).joinToString(
            "\n",
        ) {
            "${it.group}:${it.artifact}:${it.version}:${it.sha256}"
        }
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return "$base-" + digest.joinToString("") { "%02x".format(it) }.take(16)
}

/** 生成 /molifox-runtime.json 的正文；[platform] 只给 Linux 包区分原生库用。 */
fun manifestJson(
    entries: List<RuntimeEntry>,
    platform: String? = null,
): String {
    val dependencies =
        entries.sortedWith(compareBy({ it.group }, { it.artifact }, { it.version })).map { entry ->
            linkedMapOf<String, Any>(
                "group" to entry.group,
                "artifact" to entry.artifact,
                "version" to entry.version,
                "filename" to entry.filename,
                "sha256" to entry.sha256,
                "size" to entry.size,
            )
        }
    val json =
        linkedMapOf<String, Any>(
            "version" to manifestVersion(project.version.toString(), entries),
            "dependencies" to dependencies,
        )
    if (platform != null) json["platform"] = platform
    return JsonOutput.prettyPrint(JsonOutput.toJson(json))
}

// 本项目的模块由 MoliFox.jar 自己携带，不进入下载清单。
val projectModules = setOf("launcher-core", "launcher-bootstrap", "desktop-app", "multiplayer-core")

/**
 * 从解析后的运行时类路径生成清单条目：
 * - 跳过本项目模块；
 * - 跳过 material-icons-extended（37 MB，实际只用到少数图标，jar 里已按源码引用挑出）；
 * - skiko 原生库只保留 [skikoPlatform]，其他平台不下载。
 */
fun runtimeEntries(
    configuration: Configuration,
    skikoPlatform: String,
): List<RuntimeEntry> =
    configuration.incoming.artifacts.artifacts
        .mapNotNull { artifact ->
            val module =
                artifact.id.componentIdentifier as? ModuleComponentIdentifier
                    ?: return@mapNotNull null
            if (module.module in projectModules) return@mapNotNull null
            if (module.module.startsWith("material-icons-extended")) return@mapNotNull null
            if (
                module.module.startsWith("skiko-awt-runtime-") &&
                !module.module.endsWith("-$skikoPlatform")
            ) {
                return@mapNotNull null
            }
            val file = artifact.file
            RuntimeEntry(
                group = module.group,
                artifact = module.module,
                version = module.version,
                filename = file.name,
                sha256 = sha256Of(file),
                size = file.length(),
            )
        }.distinctBy { it.filename }

/** 只写清单，不再把运行时 jar 塞进启动器：首次启动由引导程序从 Maven 镜像下载并校验。 */
val preparePortableRuntime =
    tasks.register("preparePortableRuntime") {
        group = "distribution"
        description = "生成运行时依赖清单 build/portable/deps.json（依赖改为首次启动下载）"
        inputs.files(configurations.runtimeClasspath)
        outputs.file(runtimeManifestFile)
        doLast {
            val entries = runtimeEntries(configurations.runtimeClasspath.get(), "windows-x64")
            check(entries.isNotEmpty()) { "运行时依赖清单为空" }
            val native = entries.filter { it.filename.startsWith("skiko-awt-runtime-") }
            check(native.size == 1 && native.single().filename.contains("-windows-x64")) {
                "Windows 清单应只带 skiko windows-x64 原生库：$native"
            }
            val output = runtimeManifestFile.get().asFile
            output.parentFile.mkdirs()
            output.writeText(manifestJson(entries))
        }
    }

// 引导程序在“下载依赖之前”就必须能跑：Gson 解析清单、kotlin-stdlib + 协程、launcher-core 的目录定位。
// 其余运行期依赖（Compose、OKHttp、JNA……）改为首次启动时下载。
val bootstrapPayloadModules =
    setOf("launcher-core", "gson", "kotlin-stdlib", "kotlinx-coroutines-core-jvm")

fun bootstrapPayloadFiles(): List<File> =
    bootstrapProject.configurations
        .getByName("runtimeClasspath")
        .incoming
        .artifacts
        .artifacts
        .mapNotNull { artifact ->
            val identifier = artifact.id.componentIdentifier
            val name =
                when (identifier) {
                    is ModuleComponentIdentifier -> identifier.module
                    is ProjectComponentIdentifier -> identifier.projectName
                    else -> null
                }
            if (name != null && name in bootstrapPayloadModules) artifact.file else null
        }

tasks.processResources {
    from(rootProject.file("LICENSE")) { into("META-INF/molifox") }
    // 声明与第三方许可的唯一来源在 assets/legal，随包复制到同一目录；中文说明不随包分发。
    from(rootProject.file("assets/legal")) {
        into("META-INF/molifox")
        exclude("zh/**")
    }
}

tasks.register<Jar>("portableJar") {
    group = "distribution"
    description =
        "Build the launcher jar: our classes + Swing bootstrap + dependency manifest; runtime jars are downloaded on first launch"
    dependsOn(
        preparePortableRuntime,
        ":launcher-bootstrap:jar",
        ":launcher-core:jar",
        ":multiplayer-core:jvmJar",
    )
    archiveFileName.set("MoliFox.jar")
    destinationDirectory.set(layout.buildDirectory.dir("portable"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "dev.molifox.bootstrap.BootstrapMainKt",
            "Multi-Release" to "true",
        )
    }
    from(sourceSets.main.get().output)
    from({
        zipTree(
            project(":multiplayer-core")
                .tasks
                .named<Jar>("jvmJar")
                .get()
                .archiveFile
                .get()
                .asFile,
        )
    })
    // Include the actual vector icons, instead of downloading every icon style on first run.
    from({ zipTree(extendedIcons.get()) }) { include(usedIconPaths) }
    from(bootstrapSourceSets.getByName("main").output)
    from({
        bootstrapPayloadFiles().map {
            if (it.isDirectory) it else zipTree(it)
        }
    }) {
        exclude(
            "META-INF/*.SF",
            "META-INF/*.RSA",
            "META-INF/*.DSA",
            "module-info.class",
            "META-INF/versions/**/module-info.class",
        )
    }
    // 依赖清单固定落在 /molifox-runtime.json，引导程序按这个路径读取。
    from(runtimeManifestFile) { rename { "molifox-runtime.json" } }
    from(rootProject.file("LICENSE")) { into("META-INF/molifox") }
    from(rootProject.file("assets/legal")) {
        into("META-INF/molifox")
        exclude("zh/**")
    }
    from(rootProject.file("launcher-bootstrap/native/miniz/LICENSE")) {
        into("META-INF/molifox/miniz")
    }
}

val nativeBuildDirectory = layout.buildDirectory.dir("portable/native")
val mingwDirectory = providers.environmentVariable("MOLIFOX_MINGW_BIN").orNull

fun nativeCompiler(name: String) =
    mingwDirectory?.let { file("$it/$name.exe").absolutePath } ?: name

val windowsResource =
    tasks.register("prepareWindowsResource") {
        dependsOn("portableJar")
        outputs.file(nativeBuildDirectory.map { it.file("molifox.rc") })
        doLast {
            val output = nativeBuildDirectory.get().file("molifox.rc").asFile
            output.parentFile.mkdirs()
            val icon =
                rootProject.file("assets/icons/app/icon.ico").absolutePath.replace('\\', '/')
            val payload =
                layout.buildDirectory
                    .file("portable/MoliFox.jar")
                    .get()
                    .asFile
                    .absolutePath
                    .replace('\\', '/')
            output.writeText("1 ICON \"$icon\"\n101 RCDATA \"$payload\"\n")
        }
    }

val compileWindowsResource =
    tasks.register<Exec>("compileWindowsResource") {
        dependsOn(windowsResource)
        inputs.files(
            layout.buildDirectory.file("portable/MoliFox.jar"),
            rootProject.file("assets/icons/app/icon.ico"),
        )
        outputs.file(nativeBuildDirectory.map { it.file("resource.o") })
        commandLine(
            nativeCompiler("windres"),
            nativeBuildDirectory.get().file("molifox.rc").asFile,
            "-O",
            "coff",
            "-o",
            nativeBuildDirectory.get().file("resource.o").asFile,
        )
    }

// miniz.h 会随压缩 API 一起禁用归档写入，只声明压缩开关。
val windowsNativeDefinitions = listOf("-DMINIZ_NO_DEFLATE_APIS")

// 发布文件名带上版本与目标平台，避免不同系统的包互相覆盖。
val windowsPackageName = "MoliFox-${project.version}-windows-x86_64.exe"
val linuxPackageName = "MoliFox-${project.version}-linux-x86_64.run"

tasks.register<Exec>("portableWindows") {
    group = "distribution"
    dependsOn(compileWindowsResource)
    inputs.file(rootProject.file("launcher-bootstrap/native/WindowsMain.cpp"))
    inputs.dir(rootProject.file("launcher-bootstrap/native/miniz"))
    inputs.property("nativeDefinitions", windowsNativeDefinitions)
    inputs.file(nativeBuildDirectory.map { it.file("resource.o") })
    outputs.file(rootProject.file(windowsPackageName))
    outputs.file(rootProject.file("build/validation/windows-package.sha256"))
    commandLine(
        nativeCompiler("g++"),
        *windowsNativeDefinitions.toTypedArray(),
        "-x",
        "c",
        rootProject.file("launcher-bootstrap/native/miniz/miniz.c"),
        rootProject.file("launcher-bootstrap/native/miniz/miniz_tinfl.c"),
        rootProject.file("launcher-bootstrap/native/miniz/miniz_zip.c"),
        "-x",
        "c++",
        rootProject.file("launcher-bootstrap/native/WindowsMain.cpp"),
        "-x",
        "none",
        nativeBuildDirectory.get().file("resource.o").asFile,
        "-std=c++17",
        "-O2",
        "-s",
        "-static",
        "-municode",
        "-mwindows",
        "-lwinhttp",
        "-lbcrypt",
        "-lcomctl32",
        "-ladvapi32",
        "-o",
        rootProject.file(windowsPackageName),
    )
    doLast {
        val executable = rootProject.file(windowsPackageName)
        val imports =
            providers
                .exec {
                    commandLine(nativeCompiler("objdump"), "-p", executable.absolutePath)
                }.standardOutput.asText
                .get()
        check(!imports.contains("TaskDialogIndirect")) {
            "TaskDialogIndirect must remain optional; a static import prevents startup on older common controls"
        }
        val digest = sha256Of(executable)
        val checksum = rootProject.file("build/validation/windows-package.sha256")
        checksum.parentFile.mkdirs()
        checksum.writeText("$digest  $windowsPackageName\n")
    }
}

val skikoAwtVersion =
    providers.provider {
        configurations.runtimeClasspath
            .get()
            .incoming
            .artifacts
            .artifacts
            .mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
            .first { it.group == "org.jetbrains.skiko" && it.module == "skiko-awt" }
            .version
    }
val linuxRuntimeNative =
    configurations.create("linuxRuntimeNative") {
        isCanBeConsumed = false
        isTransitive = false
        defaultDependencies {
            add(
                project.dependencies.create(
                    "org.jetbrains.skiko:skiko-awt-runtime-linux-x64:" + skikoAwtVersion.get(),
                ),
            )
        }
    }
val linuxManifestFile = layout.buildDirectory.file("portable/linux/molifox-runtime.json")
val prepareLinuxRuntime =
    tasks.register("prepareLinuxRuntime") {
        dependsOn(preparePortableRuntime)
        inputs.file(runtimeManifestFile)
        inputs.files(linuxRuntimeNative)
        outputs.file(linuxManifestFile)
        doLast {
            // 复用 Windows 清单的全部条目，只把 skiko 原生库换成 linux-x64，并重新计算版本哈希。
            val host =
                groovy.json.JsonSlurper().parse(runtimeManifestFile.get().asFile) as Map<*, *>
            val shared =
                (host["dependencies"] as List<*>)
                    .map { it as Map<*, *> }
                    .filterNot { (it["filename"] as String).startsWith("skiko-awt-runtime-") }
                    .map { dependency ->
                        RuntimeEntry(
                            group = dependency["group"] as String,
                            artifact = dependency["artifact"] as String,
                            version = dependency["version"] as String,
                            filename = dependency["filename"] as String,
                            sha256 = dependency["sha256"] as String,
                            size = (dependency["size"] as Number).toLong(),
                        )
                    }
            val native =
                linuxRuntimeNative.files.map { file ->
                    RuntimeEntry(
                        group = "org.jetbrains.skiko",
                        artifact = "skiko-awt-runtime-linux-x64",
                        version = skikoAwtVersion.get(),
                        filename = file.name,
                        sha256 = sha256Of(file),
                        size = file.length(),
                    )
                }
            val output = linuxManifestFile.get().asFile
            output.parentFile.mkdirs()
            output.writeText(manifestJson(shared + native, "linux-x64"))
        }
    }

// JVM UI 代码共用；Linux 包只把清单换成 linux-x64 版（原生库同样改为首次启动下载）。
tasks.register<Jar>("portableLinuxJar") {
    group = "distribution"
    dependsOn("portableJar", prepareLinuxRuntime)
    archiveFileName.set("MoliFox-linux.jar")
    destinationDirectory.set(layout.buildDirectory.dir("portable/linux"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "dev.molifox.bootstrap.BootstrapMainKt",
            "Multi-Release" to "true",
        )
    }
    from({
        zipTree(
            layout.buildDirectory
                .file("portable/MoliFox.jar")
                .get()
                .asFile,
        )
    }) {
        exclude("molifox-runtime.json", "META-INF/MANIFEST.MF")
    }
    from(linuxManifestFile) { rename { "molifox-runtime.json" } }
}

tasks.register("portableLinux") {
    group = "distribution"
    dependsOn("portableLinuxJar")
    val payload = layout.buildDirectory.file("portable/linux/MoliFox-linux.jar")
    inputs.file(rootProject.file("launcher-bootstrap/native/LinuxMain.sh"))
    inputs.file(payload)
    outputs.file(rootProject.file(linuxPackageName))
    outputs.file(rootProject.file("build/validation/linux-package.sha256"))
    doLast {
        val jar = payload.get().asFile
        val digest = sha256Of(jar)
        val header =
            rootProject
                .file("launcher-bootstrap/native/LinuxMain.sh")
                .readText()
                .replace("\r\n", "\n")
                .replace("@VERSION@", project.version.toString())
                .replace("@PAYLOAD_SHA256@", digest)
        val output = rootProject.file(linuxPackageName)
        output.outputStream().buffered().use { destination ->
            destination.write(header.toByteArray(Charsets.UTF_8))
            jar.inputStream().buffered().use { it.copyTo(destination) }
        }
        output.setExecutable(true, false)
        val checksum = rootProject.file("build/validation/linux-package.sha256")
        checksum.parentFile.mkdirs()
        val hash = sha256Of(output)
        checksum.writeText("$hash  $linuxPackageName\n")
    }
}

tasks.register("verifyDesktopPackages") {
    group = "verification"
    description =
        "Validate the dependency manifest and jar layout (no runtime jars embedded); does not launch Linux"
    dependsOn("portableWindows", "portableLinux")
    doLast {
        val requiredBundledIcons =
            ZipFile(extendedIcons.get()).use { icons ->
                usedIconPaths
                    .map { it.replace("Kt*.class", "Kt.class") }
                    .filter { icons.getEntry(it) != null }
            }
        val platforms =
            listOf(
                "windows" to
                    layout.buildDirectory
                        .file("portable/MoliFox.jar")
                        .get()
                        .asFile,
                "linux" to
                    layout.buildDirectory
                        .file("portable/linux/MoliFox-linux.jar")
                        .get()
                        .asFile,
            )
        for ((platform, file) in platforms) {
            ZipFile(file).use { zip ->
                for (entry in listOf(
                    "dev/molifox/multiplayer/domain/MultiplayerBackend.class",
                    "dev/molifox/multiplayer/data/TerracottaInstaller.class",
                )) {
                    check(zip.getEntry(entry) != null) { "$platform 包缺少联机入口 $entry" }
                }
                for (icon in requiredBundledIcons) {
                    check(zip.getEntry(icon) != null) { "$platform 包缺少界面图标 $icon" }
                }
                for (notice in listOf("LICENSE", "NOTICE", "THIRD_PARTY.md")) {
                    check(zip.getEntry("META-INF/molifox/$notice") != null) {
                        "$platform 包缺少许可声明 $notice"
                    }
                }
                // 运行时依赖不再内嵌；jar 里只留清单。
                val embedded =
                    zip.entries().asSequence().filter {
                        it.name.startsWith("molifox-dependencies/")
                    }
                check(!embedded.iterator().hasNext()) { "MoliFox.jar 仍然内嵌了运行时依赖" }
                val json =
                    zip
                        .getInputStream(zip.getEntry("molifox-runtime.json") ?: error("缺少运行时清单"))
                        .reader()
                        .use { groovy.json.JsonSlurper().parse(it) as Map<*, *> }
                val dependencies = (json["dependencies"] as List<*>).map { it as Map<*, *> }
                check(dependencies.isNotEmpty()) { "运行时清单为空" }
                check((json["version"] as String).contains("-")) { "清单版本号缺少依赖哈希" }
                for (dependency in dependencies) {
                    val filename = dependency["filename"] as String
                    check(filename.matches(Regex("[A-Za-z0-9_.+-]+\\.jar"))) { "文件名不合法" }
                    check((dependency["group"] as String).isNotBlank()) { "缺少 group" }
                    check((dependency["artifact"] as String).isNotBlank()) { "缺少 artifact" }
                    check((dependency["version"] as String).isNotBlank()) { "缺少 version" }
                    check((dependency["sha256"] as String).matches(Regex("[0-9a-f]{64}"))) {
                        "校验值不合法：$filename"
                    }
                    check((dependency["size"] as Number).toLong() > 0) { "大小不合法：$filename" }
                }
                val native =
                    dependencies.filter {
                        (it["filename"] as String).startsWith("skiko-awt-runtime-")
                    }
                check(native.size == 1) { "清单应只有一个 skiko 原生库：$native" }
                check((native.single()["filename"] as String).contains("-$platform-x64")) {
                    "Wrong platform native library"
                }
                check(
                    dependencies.any {
                        (it["filename"] as String).startsWith("kotlin-stdlib-")
                    },
                ) {
                    "清单缺少 kotlin-stdlib"
                }
                check(zip.getEntry("dev/molifox/desktop/AppearancePageKt.class") != null)
                check(zip.getEntry("dev/molifox/bootstrap/BootstrapMainKt.class") != null)
                check(
                    checkNotNull(zip.getEntry("dev/molifox/bootstrap/BootstrapMainKt.class")).size >
                        0,
                )
            }
        }
        val report = rootProject.file("build/validation/desktop-packages.md")
        report.parentFile.mkdirs()
        report.writeText(
            "# Desktop package verification\n\n- Runtime dependency manifest: present, hashes recorded\n- Embedded runtime jars: none (downloaded on first launch)\n- Windows skiko native: declared in manifest, windows-x64\n- Linux skiko native: declared in manifest, linux-x64\n- Linux desktop runtime: structure checked here; launcher UI and Terracotta core start were executed separately in WSL Arch + Xvfb (software rendering), not on this Windows host\n",
        )
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":launcher-core"))
    implementation(project(":multiplayer-core"))
    implementation(project(":launcher-bootstrap"))
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 桌面模块的普通单元测试不打开窗口，只验证界面侧的有界内存缓存等纯内存行为。
tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}

val uiChecksCompilation =
    kotlin.target.compilations.create("uiChecks") {
        associateWith(kotlin.target.compilations.getByName("main"))
        defaultSourceSet.dependencies {
            implementation("com.google.code.gson:gson:2.13.2")
            implementation("com.squareup.okhttp3:okhttp:4.12.0")
        }
    }
tasks.register<JavaExec>("multiplayerUiSmoke") {
    dependsOn("uiChecksClasses")
    classpath =
        files(uiChecksCompilation.output.allOutputs, uiChecksCompilation.runtimeDependencyFiles)
    mainClass.set("dev.molifox.desktop.multiplayer.MultiplayerUiSmokeKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Dcompose.accessibility.enable=true")
    workingDir(rootProject.projectDir)
    timeout.set(Duration.ofMinutes(3))
}

sourceSets["uiChecks"].java.apply {
    srcDir(rootProject.file("tools"))
    include("TestWindowFocus.java")
}

compose.desktop {
    application {
        mainClass = "dev.molifox.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED")
    }
}

// Compile GUI probes inside their disposable run directory, even if compilation fails.
fun registerGuiSmoke(
    name: String,
    probe: String,
    native: Boolean,
) = tasks.register<JavaExec>(name) {
    group = "verification"
    description = "Windows GUI check with automatic removal of its isolated test data"
    dependsOn(if (native) "portableWindows" else "portableJar")
    val testDirectory =
        rootProject.layout.projectDirectory
            .dir(".work/tests/${project.name}-$name")
            .asFile
    val portableJar = layout.buildDirectory.file("portable/MoliFox.jar")
    // 运行时依赖不再内嵌进 MoliFox.jar：GUI 探针直接用 Gradle 解析出的类路径，
    // 界面类仍来自打好的 jar（排在最前，保证测的是打包产物）。
    classpath =
        files(
            File(testDirectory, "classes"),
            portableJar,
            configurations.runtimeClasspath,
        )
    mainClass.set(probe)
    systemProperty(
        "molifox.actions.report",
        rootProject.file("build/validation/$name-actions.json").absolutePath,
    )
    systemProperty("molifox.actions.seedPublicCache", (name == "packActionsSmoke").toString())
    timeout.set(Duration.ofMinutes(3))
    systemProperty("java.net.useSystemProxies", "true")
    systemProperty("stdout.encoding", "UTF-8")
    systemProperty("stderr.encoding", "UTF-8")
    doFirst {
        check(System.getProperty("os.name").startsWith("Windows")) {
            "This GUI check currently targets Windows"
        }
        val classes = File(testDirectory, "classes").apply { mkdirs() }
        val compiler =
            javax.tools.ToolProvider.getSystemJavaCompiler()
                ?: error("A JDK is required for GUI checks")
        check(
            compiler.run(
                null,
                null,
                null,
                "-encoding",
                "UTF-8",
                "-cp",
                files(portableJar, configurations.runtimeClasspath).asPath,
                "-d",
                classes.absolutePath,
                rootProject.file("tools/$probe.java").absolutePath,
                rootProject.file("tools/UiSmoke.java").absolutePath,
                rootProject.file("tools/UiFunctionalChecks.java").absolutePath,
                rootProject.file("tools/InstanceActionChecks.java").absolutePath,
                rootProject.file("tools/SettingsActionChecks.java").absolutePath,
                rootProject.file("tools/DownloadActionChecks.java").absolutePath,
                rootProject.file("tools/JavaActionChecks.java").absolutePath,
                rootProject.file("tools/AccountActionChecks.java").absolutePath,
                rootProject.file("tools/PackActionChecks.java").absolutePath,
                rootProject.file("tools/WindowActionChecks.java").absolutePath,
                rootProject.file("tools/LogActionChecks.java").absolutePath,
                rootProject.file("tools/MicrosoftActionChecks.java").absolutePath,
                rootProject.file("tools/TestWindowFocus.java").absolutePath,
                rootProject.file("tools/NativeDialogActions.java").absolutePath,
                rootProject.file("tools/RefinementActions.java").absolutePath,
                rootProject.file("tools/PackDragChecks.java").absolutePath,
                rootProject.file("tools/NativeRuntimeCache.java").absolutePath,
                rootProject.file("tools/NativeMemoryProbe.java").absolutePath,
            ) == 0,
        ) {
            "$probe compilation failed"
        }
        if (native) {
            // 沙箱里保留固定文件名，探针按 MoliFox.exe 启动；发布名不影响运行。
            rootProject
                .file(windowsPackageName)
                .copyTo(File(testDirectory, "MoliFox.exe"), overwrite = true)
            setArgs(
                listOf(
                    testDirectory.absolutePath,
                    File(testDirectory, "screenshots/MoliFox-native.png").absolutePath,
                    providers
                        .gradleProperty("nativeSmokeMode")
                        .getOrElse("online-then-offline"),
                ),
            )
        } else {
            setArgs(
                listOf(
                    File(testDirectory, "screenshots/MoliFox-home.png").absolutePath,
                    when (name) {
                        "microsoftActionsSmoke" -> "actionsMicrosoft"
                        "refinementSmoke" -> "refinement"
                        "windowActionsSmoke" -> "actionsWindow"
                        "logActionsSmoke" -> "actionsLogs"
                        "packActionsSmoke" -> "actionsPacks"
                        "downloadActionsSmoke" -> "actionsDownloads"
                        "javaActionsSmoke" -> "actionsJava"
                        "accountActionsSmoke" -> "actionsAccounts"
                        "instanceActionsSmoke" -> "actionsInstances"
                        "settingsActionsSmoke" -> "actionsSettings"
                        "storageUiSmoke" -> "storageUi"
                        "reviewSmoke" -> "review"
                        "skinSmoke" -> "skins"
                        "iconSmoke" -> "icons"
                        "screenshotsSmoke" -> "screenshots"
                        "downloadDetailsSmoke" -> "downloadDetails"
                        else -> "flow"
                    },
                ),
            )
        }
    }
}

val uiSmoke =
    tasks.register<JavaExec>("uiSmoke") {
        group = "verification"
        description =
            "Exercise current desktop UI with temporary instances and in-process API fixtures"
        dependsOn("uiChecksClasses")
        classpath =
            files(uiChecksCompilation.output.allOutputs, uiChecksCompilation.runtimeDependencyFiles)
        mainClass.set("dev.molifox.desktop.checks.UiSmokeMainKt")
        systemProperty("stdout.encoding", "UTF-8")
        systemProperty("stderr.encoding", "UTF-8")
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-Dcompose.accessibility.enable=true")
        workingDir(rootProject.projectDir)
        timeout.set(Duration.ofMinutes(5))
        doFirst {
            check(System.getProperty("os.name").startsWith("Windows")) {
                "This UI robot uses the Windows foreground adapter"
            }
        }
    }
val refinementSmoke = registerGuiSmoke("refinementSmoke", "UiSmoke", false)
tasks.register<JavaExec>("recentPlaySmoke") {
    dependsOn("uiChecksClasses")
    classpath =
        files(uiChecksCompilation.output.allOutputs, uiChecksCompilation.runtimeDependencyFiles)
    mainClass.set("dev.molifox.desktop.RecentPlayUiSmokeKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Dcompose.accessibility.enable=true")
    workingDir(rootProject.projectDir)
}
val reviewSmoke = registerGuiSmoke("reviewSmoke", "UiSmoke", false)
val skinSmoke = registerGuiSmoke("skinSmoke", "UiSmoke", false)
val storageUiSmoke = registerGuiSmoke("storageUiSmoke", "UiSmoke", false)
val iconSmoke = registerGuiSmoke("iconSmoke", "UiSmoke", false)
val screenshotsSmoke = registerGuiSmoke("screenshotsSmoke", "UiSmoke", false)
val downloadDetailsSmoke = registerGuiSmoke("downloadDetailsSmoke", "UiSmoke", false)
val instanceActionsSmoke = registerGuiSmoke("instanceActionsSmoke", "UiSmoke", false)
val settingsActionsSmoke = registerGuiSmoke("settingsActionsSmoke", "UiSmoke", false)
val downloadActionsSmoke = registerGuiSmoke("downloadActionsSmoke", "UiSmoke", false)
val javaActionsSmoke = registerGuiSmoke("javaActionsSmoke", "UiSmoke", false)
val accountActionsSmoke = registerGuiSmoke("accountActionsSmoke", "UiSmoke", false)
val packActionsSmoke = registerGuiSmoke("packActionsSmoke", "UiSmoke", false)
val windowActionsSmoke = registerGuiSmoke("windowActionsSmoke", "UiSmoke", false)
val logActionsSmoke = registerGuiSmoke("logActionsSmoke", "UiSmoke", false)
val microsoftActionsSmoke = registerGuiSmoke("microsoftActionsSmoke", "UiSmoke", false)

microsoftActionsSmoke.configure { timeout.set(Duration.ofMinutes(20)) }

windowActionsSmoke.configure { timeout.set(Duration.ofMinutes(5)) }

logActionsSmoke.configure { timeout.set(Duration.ofMinutes(5)) }

packActionsSmoke.configure { timeout.set(Duration.ofMinutes(15)) }

downloadActionsSmoke.configure { timeout.set(Duration.ofMinutes(10)) }

javaActionsSmoke.configure { timeout.set(Duration.ofMinutes(20)) }

accountActionsSmoke.configure { timeout.set(Duration.ofMinutes(10)) }

instanceActionsSmoke.configure { timeout.set(Duration.ofMinutes(10)) }

settingsActionsSmoke.configure { timeout.set(Duration.ofMinutes(10)) }

// uiSmoke 新增了存档备份/恢复/删除等真实文件步骤；3 分钟在负载高时会误杀。
uiSmoke.configure { timeout.set(Duration.ofMinutes(6)) }

val nativeSmoke = registerGuiSmoke("nativeSmoke", "NativeSmoke", true)

nativeSmoke.configure {
    timeout.set(Duration.ofMinutes(8))
    systemProperty(
        "molifox.native.memoryProfile",
        providers.gradleProperty("nativeMemoryProfile").getOrElse(""),
    )
    systemProperty(
        "molifox.native.memoryFlags",
        providers.gradleProperty("nativeMemoryFlags").getOrElse(""),
    )
    systemProperty(
        "molifox.native.report",
        rootProject.file("build/validation/native-startup.json").absolutePath,
    )
}

nativeSmoke.configure { mustRunAfter(uiSmoke) }

tasks.register<JavaExec>("nativePickerSmoke") {
    group = "verification"
    dependsOn(tasks.classes)
    val root =
        rootProject.layout.projectDirectory
            .dir(".work/tests/desktop-app-nativePickerSmoke")
            .asFile
    classpath = files(File(root, "classes"), sourceSets.main.get().runtimeClasspath)
    mainClass.set("NativePickerSmoke")
    timeout.set(Duration.ofMinutes(3))
    doFirst {
        check(System.getProperty("os.name").startsWith("Windows"))
        val classes = File(root, "classes").apply { mkdirs() }
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler() ?: error("需要 JDK")
        check(
            compiler.run(
                null,
                null,
                null,
                "-encoding",
                "UTF-8",
                "-cp",
                sourceSets.main
                    .get()
                    .runtimeClasspath.asPath,
                "-d",
                classes.absolutePath,
                rootProject.file("tools/NativePickerSmoke.java").absolutePath,
                rootProject.file("tools/NativeDialogActions.java").absolutePath,
            ) == 0,
        )
        setArgs(listOf(File(root, "fixtures").absolutePath))
    }
}

tasks.register<JavaExec>("appearanceUiSmoke") {
    group = "verification"
    dependsOn("uiChecksClasses")
    classpath =
        files(uiChecksCompilation.output.allOutputs, uiChecksCompilation.runtimeDependencyFiles)
    mainClass.set("dev.molifox.desktop.checks.UiSmokeMainKt")
    systemProperty("molifox.ui.refinements", "true")
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Dcompose.accessibility.enable=true")
    workingDir(rootProject.projectDir)
    timeout.set(Duration.ofMinutes(4))
}
