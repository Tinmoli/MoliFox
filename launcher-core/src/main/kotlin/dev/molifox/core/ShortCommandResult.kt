// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import java.io.OutputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val SHORT_COMMAND_STOP_TIMEOUT_SECONDS = 2L

internal data class ShortCommandResult(
    val exitCode: Int,
    val output: ByteArray,
    val truncated: Boolean,
)

/** 限时收集短命令输出。达到保留上限后仍排空管道，防止子进程写满后无法退出。 */
internal fun runShortCommand(
    builder: ProcessBuilder,
    timeoutSeconds: Long,
    outputLimit: Int,
): ShortCommandResult? {
    val process = builder.start()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
    val capture =
        FutureTask {
            process.inputStream.use { input ->
                val prefix = input.readNBytes(outputLimit + 1)
                input.transferTo(OutputStream.nullOutputStream())
                prefix
            }
        }
    return try {
        process.outputStream.close()
        Thread(capture, "short-command-output").apply { isDaemon = true }.start()
        if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            val output = capture.get(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
            val truncated = output.size > outputLimit
            ShortCommandResult(process.exitValue(), if (truncated) output.copyOf(outputLimit) else output, truncated)
        } else {
            null
        }
    } catch (_: TimeoutException) {
        null
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    } finally {
        try {
            stopShortCommand(process)
        } finally {
            capture.cancel(true)
        }
    }
}

private fun stopShortCommand(process: Process) {
    if (!process.isAlive) return
    val children = process.descendants().use { it.toList() }
    children.asReversed().forEach { if (it.isAlive) it.destroyForcibly() }
    process.destroyForcibly()
    process.waitFor(SHORT_COMMAND_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
}
