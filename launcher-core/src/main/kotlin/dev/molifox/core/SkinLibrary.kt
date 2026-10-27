package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SkinVariant(val title: String, val apiValue: String) {
    Classic("标准手臂", "classic"),
    Slim("纤细手臂", "slim"),
}

data class LibrarySkin(
    val id: String,
    val name: String,
    val variant: SkinVariant,
    val sha256: String,
)

/** Local assets belong to the launcher, never to a selected game directory. */
class SkinLibrary(private val store: LauncherStore) {
    private val root: Path
        get() = store.dataDirectory.resolve("skins")

    private val index: Path
        get() = root.resolve("library.json")

    suspend fun list(): List<LibrarySkin> =
        withContext(Dispatchers.IO) { synchronized(this@SkinLibrary) { readEntries() } }

    private fun readEntries(): List<LibrarySkin> {
        if (!index.exists()) return emptyList()
        val json = readObject(index)
        require(json.int("formatVersion") == 1) { "皮肤库格式无法识别，请保留原文件" }
        return json
            .array("skins")
            .map { gson.fromJson(it, LibrarySkin::class.java) }
            .also { entries ->
                require(
                    entries.size <= 1000 && entries.map { it.id }.distinct().size == entries.size
                ) {
                    "皮肤库条目异常"
                }
                entries.forEach { entry ->
                    require(
                        runCatching { UUID.fromString(entry.id) }.isSuccess &&
                            entry.name.length in 1..100 &&
                            entry.sha256.matches(Regex("[a-f0-9]{64}"))
                    ) {
                        "皮肤库条目无效"
                    }
                }
            }
    }

    private fun save(entries: List<LibrarySkin>) =
        writeJson(index, mapOf("formatVersion" to 1, "skins" to entries))

    suspend fun importSkin(source: Path, name: String, variant: SkinVariant): LibrarySkin =
        withContext(Dispatchers.IO) {
            require(name.isNotBlank() && name.trim().length <= 100) { "皮肤名称需为 1–100 字" }
            require(validSkinTexture(source)) { "请选择有效的 Minecraft PNG 皮肤（64×64、64×32 或等比例纹理）" }
            // Snapshot once: do not trust a user-selected file to stay unchanged while being
            // imported.
            val bytes = Files.newInputStream(source).use { it.readNBytes(1024 * 1024 + 1) }
            require(bytes.size <= 1024 * 1024) { "皮肤文件已改变或过大" }
            synchronized(this@SkinLibrary) {
                val entries = readEntries()
                require(entries.size < 1000) { "皮肤库已达到 1000 项上限" }
                root.createDirectories()
                val id = UUID.randomUUID().toString()
                val target = safePath(root, "$id.png")
                val staging = safePath(root, "$id.tmp")
                try {
                    staging.writeBytes(bytes)
                    require(validSkinTexture(staging)) { "所选文件已改变或不是有效皮肤" }
                    val hash = digest(staging, "SHA-256")
                    entries
                        .find { it.sha256 == hash && it.variant == variant }
                        ?.let {
                            return@synchronized it
                        }
                    val entry = LibrarySkin(id, name.trim(), variant, hash)
                    Files.move(staging, target)
                    try {
                        save(entries + entry)
                    } catch (failure: Exception) {
                        target.deleteIfExists()
                        throw failure
                    }
                    entry
                } finally {
                    staging.deleteIfExists()
                }
            }
        }

    suspend fun texture(id: String): Path =
        withContext(Dispatchers.IO) {
            synchronized(this@SkinLibrary) {
                val entry = readEntries().find { it.id == id } ?: error("皮肤已不在皮肤库中")
                val file = safePath(root, "${entry.id}.png")
                require(validSkinTexture(file) && digest(file, "SHA-256") == entry.sha256) {
                    "皮肤文件已改变或损坏，请重新导入"
                }
                file
            }
        }

    suspend fun exportSkin(id: String, destination: Path) =
        exportSkinTexture(texture(id), destination)

    suspend fun removeSkin(id: String) =
        withContext(Dispatchers.IO) {
            synchronized(this@SkinLibrary) {
                val entries = readEntries()
                require(entries.any { it.id == id }) { "皮肤已移除" }
                require(store.data.accounts.none { it.localSkinId == id }) { "此皮肤正用于离线角色，请先恢复默认预览" }
                val file = safePath(root, "$id.png")
                val holding = safePath(root, "$id.removed")
                if (file.exists()) Files.move(file, holding, StandardCopyOption.REPLACE_EXISTING)
                try {
                    save(entries.filterNot { it.id == id })
                } catch (failure: Exception) {
                    if (holding.exists()) Files.move(holding, file)
                    throw failure
                }
                holding.deleteIfExists()
            }
        }
}

internal suspend fun exportSkinTexture(source: Path, destination: Path) =
    withContext(Dispatchers.IO) {
        require(validSkinTexture(source)) { "没有可导出的皮肤纹理" }
        require(destination.extension.equals("png", true)) { "皮肤请保存为 PNG 文件" }
        val target = destination.toAbsolutePath().normalize()
        require(target != source.toAbsolutePath().normalize() && !Files.isSymbolicLink(target)) {
            "请选择不同的导出文件"
        }
        target.parent.createDirectories()
        val staging = Files.createTempFile(target.parent, ".molifox-skin-", ".tmp")
        try {
            Files.copy(source, staging, StandardCopyOption.REPLACE_EXISTING)
            require(validSkinTexture(staging)) { "皮肤在导出时改变" }
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            staging.deleteIfExists()
        }
    }

internal fun validateUploadSkin(file: Path, variant: SkinVariant) {
    require(validSkinTexture(file)) { "皮肤文件无效" }
    val image = ImageIO.read(file.toFile())
    require(image.width == 64 && image.height in setOf(32, 64)) {
        "正版上传需要 64×64 或 64×32 皮肤，不支持高清纹理"
    }
    require(variant != SkinVariant.Slim || image.height == 64) { "纤细手臂需要 64×64 皮肤" }
}
