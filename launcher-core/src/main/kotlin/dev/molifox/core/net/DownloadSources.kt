// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责登记下载源、把官方地址映射到各源，并为单个地址生成候选源列表。

package dev.molifox.core.net

import java.io.IOException

/** 源的一条地址前缀映射：官方前缀 → 该源前缀，路径原样保留。 */
data class SourceRoute(
    val from: String,
    val to: String,
)

/**
 * 一个下载源。[routes] 为空表示原样服务（官方源）；镜像与 Maven 源只服务登记过的前缀，
 * 因此同一个文件能同时拿到多个候选地址，而不是单 URL 加域名替换。
 */
class DownloadSource(
    val key: String,
    val title: String,
    /** 镜像类主机：受更低的并发上限与请求间隔限制。 */
    val mirror: Boolean,
    val routes: List<SourceRoute> = emptyList(),
) {
    /** 该源能提供的地址；服务不了这个前缀时返回 null。 */
    fun serve(url: String): String? {
        if (routes.isEmpty()) return url
        for (route in routes) {
            if (url.startsWith(route.from)) {
                return route.to + url.removePrefix(route.from)
            }
        }
        return null
    }
}

/** 候选源：源与它提供的具体地址。 */
data class CandidateSource(
    val source: DownloadSource,
    val url: String,
)

/**
 * 源登记表。顺序即自动模式的优先顺序：官方 → BMCLAPI → 各 Maven 镜像（只做回退）。
 * 每个源自带 URL 前缀表，所以「这个源能不能服务这个地址」由登记表回答。
 */
class DownloadSourceCatalog(
    sources: List<DownloadSource>,
) {
    val all: List<DownloadSource> = sources.toList()
    private val index = all.withIndex().associate { it.value.key to it.index }

    fun source(key: String): DownloadSource? = all.find { it.key == key }

    fun plus(vararg extra: DownloadSource): DownloadSourceCatalog =
        DownloadSourceCatalog(all + extra)

    /** 某个地址的候选源；[selection] 为 auto / official / bmclapi，旧值或未知值按自动。 */
    fun candidates(
        url: String,
        selection: String = AUTO,
    ): List<CandidateSource> {
        val official = source(OFFICIAL) ?: return emptyList()
        val mode = if (selection == OFFICIAL || selection == BMCLAPI) selection else AUTO
        if (mode != AUTO) {
            val manual = source(mode)
            val served = manual?.serve(url)
            // 手动源服务不了这个地址（例如 Modrinth API）：保持原名，不做域名替换。
            return if (manual != null && served != null) {
                listOf(CandidateSource(manual, served))
            } else {
                listOf(CandidateSource(official, url))
            }
        }
        // 自动模式：官方优先，其次 BMCLAPI，其余 Maven 镜像只排在回退位。
        val served =
            all
                .mapNotNull { source -> source.serve(url)?.let { CandidateSource(source, it) } }
                .sortedBy { candidate -> rankOf(candidate.source) }
                .distinctBy { it.url }
        return if (served.isEmpty()) listOf(CandidateSource(official, url)) else served
    }

    private fun rankOf(source: DownloadSource): Int =
        when (source.key) {
            OFFICIAL -> 0
            BMCLAPI -> 1
            else -> 2 + (index[source.key] ?: Int.MAX_VALUE / 2)
        }

    companion object {
        const val AUTO = "auto"
        const val OFFICIAL = "official"
        const val BMCLAPI = "bmclapi"

        /** 游戏文件：官方与 BMCLAPI。 */
        private val GAME_SOURCES =
            listOf(
                DownloadSource(OFFICIAL, "官方", mirror = false),
                DownloadSource(
                    BMCLAPI,
                    "BMCLAPI",
                    mirror = true,
                    routes =
                        listOf(
                            SourceRoute(
                                "https://piston-meta.mojang.com/",
                                "https://bmclapi2.bangbang93.com/",
                            ),
                            SourceRoute(
                                "https://launchermeta.mojang.com/",
                                "https://bmclapi2.bangbang93.com/",
                            ),
                            SourceRoute(
                                "https://piston-data.mojang.com/",
                                "https://bmclapi2.bangbang93.com/",
                            ),
                            SourceRoute(
                                "https://launcher.mojang.com/",
                                "https://bmclapi2.bangbang93.com/",
                            ),
                            SourceRoute(
                                "https://resources.download.minecraft.net/",
                                "https://bmclapi2.bangbang93.com/assets/",
                            ),
                            SourceRoute(
                                "https://libraries.minecraft.net/",
                                "https://bmclapi2.bangbang93.com/maven/",
                            ),
                        ),
                ),
            )

        /** 依赖库的 Maven 镜像：只服务 Mojang 库仓库与 Maven Central 的前缀。 */
        private val MAVEN_SOURCES =
            listOf(
                mavenMirror(
                    "aliyun",
                    "阿里云 Maven",
                    "https://maven.aliyun.com/repository/public/",
                ),
                mavenMirror(
                    "tencent",
                    "腾讯云 Maven",
                    "https://mirrors.cloud.tencent.com/nexus/repository/maven-public/",
                ),
                mavenMirror(
                    "huawei",
                    "华为云 Maven",
                    "https://repo.huaweicloud.com/repository/maven/",
                ),
                mavenMirror("central", "Maven Central", "https://repo1.maven.org/maven2/"),
            )

        private fun mavenMirror(
            key: String,
            title: String,
            base: String,
        ) = DownloadSource(
            key,
            title,
            mirror = true,
            routes =
                listOf(
                    SourceRoute("https://libraries.minecraft.net/", base),
                    SourceRoute("https://repo1.maven.org/maven2/", base),
                ),
        )

        val DEFAULT = DownloadSourceCatalog(GAME_SOURCES + MAVEN_SOURCES)

        /** 官方 + BMCLAPI，不含 Maven 镜像；测试与只关心两个主源的调用方使用。 */
        val PRIMARY = DownloadSourceCatalog(GAME_SOURCES)
    }
}

/** 单个源的一次尝试结果：URL 与状态码（或失败原因）。 */
data class SourceAttempt(
    val sourceKey: String,
    val sourceTitle: String,
    val url: String,
    val status: Int? = null,
    val reason: String = "",
) {
    fun describe(): String =
        buildString {
            append(sourceTitle).append('：').append(url)
            if (status != null) {
                append(" → HTTP ").append(status)
            } else if (reason.isNotBlank()) {
                append(" → ").append(reason.take(160))
            }
        }
}

/** 一个文件的所有候选源都失败：消息里列出每个源尝试的 URL 与状态码。 */
class DownloadSourceFailureException(
    val target: String,
    val attempts: List<SourceAttempt>,
) : IOException(buildMessage(target, attempts)) {
    companion object {
        private fun buildMessage(
            target: String,
            attempts: List<SourceAttempt>,
        ): String =
            buildString {
                append("下载失败：").append(target)
                if (attempts.isEmpty()) {
                    append("\n没有可用的下载源（全部已禁用或无法服务该地址）")
                } else {
                    append("\n每个源尝试的 URL 与状态码：")
                    attempts.take(8).forEach { append("\n  ").append(it.describe()) }
                    if (attempts.size >
                        8
                    ) {
                        append("\n  …另有 ").append(attempts.size - 8).append(" 个源")
                    }
                }
            }
    }
}
