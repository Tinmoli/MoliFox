// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责配置桌面编译、依赖清单、单文件打包与 GUI 验收任务。

import groovy.json.JsonOutput
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

evaluationDependsOn(":launcher-bootstrap")
evaluationDependsOn(":multiplayer-core")

// 界面与文档资源统一放在仓库根的 assets/；legal 与 brand 由打包任务单独处理，不进类路径。
sourceSets["main"].resources.srcDir(rootProject.file("assets"))
// 默认背景已由 core 放在 META-INF/molifox，避免桌面资源再打包一份同样的压缩包。
sourceSets["main"].resources.exclude(
    "legal/**",
    "brand/**",
    "backgrounds/**",
    "icons/app/icon.ico",
    "icons/app/icon-white-1024.png",
    "icons/app/icon-white-300.png",
)
// 测试与 GUI 检查源码统一放在仓库根的 tests/。
sourceSets["test"].kotlin.srcDirs(rootProject.file("tests/desktop-app/test/kotlin"))

val bootstrapProject = project(":launcher-bootstrap")
val bootstrapSourceSets = bootstrapProject.extensions.getByType<SourceSetContainer>()
val windowsX64ManifestFile = layout.buildDirectory.file("portable/windows-x64/molifox-runtime.json")
val linuxX64ManifestFile = layout.buildDirectory.file("portable/linux-x64/molifox-runtime.json")
val linuxArm64ManifestFile = layout.buildDirectory.file("portable/linux-arm64/molifox-runtime.json")
val macosX64ManifestFile = layout.buildDirectory.file("portable/macos-x64/molifox-runtime.json")
val macosArm64ManifestFile = layout.buildDirectory.file("portable/macos-arm64/molifox-runtime.json")
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

/** 清单版本只取决于运行依赖；单纯升级启动器不会让全部依赖失效。 */
fun manifestVersion(entries: List<RuntimeEntry>): String {
    val canonical =
        entries.sortedWith(compareBy({ it.group }, { it.artifact }, { it.version })).joinToString(
            "\n",
        ) {
            "${it.group}:${it.artifact}:${it.version}:${it.sha256}"
        }
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return "runtime-" + digest.joinToString("") { "%02x".format(it) }.take(16)
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
            "version" to manifestVersion(entries),
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
                // 有些 KMP 元数据给出的本地文件名不带版本，Maven 下载 URL 仍要求版本后缀。
                filename =
                    if (file.name ==
                        "${module.module}.jar"
                    ) {
                        "${module.module}-${module.version}.jar"
                    } else {
                        file.name
                    },
                sha256 = sha256Of(file),
                size = file.length(),
            )
        }.distinctBy { it.filename }

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

fun nativeRuntimeConfiguration(
    platform: String,
    arch: String = "x64",
): Configuration =
    configurations.create("${platform}${arch.replaceFirstChar { it.uppercase() }}RuntimeNative") {
        isCanBeConsumed = false
        isTransitive = false
        defaultDependencies {
            add(
                project.dependencies.create(
                    "org.jetbrains.skiko:skiko-awt-runtime-$platform-$arch:" + skikoAwtVersion.get(),
                ),
            )
        }
    }

val windowsRuntimeNative = nativeRuntimeConfiguration("windows")
val windowsArm64RuntimeNative = nativeRuntimeConfiguration("windows", "arm64")
val linuxRuntimeNative = nativeRuntimeConfiguration("linux")
val linuxArm64RuntimeNative = nativeRuntimeConfiguration("linux", "arm64")

/** 打包目标与构建宿主独立；原生库版本沿用实际解析的 skiko，不改变清单格式。 */
fun platformRuntimeEntries(
    native: Configuration,
    platform: String,
): List<RuntimeEntry> =
    runtimeEntries(configurations.runtimeClasspath.get(), platform)
        .filterNot { it.filename.startsWith("skiko-awt-runtime-") } +
        runtimeEntries(native, platform)

/** 只写清单，不再把运行时 jar 塞进启动器：首次启动由引导程序从 Maven 镜像下载并校验。 */
val preparePortableRuntime =
    tasks.register("preparePortableRuntime") {
        group = "distribution"
        description = "生成 Windows x64 运行时依赖清单 build/portable/windows-x64/molifox-runtime.json（依赖改为首次启动下载）"
        inputs.files(configurations.runtimeClasspath, windowsRuntimeNative)
        outputs.file(windowsX64ManifestFile)
        doLast {
            val entries = platformRuntimeEntries(windowsRuntimeNative, "windows-x64")
            check(entries.isNotEmpty()) { "运行时依赖清单为空" }
            val native = entries.filter { it.filename.startsWith("skiko-awt-runtime-") }
            check(native.size == 1 && native.single().filename.contains("-windows-x64")) {
                "Windows 清单应只带 skiko windows-x64 原生库：$native"
            }
            val output = windowsX64ManifestFile.get().asFile
            output.parentFile.mkdirs()
            output.writeText(manifestJson(entries))
        }
    }

/**
 * Windows ARM64 使用同一枚 32 位外壳启动；JAR 内同时带一份 ARM64 运行时清单，
 * 由引导程序按 JVM 架构选择，避免为两种架构维护两份几乎相同的安装包。
 */
val windowsArm64ManifestFile =
    layout.buildDirectory.file("portable/windows-arm64/molifox-runtime.json")

val prepareWindowsArm64Runtime =
    tasks.register("prepareWindowsArm64Runtime") {
        group = "distribution"
        description = "生成 Windows ARM64 运行时依赖清单"
        inputs.files(configurations.runtimeClasspath, windowsArm64RuntimeNative)
        outputs.file(windowsArm64ManifestFile)
        doLast {
            val entries = platformRuntimeEntries(windowsArm64RuntimeNative, "windows-arm64")
            check(entries.isNotEmpty()) { "Windows ARM64 运行时依赖清单为空" }
            val native = entries.filter { it.filename.startsWith("skiko-awt-runtime-") }
            check(native.size == 1 && native.single().filename.contains("-windows-arm64")) {
                "Windows ARM64 清单应只带 skiko windows-arm64 原生库：$native"
            }
            val output = windowsArm64ManifestFile.get().asFile
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
        prepareWindowsArm64Runtime,
        "prepareLinuxRuntime",
        "prepareLinuxArm64Runtime",
        "prepareMacosRuntime",
        "prepareMacosArm64Runtime",
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
            "Implementation-Version" to project.version.toString(),
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
    // 六份清单是发布的唯一运行时清单来源，不再保留按平台回退的主清单。
    from(windowsX64ManifestFile) { rename { "molifox-runtime-windows-x64.json" } }
    from(windowsArm64ManifestFile) { rename { "molifox-runtime-windows-arm64.json" } }
    from(linuxX64ManifestFile) { rename { "molifox-runtime-linux-x64.json" } }
    from(linuxArm64ManifestFile) { rename { "molifox-runtime-linux-arm64.json" } }
    from(macosX64ManifestFile) { rename { "molifox-runtime-macos-x64.json" } }
    from(macosArm64ManifestFile) { rename { "molifox-runtime-macos-arm64.json" } }
    from(rootProject.file("LICENSE")) { into("META-INF/molifox") }
    from(rootProject.file("assets/legal")) {
        into("META-INF/molifox")
        exclude("zh/**")
    }
    from(rootProject.file("launcher-bootstrap/native/miniz/LICENSE")) {
        into("META-INF/molifox/miniz")
    }
}

val nativeMessageFields =
    listOf(
        "preparationTitle",
        "javaRequiredTitle",
        "javaRequiredMessage",
        "javaDownloadCancelled",
        "javaPreparing",
        "javaExtracting",
        "startupWindowTitle",
        "startupInstruction",
        "logLocation",
        "provideLog",
        "showDetails",
        "hideDetails",
        "shaMissing",
        "bundleIncomplete",
        "httpsRequired",
        "downloadToolMissing",
        "downloadButton",
        "exitButton",
        "terminalPrompt",
        "javaCancelled",
        "archUnsupported",
        "javaPreparingLinux",
        "javaPreparingMacos",
        "checksumMissing",
        "checksumFailed",
        "runtimeUnusable",
    )
val nativeMessageLanguages = listOf("en-US", "zh-CN", "zh-TW")
val nativeMessageDirectory = layout.buildDirectory.dir("generated/native-i18n")
val nativeMessageHeader = nativeMessageDirectory.map { it.file("NativeMessages.h") }
val nativeMessageShell = nativeMessageDirectory.map { it.file("NativeMessages.sh") }

fun nativeCppText(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "")
        .replace("\n", "\\n")

fun nativeShellText(value: String): String = value.replace("'", "'\"'\"'")

val generateNativeMessages =
    tasks.register("generateNativeMessages") {
        val sources =
            nativeMessageLanguages.map { language ->
                rootProject.file("launcher-core/src/main/resources/i18n/$language/bootstrap.properties")
            }
        inputs.files(sources)
        outputs.files(nativeMessageHeader, nativeMessageShell)
        doLast {
            val messages =
                nativeMessageLanguages.associateWith { language ->
                    Properties().apply {
                        sources[nativeMessageLanguages.indexOf(language)].reader(Charsets.UTF_8).use { load(it) }
                    }
                }
            val header = nativeMessageHeader.get().asFile
            header.parentFile.mkdirs()
            header.writeText(
                buildString {
                    appendLine("#pragma once")
                    appendLine("struct NativeMessages {")
                    nativeMessageFields.forEach { appendLine("    const wchar_t* $it;") }
                    appendLine("};")
                    nativeMessageLanguages.forEach { language ->
                        val symbol = language.replace("-", "")
                        appendLine("static const NativeMessages nativeMessages$symbol = {")
                        nativeMessageFields.forEach { field ->
                            val value =
                                messages.getValue(language).getProperty("native.$field")
                                    ?: error("Missing native.$field in $language/bootstrap.properties")
                            appendLine("    L\"${nativeCppText(value)}\",")
                        }
                        appendLine("};")
                    }
                },
                Charsets.UTF_8,
            )
            val shell = nativeMessageShell.get().asFile
            shell.writeText(
                buildString {
                    appendLine("nativeLanguage=")
                    appendLine("if [ -f \"\$data/config/launcher.json\" ]; then")
                    appendLine(
                        "    nativeLanguage=$(sed -n 's/^[[:space:]]*\"language\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p' \"\$data/config/launcher.json\" | head -n 1)",
                    )
                    appendLine("fi")
                    appendLine("case \"\$nativeLanguage\" in")
                    appendLine("    zh-CN|zh-TW|zh-HK|en-US) ;;")
                    appendLine(
                        "    *) case \"\${LC_ALL:-\${LC_MESSAGES:-\${LANG:-}}}\" in zh_CN*|zh_SG*) nativeLanguage=zh-CN ;; zh_TW*|zh_HK*|zh_MO*) nativeLanguage=zh-TW ;; *) nativeLanguage=en-US ;; esac ;; ",
                    )
                    appendLine("esac")
                    appendLine("[ \"\$nativeLanguage\" = zh-HK ] && nativeLanguage=zh-TW")
                    appendLine("case \"\$nativeLanguage\" in")
                    nativeMessageLanguages.forEach { language ->
                        appendLine("$language)")
                        nativeMessageFields.forEach { field ->
                            val value =
                                messages.getValue(language).getProperty("native.$field")
                                    ?: error("Missing native.$field in $language/bootstrap.properties")
                            appendLine("    $field='${nativeShellText(value)}'")
                        }
                        appendLine("    ;;")
                    }
                    appendLine("esac")
                },
                Charsets.UTF_8,
            )
        }
    }

val nativeBuildDirectory = layout.buildDirectory.dir("portable/native")
val mingwDirectory = providers.environmentVariable("MOLIFOX_MINGW_BIN").orNull
val mingwPrefix = providers.environmentVariable("MOLIFOX_MINGW_PREFIX").orNull

fun nativeCompiler(name: String): String {
    val executable =
        when {
            name == "objdump" && mingwPrefix != null -> "llvm-objdump"
            mingwPrefix != null -> "$mingwPrefix-$name"
            else -> name
        }
    return mingwDirectory?.let { file("$it/$executable.exe").absolutePath } ?: executable
}

val windowsResource =
    tasks.register("prepareWindowsResource") {
        dependsOn("portableJar")
        inputs.property("launcherVersion", project.version.toString())
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
            val version = project.version.toString()
            val numericVersion =
                (version.split('.', '-').mapNotNull(String::toIntOrNull) + List(4) { 0 })
                    .take(4)
                    .joinToString(",")
            // 资源管理器「属性 → 详细信息」读取的是 VERSIONINFO；只放图标和载荷时这些字段全为空。
            // 中文块在前，英文块兜底；字符串保持 ASCII，避免 windres 在不同代码页下把版权符号编成乱码。
            val versionStrings =
                listOf(
                    "CompanyName" to "Tinmoli",
                    "FileDescription" to "MoliFox Launcher",
                    "FileVersion" to version,
                    "InternalName" to "MoliFox",
                    "LegalCopyright" to "Copyright (c) 2026 Tinmoli. Licensed under GPL-3.0-only.",
                    "OriginalFilename" to "MoliFox.exe",
                    "ProductName" to "MoliFox Launcher",
                    "ProductVersion" to version,
                )
            output.writeText(
                buildString {
                    appendLine("1 ICON \"$icon\"")
                    appendLine("101 RCDATA \"$payload\"")
                    appendLine()
                    appendLine("1 VERSIONINFO")
                    appendLine("FILEVERSION $numericVersion")
                    appendLine("PRODUCTVERSION $numericVersion")
                    appendLine("FILEFLAGSMASK 0x3fL")
                    appendLine("FILEFLAGS 0x0L")
                    appendLine("FILEOS 0x40004L")
                    appendLine("FILETYPE 0x1L")
                    appendLine("FILESUBTYPE 0x0L")
                    appendLine("BEGIN")
                    appendLine("  BLOCK \"StringFileInfo\"")
                    appendLine("  BEGIN")
                    for (block in listOf("080404b0", "040904b0")) {
                        appendLine("    BLOCK \"$block\"")
                        appendLine("    BEGIN")
                        for ((key, value) in versionStrings) {
                            appendLine("      VALUE \"$key\", \"$value\"")
                        }
                        appendLine("    END")
                    }
                    appendLine("  END")
                    appendLine("  BLOCK \"VarFileInfo\"")
                    appendLine("  BEGIN")
                    appendLine("    VALUE \"Translation\", 0x804, 1200, 0x409, 1200")
                    appendLine("  END")
                    appendLine("END")
                },
            )
        }
    }

val compileWindowsResource =
    tasks.register<Exec>("compileWindowsResource") {
        dependsOn(windowsResource)
        inputs.files(
            layout.buildDirectory.file("portable/MoliFox.jar"),
            rootProject.file("assets/icons/app/icon.ico"),
        )
        inputs.file(nativeBuildDirectory.map { it.file("molifox.rc") })
        inputs.property("windres", nativeCompiler("windres"))
        outputs.file(nativeBuildDirectory.map { it.file("resource.o") })
        commandLine(
            nativeCompiler("windres"),
            "-c",
            "65001",
            nativeBuildDirectory.get().file("molifox.rc").asFile,
            "-O",
            "coff",
            "-o",
            nativeBuildDirectory.get().file("resource.o").asFile,
        )
    }

// miniz.h 会随压缩 API 一起禁用归档写入，只声明压缩开关。
val windowsNativeDefinitions = listOf("-DMINIZ_NO_DEFLATE_APIS")

// 发布包只带启动器名：版本写在包内与 release 里，同目录更新时文件名保持不变。
val windowsPackageName = "MoliFox.exe"
val linuxPackageName = "MoliFox.run"

tasks.register<Exec>("portableWindows") {
    group = "distribution"
    dependsOn(compileWindowsResource, generateNativeMessages)
    inputs.file(rootProject.file("launcher-bootstrap/native/WindowsMain.cpp"))
    inputs.file(rootProject.file("launcher-bootstrap/native/WindowsUpdate.h"))
    inputs.file(nativeMessageHeader)
    inputs.dir(rootProject.file("launcher-bootstrap/native/miniz"))
    inputs.property("nativeDefinitions", windowsNativeDefinitions)
    inputs.property("gpp", nativeCompiler("g++"))
    inputs.file(nativeBuildDirectory.map { it.file("resource.o") })
    outputs.file(rootProject.file("dist/$windowsPackageName"))
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
        "-I",
        nativeMessageDirectory.get().asFile,
        "-x",
        "none",
        nativeBuildDirectory.get().file("resource.o").asFile,
        "-O2",
        "-s",
        "-static",
        "-municode",
        "-mwindows",
        "-lwinhttp",
        "-lbcrypt",
        "-lcomctl32",
        "-lshell32",
        "-ladvapi32",
        "-o",
        rootProject.file("dist/$windowsPackageName"),
    )
    doLast {
        val executable = rootProject.file("dist/$windowsPackageName")
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

val prepareLinuxRuntime =
    tasks.register("prepareLinuxRuntime") {
        inputs.files(configurations.runtimeClasspath, linuxRuntimeNative)
        outputs.file(linuxX64ManifestFile)
        doLast {
            val output = linuxX64ManifestFile.get().asFile
            output.parentFile.mkdirs()
            output.writeText(
                manifestJson(platformRuntimeEntries(linuxRuntimeNative, "linux-x64"), "linux-x64"),
            )
        }
    }

tasks.register("portableLinux") {
    group = "distribution"
    dependsOn("portableJar", generateNativeMessages)
    val payload = layout.buildDirectory.file("portable/MoliFox.jar")
    inputs.file(rootProject.file("launcher-bootstrap/native/LinuxMain.sh"))
    inputs.file(nativeMessageShell)
    inputs.file(payload)
    outputs.file(rootProject.file("dist/$linuxPackageName"))
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
                .replace("@NATIVE_MESSAGES@", nativeMessageShell.get().asFile.readText())
        val output = rootProject.file("dist/$linuxPackageName")
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

tasks.register("prepareLinuxArm64Runtime") {
    inputs.files(configurations.runtimeClasspath, linuxArm64RuntimeNative)
    outputs.file(linuxArm64ManifestFile)
    doLast {
        val output = linuxArm64ManifestFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            manifestJson(platformRuntimeEntries(linuxArm64RuntimeNative, "linux-arm64"), "linux-arm64"),
        )
    }
}

val macosRuntimeNative = nativeRuntimeConfiguration("macos")
val macosArm64RuntimeNative = nativeRuntimeConfiguration("macos", "arm64")

tasks.register("prepareMacosRuntime") {
    inputs.files(configurations.runtimeClasspath, macosRuntimeNative)
    outputs.file(macosX64ManifestFile)
    doLast {
        val output = macosX64ManifestFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            manifestJson(platformRuntimeEntries(macosRuntimeNative, "macos-x64"), "macos-x64"),
        )
    }
}

tasks.register("prepareMacosArm64Runtime") {
    inputs.files(configurations.runtimeClasspath, macosArm64RuntimeNative)
    outputs.file(macosArm64ManifestFile)
    doLast {
        val output = macosArm64ManifestFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            manifestJson(platformRuntimeEntries(macosArm64RuntimeNative, "macos-arm64"), "macos-arm64"),
        )
    }
}

// 全平台单 JAR：用户自装 Java 17+（下载运行时仍是 21），首次启动按六份清单下载当前系统运行时。
tasks.register<Copy>("universalJar") {
    group = "distribution"
    description = "把六清单 portableJar 复制为 dist/MoliFox.jar"
    dependsOn("portableJar")
    from(layout.buildDirectory.file("portable/MoliFox.jar"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "MoliFox.jar" }
}

tasks.register("verifyUniversalJar") {
    group = "verification"
    dependsOn("universalJar")
    doLast {
        val jar = rootProject.file("dist/MoliFox.jar")
        check(jar.isFile) { "缺少全平台 JAR：$jar" }
        val expected =
            mapOf(
                "molifox-runtime-windows-x64.json" to "-windows-x64",
                "molifox-runtime-windows-arm64.json" to "-windows-arm64",
                "molifox-runtime-linux-x64.json" to "-linux-x64",
                "molifox-runtime-linux-arm64.json" to "-linux-arm64",
                "molifox-runtime-macos-x64.json" to "-macos-x64",
                "molifox-runtime-macos-arm64.json" to "-macos-arm64",
            )
        ZipFile(jar).use { zip ->
            checkNotNull(zip.getEntry("dev/molifox/bootstrap/BootstrapMainKt.class")) {
                "全平台 JAR 缺少 BootstrapMainKt"
            }
            check(zip.getEntry("molifox-runtime.json") == null) {
                "全平台 JAR 不应再带回退主清单"
            }
            val manifest =
                zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF")).bufferedReader().use {
                    it.readText()
                }
            check(manifest.contains("Implementation-Version: ${project.version}")) {
                "全平台 JAR 的 Implementation-Version 与项目版本不一致"
            }
            expected.forEach { (resource, skikoSuffix) ->
                val entry = checkNotNull(zip.getEntry(resource)) { "全平台 JAR 缺少 $resource" }
                val json =
                    zip.getInputStream(entry).reader().use {
                        groovy.json.JsonSlurper().parse(it) as Map<*, *>
                    }
                val natives =
                    (json["dependencies"] as List<*>)
                        .map { it as Map<*, *> }
                        .map { it["filename"] as String }
                        .filter { it.startsWith("skiko-awt-runtime-") }
                check(natives.size == 1 && natives.single().contains(skikoSuffix)) {
                    "$resource 应只带 skiko $skikoSuffix 原生库：$natives"
                }
            }
        }
        val digest = sha256Of(jar)
        val report = rootProject.file("build/validation/universal-jar.md")
        report.parentFile.mkdirs()
        report.writeText(
            "# Universal JAR verification\n\n" +
                "- file: dist/MoliFox.jar\n" +
                "- bytes: ${jar.length()}\n" +
                "- sha256: $digest\n" +
                "- manifests: 6 (Windows/Linux/macOS x64/ARM64), no fallback manifest\n" +
                "- Java: 21+ required, no bundled JRE\n" +
                "- Not executed on ARM/macOS hardware in this build\n",
        )
        println("UNIVERSAL_JAR_OK ${jar.length()} bytes $digest")
    }
}

// Linux x64/ARM64 共用同一个 .run：payload 与全平台 JAR 完全相同，不再生成独立 arm64 包。
tasks.register("verifyDesktopPackages") {
    group = "verification"
    description =
        "Validate the dependency manifest and jar layout (no runtime jars embedded); does not launch Linux"
    dependsOn("portableWindows", "portableLinux", "universalJar")
    doLast {
        val requiredBundledIcons =
            ZipFile(extendedIcons.get()).use { icons ->
                usedIconPaths
                    .map { it.replace("Kt*.class", "Kt.class") }
                    .filter { icons.getEntry(it) != null }
            }
        val jar = rootProject.file("dist/MoliFox.jar")
        check(jar.isFile) { "缺少桌面 JAR：$jar" }
        ZipFile(jar).use { zip ->
            for (entry in listOf(
                "dev/molifox/multiplayer/domain/MultiplayerBackend.class",
                "dev/molifox/multiplayer/lobby/PclLobbyBackend.class",
                "dev/molifox/desktop/AppearancePageKt.class",
                "dev/molifox/bootstrap/BootstrapMainKt.class",
            )) {
                check(zip.getEntry(entry) != null) { "桌面 JAR 缺少 $entry" }
            }
            for (icon in requiredBundledIcons) {
                check(zip.getEntry(icon) != null) { "桌面 JAR 缺少界面图标 $icon" }
            }
            for (notice in listOf("LICENSE", "NOTICE", "THIRD_PARTY.md")) {
                check(zip.getEntry("META-INF/molifox/$notice") != null) {
                    "桌面 JAR 缺少许可声明 $notice"
                }
            }
            val agreements =
                listOf(
                    "AGREEMENT.md",
                    "AGREEMENT.en-US.md",
                    "AGREEMENT.zh-TW.md",
                    "AGREEMENT.zh-HK.md",
                )
            for (agreement in agreements) {
                check(zip.getEntry("META-INF/molifox/$agreement") != null) {
                    "桌面 JAR 缺少用户协议 $agreement"
                }
            }
            val backgrounds = zip.getEntry("META-INF/molifox/default-backgrounds.zip")
            check(backgrounds != null) { "桌面 JAR 缺少默认背景压缩资源" }
            val backgroundNames = mutableSetOf<String>()
            ZipInputStream(zip.getInputStream(backgrounds)).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    if (!entry.isDirectory) backgroundNames += entry.name
                }
            }
            check(backgroundNames == setOf("default-light.jpg", "default-dark.jpg")) {
                "桌面 JAR 的默认背景资源不完整"
            }
            check(zip.getEntry("backgrounds/default-backgrounds.zip") == null) {
                "桌面 JAR 重复嵌入默认背景"
            }
            check(zip.getEntry("icons/app/icon.png") != null) { "桌面 JAR 缺少窗口图标" }
            for (name in listOf("icon.ico", "icon-white-1024.png", "icon-white-300.png")) {
                check(zip.getEntry("icons/app/$name") == null) { "桌面 JAR 包含多余图标 $name" }
            }
            check(
                zip.entries().asSequence().none {
                    it.name.startsWith("molifox-dependencies/")
                },
            ) { "MoliFox.jar 仍然内嵌了运行时依赖" }

            val expected =
                mapOf(
                    "molifox-runtime-windows-x64.json" to "-windows-x64",
                    "molifox-runtime-windows-arm64.json" to "-windows-arm64",
                    "molifox-runtime-linux-x64.json" to "-linux-x64",
                    "molifox-runtime-linux-arm64.json" to "-linux-arm64",
                    "molifox-runtime-macos-x64.json" to "-macos-x64",
                    "molifox-runtime-macos-arm64.json" to "-macos-arm64",
                )
            expected.forEach { (resource, nativeTag) ->
                val entry = checkNotNull(zip.getEntry(resource)) { "桌面 JAR 缺少 $resource" }
                val json =
                    zip.getInputStream(entry).reader().use {
                        groovy.json.JsonSlurper().parse(it) as Map<*, *>
                    }
                val dependencies = (json["dependencies"] as List<*>).map { it as Map<*, *> }
                check(dependencies.isNotEmpty()) { "$resource 运行时清单为空" }
                check((json["version"] as String).contains("-")) { "$resource 清单版本号缺少依赖哈希" }
                check(
                    dependencies.filter { it["group"] == "com.materialkolor" }.all {
                        it["filename"] == "${it["artifact"]}-${it["version"]}.jar"
                    },
                ) { "$resource Material 运行依赖的文件名不符合 Maven 下载路径" }
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
                check(native.size == 1) { "$resource 应只有一个 skiko 原生库：$native" }
                check((native.single()["filename"] as String).contains(nativeTag)) {
                    "$resource 的 skiko 架构不正确"
                }
                check(
                    dependencies.any {
                        (it["filename"] as String).startsWith("kotlin-stdlib-")
                    },
                ) { "$resource 缺少 kotlin-stdlib" }
            }
            check(zip.getEntry("molifox-runtime.json") == null) { "桌面 JAR 不应带回退主清单" }
        }
        val report = rootProject.file("build/validation/desktop-packages.md")
        report.parentFile.mkdirs()
        report.writeText(
            "# Desktop package verification\n\n" +
                "- Artifacts: MoliFox.exe, MoliFox.run, MoliFox.jar\n" +
                "- Universal JAR: six runtime manifests (Windows/Linux/macOS x64/ARM64), no fallback manifest\n" +
                "- Embedded runtime jars: none (downloaded on first launch)\n" +
                "- Windows shell: one 32-bit exe serves x64 and ARM64; Java/JAR paths are fixed names\n" +
                "- Linux shell: one .run contains the same universal JAR; launcher selects by os.arch\n" +
                "- Windows/Linux ARM64 and macOS: package/manifest assembled; not executed on ARM or macOS hardware\n" +
                "- Linux desktop runtime: structure checked here; launcher UI was started separately in WSL Arch + Xvfb (software rendering), not on this Windows host\n",
        )
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("com.materialkolor:material-kolor:4.0.5")
    implementation("org.openani.mediamp:mediamp-mpv:0.5.3")
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

sourceSets["uiChecks"].kotlin.srcDirs(rootProject.file("tests/desktop-app/uiChecks/kotlin"))
sourceSets["uiChecks"].java.apply {
    srcDir(rootProject.file("tests/tools"))
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
            .dir("tests/.tmp/${project.name}-$name")
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
                rootProject.file("tests/tools/$probe.java").absolutePath,
                rootProject.file("tests/tools/UiSmoke.java").absolutePath,
                rootProject.file("tests/tools/UiFunctionalChecks.java").absolutePath,
                rootProject.file("tests/tools/InstanceActionChecks.java").absolutePath,
                rootProject.file("tests/tools/SettingsActionChecks.java").absolutePath,
                rootProject.file("tests/tools/DownloadActionChecks.java").absolutePath,
                rootProject.file("tests/tools/JavaActionChecks.java").absolutePath,
                rootProject.file("tests/tools/AccountActionChecks.java").absolutePath,
                rootProject.file("tests/tools/PackActionChecks.java").absolutePath,
                rootProject.file("tests/tools/WindowActionChecks.java").absolutePath,
                rootProject.file("tests/tools/LogActionChecks.java").absolutePath,
                rootProject.file("tests/tools/MicrosoftActionChecks.java").absolutePath,
                rootProject.file("tests/tools/TestWindowFocus.java").absolutePath,
                rootProject.file("tests/tools/NativeDialogActions.java").absolutePath,
                rootProject.file("tests/tools/RefinementActions.java").absolutePath,
                rootProject.file("tests/tools/PackDragChecks.java").absolutePath,
                rootProject.file("tests/tools/NativeRuntimeCache.java").absolutePath,
                rootProject.file("tests/tools/NativeMemoryProbe.java").absolutePath,
            ) == 0,
        ) {
            "$probe compilation failed"
        }
        if (native) {
            // 沙箱里保留固定文件名，探针按 MoliFox.exe 启动；发布名不影响运行。
            rootProject
                .file("dist/$windowsPackageName")
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
        systemProperty("molifox.ui.deletionOnly", providers.gradleProperty("uiDeletionOnly").getOrElse("false"))
        systemProperty("molifox.ui.lifecycleOnly", providers.gradleProperty("uiLifecycleOnly").getOrElse("false"))
        systemProperty("molifox.ui.exportOnly", providers.gradleProperty("uiExportOnly").getOrElse("false"))
        systemProperty("molifox.ui.agreementOnly", providers.gradleProperty("uiAgreementOnly").getOrElse("false"))
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
            .dir("tests/.tmp/desktop-app-nativePickerSmoke")
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
                rootProject.file("tests/tools/NativePickerSmoke.java").absolutePath,
                rootProject.file("tests/tools/NativeDialogActions.java").absolutePath,
            ) == 0,
        )
        setArgs(listOf(File(root, "fixtures").absolutePath))
    }
}

tasks.register<JavaExec>("appearanceUiSmoke") {
    group = "verification"
    description = "Windows appearance UI check with automatic removal of its isolated test data"
    dependsOn("uiChecksClasses")
    classpath =
        files(uiChecksCompilation.output.allOutputs, uiChecksCompilation.runtimeDependencyFiles)
    mainClass.set("dev.molifox.desktop.checks.UiSmokeMainKt")
    systemProperty("molifox.ui.refinements", "true")
    // 附带 -Dmolifox.ui.colorsOnly=true 时只跑 ThemeColorCases 的配色来源流程；默认跑 refinements 全套。
    systemProperty(
        "molifox.ui.colorsOnly",
        providers.systemProperty("molifox.ui.colorsOnly").getOrElse("false"),
    )
    // UiSmokeMain 要求测试数据固定隔离在 tests/.tmp 下，运行结束即清理，失败时保留现场。
    val testDirectory =
        rootProject.layout.projectDirectory
            .dir("tests/.tmp/${project.name}-appearanceUiSmoke")
            .asFile
    systemProperty("molifox.test.dir", testDirectory.absolutePath)
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Dcompose.accessibility.enable=true")
    workingDir(rootProject.projectDir)
    timeout.set(Duration.ofMinutes(6))
    doLast {
        testDirectory.deleteRecursively()
    }
}
