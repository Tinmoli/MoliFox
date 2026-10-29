// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责启动前后命令的执行与环境准备。

package dev.molifox.core

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlinx.coroutines.*

internal enum class LaunchCommandPhase(val title: String, val filename: String) {
    Before("启动前", "before-command.log"),
    After("退出后", "after-command.log"),
}

/** User-entered argv only. No shell or credential substitution is inserted by the launcher. */
internal class LaunchCommandRunner(private val store: LauncherStore) {
    @Volatile private var active: Process? = null
    private val logLimit = 8L * 1024 * 1024

    suspend fun run(
        command: String,
        timeoutSeconds: Int,
        phase: LaunchCommandPhase,
        instance: GameInstance,
        plan: LaunchPlan,
        session: LaunchSession,
        exitCode: Int? = null,
    ) =
        withContext(Dispatchers.IO) {
            if (command.isBlank()) return@withContext
            val arguments = splitArguments(command)
            require(arguments.isNotEmpty()) { "${phase.title}命令不能为空" }
            val output = session.directory.resolve(phase.filename)
            store.log("开始执行${phase.title}命令")
            val process =
                try {
                    ProcessBuilder(arguments)
                        .directory(plan.gameDirectory.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .apply {
                            environment().putAll(plan.environment)
                            environment()["MOLIFOX_GAME_DIR"] =
                                plan.gameDirectory.toAbsolutePath().toString()
                            environment()["MOLIFOX_INSTANCE_ID"] = instance.id
                            environment()["MOLIFOX_INSTANCE_NAME"] = instance.name
                            environment()["MOLIFOX_GAME_VERSION"] = instance.gameVersion
                            environment()["MOLIFOX_JAVA_PATH"] = plan.executable
                            exitCode?.let { environment()["MOLIFOX_EXIT_CODE"] = it.toString() }
                        }
                        .start()
                } catch (error: Exception) {
                    session.commandResult(phase.name, "failed to start")
                    throw IOException("${phase.title}命令无法启动，请检查可执行文件路径", error)
                }
            active = process
            var result = "failed"
            try {
                val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    currentCoroutineContext().ensureActive()
                    if (System.nanoTime() > deadline)
                        throw IOException("${phase.title}命令超过 $timeoutSeconds 秒，已终止")
                    if (output.exists() && output.fileSize() > logLimit)
                        throw IOException("${phase.title}命令日志超过 8 MB，已终止")
                }
                currentCoroutineContext().ensureActive()
                val code = process.exitValue()
                check(code == 0) { "${phase.title}命令退出码为 $code，请查看命令日志" }
                result = "completed"
            } catch (cancelled: CancellationException) {
                result = "cancelled"
                throw cancelled
            } finally {
                if (process.isAlive) terminate(process)
                active = null
                runCatching {
                    if (output.isRegularFile()) {
                        val text =
                            output
                                .inputStream()
                                .use { it.readNBytes(logLimit.toInt()) }
                                .toString(Charsets.UTF_8)
                        val cleaned = session.clean(text)
                        output.writeText(cleaned)
                        cleaned
                            .lineSequence()
                            .filter { it.isNotBlank() }
                            .take(500)
                            .forEach { store.log("[${phase.title}] $it", level = LogLevel.Trace) }
                    }
                }
                    .onFailure { store.log("${phase.title}命令日志暂时无法整理：${it.message}", true) }
                session.commandResult(phase.name, result)
            }
        }

    fun stop() {
        active?.let { terminate(it) }
    }

    private fun terminate(process: Process) {
        val children = process.descendants().use { it.toList() }
        children.asReversed().forEach { if (it.isAlive) it.destroy() }
        process.destroy()
        if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        children.asReversed().forEach { if (it.isAlive) it.destroyForcibly() }
        process.waitFor(1, TimeUnit.SECONDS)
    }
}
