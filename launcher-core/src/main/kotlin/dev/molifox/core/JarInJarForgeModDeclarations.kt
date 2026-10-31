// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 Forge/NeoForge 的 TOML 模组声明与旧版 mcmod.info 声明。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.tomlj.Toml
import org.tomlj.TomlTable
import java.util.jar.Manifest
import java.util.zip.ZipFile

private const val JAR_VERSION_PLACEHOLDER = "\${file.jarVersion}"
private const val OPTIONAL = "optional"
private const val INCOMPATIBLE = "incompatible"
private const val DISCOURAGED = "discouraged"

/** 只读取声明内容，不加载类、不解压文件、不执行构建表达式。 */
internal object JarInJarForgeModDeclarations {
    /** 先读首选 TOML，再回退到另一种加载器的 TOML；两者都不存在时由调用方回退到旧版声明。 */
    fun toml(
        zip: ZipFile,
        preferNeoForge: Boolean,
    ): JarModReadResult? {
        val text =
            if (preferNeoForge) {
                zip.declarationText(NEOFORGE_TOML) ?: zip.declarationText(FORGE_TOML)
            } else {
                zip.declarationText(FORGE_TOML) ?: zip.declarationText(NEOFORGE_TOML)
            } ?: return null
        val parsed = Toml.parse(text)
        val mods = parsed.getArray("mods")
        // 显式声明中间变量：tomlj 的 getTable 返回值带 checkerframework 可空注解，
        // 直接写进 ?.let 的推断参数会暴露不可访问的第三方注解。
        val tables: List<TomlTable>? =
            if (mods == null) {
                null
            } else {
                (0 until mods.size()).mapNotNull { mods.getTable(it) }
            }
        return when {
            parsed.hasErrors() ->
                JarModReadResult(complete = false, note = "TOML 模组声明解析失败")
            tables == null ->
                JarModReadResult(complete = false, note = "TOML 模组声明缺少 mods 列表")
            tables.isEmpty() ->
                JarModReadResult(complete = false, note = "TOML 模组声明缺少 mods 条目")
            else -> {
                val jarVersion = manifestVersion(zip)
                val declaredIds =
                    tables.mapNotNull { it.getString("modId")?.lowercase() }.toSet()
                val dependencyTable = parsed.getTable("dependencies")
                JarModReadResult(
                    tables.map { table ->
                        tomlMod(table, dependencyTable, declaredIds, jarVersion)
                    },
                )
            }
        }
    }

    fun legacy(zip: ZipFile): JarModReadResult {
        val text = zip.declarationText(LEGACY_INFO) ?: return emptyModRead
        val json = JsonParser.parseString(text)
        val mods = if (json.isJsonArray) json.asJsonArray else json.asJsonObject.array("modList")
        val entries =
            mods.mapNotNull { element ->
                element.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let(::legacyMod)
            }
        return JarModReadResult(entries)
    }

    private fun tomlMod(
        table: TomlTable,
        dependencyTable: TomlTable?,
        declaredIds: Set<String>,
        jarVersion: String,
    ): JarModDeclaration {
        val id = table.getString("modId").orEmpty()
        val dependencies = mutableListOf<JarInJarRequirement>()
        val conflicts = mutableListOf<JarInJarConflict>()
        val targetMc =
            collectTomlDependencies(
                dependencyTable,
                id,
                declaredIds,
                dependencies,
                conflicts,
            )
        return JarModDeclaration(
            id = id,
            name = table.getString("displayName") ?: id,
            version = tomlVersion(table, jarVersion),
            targetMc = targetMc,
            dependencies = dependencies.distinct(),
            conflicts = conflicts.distinct(),
        )
    }

    /** 只登记声明者属于本 JAR 的依赖区，返回第一条 Minecraft 约束作为节点目标版本。 */
    private fun collectTomlDependencies(
        dependencyTable: TomlTable?,
        modId: String,
        declaredIds: Set<String>,
        dependencies: MutableList<JarInJarRequirement>,
        conflicts: MutableList<JarInJarConflict>,
    ): String {
        if (dependencyTable == null) return ""
        // [[dependencies.<modId>]] 只属于该 mod；兄弟 mod 自己的依赖不能混进当前 mod。
        val key = dependencyTable.keySet().firstOrNull { it.equals(modId, ignoreCase = true) }
        val array = if (key == null) null else dependencyTable.getArray(key)
        if (array == null) return ""
        var targetMc = ""
        for (index in 0 until array.size()) {
            val dependency = array.getTable(index)
            if (dependency != null) {
                val minecraft =
                    collectTomlDependency(
                        dependency,
                        declaredIds,
                        modId.lowercase(),
                        dependencies,
                        conflicts,
                    )
                if (targetMc.isBlank()) targetMc = minecraft
            }
        }
        return targetMc
    }

    /** 登记一条 Forge 依赖或冲突，返回该条目的 Minecraft 约束（用于节点目标版本）。 */
    private fun collectTomlDependency(
        entry: TomlTable,
        declaredIds: Set<String>,
        declaringId: String,
        dependencies: MutableList<JarInJarRequirement>,
        conflicts: MutableList<JarInJarConflict>,
    ): String {
        val dependencyId = entry.getString("modId") ?: return ""
        val type = entry.getString("type").orEmpty().lowercase()
        val serverOnly = entry.getString("side").orEmpty().equals("server", true)
        val range = entry.getString("versionRange").orEmpty()
        val minecraft = if (dependencyId.equals("minecraft", true)) range else ""
        when {
            type == INCOMPATIBLE || type == DISCOURAGED ->
                if (!serverOnly) {
                    conflicts +=
                        JarInJarConflict(
                            dependencyId,
                            range,
                            hard = type == INCOMPATIBLE,
                            declarerId = declaringId,
                        )
                }
            // 同一个 JAR 内的兄弟 mod 由自己提供，不算外部依赖；服务端专用依赖不影响客户端。
            serverOnly || dependencyId.lowercase() in declaredIds -> Unit
            else ->
                dependencies +=
                    JarInJarRequirement(
                        dependencyId,
                        range,
                        optional = type == OPTIONAL || entry.getBoolean("mandatory") == false,
                    )
        }
        return minecraft
    }

    private fun tomlVersion(
        table: TomlTable,
        jarVersion: String,
    ): String = table.getString("version").orEmpty().replace(JAR_VERSION_PLACEHOLDER, jarVersion)

    private fun manifestVersion(zip: ZipFile): String =
        zip
            .declarationText(MANIFEST)
            ?.let {
                Manifest(it.byteInputStream()).mainAttributes.getValue("Implementation-Version")
            }.orEmpty()

    private fun legacyMod(info: JsonObject): JarModDeclaration {
        val id = info.string("modid")
        val required =
            (info.array("requiredMods").toList() + info.array("dependencies").toList())
                .mapNotNull { legacyRequirement(it) }
        return JarModDeclaration(
            id = id,
            name = info.string("name", id),
            version = info.string("version"),
            targetMc = legacyMinecraft(info),
            dependencies = required.distinct(),
        )
    }

    private fun legacyMinecraft(info: JsonObject): String {
        val constraints =
            listOf(info.string("mcversion"), info.string("gameVersion")) +
                info.array("acceptableMinecraftVersions").mapNotNull { requiredString(it) }
        return constraints.filter { it.isNotBlank() }.distinct().joinToString(" || ")
    }

    /** 旧版声明的必选依赖写作 `modid` 或 `modid@[1.0,)`。 */
    private fun legacyRequirement(element: JsonElement): JarInJarRequirement? {
        val raw = requiredString(element).trim()
        if (raw.isEmpty()) return null
        val separator = raw.indexOf('@')
        val id = if (separator < 0) raw else raw.substring(0, separator)
        if (id.isBlank()) return null
        val constraint = if (separator < 0) "" else raw.substring(separator + 1).trim()
        return JarInJarRequirement(id, constraint)
    }
}

/** Gson 的数字/布尔基元按 `asString` 取值会得到文本；非基元返回空串。 */
private fun requiredString(element: JsonElement): String =
    if (element.isJsonPrimitive) element.asString else ""
