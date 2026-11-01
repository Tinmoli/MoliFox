// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示实例内容更新入口与各类更新对话框。

package dev.molifox.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonParseException
import dev.molifox.core.LauncherController
import dev.molifox.core.ModUpdateReport
import dev.molifox.core.PackUpdateReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 本次进入实例详情页时的更新检查结果：模组一份，资源包与光影一份（同一次检查）。
 *
 * 所有页签共用同一份结果，切换页签不会重复检查；离开页面时 [cancelAll] 取消还没结束的请求。
 * 打开页面时自动检查一次；左栏「检查更新」[checkAll] 会同时重查模组、资源包与光影三部分。
 */
internal class InstanceUpdateCenter(
    private val controller: LauncherController,
    private val instanceId: String,
) {
    var mods by mutableStateOf<ModUpdateReport?>(null)
    var modsChecking by mutableStateOf(false)
    var modsError by mutableStateOf<String?>(null)
    var packs by mutableStateOf<PackUpdateReport?>(null)
    var packsChecking by mutableStateOf(false)
    var packsError by mutableStateOf<String?>(null)
    private var modsJob: Job? = null
    private var packsJob: Job? = null

    /** 是否有任一检查在进行中：左栏进度与按钮状态都看这个值。 */
    val checking: Boolean
        get() = modsChecking || packsChecking

    /** 打开页面时自动检查一次：模组与资源包/光影并行，互不阻塞；[refresh] 为 true 时强制重查三部分。 */
    fun checkAll(
        scope: CoroutineScope,
        refresh: Boolean = false,
    ) {
        if (refresh || mods == null) checkMods(scope)
        if (refresh || packs == null) checkPacks(scope)
    }

    fun checkMods(scope: CoroutineScope) {
        modsJob?.cancel()
        modsJob =
            scope.launch {
                modsChecking = true
                modsError = null
                try {
                    mods =
                        withContext(Dispatchers.IO) {
                            controller.content.modUpdates.check(instanceId)
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: IOException) {
                    modsError = failure.message ?: tr("deskF.updateCenter.modsFailed")
                } catch (failure: IllegalStateException) {
                    modsError = failure.message ?: tr("deskF.updateCenter.modsFailed")
                } catch (failure: JsonParseException) {
                    modsError = failure.message ?: tr("deskF.updateCenter.modsFailed")
                } finally {
                    if (modsJob == coroutineContext[Job]) modsChecking = false
                }
            }
    }

    fun checkPacks(scope: CoroutineScope) {
        packsJob?.cancel()
        packsJob =
            scope.launch {
                packsChecking = true
                packsError = null
                try {
                    packs =
                        withContext(Dispatchers.IO) {
                            controller.content.packUpdates.check(instanceId)
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: IOException) {
                    packsError = failure.message ?: tr("deskF.updateCenter.packsFailed")
                } catch (failure: IllegalStateException) {
                    packsError = failure.message ?: tr("deskF.updateCenter.packsFailed")
                } catch (failure: JsonParseException) {
                    packsError = failure.message ?: tr("deskF.updateCenter.packsFailed")
                } finally {
                    if (packsJob == coroutineContext[Job]) packsChecking = false
                }
            }
    }

    fun cancelMods() {
        modsJob?.cancel()
        modsJob = null
        modsChecking = false
    }

    fun cancelPacks() {
        packsJob?.cancel()
        packsJob = null
        packsChecking = false
    }

    fun cancelAll() {
        cancelMods()
        cancelPacks()
    }
}
