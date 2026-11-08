// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责模块内部的网络细节入口：候选源顺序与需要认证/白名单的端点。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.molifox.core.net.CandidateSource

/** 某个地址的候选源；自动模式给多个，手动模式只给选中的源。 */
internal fun NetworkClient.candidateSources(
    url: String,
    selection: String = actions.settings.source(),
): List<CandidateSource> = actions.candidateSources(url, selection)

internal fun NetworkClient.downloadCandidates(
    url: String,
    selection: String = actions.settings.source(),
): List<String> = actions.downloadCandidates(url, selection)

/**
 * 实际请求用的源与地址顺序：自动模式按地区排首选（中国镜像优先，其余地区测速排序），
 * 再按本轮源健康度重排（禁用的源剔除、限流的排到末尾）。官方与镜像地址相同的主机不发探测请求。
 */
internal suspend fun NetworkClient.orderedSources(
    url: String,
    selection: String = actions.settings.source(),
): List<CandidateSource> = actions.orderedSources(url, selection)

internal suspend fun NetworkClient.orderedCandidates(
    url: String,
    selection: String = actions.settings.source(),
): List<String> = actions.orderedCandidates(url, selection)

/** 给 Java 元数据源测速并排序，不可达的源排最后；只在当前任务中共用初始化顺序。 */
internal suspend fun NetworkClient.fastestFirst(urls: List<String>): List<String> = actions.fastestFirst(urls)

/** GitHub 发布页只走 api.github.com，避免把 token 或重定向带到别的主机。 */
internal suspend fun NetworkClient.githubJson(url: String): JsonElement = actions.githubJson(url)

/** 百科页面：只允许 mcmod.cn，限制响应体积并转成 UTF-8 文本。 */
internal suspend fun NetworkClient.encyclopediaText(url: String): String = actions.encyclopediaText(url)

/** CurseForge 公共镜像：不带 API Key，按设置里的根地址请求。 */
internal suspend fun NetworkClient.curseForgeJson(
    url: String,
    body: JsonObject? = null,
): JsonObject = actions.curseForgeJson(url, body)

/** 上传皮肤：只允许官方端点，失败时抛 [ServiceHttpException]。 */
internal suspend fun NetworkClient.uploadMinecraftSkin(
    url: String,
    token: String,
    variant: String,
    png: ByteArray,
) = actions.uploadMinecraftSkin(url, token, variant, png)
