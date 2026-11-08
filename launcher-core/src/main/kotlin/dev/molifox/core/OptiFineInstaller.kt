// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 安装 OptiFine：用官方安装器里的 Patcher 处理原版客户端 JAR，再生成 LaunchWrapper 启动配置。

package dev.molifox.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

/** BMCLAPI 的 OptiFine 安装器下载根地址。 */
private const val OPTIFINE_DOWNLOAD_ROOT = "https://bmclapi2.bangbang93.com/optifine"

/** 安装器下载阶段占用的进度比例。 */
private const val OPTIFINE_DOWNLOAD_PROGRESS = 0.4f

/** Patcher 处理阶段占用的进度起点。 */
private const val OPTIFINE_PATCH_PROGRESS = 0.7f

/** OptiFine 与 LaunchWrapper 的配置合成。 */
internal class OptiFineInstaller(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val java: JavaService,
) {
    internal suspend fun fetchProfile(
        instance: GameInstance,
        base: ResolvedProfile,
        context: JobContext,
        from: Float = 0f,
        to: Float = 1f,
    ): JsonObject {
        val at = { fraction: Float -> from + (to - from) * fraction }
        val root = store.minecraftRoot(instance)
        val fullVersion = "${instance.gameVersion}_${instance.loaderVersion}"
        val clientJar = resolveClientJar(instance, base)
        val installer = downloadInstaller(instance, context, at)
        val runtime =
            java.resolve(
                base.requiredJava,
                instance.javaPath.ifBlank { store.data.settings.javaPath },
                context,
            )
        ensurePatched(installer, clientJar, optiFineLibrary(root, fullVersion), runtime.path, context, at)
        val libraries = JsonArray().apply { add(optiFineLibraryEntry(fullVersion)) }
        addLaunchWrapper(installer, root, libraries, context)
        context.progress(
            to,
            I18n.t(
                "networkB2.loaderProfileFetcher.profileReady",
                InstallPhase.LOADER,
                instance.loader.title,
            ),
        )
        return launchProfile(libraries)
    }

    private fun resolveClientJar(
        instance: GameInstance,
        base: ResolvedProfile,
    ): Path {
        val path =
            store
                .minecraftRoot(instance)
                .resolve("versions")
                .resolve(safeFilename(base.jarId))
                .resolve("${base.jarId}.jar")
        require(path.isRegularFile()) {
            I18n.t("networkB2.optiFineInstaller.clientJarMissing", base.jarId)
        }
        return path
    }

    /** OptiFine 的安装器缓存；同一游戏版本/类型/补丁只下载一次。 */
    private suspend fun downloadInstaller(
        instance: GameInstance,
        context: JobContext,
        at: (Float) -> Float,
    ): Path {
        val type = instance.loaderVersion.substringBeforeLast('_')
        val patch = instance.loaderVersion.substringAfterLast('_')
        require(type.isNotBlank() && patch.isNotBlank() && type != instance.loaderVersion) {
            I18n.t("networkB2.optiFineInstaller.invalidVersion", instance.loaderVersion)
        }
        val target =
            store.downloadDirectory.resolve(
                "loaders/optifine-${safeFilename(instance.gameVersion)}" +
                    "-${safeFilename(type)}-${safeFilename(patch)}.jar",
            )
        if (target.isRegularFile()) return target
        target.parent.createDirectories()
        context.progress(
            at(OPTIFINE_DOWNLOAD_PROGRESS),
            I18n.t(
                "networkB2.loaderProfileFetcher.downloadingInstaller",
                InstallPhase.LOADER,
                "OptiFine",
            ),
        )
        val url = installerUrl(instance, type, patch)
        network.downloadTracked(context, DownloadArtifact(url, target))
        return target
    }

    private fun installerUrl(
        instance: GameInstance,
        type: String,
        patch: String,
    ): String =
        "$OPTIFINE_DOWNLOAD_ROOT/${encoded(instance.gameVersion)}/" +
            "${encoded(type)}/${encoded(patch)}"

    /** Patcher 生成带 OptiFine 类的库 JAR；老版本没有 Patcher 时安装器本身就是可加载的库。 */
    private suspend fun ensurePatched(
        installer: Path,
        clientJar: Path,
        output: Path,
        javaPath: String,
        context: JobContext,
        at: (Float) -> Float,
    ) {
        if (output.isRegularFile()) return
        output.parent.createDirectories()
        if (!hasEntry(installer, "optifine/Patcher.class")) {
            context.writeOutput(output) { Files.copy(installer, output) }
            return
        }
        context.progress(
            at(OPTIFINE_PATCH_PROGRESS),
            I18n.t("networkB2.optiFineInstaller.patching"),
        )
        val tracked = context.trackOutput(output)
        try {
            runJavaProcess(
                javaPath,
                listOf(
                    "-cp",
                    installer.toString(),
                    "optifine.Patcher",
                    clientJar.toString(),
                    installer.toString(),
                    output.toString(),
                ),
                output.parent,
            ) { line -> store.log(line, level = LogLevel.Trace) }
        } finally {
            tracked()
        }
    }

    private fun launchProfile(libraries: JsonArray): JsonObject =
        JsonObject().apply {
            addProperty("mainClass", "net.minecraft.launchwrapper.Launch")
            add("libraries", libraries)
            add(
                "arguments",
                JsonObject().apply {
                    add(
                        "game",
                        JsonArray().apply {
                            add("--tweakClass")
                            add("optifine.OptiFineTweaker")
                        },
                    )
                },
            )
        }

    private fun optiFineLibraryEntry(fullVersion: String): JsonObject =
        JsonObject().apply {
            addProperty("name", "optifine:OptiFine:$fullVersion")
        }

    private fun optiFineLibrary(
        root: Path,
        fullVersion: String,
    ): Path = root.resolve("libraries").resolve(mavenPath("optifine:OptiFine:$fullVersion"))

    private fun hasEntry(
        archive: Path,
        name: String,
    ): Boolean = ZipFile(archive.toFile()).use { it.getEntry(name) != null }
}

private fun addLaunchWrapper(
    installer: Path,
    root: Path,
    libraries: JsonArray,
    context: JobContext,
) {
    val copied =
        ZipFile(installer.toFile()).use { zip ->
            copyBundledLaunchWrapper(zip, root, libraries, context)
        }
    if (!copied) addFallbackLaunchWrapper(libraries)
}

private fun copyBundledLaunchWrapper(
    zip: ZipFile,
    root: Path,
    libraries: JsonArray,
    context: JobContext,
): Boolean =
    copyFixedLaunchWrapper(zip, root, libraries, context) ||
        copyVersionedLaunchWrapper(zip, root, libraries, context)

private fun copyFixedLaunchWrapper(
    zip: ZipFile,
    root: Path,
    libraries: JsonArray,
    context: JobContext,
): Boolean =
    copyLaunchWrapper(
        zip,
        "launchwrapper-2.0.jar",
        "optifine:launchwrapper:2.0",
        root,
        libraries,
        context,
    ) != null

private fun copyVersionedLaunchWrapper(
    zip: ZipFile,
    root: Path,
    libraries: JsonArray,
    context: JobContext,
): Boolean {
    val version = launchWrapperVersion(zip) ?: return false
    return copyLaunchWrapper(
        zip,
        "launchwrapper-of-$version.jar",
        "optifine:launchwrapper-of:$version",
        root,
        libraries,
        context,
    ) != null
}

private fun launchWrapperVersion(zip: ZipFile): String? {
    val entry = zip.getEntry("launchwrapper-of.txt") ?: return null
    val text =
        zip
            .getInputStream(entry)
            .bufferedReader()
            .use { it.readText() }
            .trim()
    return text.takeIf { it.isNotBlank() }
}

private fun copyLaunchWrapper(
    zip: ZipFile,
    entryName: String,
    mavenName: String,
    root: Path,
    libraries: JsonArray,
    context: JobContext,
): JsonObject? {
    val entry = zip.getEntry(entryName) ?: return null
    val target = root.resolve("libraries").resolve(mavenPath(mavenName))
    target.parent.createDirectories()
    context.writeOutput(target) {
        zip.getInputStream(entry).use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
    return JsonObject()
        .apply { addProperty("name", mavenName) }
        .also(libraries::add)
}

private fun addFallbackLaunchWrapper(libraries: JsonArray) {
    libraries.add(
        JsonObject().apply {
            addProperty("name", "net.minecraft:launchwrapper:1.12")
            add(
                "downloads",
                JsonObject().apply {
                    add(
                        "artifact",
                        JsonObject().apply {
                            addProperty(
                                "path",
                                "net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar",
                            )
                            addProperty(
                                "url",
                                "https://libraries.minecraft.net/net/minecraft/" +
                                    "launchwrapper/1.12/launchwrapper-1.12.jar",
                            )
                        },
                    )
                },
            )
        },
    )
}
