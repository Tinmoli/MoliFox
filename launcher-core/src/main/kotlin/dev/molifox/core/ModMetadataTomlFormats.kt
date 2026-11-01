// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 NeoForge / Forge 两种 TOML 声明的解析。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable
import java.util.jar.Manifest
import java.util.zip.ZipFile

/** 只读取声明内容，不加载类、不解压文件、不执行构建表达式。 */
internal object ModMetadataTomlFormats {
    fun parseNeoForge(zip: ZipFile): ModMetadata? = parseForgeToml(zip, "META-INF/neoforge.mods.toml", "NeoForge")

    fun parseForge(zip: ZipFile): ModMetadata? = parseForgeToml(zip, "META-INF/mods.toml", "Forge")

    private fun parseForgeToml(
        zip: ZipFile,
        filename: String,
        loader: String,
    ): ModMetadata? {
        val source = zip.declarationText(filename) ?: return null
        val parsed = Toml.parse(source)
        require(!parsed.hasErrors()) { I18n.t("packsC.toml.malformed") }
        val jarVersion =
            zip
                .declarationText("META-INF/MANIFEST.MF")
                ?.let {
                    Manifest(it.byteInputStream()).mainAttributes.getValue("Implementation-Version")
                }.orEmpty()
        val mods =
            parsed.getArray("mods")
                ?: return ModMetadata(loader, warning = I18n.t("packsC.toml.missingMods"))
        return ModMetadata(
            loader,
            (0 until mods.size()).map { index -> tomlEntry(parsed, mods, index, jarVersion) },
        )
    }

    private fun tomlEntry(
        parsed: TomlParseResult,
        mods: TomlArray,
        index: Int,
        jarVersion: String,
    ): ModEntry {
        val mod = mods.getTable(index)
        val id = mod.getString("modId").orEmpty()
        val dependencies = tomlDependencies(parsed, id)
        val minecraftConstraint =
            dependencies
                .firstOrNull { it.getString("modId") == "minecraft" }
                ?.getString("versionRange")
                .orEmpty()
        return ModEntry(
            id,
            mod.getString("displayName") ?: id,
            mod.getString("version").orEmpty().replace("\${file.jarVersion}", jarVersion),
            mod.getString("authors").orEmpty(),
            mod.getString("description").orEmpty(),
            dependencies.map {
                "${it.getString("modId").orEmpty()} ${it.getString("versionRange").orEmpty()}"
            },
            minecraftConstraint,
            projectRefs(
                listOfNotNull(
                    mod.getString("displayURL"),
                    mod.getString("displayUrl"),
                    mod.getString("homepage"),
                ),
            ),
        )
    }

    private fun tomlDependencies(
        parsed: TomlParseResult,
        id: String,
    ): List<TomlTable> {
        val dependencies =
            parsed.getTable("dependencies")?.getArray(listOf(id)) ?: return emptyList()
        return (0 until dependencies.size()).map { dependencies.getTable(it) }
    }
}
