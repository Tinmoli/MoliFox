// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责本地皮肤文件的导入、索引和导出。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name
import kotlin.io.path.writeBytes

/** 皮肤库最多保留的条目数，超过后拒绝继续导入。 */
private const val MAX_SKIN_COUNT = 1000

/** 皮肤显示名称的长度上限，与导入校验和库文件校验共用。 */
private const val MAX_SKIN_NAME_LENGTH = 100

/** 单个皮肤文件的大小上限（1 MiB），导入时按该值 +1 读取以识别超限。 */
private const val MAX_SKIN_FILE_BYTES = 1024 * 1024

/** 正版与本地皮肤都要求的标准纹理宽度。 */
private const val STANDARD_SKIN_WIDTH = 64

/** 正版与本地皮肤都支持的标准纹理高度（64×64）。 */
private const val STANDARD_SKIN_HEIGHT = 64

/** 旧版单层皮肤纹理高度（64×32）。 */
private const val LEGACY_SKIN_HEIGHT = 32

enum class SkinVariant(
    private val titleKey: String,
    val apiValue: String,
) {
    Classic("core.skinVariant.classic", "classic"),
    Slim("core.skinVariant.slim", "slim"),
    ;

    /** 显示名在读取时翻译，切换语言后重新取值。 */
    val title: String
        get() = I18n.t(titleKey)
}

data class LibrarySkin(
    val id: String,
    val name: String,
    val variant: SkinVariant,
    val sha256: String,
)

/** Local assets belong to the launcher, never to a selected game directory. */
class SkinLibrary(
    private val store: LauncherStore,
) {
    private val root: Path
        get() = store.dataDirectory.resolve("skins")

    /** 皮肤库目录（`MoliFox/skins`）；离线皮肤文件与 `library.json` 都在这里。 */
    val directory: Path
        get() = root

    private val index: Path
        get() = root.resolve("library.json")

    suspend fun list(): List<LibrarySkin> =
        withContext(Dispatchers.IO) { synchronized(this@SkinLibrary) { readEntries() } }

    private fun readEntries(): List<LibrarySkin> {
        if (!index.exists()) return emptyList()
        val json = readObject(index)
        require(json.int("formatVersion") == 1) { I18n.t("core.skinLibrary.3") }
        return json
            .array("skins")
            .map { gson.fromJson(it, LibrarySkin::class.java) }
            .also { entries ->
                require(
                    entries.size <= MAX_SKIN_COUNT &&
                        entries.map { it.id }.distinct().size == entries.size,
                ) {
                    I18n.t("core.skinLibrary.4")
                }
                entries.forEach { entry ->
                    require(
                        runCatching { UUID.fromString(entry.id) }.isSuccess &&
                            entry.name.length in 1..MAX_SKIN_NAME_LENGTH &&
                            entry.sha256.matches(Regex("[a-f0-9]{64}")),
                    ) {
                        I18n.t("core.skinLibrary.5")
                    }
                }
            }
    }

    private fun save(entries: List<LibrarySkin>) = writeJson(index, mapOf("formatVersion" to 1, "skins" to entries))

    suspend fun importSkin(
        source: Path,
        name: String,
        variant: SkinVariant,
    ): LibrarySkin =
        withContext(Dispatchers.IO) {
            require(name.isNotBlank() && name.trim().length <= MAX_SKIN_NAME_LENGTH) {
                I18n.t("core.skinLibrary.6")
            }
            require(validSkinTexture(source)) { I18n.t("core.skinLibrary.7") }
            // Snapshot once: do not trust a user-selected file to stay unchanged while being
            // imported.
            val bytes = Files.newInputStream(source).use { it.readNBytes(MAX_SKIN_FILE_BYTES + 1) }
            require(bytes.size <= MAX_SKIN_FILE_BYTES) { I18n.t("core.skinLibrary.8") }
            synchronized(this@SkinLibrary) {
                val entries = readEntries()
                require(entries.size < MAX_SKIN_COUNT) { I18n.t("core.skinLibrary.9") }
                root.createDirectories()
                val id = UUID.randomUUID().toString()
                val target = safePath(root, "$id.png")
                val staging = safePath(root, "$id.tmp")
                try {
                    staging.writeBytes(bytes)
                    require(validSkinTexture(staging)) { I18n.t("core.skinLibrary.10") }
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
                    } catch (failure: IOException) {
                        // 保存索引失败时回滚已写入的纹理，避免留下无索引的孤立文件。
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
                val entry = readEntries().find { it.id == id } ?: error(I18n.t("core.skinLibrary.11"))
                val file = safePath(root, "${entry.id}.png")
                require(validSkinTexture(file) && digest(file, "SHA-256") == entry.sha256) {
                    I18n.t("core.skinLibrary.12")
                }
                file
            }
        }

    suspend fun exportSkin(
        id: String,
        destination: Path,
    ) = exportSkinTexture(texture(id), destination)

    suspend fun removeSkin(id: String) =
        withContext(Dispatchers.IO) {
            synchronized(this@SkinLibrary) {
                val entries = readEntries()
                require(entries.any { it.id == id }) { I18n.t("core.skinLibrary.13") }
                require(store.data.accounts.none { it.localSkinId == id }) { I18n.t("core.skinLibrary.14") }
                val file = safePath(root, "$id.png")
                val holding = safePath(root, "$id.removed")
                if (file.exists()) Files.move(file, holding, StandardCopyOption.REPLACE_EXISTING)
                try {
                    save(entries.filterNot { it.id == id })
                } catch (failure: IOException) {
                    // 索引保存失败时把纹理移回原位，保持库目录与索引一致。
                    if (holding.exists()) Files.move(holding, file)
                    throw failure
                }
                holding.deleteIfExists()
            }
        }
}

internal suspend fun exportSkinTexture(
    source: Path,
    destination: Path,
) = withContext(Dispatchers.IO) {
    require(validSkinTexture(source)) { I18n.t("core.skinLibrary.15") }
    require(destination.extension.equals("png", true)) { I18n.t("core.skinLibrary.16") }
    val target = destination.toAbsolutePath().normalize()
    require(target != source.toAbsolutePath().normalize() && !Files.isSymbolicLink(target)) {
        I18n.t("core.skinLibrary.17")
    }
    target.parent.createDirectories()
    val staging = Files.createTempFile(target.parent, ".molifox-skin-", ".tmp")
    try {
        Files.copy(source, staging, StandardCopyOption.REPLACE_EXISTING)
        require(validSkinTexture(staging)) { I18n.t("core.skinLibrary.18") }
        Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        staging.deleteIfExists()
    }
}

internal fun validateUploadSkin(
    file: Path,
    variant: SkinVariant,
) {
    require(validSkinTexture(file)) { I18n.t("core.skinLibrary.19") }
    val image = ImageIO.read(file.toFile())
    require(
        image.width == STANDARD_SKIN_WIDTH &&
            image.height in setOf(LEGACY_SKIN_HEIGHT, STANDARD_SKIN_HEIGHT),
    ) {
        I18n.t("core.skinLibrary.20")
    }
    require(variant != SkinVariant.Slim || image.height == STANDARD_SKIN_HEIGHT) {
        I18n.t("core.skinLibrary.21")
    }
}

/**
 * 写入皮肤模组本地目录（CustomSkinLoader `LocalSkin/skins/<name>.png`）前的校验。
 *
 * 模组目录里的文件是按原版皮肤角色读取的，所以只接受 64×64 / 64×32 标准尺寸； 启动器皮肤库仍可保留高清纹理仅作预览，两者边界不同。
 */
internal fun validateLocalSkinFile(
    file: Path,
    variant: SkinVariant,
) {
    require(validSkinTexture(file)) { I18n.t("core.skinLibrary.19") }
    val image = ImageIO.read(file.toFile()) ?: error(I18n.t("core.skinLibrary.19"))
    try {
        require(
            image.width == STANDARD_SKIN_WIDTH &&
                image.height in setOf(LEGACY_SKIN_HEIGHT, STANDARD_SKIN_HEIGHT),
        ) {
            I18n.t("core.skinLibrary.22")
        }
        require(variant != SkinVariant.Slim || image.height == STANDARD_SKIN_HEIGHT) {
            I18n.t("core.skinLibrary.21")
        }
    } finally {
        image.flush()
    }
}
