// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责启动前后命令的执行与环境准备。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

private const val COMMAND_POLL_INTERVAL_MS = 100L
private const val MAX_COMMAND_OUTPUT_LINES = 500

internal enum class LaunchCommandPhase(
    private val titleKey: String,
    val filename: String,
) {
    Before("core.launchCommandPhase.before", "before-command.log"),
    After("core.launchCommandPhase.after", "after-command.log"),
    ;

    /** 显示名在读取时翻译，切换语言后重新取值。 */
    val title: String
        get() = I18n.t(titleKey)
}

/** User-entered argv only. No shell or credential substitution is inserted by the launcher. */
internal class LaunchCommandRunner(
    private val store: LauncherStore,
) {
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
    ) = withContext(Dispatchers.IO) {
        if (command.isBlank()) return@withContext
        val arguments = splitArguments(command)
        require(arguments.isNotEmpty()) { I18n.t("diagA2.launchCommandRunner.emptyCommand", phase.title) }
        val output = session.directory.resolve(phase.filename)
        store.log(I18n.t("diagA2.launchCommandRunner.startLog", phase.title))
        val process =
            try {
                startProcess(arguments, output, plan, instance, exitCode)
            } catch (error: IOException) {
                processStartFailure(session, phase, error)
            } catch (error: SecurityException) {
                processStartFailure(session, phase, error)
            } catch (error: IllegalArgumentException) {
                processStartFailure(session, phase, error)
            }
        active = process
        var result = "failed"
        try {
            waitForCommand(process, timeoutSeconds, phase, output)
            result = "completed"
        } catch (cancelled: CancellationException) {
            result = "cancelled"
            throw cancelled
        } finally {
            if (process.isAlive) terminate(process)
            active = null
            cleanCommandLog(output, phase, session)
            session.commandResult(phase.name, result)
        }
    }

    private suspend fun waitForCommand(
        process: Process,
        timeoutSeconds: Int,
        phase: LaunchCommandPhase,
        output: Path,
    ) {
        val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
        while (!process.waitFor(COMMAND_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
            currentCoroutineContext().ensureActive()
            if (System.nanoTime() > deadline) {
                throw IOException(
                    I18n.t("diagA2.launchCommandRunner.timeout", phase.title, timeoutSeconds),
                )
            }
            if (output.exists() && output.fileSize() > logLimit) {
                throw IOException(I18n.t("diagA2.launchCommandRunner.logTooLarge", phase.title))
            }
        }
        currentCoroutineContext().ensureActive()
        val code = process.exitValue()
        check(code == 0) {
            I18n.t("diagA2.launchCommandRunner.nonZeroExit", phase.title, code)
        }
    }

    private fun startProcess(
        arguments: List<String>,
        output: Path,
        plan: LaunchPlan,
        instance: GameInstance,
        exitCode: Int?,
    ): Process =
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
            }.start()

    private fun processStartFailure(
        session: LaunchSession,
        phase: LaunchCommandPhase,
        error: Exception,
    ): Nothing {
        session.commandResult(phase.name, "failed to start")
        throw IOException(I18n.t("diagA2.launchCommandRunner.startFailed", phase.title), error)
    }

    private fun cleanCommandLog(
        output: Path,
        phase: LaunchCommandPhase,
        session: LaunchSession,
    ) {
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
                    .take(MAX_COMMAND_OUTPUT_LINES)
                    .forEach { store.log("[${phase.title}] $it", level = LogLevel.Trace) }
            }
        }.onFailure {
            store.log(
                I18n.t("diagA2.launchCommandRunner.logCleanFailed", phase.title, it.message),
                true,
            )
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
