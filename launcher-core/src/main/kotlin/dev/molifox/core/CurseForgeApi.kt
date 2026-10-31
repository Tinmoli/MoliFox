// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 CurseForge 镜像地址、请求封装、分类表与公共 JSON 读取。

package dev.molifox.core

import com.google.gson.JsonObject

/** 搜索与文件分页共用的结果窗口上限，超出部分镜像不会返回。 */
internal const val CURSE_FORGE_RESULT_LIMIT = 10000

/** 批量 POST 请求一次最多携带的编号数量，镜像按此上限接受。 */
internal const val CURSE_FORGE_BATCH_SIZE = 50

/** CurseForge 只承载 Minecraft 内容；镜像省略 gameId 时按该编号处理。 */
internal const val MINECRAFT_GAME_ID = 432

/** 日志里带出的响应片段长度：够定位问题，又不把整页 JSON 写进日志。 */
private const val FAILURE_SNIPPET_LENGTH = 200

/** 社区镜像不提供介绍/更新日志端点时返回的状态码。 */
private const val HTTP_STATUS_NOT_FOUND = 404

/** CurseForge 的 modLoaderType 编号；没有编号的加载器不发该筛选。 */
private const val LOADER_ID_FORGE = 1
private const val LOADER_ID_FABRIC = 4
private const val LOADER_ID_QUILT = 5
private const val LOADER_ID_NEOFORGE = 6

/** 项目介绍转纯文本时保留的最大字符数。 */
private const val PLAIN_DESCRIPTION_MAX_LENGTH = 250000

/** 世界在 CurseForge 的固定分类编号；镜像分类表省略 worlds 时按此兜底。 */
internal const val CURSE_FORGE_WORLD_CLASS_ID = 17

/** 资源类型 → 镜像分类 slug；镜像新增的分类只有在表内才被识别。 */
internal val curseForgeClassSlugs =
    mapOf(
        "mod" to "mc-mods",
        "modpack" to "modpacks",
        "resourcepack" to "texture-packs",
        "shader" to "shaders",
        "datapack" to "data-packs",
        "world" to "worlds",
    )

/** 官方分类编号的固定兜底：部分社区镜像的分类表不返回这些顶层类型。 */
private val curseForgeClassIds =
    mapOf(
        "world" to CURSE_FORGE_WORLD_CLASS_ID,
    )

/** 顶层资源类型（游戏 ID 432 的 class 分类）；[payload] 用于失败时回带响应片段。 */
internal class CurseForgeClassIndex(
    val kinds: Map<Int, String>,
    val payload: JsonObject,
)

internal class CurseForgeApi(
    private val store: LauncherStore,
    private val network: NetworkClient,
) {
    /** 设置变更后立即生效；空值/非法值回落默认镜像，只记录一次日志。 */
    private var fallbackLoggedFor: String? = null

    private fun baseUrl(): String {
        val raw =
            store.data.settings.curseForgeBaseUrl
                .orEmpty()
        val effective = normalizedCurseForgeBaseUrl(raw)
        if (effective == DEFAULT_CURSE_FORGE_BASE_URL && raw.trim().trimEnd('/') != effective) {
            if (fallbackLoggedFor != raw) {
                store.log("CurseForge 服务地址无效，已使用默认镜像：$DEFAULT_CURSE_FORGE_BASE_URL", true)
                fallbackLoggedFor = raw
            }
        } else {
            fallbackLoggedFor = null
        }
        return effective
    }

    /** 所有 CurseForge API 请求的根地址（含版本段）；社区镜像与官方 API 的路径结构一致。 */
    val rootUrl: String
        get() = "${baseUrl()}/v1"

    suspend fun request(
        path: String,
        body: JsonObject? = null,
    ) = network.curseForgeJson("$rootUrl/$path", body)

    /** 失败时只带响应开头，供日志和界面定位是哪一级、哪段数据出的问题。 */
    private fun snippet(payload: Any): String =
        payload
            .toString()
            .take(FAILURE_SNIPPET_LENGTH)
            .replace('\n', ' ')
            .replace('\r', ' ')

    fun fail(
        stage: String,
        detail: String,
        payload: Any,
    ): Nothing {
        val message = "CurseForge $stage 失败：$detail；响应片段：${snippet(payload)}"
        store.log(message, true)
        throw IllegalStateException(message)
    }

    /** 顶层资源类型（游戏 ID 432 的 class 分类）。镜像字段可能缺失，这里按顶层项容错识别。 */
    suspend fun classIndex(): CurseForgeClassIndex {
        val payload = request("categories?gameId=432&classesOnly=true")
        val kinds =
            payload
                .array("data")
                .mapNotNull { element -> runCatching { element.asJsonObject }.getOrNull() }
                .filter { it.isMinecraftProject() }
                // 社区镜像可能不返回 isClass：没有父分类的顶层项同样当作资源类型。
                .filter { category ->
                    category.flag("isClass")
                        ?: (category.get("classId") == null || category.get("classId").isJsonNull)
                }.mapNotNull { category ->
                    curseForgeClassSlugs.entries
                        .find { it.value == category.string("slug") }
                        ?.let { category.int("id") to it.key }
                }.toMap()
        if (kinds.isEmpty()) {
            fail("分类映射", "镜像没有返回可识别的资源类型（mc-mods、modpacks 等）", payload)
        }
        return CurseForgeClassIndex(kinds, payload)
    }

    /** 资源类型在镜像分类表里的编号；缺失时按 [stage] 归因失败。 */
    fun classId(
        index: CurseForgeClassIndex,
        kind: String,
        stage: String,
    ): Int =
        index.kinds.entries
            .find { it.value == kind }
            ?.key
            ?: curseForgeClassIds[kind]
            ?: fail(stage, "镜像没有 $kind 对应的资源类型", index.payload)

    /** 项目 classId → 资源类型：镜像分类表缺失时使用固定编号兜底，避免世界项目无法打开详情。 */
    fun kindOfClassId(
        index: CurseForgeClassIndex,
        classId: Int?,
    ): String? =
        classId?.let { id ->
            index.kinds[id] ?: curseForgeClassIds.entries.find { it.value == id }?.key
        }

    /** 社区镜像不提供介绍/更新日志端点（404）；它们只是可选展示内容，不影响安装。 */
    suspend fun optionalText(path: String): String =
        try {
            request(path).string("data")
        } catch (missing: CurseForgeHttpException) {
            if (missing.statusCode == HTTP_STATUS_NOT_FOUND) "" else throw missing
        }

    suspend fun rawProject(id: String): JsonObject =
        request("mods/${curseForgeProjectId(id)}").obj("data").also {
            require(it.isMinecraftProject() && it.string("id") == curseForgeProjectId(id)) {
                "CurseForge 项目不属于 Minecraft 或标识不匹配"
            }
        }

    /**
     * 版本筛选只接受真实的 Minecraft 版本：实例目录名（`TwilightMC-26.3v1.0`）会解析成 `26.3`，
     * 解析不到就不发 gameVersion 并记一条日志，而不是让服务端返回空列表。
     */
    fun resolvedGameVersion(requested: String): String? {
        val value = requested.trim()
        if (value.isEmpty()) return null
        val resolved = minecraftVersionTag(value)
        when {
            resolved == null ->
                store.log("CurseForge 已忽略无法识别的游戏版本筛选：$value", true)
            resolved != value -> store.log("CurseForge 游戏版本筛选按识别结果发送：$value → $resolved")
        }
        return resolved
    }

    /** 没有对应编号的加载器返回 null：绝不能发 `modLoaderType=0`，镜像会把它当作匹配不到任何模组的筛选。 */
    fun loaderId(loader: LoaderType?): Int? =
        when (loader) {
            LoaderType.Forge -> LOADER_ID_FORGE
            LoaderType.Fabric -> LOADER_ID_FABRIC
            LoaderType.Quilt -> LOADER_ID_QUILT
            LoaderType.NeoForge -> LOADER_ID_NEOFORGE
            else -> null
        }
}

/** 镜像省略 gameId 时按 Minecraft 处理；明确给了别的游戏 ID 才算不匹配。 */
internal fun JsonObject.isMinecraftProject(): Boolean =
    intOrNull("gameId")?.let { it == MINECRAFT_GAME_ID } != false

/** 缺失或为 null 的数字字段按「没有」处理，不把 0 当成有效值。 */
internal fun JsonObject.intOrNull(key: String): Int? =
    get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asInt }.getOrNull() }

/** 缺失或为 null 的布尔字段返回 null，便于区分「没给」和「false」。 */
private fun JsonObject.flag(key: String): Boolean? =
    get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() }

/** 项目介绍 HTML 转纯文本：只保留展示需要的换行与转义字符。 */
internal fun plainDescription(html: String) =
    html
        .take(PLAIN_DESCRIPTION_MAX_LENGTH)
        .replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>|</(?:p|div|li|h[1-6])>"), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .trim()
