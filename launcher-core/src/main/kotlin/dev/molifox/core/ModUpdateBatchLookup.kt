// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 批量版本查询：按「内容源 + 是否接受预发布」分组，一次结算指纹命中文件的版本。

package dev.molifox.core

import com.google.gson.JsonParseException
import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** 批量版本查询：接口不支持的源返回 null，对应文件回退到逐项目查询。 */
internal class ModUpdateBatchLookup(
    private val store: LauncherStore,
) {
    /**
     * 按「内容源 + 预发布策略」分组调用批量接口；不支持的源返回 null，对应文件留待逐项目查询。
     * 分组是为了保持每个文件各自的预发布策略，同时避免为每个项目单独发一次请求。
     * 当前文件是预发布而设置未放开时走「正式版优先」：先查正式版，查不到的文件再按预发布回退，
     * 避免装过 beta 就沿 beta 线一路更新。
     */
    suspend fun latestVersions(
        files: List<LocalModFile>,
        fingerprints: Map<String, Pair<ModUpdateSource, RemoteModVersion>>,
        instance: GameInstance,
        includePrerelease: Boolean,
    ): Map<String, BatchLatest> {
        val matched =
            files.mapNotNull { file ->
                val hit = fingerprints[file.filename] ?: return@mapNotNull null
                Triple(file, hit.first, hit.second)
            }
        if (matched.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, BatchLatest>()
        val groups =
            matched.groupBy { (_, source, installed) ->
                source.provider to (includePrerelease to isPrereleaseVersion(installed.number))
            }
        for ((key, group) in groups) {
            val (settingOn, currentIsPrerelease) = key.second
            val source = group.first().second
            if (settingOn || !currentIsPrerelease) {
                val latest = latestForGroup(source, group, instance, settingOn) ?: continue
                group.forEach { (file, _, _) ->
                    result[file.filename] = BatchLatest(true, latest[file.filename])
                }
            } else {
                preferReleaseGroup(source, group, instance, result)
            }
        }
        return result
    }

    /** 正式版优先分组：先按仅正式版查询；没有正式版的文件再用预发布查询回退。 */
    private suspend fun preferReleaseGroup(
        source: ModUpdateSource,
        group: List<Triple<LocalModFile, ModUpdateSource, RemoteModVersion>>,
        instance: GameInstance,
        result: MutableMap<String, BatchLatest>,
    ) {
        // 源不支持批量接口或查询失败：整组留给逐项目查询，不能标记 checked 挡住回退。
        val releases = latestForGroup(source, group, instance, false) ?: return
        group.forEach { (file, _, _) ->
            result[file.filename] = BatchLatest(true, releases[file.filename])
        }
        val missing = group.filter { releases[it.first.filename] == null }
        if (missing.isEmpty()) return
        val fallback = latestForGroup(source, missing, instance, true) ?: return
        missing.forEach { (file, _, _) ->
            result[file.filename] = BatchLatest(true, fallback[file.filename])
        }
    }

    /** 单个分组的批量查询结果；接口失败时记录原因并返回 null，让文件回退到逐项目查询。 */
    private suspend fun latestForGroup(
        source: ModUpdateSource,
        group: List<Triple<LocalModFile, ModUpdateSource, RemoteModVersion>>,
        instance: GameInstance,
        allowPrerelease: Boolean,
    ): Map<String, RemoteModVersion>? {
        val lookup =
            lookupLatestVersions(source, group.map { it.first }, instance, allowPrerelease)
        val failure = lookup.failure
        if (failure != null) {
            store.log(
                I18n.t("packsC.batchLookup.fallback", source.provider, failure),
                true,
            )
            return null
        }
        return lookup.versions
    }

    private suspend fun lookupLatestVersions(
        source: ModUpdateSource,
        files: List<LocalModFile>,
        instance: GameInstance,
        allowPrerelease: Boolean,
    ): BatchLookup =
        try {
            BatchLookup(source.latestVersions(files, instance, allowPrerelease))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            BatchLookup(null, shortErrorMessage(error).ifBlank { I18n.t("packsB2.packUpdates.requestFailed") })
        } catch (error: IllegalStateException) {
            BatchLookup(null, shortErrorMessage(error).ifBlank { I18n.t("packsB2.packUpdates.requestFailed") })
        } catch (error: JsonParseException) {
            BatchLookup(null, shortErrorMessage(error).ifBlank { I18n.t("packsB2.packUpdates.requestFailed") })
        }
}

/** 一次批量版本查询的结果：失败时带上面向日志的简短原因，没有批量接口时两者都为 null。 */
private data class BatchLookup(
    val versions: Map<String, RemoteModVersion>?,
    val failure: String? = null,
)
