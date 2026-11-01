// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责获取 Fabric/Quilt 与 Forge/NeoForge 的加载器配置。

package dev.molifox.core

import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.jar.JarFile
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name

/** Forge 安装程序下载阶段占用的进度比例。 */
private const val FORGE_INSTALLER_PROGRESS = 0.1f

/** Forge 依赖库下载阶段占用的进度区间。 */
private const val FORGE_LIBRARIES_PROGRESS_START = 0.2f
private const val FORGE_LIBRARIES_PROGRESS_END = 0.5f

/** 处理器阶段的进度起点与跨度：起点之后按处理器数量均分。 */
private const val FORGE_PROCESSORS_PROGRESS_START = 0.5f
private const val FORGE_PROCESSORS_PROGRESS_SPAN = 0.45f

/** 安装器变量最多展开的轮数，防止环形变量让展开永不收敛。 */
private const val FORGE_VARIABLE_EXPANSION_ROUNDS = 12

/**
 * 加载器配置的获取：Fabric/Quilt 直接取合成好的配置，Forge/NeoForge 需要运行安装程序里的处理器。
 *
 * 两种结果都只作为合成输入，不单独写入 `versions/`；最终落盘的是实例目录里的一份完整配置。
 */
internal class LoaderProfileFetcher(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val java: JavaService,
    private val fabricMetaBase: String,
    private val quiltMetaBase: String,
    private val legacyFabricMetaBase: String,
) {
    internal suspend fun fetchFabricProfile(
        instance: GameInstance,
        context: JobContext,
        from: Float = 0f,
        to: Float = 1f,
    ): JsonObject {
        context.progress(
            from,
            I18n.t(
                "networkB2.loaderProfileFetcher.loadingProfile",
                InstallPhase.LOADER,
                instance.loader.title,
            ),
        )
        val host =
            when (instance.loader) {
                LoaderType.Fabric -> fabricMetaBase
                LoaderType.LegacyFabric -> legacyFabricMetaBase
                else -> quiltMetaBase
            }
        val apiVersion = if (instance.loader == LoaderType.Quilt) "v3" else "v2"
        val profile =
            network
                .json(
                    "$host/$apiVersion/versions/loader/${encoded(
                        instance.gameVersion,
                    )}/${encoded(instance.loaderVersion)}/profile/json",
                ).asJsonObject
        context.checkCancelled()
        context.progress(
            to,
            I18n.t(
                "networkB2.loaderProfileFetcher.profileReady",
                InstallPhase.LOADER,
                instance.loader.title,
            ),
        )
        return profile
    }

    /** OptiFine 与 LiteLoader 的配置生成各自独立，避免本类继续变大。 */
    internal val optiFine = OptiFineInstaller(store, network, java)
    internal val liteLoader = LiteLoaderInstaller(network)

    internal suspend fun fetchForgeProfile(
        instance: GameInstance,
        base: ResolvedProfile,
        context: JobContext,
        from: Float = 0f,
        to: Float = 1f,
    ): JsonObject {
        val at = { fraction: Float -> from + (to - from) * fraction }
        val root = store.minecraftRoot(instance)
        val archive = downloadForgeInstaller(instance, context, at)
        val runtime =
            java.resolve(
                base.requiredJava,
                instance.javaPath.ifBlank { store.data.settings.javaPath },
                context,
            )
        val temporaryRoot =
            store.temporaryDirectory.resolve("forge-${UUID.randomUUID()}").apply {
                createDirectories()
            }
        try {
            return ZipFile(archive.toFile()).use { zip ->
                installForgeProfile(
                    zip = zip,
                    instance = instance,
                    base = base,
                    root = root,
                    runtime = runtime,
                    archive = archive,
                    temporaryRoot = temporaryRoot,
                    context = context,
                    at = at,
                    to = to,
                )
            }
        } finally {
            deleteForgeTemporaryRoot(temporaryRoot)
        }
    }

    /** 下载安装程序并按官方 `.sha1` 校验；返回本地缓存路径。 */
    private suspend fun downloadForgeInstaller(
        instance: GameInstance,
        context: JobContext,
        at: (Float) -> Float,
    ): Path {
        val (url, project, version) = forgeInstallerUrl(instance)
        val archive =
            store.downloadDirectory.resolve(
                "loaders/${safeFilename(project)}-${safeFilename(version)}-installer.jar",
            )
        if (archive.exists()) return archive
        context.progress(
            at(FORGE_INSTALLER_PROGRESS),
            I18n.t(
                "networkB2.loaderProfileFetcher.downloadingInstaller",
                InstallPhase.LOADER,
                instance.loader.title,
            ),
        )
        val installerHash =
            if (instance.loader == LoaderType.Cleanroom) {
                ""
            } else {
                network
                    .text("$url.sha1")
                    .trim()
                    .substringBefore(' ')
                    .trim()
            }
        require(installerHash.isBlank() || installerHash.matches(Regex("[a-fA-F0-9]{40}"))) {
            I18n.t("networkB2.loaderProfileFetcher.installerHashMissing")
        }
        network.downloadTracked(context, DownloadArtifact(url, archive, installerHash))
        return archive
    }

    /** 安装包内的编排：读配置、解出安装器自带的库、装依赖、展开变量并运行处理器。 */
    private suspend fun installForgeProfile(
        zip: ZipFile,
        instance: GameInstance,
        base: ResolvedProfile,
        root: Path,
        runtime: JavaRuntime,
        archive: Path,
        temporaryRoot: Path,
        context: JobContext,
        at: (Float) -> Float,
        to: Float,
    ): JsonObject {
        val (profile, versionJson) = readForgeInstallerProfile(zip)
        // 加载器配置只作为合成输入：id/inheritsFrom 由发布阶段按目标实例决定，
        // 不再固定写成 molifox-forge-… 目录。
        versionJson.remove("inheritsFrom")
        versionJson.remove("jar")
        extractInstallerArtifacts(zip, profile, versionJson, root, context)
        downloadForgeLibraries(profile, root, context, at)
        val variables =
            buildForgeVariables(profile, root, base, instance, archive, zip, temporaryRoot)
        val processors =
            profile
                .array("processors")
                .map { it.asJsonObject }
                .filter {
                    !it.has("sides") ||
                        it.array("sides").any { side -> side.asString == "client" }
                }
        runForgeProcessors(processors, variables, root, runtime, context, at)
        context.progress(
            to,
            I18n.t(
                "networkB2.loaderProfileFetcher.profileReady",
                InstallPhase.LOADER,
                instance.loader.title,
            ),
        )
        return versionJson
    }

    /** 安装包里的配置：`install_profile.json` 与（内嵌或单独的）`version.json`。 */
    private fun readForgeInstallerProfile(zip: ZipFile): Pair<JsonObject, JsonObject> {
        val profile =
            zip.getEntry("install_profile.json")?.let {
                zip.getInputStream(it).reader().use { reader ->
                    com.google.gson.JsonParser
                        .parseReader(reader)
                        .asJsonObject
                }
            } ?: error(I18n.t("networkB2.loaderProfileFetcher.installerMissingProfile"))
        val versionJson =
            if (profile.has("versionInfo")) {
                profile.obj("versionInfo")
            } else {
                val jsonEntry =
                    zip.getEntry(profile.string("json", "/version.json").removePrefix("/"))
                        ?: error(I18n.t("networkB2.loaderProfileFetcher.installerMissingVersionJson"))
                zip.getInputStream(jsonEntry).reader().use {
                    com.google.gson.JsonParser
                        .parseReader(it)
                        .asJsonObject
                }
            }
        return profile to versionJson
    }

    /** 只解出安装器自带的 maven 产物，绝不按压缩包里的任意路径写盘。 */
    private suspend fun extractInstallerArtifacts(
        zip: ZipFile,
        profile: JsonObject,
        versionJson: JsonObject,
        root: Path,
        context: JobContext,
    ) {
        // Extract only installer-owned Maven artifacts, never arbitrary archive paths.
        for ((entry, target) in validateArchive(zip, "maven/", root.resolve("libraries"))) {
            context.checkCancelled()
            context.writeOutput(target) {
                target.parent.createDirectories()
                zip.getInputStream(entry).use {
                    Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        // Pre-1.13 installers embed the universal JAR outside maven/.
        if (!profile.has("versionInfo")) return
        val embedded =
            zip.entries().asSequence().firstOrNull {
                !it.isDirectory &&
                    !it.name.contains('/') &&
                    it.name.endsWith("universal.jar")
            }
        val forgeLibrary =
            versionJson
                .array("libraries")
                .map { it.asJsonObject }
                .find { it.string("name").startsWith("net.minecraftforge:forge:") }
        if (embedded == null || forgeLibrary == null) return
        val target =
            safePath(
                root.resolve("libraries"),
                mavenPath(forgeLibrary.string("name")),
            )
        context.writeOutput(target) {
            target.parent.createDirectories()
            zip.getInputStream(embedded).use {
                Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private suspend fun downloadForgeLibraries(
        profile: JsonObject,
        root: Path,
        context: JobContext,
        at: (Float) -> Float,
    ) {
        val resolver = VersionResolver(root, store.platform)
        val libraries =
            profile
                .array("libraries")
                .map { it.asJsonObject }
                .filter { resolver.allowed(it) }
                .flatMap { resolver.libraryArtifacts(it).map { pair -> pair.first } }
        network.downloadAll(
            libraries,
            store.data.settings.downloadThreads,
            context,
            I18n.t("networkB2.loaderProfileFetcher.downloadingLibraries", InstallPhase.LOADER),
            at(FORGE_LIBRARIES_PROGRESS_START),
            at(FORGE_LIBRARIES_PROGRESS_END),
        )
    }

    /** 安装器处理器要用的变量表：固定变量 + `data` 段里的客户端取值（斜杠开头的是包内文件）。 */
    private fun buildForgeVariables(
        profile: JsonObject,
        root: Path,
        base: ResolvedProfile,
        instance: GameInstance,
        archive: Path,
        zip: ZipFile,
        temporaryRoot: Path,
    ): Map<String, String> {
        val variables =
            mutableMapOf(
                "SIDE" to "client",
                "ROOT" to root.toString(),
                "LIBRARY_DIR" to root.resolve("libraries").toString(),
                "MINECRAFT_JAR" to
                    root.resolve("versions/${base.jarId}/${base.jarId}.jar").toString(),
                "MINECRAFT_VERSION" to instance.gameVersion,
                "INSTALLER" to archive.toString(),
            )
        for ((key, element) in profile.obj("data").entrySet()) {
            val value = element.asJsonObject.string("client")
            variables[key] =
                if (
                    value.startsWith('/') && zip.getEntry(value.removePrefix("/")) != null
                ) {
                    val entry = zip.getEntry(value.removePrefix("/"))
                    val target = safePath(temporaryRoot, value.removePrefix("/"))
                    target.parent.createDirectories()
                    zip.getInputStream(entry).use {
                        Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                    target.toString()
                } else {
                    value
                }
        }
        return variables
    }

    private suspend fun runForgeProcessors(
        processors: List<JsonObject>,
        variables: Map<String, String>,
        root: Path,
        runtime: JavaRuntime,
        context: JobContext,
        at: (Float) -> Float,
    ) {
        for ((index, processor) in processors.withIndex()) {
            context.checkCancelled()
            val finishedFraction = index.toFloat() / maxOf(processors.size, 1)
            val fraction =
                FORGE_PROCESSORS_PROGRESS_START + finishedFraction * FORGE_PROCESSORS_PROGRESS_SPAN
            context.progress(
                at(fraction),
                I18n.t(
                    "networkB2.loaderProfileFetcher.processingComponent",
                    InstallPhase.LOADER,
                    index + 1,
                    processors.size,
                ),
            )
            runForgeProcessor(processor, variables, root, runtime, context)
        }
    }

    private suspend fun runForgeProcessor(
        processor: JsonObject,
        variables: Map<String, String>,
        root: Path,
        runtime: JavaRuntime,
        context: JobContext,
    ) {
        val outputs = processorOutputs(processor, variables, root)
        if (outputs.isNotEmpty() && outputs.all { (file, hash) -> isFreshOutput(file, hash) }) {
            return
        }
        val artifact =
            safePath(
                root.resolve("libraries"),
                mavenPath(processor.string("jar")),
            )
        val mainClass =
            JarFile(artifact.toFile()).use {
                it.manifest?.mainAttributes?.getValue("Main-Class")
            } ?: error(I18n.t("networkB2.loaderProfileFetcher.processorMissingMain"))
        val classpath =
            processorClasspath(processor, artifact, root, store.platform.classpathSeparator)
        val trackedOutputs =
            outputs.map { (file, _) ->
                context.trackOutput(Path.of(file))
            }
        try {
            runJavaProcess(
                runtime.path,
                listOf("-cp", classpath, mainClass) +
                    processor.array("args").map {
                        expandForgeVariable(it.asString, variables, root)
                    },
                root,
            ) { line ->
                store.log(line, level = LogLevel.Trace)
            }
        } finally {
            trackedOutputs.forEach { it() }
        }
        verifyProcessorOutputs(outputs)
    }

    private fun deleteForgeTemporaryRoot(temporaryRoot: Path) {
        val cacheRoot = store.temporaryDirectory.toAbsolutePath().normalize()
        check(
            temporaryRoot.toAbsolutePath().normalize().parent == cacheRoot &&
                temporaryRoot.name.startsWith("forge-"),
        )
        if (temporaryRoot.exists() && !Files.isSymbolicLink(temporaryRoot)) {
            Files.walk(temporaryRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}

/** 展开 `{变量}` 引用；方括号包起来的结果是 maven 坐标，单引号包起来的整体去掉引号。 */
private fun expandForgeVariable(
    value: String,
    variables: Map<String, String>,
    root: Path,
): String {
    var result = value
    repeat(FORGE_VARIABLE_EXPANSION_ROUNDS) {
        result =
            Regex("\\{([^}]+)}").replace(result) { match ->
                variables[match.groupValues[1]] ?: match.value
            }
    }
    if (result.startsWith('[') && result.endsWith(']')) {
        return safePath(
            root.resolve("libraries"),
            mavenPath(result.substring(1, result.length - 1)),
        ).toString()
    }
    if (result.startsWith('\'') && result.endsWith('\'')) {
        result = result.substring(1, result.length - 1)
    }
    require(!Regex("\\{[^}]+}").containsMatchIn(result)) {
        I18n.t("networkB2.loaderProfileFetcher.unresolvedVariable", result)
    }
    return result
}

/** 处理器声明的输出：文件路径与期望哈希。 */
private fun processorOutputs(
    processor: JsonObject,
    variables: Map<String, String>,
    root: Path,
): List<Pair<String, String>> =
    processor.obj("outputs").entrySet().map {
        expandForgeVariable(it.key, variables, root) to
            expandForgeVariable(it.value.asString, variables, root)
    }

/** 处理器的 classpath：声明的库按 maven 坐标落盘后，再接上处理器自己的 JAR。 */
private fun processorClasspath(
    processor: JsonObject,
    artifact: Path,
    root: Path,
    separator: String,
): String =
    (
        processor.array("classpath").map {
            safePath(
                root.resolve("libraries"),
                mavenPath(it.asString),
            ).toString()
        } + artifact.toString()
    ).joinToString(separator)

/** 处理器声明的输出必须存在且哈希一致，否则说明这一步没有真正产出。 */
private fun verifyProcessorOutputs(outputs: List<Pair<String, String>>) {
    outputs.forEach { (file, hash) ->
        require(
            Path.of(file).exists() &&
                (
                    !hash.matches(Regex("[0-9a-fA-F]{40}")) ||
                        digest(Path.of(file)).equals(hash, true)
                ),
        ) {
            I18n.t("networkB2.loaderProfileFetcher.processorOutputInvalid")
        }
    }
}

/** Forge/NeoForge 安装器在各自 Maven 上的直链。 */
private fun forgeInstallerUrl(instance: GameInstance): Triple<String, String, String> =
    when (instance.loader) {
        LoaderType.Cleanroom ->
            Triple(
                "https://hmcl.glavo.site/metadata/cleanroom/files/" +
                    "cleanroom-${encoded(instance.loaderVersion)}-installer.jar",
                "cleanroom",
                instance.loaderVersion,
            )
        LoaderType.Forge -> forgeInstallUrl(instance, forge = true)
        else -> forgeInstallUrl(instance, forge = false)
    }

private fun forgeInstallUrl(
    instance: GameInstance,
    forge: Boolean,
): Triple<String, String, String> {
    val project = if (forge || instance.gameVersion == "1.20.1") "forge" else "neoforge"
    val version =
        if (forge) {
            "${instance.gameVersion}-${instance.loaderVersion}"
        } else {
            instance.loaderVersion
        }
    val host =
        if (forge) {
            "https://maven.minecraftforge.net"
        } else {
            "https://maven.neoforged.net/releases"
        }
    val group = if (forge) "net/minecraftforge" else "net/neoforged"
    val url = "$host/$group/$project/$version/$project-$version-installer.jar"
    return Triple(url, project, version)
}
