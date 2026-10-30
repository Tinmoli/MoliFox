// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责候选源选择、测速与健康度排序。

package dev.molifox.core

import dev.molifox.core.net.CandidateSource
import dev.molifox.core.net.DownloadSourceCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.coroutineContext

/** 自动源只在官方与 BMCLAPI 之间挑，和设置里的两个手动源一一对应。 */
private val AUTO_PROBE_SOURCES = listOf("official", "bmclapi")

/** 探测目标：两个源都提供同一份版本清单，只读开头 256 字节确认可达。 */
private const val AUTO_PROBE_URL =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

/** 候选源与顺序：自动模式先测速，再按本轮健康度剔除禁用或后置限流的源。 */
internal abstract class NetworkSourceActions(
    settings: NetworkClientSettings,
) : NetworkActionBase(settings) {
    /** 某个地址的候选源；自动模式给多个，手动模式只给选中的源。 */
    fun candidateSources(
        url: String,
        selection: String = settings.source(),
    ): List<CandidateSource> = settings.catalog.candidates(url, selection)

    fun downloadCandidates(
        url: String,
        selection: String = settings.source(),
    ): List<String> = candidateSources(url, selection).map { it.url }.distinct()

    /**
     * 实际请求用的源与地址顺序：自动模式先按测速结果（最快优先），再按本轮源健康度重排
     * （禁用的源剔除、限流的排到末尾）。官方与镜像地址相同的主机不测速，也不会多发探测请求。
     */
    suspend fun orderedSources(
        url: String,
        selection: String = settings.source(),
    ): List<CandidateSource> {
        val base = candidateSources(url, selection)
        if (base.isEmpty()) return base
        val ordered = if (selection == DownloadSourceCatalog.AUTO) orderByProbe(base) else base
        return settings.health.arrange(ordered)
    }

    suspend fun orderedCandidates(
        url: String,
        selection: String = settings.source(),
    ): List<String> = orderedSources(url, selection).map { it.url }.distinct()

    /**
     * 给 Java 元数据源测速并排序，不可达的源排最后；只在当前任务中共用初始化顺序。
     */
    suspend fun fastestFirst(urls: List<String>): List<String> {
        val distinct = urls.distinct()
        if (distinct.size < 2) return distinct
        return taskSourceOrder(distinct) {
            coroutineScope {
                distinct
                    .map { url -> async(Dispatchers.IO) { url to measureUrl(url) } }
                    .awaitAll()
                    .sortedWith(compareBy({ it.second == null }, { it.second ?: Long.MAX_VALUE }))
                    .map { it.first }
            }
        }
    }

    /** 自动模式：只对官方/BMCLAPI 这一对做测速，Maven 镜像仍排在回退位。 */
    private suspend fun orderByProbe(candidates: List<CandidateSource>): List<CandidateSource> {
        if (candidates.none { it.source.key == DownloadSourceCatalog.BMCLAPI }) return candidates
        val rank = autoMirrorOrder()
        val primary =
            candidates.filter { it.source.key in rank }.sortedBy { rank.indexOf(it.source.key) }
        if (primary.isEmpty()) return candidates
        return primary + candidates.filter { it.source.key !in rank }
    }

    /** 官方与镜像并发测速；同一任务共用选定顺序，任务外每次查询重新测速。 */
    private suspend fun autoMirrorOrder(): List<String> =
        taskSourceOrder(AUTO_PROBE_SOURCES.map { mirrorUrl(AUTO_PROBE_URL, it) }) {
            coroutineScope {
                val measured =
                    AUTO_PROBE_SOURCES
                        .map { mirror ->
                            async(Dispatchers.IO) { mirror to measureSource(mirror) }
                        }.awaitAll()
                measured.filter { it.second != null }.sortedBy { it.second }.map { it.first } +
                    measured.filter { it.second == null }.map { it.first }
            }
        }

    private suspend fun taskSourceOrder(
        urls: List<String>,
        measure: suspend () -> List<String>,
    ): List<String> =
        coroutineContext[TaskSourceSelection]?.order(this, settings.source(), urls, measure)
            ?: measure()

    /** 单源测速：取版本清单开头的一小段确认真的能取到内容，返回毫秒；不可达返回 null。 */
    private suspend fun measureSource(source: String): Long? =
        measureUrl(mirrorUrl(AUTO_PROBE_URL, source))
}
