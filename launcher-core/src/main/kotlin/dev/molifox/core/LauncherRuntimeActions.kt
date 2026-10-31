// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责更新、通用操作与日志导出。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.io.path.writeText

/** 更新检查、通用后台操作与日志导出。 */
internal interface LauncherRuntimeApi {
    /** 界面在窗口尺寸变化时上报当前窗口大小，供「与启动器一样大」的游戏窗口模式使用。 */
    fun reportLauncherWindowSize(
        widthPx: Int,
        heightPx: Int,
    )

    fun isInstanceBusy(id: String): Boolean

    fun checkForUpdates(manual: Boolean)

    fun downloadUpdate(): String

    /** 用已下载的更新包自动替换当前启动器并重启；成功后本进程退出。 */
    fun applyDownloadedUpdate()

    fun perform(action: suspend () -> Unit): Job

    fun requireIdle(vararg instanceIds: String)

    fun exportLogs(destination: Path)

    /** 打开 `logs/` 目录，便于直接查看 launcher.log 与每次启动的游戏输出。 */
    fun openLogsFolder()

    /** 清空日志页列表；磁盘日志保留，导出与诊断不受影响。 */
    fun clearLogs()

    fun exportDiagnostics(destination: Path)
}

/** 实现由 [LauncherController] 委托调用；方法体与原实现一致。 */
internal abstract class LauncherRuntimeActions(
    core: LauncherControllerCore,
) : LauncherActionsBase(core),
    LauncherRuntimeApi {
    /** 界面在窗口尺寸变化时上报当前窗口大小，供「与启动器一样大」的游戏窗口模式使用。 */
    override fun reportLauncherWindowSize(
        widthPx: Int,
        heightPx: Int,
    ) {
        if (widthPx > 0 && heightPx > 0) store.launcherWindowSize = widthPx to heightPx
    }

    override fun isInstanceBusy(id: String): Boolean =
        jobs.conflicts(taskResourcesForInstances(store, id)) ||
            (
                !store.instance(id).installed &&
                    state.value.jobs.any {
                        it.status in setOf(JobStatus.Queued, JobStatus.Running) &&
                            it.resources.any { resource -> resource.startsWith("new-pack:") }
                    }
            )

    override fun checkForUpdates(manual: Boolean) {
        scope.launch { updater.check(manual) }
    }

    override fun downloadUpdate(): String {
        val release = requireNotNull(state.value.update.release) { "请先检查更新" }
        return jobs.enqueue("更新 MoliFox ${release.version}", setOf("launcher-update")) {
            updater.download(release, this)
        }
    }

    override fun applyDownloadedUpdate() {
        val downloaded =
            state.value.update.downloadedFile
                ?.let(Path::of)
        if (downloaded == null) {
            store.notify("请先下载更新包")
            return
        }
        UpdateApplier(store)
            .apply(downloaded)
            .onSuccess {
                store.log("已创建更新替换脚本，启动器即将退出并重启")
                platform.exitLauncher()
            }.onFailure { failure ->
                val message = failure.message ?: "自动更新失败，请手动替换启动器文件"
                store.log(message, true)
                store.notify(message)
            }
    }

    override fun perform(action: suspend () -> Unit): Job =
        scope.launch {
            runCatching { action() }
                .onFailure { error ->
                    // 界面的最后一道错误边界：action 由各页面传入，失败类型不可穷举，
                    // 必须一律转成日志与提示，不能让异常冒到 Compose 事件线程；取消沿用原样静默。
                    if (error is CancellationException) return@onFailure
                    store.log(error.message ?: "操作失败", true)
                    store.notify(error.message ?: "操作失败")
                }
        }

    override fun requireIdle(vararg instanceIds: String) {
        if (instanceIds.isEmpty()) {
            check(!jobs.busy && !game.isRunning) { "此操作会修改游戏目录，请等待游戏或后台任务结束后再操作" }
            return
        }
        val resources = taskResourcesForInstances(store, *instanceIds)
        check(
            instanceIds.none(::isInstanceBusy) &&
                state.value.runningGames.none {
                    taskResourcesConflict(
                        resources,
                        taskResourcesForInstances(store, it.instanceId),
                    )
                },
        ) {
            "此实例或其共用文件正在被使用，请等待相关游戏或任务结束"
        }
    }

    override fun exportLogs(destination: Path) {
        destination.writeText(
            store.state.value.logs.joinToString("\n") {
                "${it.time} [${it.level.label}] ${it.message}"
            },
        )
    }

    /** 打开 `logs/` 目录，便于直接查看 launcher.log 与每次启动的游戏输出。 */
    override fun openLogsFolder() {
        platform.openFolder(store.directories.logs)
    }

    /** 清空日志页列表；磁盘日志保留，导出与诊断不受影响。 */
    override fun clearLogs() = store.clearLogs()

    override fun exportDiagnostics(destination: Path) {
        exportDiagnosticBundle(store, destination)
        store.notify("诊断包已导出")
    }
}
