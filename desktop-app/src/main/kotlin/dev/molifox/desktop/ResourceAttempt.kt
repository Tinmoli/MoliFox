// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import com.google.gson.JsonParseException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** 资源读取与皮肤操作共用的失败边界；取消和其他异常继续向上传递。 */
internal suspend fun <T> resourceAttempt(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        Result.failure(failure)
    } catch (failure: JsonParseException) {
        Result.failure(failure)
    } catch (failure: IllegalStateException) {
        Result.failure(failure)
    } catch (failure: IllegalArgumentException) {
        Result.failure(failure)
    }

internal suspend fun resourceFailureMessage(block: suspend () -> Unit): String? =
    resourceAttempt(block).exceptionOrNull()?.message
