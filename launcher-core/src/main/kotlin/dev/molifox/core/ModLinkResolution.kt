// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 本地模组详情里的项目链接：先按指纹认定项目，再退回名称搜索，尽量对齐下载页那一排链接。

package dev.molifox.core

import com.google.gson.JsonParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.io.path.isRegularFile

/** 名称太短容易误配，低于该长度不做名称搜索。 */
private const val MOD_LINK_QUERY_MIN = 3

/** 前缀命中要求的最小长度，避免短词链到无关项目。 */
private const val MOD_LINK_PREFIX_MIN = 8

/**
 * 本地模组详情弹窗用的项目资料：指纹命中就用官方项目，未命中再按名称搜索。
 *
 * 识别不到或网络失败都返回 null，界面只显示本地文件信息，不阻塞弹窗。
 */
suspend fun ContentService.modDetails(
    instanceId: String,
    filename: String,
    title: String,
): ContentDetails? =
    withContext(Dispatchers.IO) {
        val projectId = projectIdFor(instanceId, filename, title)
        if (projectId == null) null else detailsOrNull(projectId, instanceId)
    }

/** 只留字母与数字并转小写：搜索命中与本地文件名用同一口径比较。 */
internal fun normalizedModTitle(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

/** 搜索命中的项目是否就是本地这个模组：完全同名，或名称足够长时唯一前缀命中。 */
internal fun matchModHit(
    hits: List<ContentHit>,
    query: String,
): ContentHit? =
    hits.firstOrNull { normalizedModTitle(it.title) == query }
        ?: if (query.length < MOD_LINK_PREFIX_MIN) {
            null
        } else {
            hits.filter { normalizedModTitle(it.title).startsWith(query) }.singleOrNull()
        }

/** 项目详情；网络失败与未知项目都当作「没有」，调用方据此退化显示。 */
private suspend fun ContentService.detailsOrNull(
    projectId: String,
    instanceId: String,
): ContentDetails? =
    try {
        catalog.details(projectId, instanceId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        null
    } catch (_: JsonParseException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

private suspend fun ContentService.projectIdFor(
    instanceId: String,
    filename: String,
    title: String,
): String? {
    val file =
        core.store
            .instancePath(instanceId)
            .resolve("mods")
            .resolve(filename)
    val byHash = if (file.isRegularFile()) catalog.projectIdByHash(file) else null
    val query =
        normalizedModTitle(title).takeIf { it.length >= MOD_LINK_QUERY_MIN }
            ?: normalizedModTitle(filename.substringBeforeLast('.')).takeIf { it.length >= MOD_LINK_QUERY_MIN }
    val hits = if (byHash == null && query != null) searchHits(query, instanceId) else emptyList()
    return byHash ?: query?.let { matchModHit(hits, it)?.id }
}

/** 名称搜索失败（离线、镜像不支持）按「没有命中」处理。 */
private suspend fun ContentService.searchHits(
    query: String,
    instanceId: String,
): List<ContentHit> =
    try {
        search(query, "mod", instanceId).hits
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        emptyList()
    } catch (_: JsonParseException) {
        emptyList()
    } catch (_: IllegalStateException) {
        emptyList()
    }
