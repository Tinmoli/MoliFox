// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责资源包与光影更新检查中的版本读取与同名版本匹配。

package dev.molifox.core

import com.google.gson.JsonParseException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** 读取单个项目在指定内容目录下的可用版本；失败转成 [Result] 交给调用方决定提示。 */
internal suspend fun loadPackVersions(
    source: PackUpdateSource,
    project: RemoteModProject,
    folder: PackFolder,
    instance: GameInstance,
): Result<List<RemoteModVersion>> = packSourceAttempt { source.versions(project, folder, instance) }

/** 更新内容源共用相同的可恢复异常；取消与其他异常仍由调用链处理。 */
internal suspend fun <T> packSourceAttempt(block: suspend () -> T): Result<T> {
    val result =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            Result.failure(error)
        } catch (error: IllegalArgumentException) {
            Result.failure(error)
        } catch (error: IllegalStateException) {
            Result.failure(error)
        } catch (error: JsonParseException) {
            Result.failure(error)
        }
    return result
}

/** 名称匹配时，项目版本里与本地文件同名的那个版本号；找不到时为空串。 */
internal fun sameNamePackVersion(
    versions: List<RemoteModVersion>,
    file: LocalPackFile,
): String =
    versions
        .firstOrNull { it.filename.equals(file.filename, true) }
        ?.number
        .orEmpty()
