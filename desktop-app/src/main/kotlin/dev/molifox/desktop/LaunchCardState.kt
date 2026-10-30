// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责启动浮窗的状态，与进度控件渲染分离。

package dev.molifox.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.molifox.core.DownloadJob
import dev.molifox.core.GameInstance

/**
 * 开始页的启动浮窗状态。
 *
 * 这里只记录「哪个任务、哪个实例、从何时开始」，阶段与百分比一律读 [DownloadJob] 的真实进度（由 `JobContext.progress` 写入），不另造一套启动进度模型。状态挂在
 * `LaunchPage` 的 `remember` 上， 离开页面即随组合释放（启动任务本身仍在任务队列里继续）。
 */
class LaunchCardState {
    var jobId by mutableStateOf<String?>(null)
        private set

    var instanceId by mutableStateOf("")
        private set

    var instanceName by mutableStateOf("")
        private set

    var startedAtMillis by mutableLongStateOf(0L)
        private set

    /** `controller.launch()` 同步抛出的错误（尚未生成任务时也要在卡片里说明原因）。 */
    var localError by mutableStateOf<String?>(null)
        private set

    /** 任务被「清除已结束任务」等操作移除后，卡片仍保留最后一帧真实进度。 */
    var lastJob by mutableStateOf<DownloadJob?>(null)
        private set

    /** 是否真的看到过游戏进程运行：用来区分「上一次退出事件」和本次启动。 */
    var sawRunning by mutableStateOf(false)
        private set

    val visible: Boolean
        get() = jobId != null || localError != null

    fun begin(instance: GameInstance) {
        jobId = null
        localError = null
        lastJob = null
        sawRunning = false
        instanceId = instance.id
        instanceName = instance.name
        startedAtMillis = System.currentTimeMillis()
    }

    fun attach(id: String) {
        jobId = id
    }

    fun fail(message: String) {
        localError = message
    }

    fun remember(job: DownloadJob) {
        lastJob = job
    }

    fun markRunning() {
        sawRunning = true
    }

    fun clear() {
        jobId = null
        localError = null
        lastJob = null
        sawRunning = false
        instanceId = ""
        instanceName = ""
        startedAtMillis = 0L
    }
}
