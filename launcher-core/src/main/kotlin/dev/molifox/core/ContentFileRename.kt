// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists

/** 重命名是独立的本地内容写操作，不扩大只负责列出、导入和开关的 [ContentLocalApi]。 */
internal interface ContentRenameApi {
    fun renamePack(
        id: String,
        folder: PackFolder,
        filename: String,
        newName: String,
    ): String

    fun renameMod(
        id: String,
        filename: String,
        newName: String,
    ): String
}

internal class ContentRenameActions(
    private val core: ContentServiceCore,
) : ContentRenameApi {
    override fun renamePack(
        id: String,
        folder: PackFolder,
        filename: String,
        newName: String,
    ): String = core.localPacks.rename(id, folder, filename, newName)

    override fun renameMod(
        id: String,
        filename: String,
        newName: String,
    ): String = renameInstalledContent(core.store, id, "mods", filename, newName, RenameContentKind.Mod)
}

/** 在实例内容目录内重命名，并同步维护启动器的来源登记。 */
internal fun renameInstalledContent(
    store: LauncherStore,
    instanceId: String,
    folder: String,
    filename: String,
    newName: String,
    kind: RenameContentKind,
): String {
    val root = store.instancePath(instanceId).resolve(folder)
    val source = safePath(root, safeFilename(filename))
    val attributes = Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    require(!attributes.isSymbolicLink && !attributes.isOther) {
        I18n.t("contentA1.local.renameSourceMissing")
    }
    val targetName = renamedContentFilename(filename, newName, kind)
    if (targetName == filename) return filename
    val target = safePath(root, safeFilename(targetName))
    require(!target.exists(LinkOption.NOFOLLOW_LINKS)) {
        I18n.t("contentA1.local.renameTargetExists", targetName)
    }
    val registryFile = store.instancePath(instanceId).resolve(".molifox-content.json")
    val registry = registryFile.takeIf { it.exists(LinkOption.NOFOLLOW_LINKS) }?.let(::readObject)
    Files.move(source, target)
    try {
        registry?.let {
            updateRegisteredFilename(it, folder, filename, targetName)
            writeJson(registryFile, it)
        }
    } catch (failure: java.io.IOException) {
        runCatching { Files.move(target, source) }
        throw failure
    }
    store.notify(I18n.t("contentA1.local.renamed", filename, targetName))
    return targetName
}

internal enum class RenameContentKind {
    Mod,
    ZipPack,
    DirectoryPack,
}

private fun renamedContentFilename(
    oldName: String,
    requested: String,
    kind: RenameContentKind,
): String {
    val trimmed = requested.trim()
    require(trimmed.isNotBlank()) { I18n.t("packsB1.localPacks.blankName") }
    safeFilename(trimmed)
    return when (kind) {
        RenameContentKind.DirectoryPack -> trimmed
        RenameContentKind.ZipPack -> if (trimmed.endsWith(".zip", true)) trimmed else "$trimmed.zip"
        RenameContentKind.Mod -> renamedModFilename(oldName, trimmed)
    }
}

private fun renamedModFilename(
    oldName: String,
    requested: String,
): String {
    val disabled = oldName.endsWith(".disabled", true)
    val withoutDisabled = contentBaseFilename(requested)
    val jar = if (withoutDisabled.endsWith(".jar", true)) withoutDisabled else "$withoutDisabled.jar"
    return if (disabled) "$jar.disabled" else jar
}

private fun updateRegisteredFilename(
    registry: com.google.gson.JsonObject,
    folder: String,
    oldName: String,
    newName: String,
) {
    registry.entrySet().forEach { (_, value) ->
        val record = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
        if (record.string("folder") == folder && record.string("file") == contentBaseFilename(oldName)) {
            record.addProperty("file", contentBaseFilename(newName))
        }
    }
}
