// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 各种模组声明格式共用的读取与链接提取工具。

package dev.molifox.core

import java.util.zip.ZipFile

/** 单个声明最多 2 MB，避免压缩炸弹把整个文件读进内存。 */
private const val MAX_DECLARATION_BYTES = 2 * 1024 * 1024

/** 读取 ZIP 里指定名称的声明文本；条目不存在时返回 null。 */
internal fun ZipFile.declarationText(name: String): String? {
    val entry = getEntry(name) ?: return null
    require(entry.size <= MAX_DECLARATION_BYTES) { "声明文件过大" }
    return getInputStream(entry).use {
        val bytes = it.readNBytes(MAX_DECLARATION_BYTES + 1)
        require(bytes.size <= MAX_DECLARATION_BYTES) { "声明文件过大" }
        bytes.toString(Charsets.UTF_8)
    }
}

/** 从主页/源码链接里提取 Modrinth 与 CurseForge 项目 slug，其它域名忽略。 */
internal fun projectRefs(urls: Iterable<String>): List<ModProjectRef> {
    val refs = mutableListOf<ModProjectRef>()
    for (url in urls) {
        modrinthProjectUrl.find(url)?.groupValues?.getOrNull(1)?.let {
            refs += ModProjectRef("modrinth", it)
        }
        curseForgeProjectUrl.find(url)?.groupValues?.getOrNull(1)?.let {
            refs += ModProjectRef("curseforge", it)
        }
    }
    return refs.distinct()
}

private val modrinthProjectUrl =
    Regex("(?i)https?://(?:www\\.)?modrinth\\.com/(?:mod|project)/([a-z0-9_-]+)")

private val curseForgeProjectUrl =
    Regex("(?i)https?://(?:www\\.)?curseforge\\.com/minecraft/mc-mods/([a-z0-9_-]+)")
