// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 Fabric/Quilt 的 JSON 模组声明：depends/recommends/suggests/breaks/provides 与图标元数据。

package dev.molifox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.zip.ZipFile

/** 只读取声明内容；`schemaVersion`/`schema_version` 是识别 Fabric/Quilt 文本的最低门槛。 */
internal object JarInJarFabricModDeclarations {
    fun fabric(zip: ZipFile): JarModReadResult {
        val text = zip.declarationText(FABRIC_MOD) ?: return emptyModRead
        // 没有 schemaVersion 的文本不当作 Fabric 声明，避免把别的 JSON 误认成模组元数据。
        if (!text.contains("schemaVersion")) return emptyModRead
        val root = JsonParser.parseString(text).asJsonObject
        val id = root.string("id")
        return JarModReadResult(
            listOf(
                JarModDeclaration(
                    id = id,
                    name = root.string("name", id),
                    version = root.string("version"),
                    targetMc = jsonVersionText(root.obj("depends").get("minecraft")),
                    dependencies = fabricRequirements(root),
                    conflicts = fabricConflicts(root),
                    provides = providedIds(root.array("provides")),
                ),
            ),
        )
    }

    fun quilt(zip: ZipFile): JarModReadResult {
        val text = zip.declarationText(QUILT_MOD) ?: return emptyModRead
        if (!text.contains("schema_version")) return emptyModRead
        val loader = JsonParser.parseString(text).asJsonObject.obj("quilt_loader")
        val metadata = loader.obj("metadata")
        val id = loader.string("id")
        val depends = loader.array("depends")
        return JarModReadResult(
            listOf(
                JarModDeclaration(
                    id = id,
                    name = metadata.string("name", id),
                    version = loader.string("version"),
                    targetMc = quiltMinecraftConstraint(depends),
                    dependencies = quiltRequirements(depends),
                    conflicts = quiltConflicts(loader.array("breaks")),
                    provides = emptyList(),
                ),
            ),
        )
    }

    private fun fabricRequirements(root: JsonObject): List<JarInJarRequirement> {
        val required = jsonRequirements(root.obj("depends"), optional = false)
        val optional =
            listOf("recommends", "suggests").flatMap {
                jsonRequirements(root.obj(it), optional = true)
            }
        return required + optional
    }

    private fun jsonRequirements(
        table: JsonObject,
        optional: Boolean,
    ): List<JarInJarRequirement> =
        table.entrySet().mapNotNull { (id, value) ->
            id.takeIf { it.isNotBlank() }?.let {
                JarInJarRequirement(it, jsonVersionText(value), optional)
            }
        }

    private fun fabricConflicts(root: JsonObject): List<JarInJarConflict> =
        jsonConflicts(root.obj("breaks"), hard = true) +
            jsonConflicts(root.obj("conflicts"), hard = false)

    private fun jsonConflicts(
        table: JsonObject,
        hard: Boolean,
    ): List<JarInJarConflict> =
        table.entrySet().mapNotNull { (id, value) ->
            id.takeIf { it.isNotBlank() }?.let {
                JarInJarConflict(it, jsonVersionText(value), hard)
            }
        }

    private fun providedIds(array: JsonArray): List<JarInJarProvided> =
        array
            .mapNotNull { it.stringOrNull()?.takeIf(String::isNotBlank) }
            .map { JarInJarProvided(it) }

    private fun quiltMinecraftConstraint(depends: JsonArray): String =
        depends
            .mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            .firstOrNull { it.string("id").equals("minecraft", true) }
            ?.let { jsonVersionText(it.get("versions")) }
            .orEmpty()

    private fun quiltRequirements(depends: JsonArray): List<JarInJarRequirement> =
        depends.mapNotNull { element ->
            val item = element.takeIf(JsonElement::isJsonObject)?.asJsonObject
            if (item == null) return@mapNotNull null
            val id = item.string("id")
            if (id.isBlank()) return@mapNotNull null
            val optional =
                item.get("optional")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
            JarInJarRequirement(id, jsonVersionText(item.get("versions")), optional)
        }

    private fun quiltConflicts(breaks: JsonArray): List<JarInJarConflict> =
        quiltRequirements(breaks).map { JarInJarConflict(it.id, it.constraint, hard = true) }
}

/** Fabric 版本值可能是单个字符串或数组，数组按 ` || ` 连接成一条约束。 */
private fun jsonVersionText(value: JsonElement?): String =
    when {
        value == null || value.isJsonNull -> ""
        value.isJsonArray ->
            value.asJsonArray
                .mapNotNull { it.stringOrNull() }
                .joinToString(" || ")
        else -> value.stringOrNull().orEmpty()
    }

/** Gson 的数字/布尔基元按 `asString` 取值会得到文本；非基元返回 null。 */
private fun JsonElement.stringOrNull(): String? = if (isJsonPrimitive) asString else null
