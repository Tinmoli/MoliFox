// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责把加载器更新合并进外部（其它启动器维护的）版本配置，保留原结构。

package dev.molifox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

private const val MIN_MAVEN_COORDINATE_PARTS = 3
private const val MAVEN_CLASSIFIER_PART_INDEX = 3

/** 加载器自有依赖库的坐标前缀：升级时替换这些库，其它库与自定义字段原样保留。 */
private val loaderLibraryPrefixes =
    listOf("net.fabricmc:", "org.quiltmc:", "net.minecraftforge:", "net.neoforged:", "cpw.mods:")

/**
 * 把加载器官方配置合并进外部版本 JSON。
 *
 * 以外部 JSON 为底稿：`id`、`inheritsFrom`、`type`、`time` 以及其它自定义字段全部保留；
 * 只更新 `mainClass`、加载器自有依赖库与加载器参数。不会写入 `molifoxManaged`，也不会展开成
 * 自包含配置，因此原启动器仍然认得这份文件。
 */
internal fun mergeExternalLoaderProfile(
    original: JsonObject,
    loader: JsonObject,
): JsonObject {
    val merged = original.deepCopy()
    loader
        .string(
            "mainClass",
        ).takeIf { it.isNotBlank() }
        ?.let { merged.addProperty("mainClass", it) }
    merged.add("libraries", mergedExternalLibraries(original, loader))
    if (original.has("arguments") || loader.has("arguments")) {
        merged.add("arguments", mergedExternalArguments(original, loader))
    }
    // 1.12 及更早的 Forge 用 minecraftArguments；加载器给出新值时替换，没给就保留原文。
    loader.string("minecraftArguments").takeIf { it.isNotBlank() }?.let {
        merged.addProperty("minecraftArguments", it)
    }
    return merged
}

/** 保留非加载器库，再追加新加载器库；同一坐标以新库为准。 */
private fun mergedExternalLibraries(
    original: JsonObject,
    loader: JsonObject,
): JsonArray {
    val byCoordinate = linkedMapOf<String, JsonElement>()
    original.array("libraries").forEach { element ->
        val name = runCatching { element.asJsonObject.string("name") }.getOrDefault("")
        if (loaderLibraryPrefixes.any { name.startsWith(it) }) return@forEach
        byCoordinate[libraryCoordinate(name)] = element.deepCopy()
    }
    loader.array("libraries").forEach { element ->
        val name = runCatching { element.asJsonObject.string("name") }.getOrDefault("")
        byCoordinate[libraryCoordinate(name)] = element.deepCopy()
    }
    return JsonArray().apply { byCoordinate.values.forEach(::add) }
}

/** 坐标身份只取 group:artifact:classifier，版本变化时替换而不是重复堆叠。 */
private fun libraryCoordinate(name: String): String {
    val parts = name.split(':')
    if (parts.size < MIN_MAVEN_COORDINATE_PARTS) return name
    val classifier = parts.getOrElse(MAVEN_CLASSIFIER_PART_INDEX) { "" }
    return "${parts[0]}:${parts[1]}:$classifier"
}

/**
 * 合并参数：新加载器声明的开关（含紧跟的值）替换旧值，其余原有参数按顺序保留，
 * 重复项只留一份；这样旧版本的 `--fml.forgeVersion` 之类不会和新值并存。
 *
 * `arguments.game/jvm` 里还可能有 `{"rules":…,"value":…}` 条件参数：它们不是字符串
 * token，不能参与开关/值的替换匹配，只能原样保留；新旧条件参数按结构相等去重。
 */
private fun mergedExternalArguments(
    original: JsonObject,
    loader: JsonObject,
): JsonObject {
    val result = JsonObject()
    val oldArguments = original.obj("arguments")
    val newArguments = loader.obj("arguments")
    for (kind in listOf("game", "jvm")) {
        val old = oldArguments.array(kind).map { it.deepCopy() }
        val incoming = newArguments.array(kind).map { it.deepCopy() }
        if (old.isEmpty() && incoming.isEmpty()) continue
        result.add(kind, JsonArray().apply { mergeArgumentTokens(old, incoming).forEach(::add) })
    }
    return result
}

/** 字符串 token 的开关替换规则；对象/数组条件参数只做结构去重，不参与 token 匹配。 */
private fun mergeArgumentTokens(
    old: List<JsonElement>,
    incoming: List<JsonElement>,
): List<JsonElement> {
    val replacedFlags =
        incoming.mapNotNull { it.argumentToken() }.filter { it.startsWith("-") }.toSet()
    val kept = mutableListOf<JsonElement>()
    var skipValue = false
    for (element in old) {
        val token = element.argumentToken()
        when {
            // 只跳过被替换开关紧跟的字符串值；条件参数不是值，遇到时保留并结束跳过状态。
            skipValue && token != null && !token.startsWith("-") -> skipValue = false
            token != null && token in replacedFlags -> skipValue = true
            else -> {
                skipValue = false
                kept += element
            }
        }
    }
    for (element in incoming) {
        val token = element.argumentToken()
        val duplicate =
            if (token == null) {
                kept.any { it == element }
            } else {
                kept.any { it.argumentToken() == token }
            }
        if (!duplicate) kept += element
    }
    return kept
}

/** 对象/数组不是参数 token；数字等原始值沿用旧行为按文本比较。 */
private fun JsonElement.argumentToken(): String? = takeIf { it.isJsonPrimitive }?.asString
