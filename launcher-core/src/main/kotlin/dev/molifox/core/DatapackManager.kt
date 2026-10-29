// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责存档数据包的列出、安装、删除，以及写回 level.dat 的启用/禁用状态。

package dev.molifox.core

import com.google.gson.JsonParser
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.*

/**
 * `saves/<世界>/datapacks/` 里的一个数据包：`.zip` 文件或文件夹。
 *
 * [enabled] 为 null 表示 level.dat 无法解析、状态未知；此时界面只展示、不提供开关。
 */
data class DatapackEntry(
    /** 文件或文件夹名（含 `.zip` 后缀），也是 `level.dat` 里 `file/<名称>` 的标识部分。 */
    val name: String,
    val path: Path,
    val directory: Boolean,
    val size: Long,
    val enabled: Boolean?,
)

/** 一个世界的数据包清单；[toggleAvailable] 为 false 时开关只读，[note] 说明原因。 */
data class DatapackListing(
    val world: String,
    val entries: List<DatapackEntry>,
    val toggleAvailable: Boolean,
    val note: String,
)

/** `level.dat` 里 `Data.Packs.Enabled/Disabled` 的字符串标签类型号。 */
private const val NBT_TAG_STRING = 8

/** 新建 `Enabled` 列表时补上的原版数据包标识，避免只写一个数据包把原版数据包关掉。 */
private const val VANILLA_PACK_ID = "vanilla"

/**
 * 数据包管理：读 `Data.Packs.Enabled`/`Disabled` 判断启用状态，安装时校验 `pack.mcmeta` 与 `pack_format`。
 *
 * 约定：
 * - `level.dat` 是 gzip 压缩的 NBT，复用现有 [readNbtDocument]/[encodeNbt]（覆盖 1–12 号标签、保留根名称与压缩形式）；
 *   解析失败时抛中文错误且**不写文件**，界面据此退回只读。
 * - 写回先写临时文件、核对 `level.dat` 未变化，再原子改名；原文件滚动备份到启动器数据的
 *   `backups/level-dat/`，每次覆盖同一个名字，不会无限堆积。
 * - `session.lock` 被占用（世界正被游戏打开）时拒绝安装、删除与切换。
 * - 删除走 [PlatformServices.moveToTrash]，并尽力清掉 `level.dat` 里对应的旧条目（失败只记日志）。
 */
class DatapackManager(private val store: LauncherStore) {

    /** 列出一个世界的数据包与启用状态；世界不存在或 level.dat 无法解析时不抛异常，返回只读清单。 */
    fun list(instanceId: String, worldName: String): DatapackListing {
        val world = worldDirectory(instanceId, worldName)
        val packs =
            runCatching { packsOf(readNbtDocument(world.resolve("level.dat"))) }.getOrNull()
        val directory = world.resolve(DATAPACKS_DIRECTORY)
        val children =
            if (!directory.isDirectory(LinkOption.NOFOLLOW_LINKS) || isLink(directory))
                emptyList()
            else runCatching { directory.listDirectoryEntries() }.getOrDefault(emptyList())
        val entries =
            children
                .mapNotNull { child -> describe(child, packs) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val locked = sessionLockHeld(world)
        val note =
            when {
                packs == null -> "level.dat 无法解析，数据包开关暂时只读；可用「打开文件夹」手动整理。"
                locked -> "世界正在游戏中打开，退出游戏后才能切换数据包。"
                else -> ""
            }
        return DatapackListing(
            world = worldName,
            entries = entries,
            toggleAvailable = packs != null && !locked,
            note = note,
        )
    }

    /**
     * 把用户选的 `.zip` 安装到世界的 `datapacks/`，返回安装后的文件名。
     *
     * 只接受根目录带 `pack.mcmeta`、且 `pack.pack_format` 为数字的压缩包；同名文件已存在时拒绝，不覆盖。
     */
    fun install(instanceId: String, worldName: String, archive: Path): String {
        requireGameStopped(instanceId)
        val world = worldDirectory(instanceId, worldName)
        require(!sessionLockHeld(world)) {
            "世界「$worldName」正在游戏中打开，请先退出游戏再安装数据包"
        }
        require(archive.isRegularFile(LinkOption.NOFOLLOW_LINKS)) { "找不到数据包文件：$archive" }
        require(archive.name.endsWith(".zip", true)) { "数据包必须是 .zip 文件" }
        require(!isLink(archive)) { "数据包文件不能是符号链接或目录联接" }
        val packFormat = validatePackArchive(archive)
        val name = archive.name
        val directory = datapacksDirectory(world, create = true)
        val target = directory.resolve(name)
        require(target.parent.toAbsolutePath().normalize() == absolute(directory)) {
            "数据包文件名无效：$name"
        }
        require(!target.exists(LinkOption.NOFOLLOW_LINKS)) {
            "存档里已有同名数据包，请先删除或改名：$name"
        }
        val staging = directory.resolve(".molifox-${UUID.randomUUID()}.tmp")
        try {
            Files.copy(archive, staging)
            filesMove(staging, target)
        } finally {
            Files.deleteIfExists(staging)
        }
        store.log("已安装数据包：${store.instance(instanceId).name} / $worldName / $name（pack_format $packFormat）")
        return name
    }

    /**
     * 启用或禁用 `datapacks/` 里的一个数据包。
     *
     * 启用 = 从 `Disabled` 移除并加入 `Enabled`，禁用反之；其他条目（`vanilla`、其他数据包）原样保留。
     * 写入前要求 `level.dat` 可解析、世界未被游戏打开，写入用临时文件 + 原子改名。
     */
    fun setEnabled(instanceId: String, worldName: String, name: String, enabled: Boolean) {
        requireGameStopped(instanceId)
        val world = worldDirectory(instanceId, worldName)
        require(!sessionLockHeld(world)) {
            "世界「$worldName」正在游戏中打开，请先退出游戏再切换数据包"
        }
        val entry = datapackEntry(world, name) ?: throw IllegalArgumentException("找不到数据包：$name")
        require(entry.isRegularFile(LinkOption.NOFOLLOW_LINKS) || entry.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
            "数据包路径不是文件或文件夹：$name"
        }
        val level = world.resolve("level.dat")
        val before = runCatching { Files.readAllBytes(level) }.getOrElse {
            throw IllegalArgumentException("无法读取 level.dat，数据包开关暂时只读")
        }
        val document =
            runCatching { readNbtDocument(level) }.getOrElse {
                throw IllegalArgumentException("level.dat 无法解析，数据包开关暂时只读；未修改任何文件")
            }
        val packs = packsOf(document)
        val currentEnabled = packs.second
        val currentDisabled = packs.third
        val existingId =
            (currentEnabled + currentDisabled).firstOrNull { matchesPackId(it, name) }
                ?: "file/$name"
        val nextEnabled: List<String>
        val nextDisabled: List<String>
        if (enabled) {
            nextDisabled = currentDisabled.filterNot { matchesPackId(it, name) }
            nextEnabled =
                if (currentEnabled.any { matchesPackId(it, name) }) currentEnabled
                else currentEnabled + existingId
        } else {
            nextEnabled = currentEnabled.filterNot { matchesPackId(it, name) }
            nextDisabled =
                if (currentDisabled.any { matchesPackId(it, name) }) currentDisabled
                else currentDisabled + existingId
        }
        if (nextEnabled == currentEnabled && nextDisabled == currentDisabled) return
        val root = withPacks(document.root, nextEnabled, nextDisabled)
        val bytes = encodeNbt(document.copy(root = root))
        backupLevelDat(world, before)
        val temporary = level.resolveSibling(".level-${UUID.randomUUID()}.part")
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW)
            require(Files.readAllBytes(level).contentEquals(before)) {
                "level.dat 已被其他程序修改，请刷新后再试；未写入"
            }
            filesMove(temporary, level)
        } finally {
            Files.deleteIfExists(temporary)
        }
        store.log(
            "数据包已${if (enabled) "启用" else "禁用"}：${store.instance(instanceId).name} / $worldName / $name"
        )
    }

    /** 删除一个数据包（移入回收站），并尽力清理 `level.dat` 里的旧条目；找不到或失败返回 false。 */
    fun delete(instanceId: String, worldName: String, name: String): Boolean {
        requireGameStopped(instanceId)
        val world = worldDirectory(instanceId, worldName)
        require(!sessionLockHeld(world)) {
            "世界「$worldName」正在游戏中打开，请先退出游戏再删除数据包"
        }
        val entry = runCatching { datapackEntry(world, name) }.getOrNull() ?: return false
        return runCatching {
            store.platform.moveToTrash(entry)
            store.log("数据包已移入回收站：${store.instance(instanceId).name} / $worldName / $name")
            runCatching { forgetPack(world, name) }
                .onFailure {
                    store.log("清理 level.dat 里的数据包条目失败：${it.message ?: name}", true)
                }
            true
        }
            .getOrElse {
                store.log("删除数据包失败：${it.message ?: name}", true)
                false
            }
    }

    // ---- 内部 ----

    /** 从 level.dat 的列表里移除一个数据包条目；level.dat 解析失败时跳过，不算删除失败。 */
    private fun forgetPack(world: Path, name: String) {
        val level = world.resolve("level.dat")
        val before = runCatching { Files.readAllBytes(level) }.getOrNull() ?: return
        val document = runCatching { readNbtDocument(level) }.getOrNull() ?: return
        val packs = packsOf(document)
        val enabled = packs.second.filterNot { matchesPackId(it, name) }
        val disabled = packs.third.filterNot { matchesPackId(it, name) }
        if (enabled == packs.second && disabled == packs.third) return
        val bytes = encodeNbt(document.copy(root = withPacks(document.root, enabled, disabled)))
        val temporary = level.resolveSibling(".level-${UUID.randomUUID()}.part")
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW)
            if (!Files.readAllBytes(level).contentEquals(before)) return
            filesMove(temporary, level)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** 读取 `Data.Packs`：返回 (Packs 复合标签, Enabled 字符串, Disabled 字符串)；结构异常时抛错。 */
    private fun packsOf(document: NbtDocument): Triple<NbtCompound, List<String>, List<String>> {
        val data = document.root.compound("Data") ?: NbtCompound(emptyMap())
        val packs = data.compound("Packs") ?: NbtCompound(emptyMap())
        return Triple(packs, packStrings(packs, "Enabled"), packStrings(packs, "Disabled"))
    }

    private fun packStrings(packs: NbtCompound, key: String): List<String> {
        val list = packs.list(key) ?: return emptyList()
        require(list.elementType == NBT_TAG_STRING || list.elements.isEmpty()) {
            "level.dat 的 Packs.$key 不是字符串列表，未修改文件"
        }
        return list.elements.map { element ->
            require(element is NbtString) { "level.dat 的 Packs.$key 含有非字符串项，未修改文件" }
            element.value
        }
    }

    /** 只替换 `Data.Packs.Enabled`/`Disabled` 两个列表，其他标签原样保留。 */
    private fun withPacks(
        root: NbtCompound,
        enabled: List<String>,
        disabled: List<String>,
    ): NbtCompound {
        val data = root.compound("Data") ?: NbtCompound(emptyMap())
        val packs = data.compound("Packs") ?: NbtCompound(emptyMap())
        // 新建 Enabled 时补 vanilla：只写一个数据包名会让原版数据包被当成未选中。
        val enabledIds =
            if (packs.list("Enabled") == null && VANILLA_PACK_ID !in enabled) {
                listOf(VANILLA_PACK_ID) + enabled
            } else enabled
        val updated =
            NbtCompound(
                packs.entries +
                    ("Enabled" to NbtList(enabledIds.map(::NbtString), NBT_TAG_STRING)) +
                    ("Disabled" to NbtList(disabled.map(::NbtString), NBT_TAG_STRING))
            )
        return NbtCompound(root.entries + ("Data" to NbtCompound(data.entries + ("Packs" to updated))))
    }

    /** 数据包标识匹配：同时接受 `file/<名称>` 与裸名称（手改过的 level.dat 常见）。 */
    private fun matchesPackId(id: String, name: String): Boolean =
        id == name || id == "file/$name"

    private fun describe(path: Path, packs: Triple<NbtCompound, List<String>, List<String>>?): DatapackEntry? {
        val attributes =
            runCatching {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }
                .getOrNull() ?: return null
        if (attributes.isSymbolicLink || attributes.isOther) return null
        val name = path.fileName?.toString() ?: return null
        // 安装过程的临时文件不列出。
        if (name.startsWith(".molifox-")) return null
        val directory = attributes.isDirectory
        if (!directory && !(attributes.isRegularFile && name.endsWith(".zip", true))) return null
        val enabled =
            packs?.let { (_, enabledList, disabledList) ->
                when {
                    enabledList.any { matchesPackId(it, name) } -> true
                    disabledList.any { matchesPackId(it, name) } -> false
                    // 两条列表都没有 = 原版默认启用。
                    else -> true
                }
            }
        return DatapackEntry(
            name = name,
            path = path,
            directory = directory,
            size = if (directory) directorySize(path) else attributes.size(),
            enabled = enabled,
        )
    }

    /** 校验 `pack.mcmeta` 与 `pack_format`，返回 pack_format；不合法时抛中文错误。 */
    private fun validatePackArchive(archive: Path): Int =
        runCatching {
                ZipFile(archive.toFile()).use { zip ->
                    val manifest = zip.getEntry("pack.mcmeta")
                        ?: throw IllegalArgumentException("数据包缺少 pack.mcmeta（必须在 ZIP 根目录）")
                    require(!manifest.isDirectory && manifest.size in 1..65536L) {
                        "pack.mcmeta 大小异常"
                    }
                    val text = zip.getInputStream(manifest).use { it.readNBytes(65537) }
                    require(text.size <= 65536) { "pack.mcmeta 过大" }
                    val json = JsonParser.parseString(text.toString(Charsets.UTF_8)).asJsonObject
                    val pack = json.getAsJsonObject("pack") ?: throw IllegalArgumentException("pack.mcmeta 缺少 pack 对象")
                    val format =
                        pack.get("pack_format") ?: throw IllegalArgumentException("pack.mcmeta 缺少 pack_format")
                    require(format.isJsonPrimitive && format.asJsonPrimitive.isNumber) {
                        "pack.mcmeta 的 pack_format 不是数字"
                    }
                    val value = format.asInt
                    require(value in 1..1000) { "pack_format 超出支持范围：$value" }
                    value
                }
            }
            .getOrElse { failure ->
                if (failure is IllegalArgumentException) throw failure
                throw IllegalArgumentException("数据包不是有效的 ZIP：${archive.name}", failure)
            }

    /** 世界目录：必须是 `saves/` 的直接普通子目录，并带 level.dat。 */
    private fun worldDirectory(instanceId: String, worldName: String): Path {
        require(worldName.isNotBlank() && worldName.length <= 80) { "世界名称无效" }
        require('/' !in worldName && '\\' !in worldName && ':' !in worldName) {
            "世界名称无效：$worldName"
        }
        require(store.instance(instanceId).id.isNotBlank())
        val saves = store.instancePath(instanceId).resolve("saves")
        val world = runCatching { safePath(saves, worldName) }.getOrElse {
            throw IllegalArgumentException("世界名称不安全：$worldName")
        }
        require(world.parent.toAbsolutePath().normalize() == absolute(saves)) {
            "只能操作 saves 目录下的世界：$worldName"
        }
        require(world.isDirectory(LinkOption.NOFOLLOW_LINKS) && !isLink(world)) {
            "找不到世界存档：$worldName"
        }
        require(
            world.resolve("level.dat").isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
                !isLink(world.resolve("level.dat"))
        ) {
            "世界缺少 level.dat，无法管理数据包：$worldName"
        }
        return world
    }

    private fun datapacksDirectory(world: Path, create: Boolean): Path {
        val directory = world.resolve(DATAPACKS_DIRECTORY)
        if (directory.exists(LinkOption.NOFOLLOW_LINKS)) {
            require(directory.isDirectory(LinkOption.NOFOLLOW_LINKS) && !isLink(directory)) {
                "datapacks 路径不是普通目录：$directory"
            }
        } else if (create) {
            directory.createDirectories()
        }
        return directory
    }

    private fun datapackEntry(world: Path, name: String): Path? {
        require(name.isNotBlank() && name.length <= 200) { "数据包名称无效" }
        require('/' !in name && '\\' !in name && ':' !in name) { "数据包名称无效：$name" }
        val directory = world.resolve(DATAPACKS_DIRECTORY)
        val path = runCatching { safePath(directory, name) }.getOrElse { return null }
        require(path.parent.toAbsolutePath().normalize() == absolute(directory)) { "数据包名称越界：$name" }
        if (!path.exists(LinkOption.NOFOLLOW_LINKS) || isLink(path)) return null
        return path
    }

    /** 原始 `level.dat` 的滚动备份：每个世界一个文件，覆盖写，不随操作次数膨胀。 */
    private fun backupLevelDat(world: Path, bytes: ByteArray) {
        runCatching {
                val directory = store.dataDirectory.resolve("backups/level-dat")
                directory.createDirectories()
                val label =
                    world.fileName.toString().replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
                Files.write(
                    directory.resolve("$label.dat"),
                    bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            }
            .onFailure { store.log("备份 level.dat 失败：${it.message ?: world.fileName}", true) }
    }

    private fun requireGameStopped(instanceId: String) {
        val target = taskPathKey(store.instancePath(instanceId))
        require(store.state.value.runningGames.none { taskPathKey(store.instancePath(it.instanceId)) == target }) {
            "游戏正在使用此存档目录，请先退出游戏"
        }
    }

    private fun directorySize(directory: Path): Long {
        var total = 0L
        runCatching {
            Files.walkFileTree(
                directory,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult =
                        if (dir != directory && (attrs.isOther || attrs.isSymbolicLink))
                            FileVisitResult.SKIP_SUBTREE
                        else FileVisitResult.CONTINUE

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        if (attrs.isRegularFile && !attrs.isOther)
                            total += runCatching { file.fileSize() }.getOrDefault(0L)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        error: IOException,
                    ): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
        }
        return total
    }

    private fun absolute(path: Path): Path = path.toAbsolutePath().normalize()

    private fun isLink(path: Path): Boolean = runCatching {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }
        .map { it.isSymbolicLink || it.isOther }
        .getOrDefault(false)

    private fun filesMove(source: Path, target: Path) {
        runCatching { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE) }
            .getOrElse { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING) }
    }

    private companion object {
        const val DATAPACKS_DIRECTORY = "datapacks"
    }
}