// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中模组更新的输入输出与来源接口，避免查询服务依赖具体后端。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Path

/** 纳秒到毫秒的换算基数。 */
private const val NANOS_PER_MILLI = 1_000_000L

internal typealias ModFileHasher = (Path, String) -> String

/** 更新检查结果：有更新、匹配到项目但已是最新、或无法确认当前版本。 */
enum class ModUpdateStatus(
    private val titleKey: String,
) {
    Updatable("core.modUpdateStatus.updatable"),
    Latest("core.modUpdateStatus.latest"),
    Unknown("core.modUpdateStatus.unknown"),
    ;

    /** 显示名在读取时翻译，切换语言后重新取值。 */
    val title: String
        get() = I18n.t(titleKey)
}

/** 识别依据：文件指纹最可靠，其次是声明里的项目标识；都不命中就标记未查询。 */
enum class ModMatchSource(
    private val titleKey: String,
) {
    Fingerprint("core.modMatchSource.fingerprint"),
    ProjectId("core.modMatchSource.projectId"),
    ;

    /** 显示名在读取时翻译，切换语言后重新取值。 */
    val title: String
        get() = I18n.t(titleKey)
}

data class ModUpdateCandidate(
    val projectId: String,
    val projectTitle: String,
    val versionId: String,
    val versionNumber: String,
    val filename: String,
    val downloadUrl: String,
    val size: Long,
    val sha512: String,
    val sha1: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val provider: String,
)

data class ModUpdateItem(
    val filename: String,
    val title: String,
    val currentVersion: String,
    val enabled: Boolean,
    val size: Long,
    val modified: Long,
    val status: ModUpdateStatus = ModUpdateStatus.Unknown,
    val latestVersion: String = "",
    val candidate: ModUpdateCandidate? = null,
    val matchSource: ModMatchSource? = null,
    /** 是否可以由用户显式应用；无法确认或缺少下载地址时为 false。 */
    val applicable: Boolean = false,
    val warning: String = "",
    /** 非空表示本次判定是「查询失败」而不是确定性结论；跨语言区分状态时用它，不比对提示文案。 */
    val failure: String = "",
) {
    /** 批量更新只能应用已确认且可下载的项目。 */
    val batchApplicable: Boolean
        get() = status == ModUpdateStatus.Updatable && applicable
}

data class ModUpdateReport(
    val instanceId: String,
    val gameVersion: String,
    val loader: LoaderType,
    val items: List<ModUpdateItem>,
) {
    val updates
        get() = items.count { it.status == ModUpdateStatus.Updatable }

    val latest
        get() = items.count { it.status == ModUpdateStatus.Latest }

    val unknown
        get() = items.count { it.status == ModUpdateStatus.Unknown }

    fun batchItems(filenames: Set<String>): List<ModUpdateItem> =
        items.filter { it.filename in filenames && it.batchApplicable }
}

/** 一个已安装 JAR 的本地信息；SHA-512 与声明一起按路径 + 大小 + 修改时间缓存。 */
internal data class LocalModFile(
    val path: Path,
    val filename: String,
    val size: Long,
    val modified: Long,
    val enabled: Boolean,
    val sha512: String,
    val metadata: ModMetadata,
)

internal data class RemoteModProject(
    val id: String,
    val slug: String,
    val title: String,
    val provider: String,
)

internal data class RemoteModVersion(
    val project: RemoteModProject,
    val id: String,
    val number: String,
    val name: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val versionType: String,
    val downloadUrl: String,
    val filename: String,
    val size: Long,
    val sha512: String,
    val sha1: String,
)

/** 每个内容源的更新查询；真实实现全部走 NetworkClient / CurseForgeBackend，测试可注入替身。 */
internal interface ModUpdateSource {
    val provider: String

    suspend fun fingerprint(files: List<LocalModFile>): Map<String, RemoteModVersion>

    /**
     * 指纹命中后的批量「最新可用版本」查询：一次请求结算多个文件。
     *
     * 返回 null 表示该源没有批量接口，调用方回退到逐项目 [versions]。
     * 返回的映射里缺少某个文件表示「没有适用的更新」，与「没查」区分开。
     */
    suspend fun latestVersions(
        files: List<LocalModFile>,
        instance: GameInstance,
        allowPrerelease: Boolean,
    ): Map<String, RemoteModVersion>? = null

    suspend fun projectBySlug(
        slug: String,
        instance: GameInstance,
    ): RemoteModProject?

    suspend fun versions(
        project: RemoteModProject,
        instance: GameInstance,
    ): List<RemoteModVersion>
}

/** 批量版本查询的结果：是否已批量结算、以及结算出的最新版本（null 表示没有可用更新）。 */
internal data class BatchLatest(
    val checked: Boolean,
    val version: RemoteModVersion?,
)

/** 逐项目版本查询的并发上限：服务端能承受，又不至于把一条慢连接排成一列。 */
internal const val UPDATE_LOOKUP_CONCURRENCY = 6

internal fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / NANOS_PER_MILLI

internal typealias ModUpdateDownloader =
    suspend (version: RemoteModVersion, target: Path, context: JobContext?) -> Unit
