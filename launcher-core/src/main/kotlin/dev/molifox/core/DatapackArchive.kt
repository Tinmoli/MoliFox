// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 数据包归档的校验与落盘：安装前读 pack.mcmeta，安装/改写时用原子改名替换目标文件。

package dev.molifox.core

import com.google.gson.JsonParser
import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.name

/** `pack.mcmeta` 的大小上限：超过即视为异常数据包，避免把大文件整块读进内存。 */
private const val MAX_PACK_META_BYTES = 65536L

/** 探测读取长度 = 上限 + 1，多读的一个字节用来判断「已超过上限」。 */
private const val PACK_META_PROBE_BYTES = 65537

/** `pack.pack_format` 的支持范围上限；超出范围的数据包不给安装。 */
private const val MAX_PACK_FORMAT = 1000

/** 校验 `pack.mcmeta` 与 `pack_format`，返回 pack_format；不合法时抛中文错误。 */
internal fun validatePackArchive(archive: Path): Int =
    runCatching {
        ZipFile(archive.toFile()).use { zip ->
            val manifest =
                zip.getEntry("pack.mcmeta")
                    ?: throw IllegalArgumentException(I18n.t("saveA1.datapackArchive.missingManifest"))
            require(!manifest.isDirectory && manifest.size in 1..MAX_PACK_META_BYTES) {
                I18n.t("saveA1.datapackArchive.manifestSize")
            }
            val text = zip.getInputStream(manifest).use { it.readNBytes(PACK_META_PROBE_BYTES) }
            require(text.size <= MAX_PACK_META_BYTES) {
                I18n.t("saveA1.datapackArchive.manifestTooLarge")
            }
            val json = JsonParser.parseString(text.toString(Charsets.UTF_8)).asJsonObject
            val pack =
                json.getAsJsonObject("pack")
                    ?: throw IllegalArgumentException(I18n.t("saveA1.datapackArchive.missingPack"))
            val format =
                pack.get("pack_format")
                    ?: throw IllegalArgumentException(I18n.t("saveA1.datapackArchive.missingPackFormat"))
            require(format.isJsonPrimitive && format.asJsonPrimitive.isNumber) {
                I18n.t("saveA1.datapackArchive.packFormatNotNumber")
            }
            val value = format.asInt
            require(value in 1..MAX_PACK_FORMAT) {
                I18n.t("saveA1.datapackArchive.packFormatUnsupported", value)
            }
            value
        }
    }.getOrElse { failure ->
        if (failure is IllegalArgumentException) throw failure
        throw IllegalArgumentException(I18n.t("saveA1.datapackArchive.invalidZip", archive.name), failure)
    }

/** 先尝试原子改名，文件系统不支持时退回覆盖替换。 */
internal fun replaceFile(
    source: Path,
    target: Path,
) {
    runCatching { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE) }
        .getOrElse { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING) }
}
