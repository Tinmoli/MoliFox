// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责校验用户拖入/选择的存档压缩包，并给出要剥离的包装目录。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name

/** 存档压缩包的校验结果。 */
internal data class SaveArchiveLayout(
    /** 世界内容前缀：`""` 表示 `level.dat` 就在压缩包根目录，`MyWorld/` 表示有一层包装目录。 */
    val prefix: String,
) {
    val wrapped: Boolean
        get() = prefix.isNotBlank()
}

/**
 * 校验一个当作世界导入的 ZIP：必须在根目录、或唯一一层包装目录的根目录里有非空的 `level.dat`。
 *
 * 只读取条目名与大小，不解压、不写盘；路径穿越、绝对路径与多世界目录都在这里拒绝， 因此无效输入不会在 `saves/` 留下任何内容。
 */
internal object SaveArchive {
    fun inspect(archive: Path): SaveArchiveLayout {
        require(
            archive.isRegularFile(LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(archive),
        ) {
            I18n.t("saveA1.saveArchive.notFound", archive.fileName)
        }
        ZipFile(archive.toFile()).use { zip ->
            val entries = readEntries(zip)
            require(entries.isNotEmpty()) { I18n.t("saveA1.saveArchive.empty") }
            rejectUnsafeNames(entries)
            val rootLevel = entries.firstOrNull { (name, _) -> name.equals("level.dat", true) }
            if (rootLevel != null) {
                require(rootLevel.second > 0) { I18n.t("saveA1.saveArchive.emptyLevelDat") }
                return SaveArchiveLayout("")
            }
            return wrappedLayout(entries)
        }
    }

    /** 只读名字与大小，不触碰内容。 */
    private fun readEntries(zip: ZipFile): List<Pair<String, Long>> =
        zip
            .entries()
            .asSequence()
            .filter { !it.isDirectory && !it.name.endsWith("/") }
            .map { it.name.replace('\\', '/') to it.size }
            .toList()

    /** 路径穿越与绝对路径在这里拒绝，无效输入不会在 `saves/` 留下任何内容。 */
    private fun rejectUnsafeNames(entries: List<Pair<String, Long>>) {
        for ((name, _) in entries) {
            require(name.split('/').none { it == ".." }) {
                I18n.t("saveA1.saveArchive.traversal", name)
            }
            require(!name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(name)) {
                I18n.t("saveA1.saveArchive.absolutePath", name)
            }
        }
    }

    /** 只有一层包装目录、且它直接包含 level.dat 时才剥掉这一层。 */
    private fun wrappedLayout(entries: List<Pair<String, Long>>): SaveArchiveLayout {
        val wrappers =
            entries
                .filter { (name, _) ->
                    name.count { it == '/' } == 1 &&
                        name.substringAfter('/').equals("level.dat", true)
                }.map { (name, size) -> name.substringBefore('/') to size }
                .distinctBy { it.first.lowercase() }
        require(wrappers.isNotEmpty()) { I18n.t("saveA1.saveArchive.missingLevelDat") }
        require(wrappers.size == 1) {
            I18n.t(
                "saveA1.saveArchive.multipleWorlds",
                wrappers.joinToString("、") { it.first },
            )
        }
        val (wrapper, levelSize) = wrappers.single()
        require(levelSize > 0) { I18n.t("saveA1.saveArchive.emptyLevelDat") }
        val prefix = "$wrapper/"
        require(entries.all { (name, _) -> name.startsWith(prefix, ignoreCase = true) }) {
            I18n.t(
                "saveA1.saveArchive.outsideWrapper",
                entries.first { (name, _) -> !name.startsWith(prefix, ignoreCase = true) }.first,
            )
        }
        return SaveArchiveLayout(prefix)
    }
}
