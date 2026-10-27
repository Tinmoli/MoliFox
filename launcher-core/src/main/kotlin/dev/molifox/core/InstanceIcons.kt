package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class InstanceIconStyle(val title: String, val loader: LoaderType?) {
    Auto("自动：原版草方块 / 加载器图标", null),
    Grass("草方块", LoaderType.Vanilla),
    Fabric("布卷", LoaderType.Fabric),
    Quilt("拼布", LoaderType.Quilt),
    Forge("铁砧", LoaderType.Forge),
    NeoForge("橙色铁砧", LoaderType.NeoForge),
    Custom("自定义图片", null),
}

internal fun validInstanceIcon(file: Path): Boolean = runCatching {
    if (
        !file.isRegularFile() ||
            Files.isSymbolicLink(file) ||
            file.fileSize() !in 1..2L * 1024 * 1024
    )
        return false
    ImageIO.createImageInputStream(file.toFile()).use { input ->
        val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return false
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            width in 16..1024 &&
                height == width &&
                reader.formatName.lowercase() in setOf("png", "jpeg", "jpg") &&
                reader.read(0) != null
        } finally {
            reader.dispose()
        }
    }
}
    .getOrDefault(false)

/** Managed assets are content-addressed; copies may safely share immutable icon files. */
class InstanceIcons(private val store: LauncherStore) {
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
                }
                    .getOrNull()
            }
            if (instance.iconStyle == InstanceIconStyle.Auto && instance.packIconId.isNotBlank())
                runCatching {
                        managed(instance.packIconId).takeIf {
                            validInstanceIcon(it) && digest(it, "SHA-256") == instance.packIconId
                        }
                    }
                    .getOrNull()
                    ?.let {
                        return@withContext it
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
            val candidates =
                listOf(version, store.gamePath(instance))
                    .distinct()
                    .flatMap { directory ->
                        listOf("png", "jpg", "jpeg").map { directory.resolve("icon.$it") }
                    }
                    .toMutableList()
            val settings = version.resolve("PCL/Setup.ini")
            if (
                settings.isRegularFile() &&
                    !Files.isSymbolicLink(settings) &&
                    settings.fileSize() <= 128 * 1024
            ) {
                val entries = runCatching {
                    val bytes = settings.readBytes()
                    val text = runCatching {
                        Charsets.UTF_8.newDecoder()
                            .decode(java.nio.ByteBuffer.wrap(bytes))
                            .toString()
                    }.getOrElse { bytes.toString(java.nio.charset.Charset.forName("GB18030")) }
                    text
                        .lineSequence()
                        .mapNotNull { line ->
                            val index = line.indexOf(':').takeIf { it >= 0 } ?: line.indexOf('=')
                            if (index <= 0) null
                            else
                                line.substring(0, index).trim().lowercase() to
                                    line.substring(index + 1).trim()
                        }
                        .toMap()
                }
                    .getOrDefault(emptyMap())
                if (
                    entries["logocustom"].equals("true", true) &&
                        entries["logo"].orEmpty().replace('\\', '/').endsWith("PCL/Logo.png", true)
                )
                    candidates.add(version.resolve("PCL/Logo.png"))
            }
            candidates.firstOrNull(::validInstanceIcon)
        }

    suspend fun setCustom(instanceId: String, source: Path) = setImage(instanceId, source, false)

    internal suspend fun setPackIcon(instanceId: String, source: Path) =
        setImage(instanceId, source, true)

    private suspend fun setImage(instanceId: String, source: Path, fromPack: Boolean) =
        withContext(Dispatchers.IO) {
            val original = store.instance(instanceId)
            require(validInstanceIcon(source)) { "请选择 16–1024 像素的正方形 PNG/JPEG 图标（不超过 2 MiB）" }
            root.createDirectories()
            val staging = safePath(root, ".${UUID.randomUUID()}.tmp")
            try {
                val bytes = Files.newInputStream(source).use { it.readNBytes(2 * 1024 * 1024 + 1) }
                require(bytes.size <= 2 * 1024 * 1024) { "所选图标已改变或过大" }
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
                        if (fromPack) it.copy(packIconId = hash)
                        else it.copy(iconStyle = InstanceIconStyle.Custom, iconId = hash)
                    }
                } catch (failure: Exception) {
                    if (!existed) target.deleteIfExists()
                    throw failure
                }
                releaseUnused(if (fromPack) original.packIconId else original.iconId)
            } finally {
                staging.deleteIfExists()
            }
        }

    suspend fun setPreset(instanceId: String, style: InstanceIconStyle) =
        withContext(Dispatchers.IO) {
            require(style != InstanceIconStyle.Custom) { "自定义图标需要先选择图片" }
            val old = store.instance(instanceId)
            store.updateInstance(instanceId) { it.copy(iconStyle = style, iconId = "") }
            releaseUnused(old.iconId)
        }

    internal fun releaseUnused(id: String) {
        if (id.isBlank() || store.data.instances.any { it.iconId == id || it.packIconId == id })
            return
        runCatching { managed(id).deleteIfExists() }
            .onFailure { store.log("旧实例图标稍后清理：${it.message}") }
    }
}
