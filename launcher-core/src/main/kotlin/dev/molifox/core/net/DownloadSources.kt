// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责登记下载源、把官方地址映射到各源，并为单个地址生成候选源列表。

package dev.molifox.core.net

import dev.molifox.core.i18n.I18n
import java.io.IOException

/** 失败原因在描述里的最大保留长度。 */
private const val MAX_REASON_LENGTH = 160

/** 失败消息里最多逐条列出的源尝试数量，其余合并为「另有 N 个源」。 */
private const val MAX_LISTED_ATTEMPTS = 8

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
    /** 镜像类主机：受更低的并发上限与请求间隔限制。 */
    val mirror: Boolean,
    val routes: List<SourceRoute> = emptyList(),
) {
    /** 展示名只用于日志与失败消息；按 key 在读取时翻译，避免类初始化时固化语言。 */
    val title: String
        get() = sourceTitleOf(key)

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

/** 源展示名：日志与失败消息使用，按当前语言解析。 */
private fun sourceTitleOf(key: String): String =
    when (key) {
        DownloadSourceCatalog.OFFICIAL -> I18n.t("settings.download.source.official")
        "bmclapi" -> "BMCLAPI"
        "aliyun" -> I18n.t("networkA.source.aliyun")
        "tencent" -> I18n.t("networkA.source.tencent")
        "huawei" -> I18n.t("networkA.source.huawei")
        "central" -> "Maven Central"
        else -> key
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

    fun plus(vararg extra: DownloadSource): DownloadSourceCatalog = DownloadSourceCatalog(all + extra)

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
        // 自动模式：中国网络优先镜像、其余地区优先官方，未登记的 Maven 镜像排在回退位。
        val mirrorFirst = MachineRegion.prefersMirror()
        val served =
            all
                .mapNotNull { source -> source.serve(url)?.let { CandidateSource(source, it) } }
                .sortedBy { candidate -> rankOf(candidate.source, mirrorFirst) }
                .distinctBy { it.url }
        return if (served.isEmpty()) listOf(CandidateSource(official, url)) else served
    }

    private fun rankOf(
        source: DownloadSource,
        mirrorFirst: Boolean,
    ): Int =
        when {
            source.key == BMCLAPI -> if (mirrorFirst) 0 else 1
            source.key == OFFICIAL -> if (mirrorFirst) 1 else 0
            else -> 2 + (index[source.key] ?: Int.MAX_VALUE / 2)
        }

    companion object {
        const val AUTO = "auto"
        const val OFFICIAL = "official"
        const val BMCLAPI = "bmclapi"

        /** 游戏文件：官方与 BMCLAPI。 */
        private val GAME_SOURCES =
            listOf(
                DownloadSource(OFFICIAL, mirror = false),
                DownloadSource(
                    BMCLAPI,
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
                            SourceRoute(
                                "https://maven.neoforged.net/releases/",
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
                    "https://maven.aliyun.com/repository/public/",
                ),
                mavenMirror(
                    "tencent",
                    "https://mirrors.cloud.tencent.com/nexus/repository/maven-public/",
                ),
                mavenMirror(
                    "huawei",
                    "https://repo.huaweicloud.com/repository/maven/",
                ),
                mavenMirror("central", "https://repo1.maven.org/maven2/"),
            )

        private fun mavenMirror(
            key: String,
            base: String,
        ) = DownloadSource(
            key,
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
                append(" → ").append(reason.take(MAX_REASON_LENGTH))
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
                append(I18n.t("networkA.downloadSources.failed", target))
                if (attempts.isEmpty()) {
                    append(I18n.t("networkA.downloadSources.noSource"))
                } else {
                    append(I18n.t("networkA.downloadSources.attemptList"))
                    attempts.take(MAX_LISTED_ATTEMPTS).forEach {
                        append("\n  ").append(it.describe())
                    }
                    if (attempts.size > MAX_LISTED_ATTEMPTS) {
                        append(
                            I18n.t(
                                "networkA.downloadSources.moreSources",
                                attempts.size - MAX_LISTED_ATTEMPTS,
                            ),
                        )
                    }
                }
            }
    }
}
