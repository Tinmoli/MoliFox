// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 Quilt / Fabric / 旧版 mcmod.info 三种 JSON 声明的解析。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.zip.ZipFile

/** 只读取声明内容，不加载类、不解压文件、不执行构建表达式。 */
internal object ModMetadataJsonFormats {
    fun parseQuilt(zip: ZipFile): ModMetadata? {
        val source = zip.declarationText("quilt.mod.json") ?: return null
        val root = JsonParser.parseString(source).asJsonObject
        val loader = root.obj("quilt_loader")
        val info = loader.obj("metadata")
        val minecraft =
            loader
                .array("depends")
                .mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
                .firstOrNull { it.string("id") == "minecraft" }
        return ModMetadata(
            "Quilt",
            listOf(
                ModEntry(
                    loader.string("id"),
                    info.string("name", loader.string("id")),
                    loader.string("version"),
                    info.obj("contributors").keySet().joinToString(),
                    info.string("description"),
                    quiltDependencyIds(loader),
                    minecraft
                        ?.get("versions")
                        ?.let { jsonStrings(it).joinToString(" || ") }
                        .orEmpty(),
                    projectRefs(
                        jsonStrings(info.get("contact")) + jsonStrings(info.get("homepage")),
                    ),
                ),
            ),
        )
    }

    private fun quiltDependencyIds(loader: JsonObject): List<String> =
        loader.array("depends").mapNotNull { dependency ->
            dependency
                .takeIf(JsonElement::isJsonObject)
                ?.asJsonObject
                ?.string("id")
                ?.takeIf(String::isNotBlank)
        }

    fun parseFabric(zip: ZipFile): ModMetadata? {
        val source = zip.declarationText("fabric.mod.json") ?: return null
        val info = JsonParser.parseString(source).asJsonObject
        return ModMetadata(
            "Fabric",
            listOf(
                ModEntry(
                    info.string("id"),
                    info.string("name", info.string("id")),
                    info.string("version"),
                    info.array("authors").joinToString { authorName(it) },
                    info.string("description"),
                    info.obj("depends").entrySet().map { (id, value) -> "$id $value" },
                    jsonStrings(info.obj("depends").get("minecraft")).joinToString(" || "),
                    projectRefs(
                        jsonStrings(info.get("contact")) +
                            jsonStrings(info.get("homepage")) +
                            jsonStrings(info.get("sources")),
                    ),
                ),
            ),
        )
    }

    fun parseLegacy(zip: ZipFile): ModMetadata? {
        val source = zip.declarationText("mcmod.info") ?: return null
        val json = JsonParser.parseString(source)
        val mods = if (json.isJsonArray) json.asJsonArray else json.asJsonObject.array("modList")
        return ModMetadata(
            "Forge (legacy)",
            mods.map { value -> legacyEntry(value.asJsonObject) },
        )
    }

    private fun legacyEntry(info: JsonObject): ModEntry {
        val minecraftConstraint =
            (
                listOf(info.string("mcversion"), info.string("gameVersion")) +
                    info.array("acceptableMinecraftVersions").map { it.asString }
            ).filter { it.isNotBlank() }
                .distinct()
                .joinToString(" || ")
        return ModEntry(
            info.string("modid"),
            info.string("name", info.string("modid")),
            info.string("version"),
            info.array("authorList").joinToString { authorName(it) },
            info.string("description"),
            emptyList(),
            minecraftConstraint,
            projectRefs(listOf(info.string("url"))),
        )
    }

    private fun jsonStrings(value: JsonElement?): List<String> =
        when {
            value == null || value.isJsonNull -> emptyList()
            value.isJsonPrimitive ->
                if (value.asJsonPrimitive.isString) listOf(value.asString) else emptyList()
            value.isJsonArray -> value.asJsonArray.flatMap { jsonStrings(it) }
            value.isJsonObject ->
                value.asJsonObject.entrySet().flatMap { (key, child) ->
                    // 只收集可能是链接或版本号的文本字段，跳过颜色、文件名等无关值。
                    if (key.equals("logoFile", true) || key.equals("icon", true)) {
                        emptyList()
                    } else {
                        jsonStrings(child)
                    }
                }
            else -> emptyList()
        }

    private fun authorName(value: JsonElement): String =
        if (value.isJsonObject) {
            value.asJsonObject.string("name")
        } else if (value.isJsonPrimitive) {
            value.asString
        } else {
            ""
        }
}
