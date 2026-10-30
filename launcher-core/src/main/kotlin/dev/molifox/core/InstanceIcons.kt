// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例图标导入、优先级解析和共享资源回收。

package dev.molifox.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readBytes
import kotlin.io.path.reader
import kotlin.io.path.writeBytes

/** 实例图标文件上限 2 MiB；读取时多读 1 字节，用来判断原文件是否更大。 */
private const val MAX_ICON_BYTES = 2L * 1024 * 1024

/** 图标必须是 16–1024 像素的正方形。 */
private const val MIN_ICON_SIZE = 16
private const val MAX_ICON_SIZE = 1024

/** PCL 的 Setup.ini 只是几行键值，超过这个大小就不当配置文件读。 */
private const val MAX_SETUP_INI_BYTES = 128L * 1024

enum class InstanceIconStyle(
    val title: String,
    val loader: LoaderType?,
) {
    Auto("自动：原版草方块 / 加载器图标", null),
    Grass("草方块", LoaderType.Vanilla),
    Fabric("布卷", LoaderType.Fabric),
    Quilt("拼布", LoaderType.Quilt),
    Forge("铁砧", LoaderType.Forge),
    NeoForge("橙色铁砧", LoaderType.NeoForge),
    Custom("自定义图片", null),
}

internal fun validInstanceIcon(file: Path): Boolean =
    runCatching {
        if (
            !file.isRegularFile() ||
            Files.isSymbolicLink(file) ||
            file.fileSize() !in 1..MAX_ICON_BYTES
        ) {
            return false
        }
        ImageIO.createImageInputStream(file.toFile()).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return false
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                width in MIN_ICON_SIZE..MAX_ICON_SIZE &&
                    height == width &&
                    reader.formatName.lowercase() in setOf("png", "jpeg", "jpg") &&
                    reader.read(0) != null
            } finally {
                reader.dispose()
            }
        }
    }.getOrDefault(false)

/** 版本目录与游戏目录下的常见图标名，按顺序即优先级；两个目录相同时只留一份。 */
private fun iconCandidates(
    store: LauncherStore,
    instance: GameInstance,
    version: Path,
): MutableList<Path> =
    listOf(version, store.gamePath(instance))
        .distinct()
        .flatMap { directory ->
            listOf("png", "jpg", "jpeg").map { directory.resolve("icon.$it") }
        }.toMutableList()

/** Setup.ini 必须是普通文件且足够小，才值得读进来找自定义 Logo 设置。 */
private fun isReadableSetupIni(settings: Path): Boolean =
    settings.isRegularFile() &&
        !Files.isSymbolicLink(settings) &&
        settings.fileSize() <= MAX_SETUP_INI_BYTES

/** 按 PCL 的 `logocustom`/`logo` 约定，把 `PCL/Logo.png` 追加为最低优先级候选。 */
private fun appendPclLogo(
    candidates: MutableList<Path>,
    version: Path,
    entries: Map<String, String>,
) {
    if (
        entries["logocustom"].equals("true", true) &&
        entries["logo"].orEmpty().replace('\\', '/').endsWith("PCL/Logo.png", true)
    ) {
        candidates.add(version.resolve("PCL/Logo.png"))
    }
}

/** 逐行解析 Setup.ini；读不到或格式异常时返回空表，不影响候选图标判断。 */
private fun parseSetupIni(settings: Path): Map<String, String> =
    runCatching {
        val bytes = settings.readBytes()
        decodeSetupIni(bytes)
            .lineSequence()
            .mapNotNull { line -> splitSetupIniLine(line) }
            .toMap()
    }.getOrDefault(emptyMap())

/** 优先按 UTF-8 解码，失败时回退到 PCL 常用的 GB18030。 */
private fun decodeSetupIni(bytes: ByteArray): String =
    runCatching {
        Charsets.UTF_8
            .newDecoder()
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }.getOrElse {
        bytes.toString(
            java.nio.charset.Charset
                .forName("GB18030"),
        )
    }

/** 一行键值：冒号优先，没有冒号再用等号；空行与无键名的行返回 null。 */
private fun splitSetupIniLine(line: String): Pair<String, String>? {
    val index = line.indexOf(':').takeIf { it >= 0 } ?: line.indexOf('=')
    if (index <= 0) return null
    return line.substring(0, index).trim().lowercase() to
        line.substring(index + 1).trim()
}

/** Managed assets are content-addressed; copies may safely share immutable icon files. */
class InstanceIcons(
    private val store: LauncherStore,
) {
    private val root: Path
        get() = store.directories.icons

    private fun managed(id: String): Path {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "实例图标标识无效" }
        return safePath(root, "$id.png")
    }

    suspend fun texture(instance: GameInstance): Path? =
        withContext(Dispatchers.IO) {
            if (instance.iconStyle == InstanceIconStyle.Custom) {
                return@withContext runCatching {
                    managed(instance.iconId).takeIf {
                        validInstanceIcon(it) && digest(it, "SHA-256") == instance.iconId
                    }
                }.getOrNull()
            }
            if (instance.iconStyle == InstanceIconStyle.Auto && instance.packIconId.isNotBlank()) {
                runCatching {
                    managed(instance.packIconId).takeIf {
                        validInstanceIcon(it) && digest(it, "SHA-256") == instance.packIconId
                    }
                }.getOrNull()
                    ?.let {
                        return@withContext it
                    }
            }
            if (instance.iconStyle == InstanceIconStyle.Auto) existingTexture(instance) else null
        }

    internal suspend fun exportImage(instance: GameInstance): ByteArray? {
        val file = texture(instance) ?: return null
        return java.io.ByteArrayOutputStream().use { output ->
            check(ImageIO.write(ImageIO.read(file.toFile()), "png", output))
            output.toByteArray()
        }
    }

    suspend fun existingTexture(instance: GameInstance): Path? =
        withContext(Dispatchers.IO) {
            val version =
                safePath(
                    store.minecraftRoot(instance),
                    "versions/${safeFilename(instance.versionId)}",
                )
            val candidates = iconCandidates(store, instance, version)
            val settings = version.resolve("PCL/Setup.ini")
            if (isReadableSetupIni(settings)) {
                appendPclLogo(candidates, version, parseSetupIni(settings))
            }
            candidates.firstOrNull(::validInstanceIcon)
        }

    suspend fun setCustom(
        instanceId: String,
        source: Path,
    ) = setImage(instanceId, source, false)

    internal suspend fun setPackIcon(
        instanceId: String,
        source: Path,
    ) = setImage(instanceId, source, true)

    private suspend fun setImage(
        instanceId: String,
        source: Path,
        fromPack: Boolean,
    ) = withContext(Dispatchers.IO) {
        val original = store.instance(instanceId)
        require(validInstanceIcon(source)) { "请选择 16–1024 像素的正方形 PNG/JPEG 图标（不超过 2 MiB）" }
        root.createDirectories()
        val staging = safePath(root, ".${UUID.randomUUID()}.tmp")
        try {
            val bytes =
                Files.newInputStream(source).use { it.readNBytes(MAX_ICON_BYTES.toInt() + 1) }
            require(bytes.size <= MAX_ICON_BYTES) { "所选图标已改变或过大" }
            staging.writeBytes(bytes)
            require(validInstanceIcon(staging)) { "所选图标已改变或损坏" }
            val image = ImageIO.read(staging.toFile())
            ImageIO.write(image, "png", staging.toFile())
            val hash = digest(staging, "SHA-256")
            val target = managed(hash)
            val existed = target.exists()
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
            try {
                store.updateInstance(instanceId) {
                    if (fromPack) {
                        it.copy(packIconId = hash)
                    } else {
                        it.copy(iconStyle = InstanceIconStyle.Custom, iconId = hash)
                    }
                }
            } catch (failure: IllegalStateException) {
                if (!existed) target.deleteIfExists()
                throw failure
            } catch (failure: IOException) {
                if (!existed) target.deleteIfExists()
                throw failure
            }
            releaseUnused(if (fromPack) original.packIconId else original.iconId)
        } finally {
            staging.deleteIfExists()
        }
    }

    suspend fun setPreset(
        instanceId: String,
        style: InstanceIconStyle,
    ) = withContext(Dispatchers.IO) {
        require(style != InstanceIconStyle.Custom) { "自定义图标需要先选择图片" }
        val old = store.instance(instanceId)
        store.updateInstance(instanceId) { it.copy(iconStyle = style, iconId = "") }
        releaseUnused(old.iconId)
    }

    internal fun releaseUnused(id: String) {
        if (id.isBlank() || store.data.instances.any { it.iconId == id || it.packIconId == id }) {
            return
        }
        runCatching { managed(id).deleteIfExists() }
            .onFailure { store.log("旧实例图标稍后清理：${it.message}") }
    }
}
