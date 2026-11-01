// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop.instance

import dev.molifox.core.GameInstance
import dev.molifox.core.LauncherController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.io.IOException

/** false 表示草稿的目标已失效或暂不可写；真正的保存仍由核心严格校验。 */
internal suspend fun savePendingInstanceSettings(
    controller: LauncherController,
    draft: GameInstance,
): Result<Boolean> =
    try {
        withContext(Dispatchers.IO) {
            val current = controller.state.value.data.instances.find { it.id == draft.id }
            if (current == null || controller.isInstanceBusy(current.id)) {
                return@withContext Result.success(false)
            }
            controller.saveInstanceConfiguration(draft.copy(name = current.name), notify = false)
            Result.success(true)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        Result.failure(failure)
    } catch (failure: IllegalArgumentException) {
        Result.failure(failure)
    } catch (failure: IllegalStateException) {
        // 删除可能发生在上述检查与核心写入之间；只忽略已经确认消失的目标。
        if (controller.state.value.data.instances.none { it.id == draft.id }) {
            Result.success(false)
        } else {
            Result.failure(failure)
        }
    }
