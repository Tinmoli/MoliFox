// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 独立 JAR 更新助手：旧 JVM 退出后替换目标 JAR，校验后重启；失败恢复旧文件。

package dev.molifox.bootstrap

import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.isRegularFile
import kotlin.system.exitProcess

private const val WAIT_SECONDS = 60L
private const val REPLACE_RETRY_COUNT = 30
private const val REPLACE_RETRY_MILLIS = 200L

/**
 * 由当前 JAR 启动，不能在旧 JVM 内直接替换自己：
 * 先写 ready 让界面退出，再等旧 PID 结束后替换，最后用同一个 Java 重启。
 */
object JarUpdateMain {
    @JvmStatic
    @Suppress("TooGenericExceptionCaught") // 更新助手必须捕获所有可恢复失败并回滚，不能留下半替换文件。
    fun main(args: Array<String>) {
        val options = parseOptions(args)
        val target = Path.of(requireOption(options, "--target")).toAbsolutePath().normalize()
        val source = Path.of(requireOption(options, "--source")).toAbsolutePath().normalize()
        val ready = Path.of(requireOption(options, "--ready")).toAbsolutePath().normalize()
        val data = Path.of(requireOption(options, "--data-dir")).toAbsolutePath().normalize()
        val log = data.resolve("logs/update.log")
        val previous = target.resolveSibling("${target.fileName}.previous")
        val expectedSha256 = sha256(source)

        Files.createDirectories(ready.parent)
        Files.createDirectories(log.parent)
        Files.writeString(ready, "ready")
        log(log, "JAR update started: $target <- $source")

        try {
            waitForOldProcesses(options["--wait-pid"].orEmpty())
            require(target.isRegularFile()) { "target JAR is missing" }
            Files.deleteIfExists(previous)
            moveWithRetry(target, previous)
            try {
                moveWithRetry(source, target)
                require(sha256(target) == expectedSha256) { "target SHA-256 mismatch" }
            } catch (failure: Exception) {
                runCatching {
                    Files.deleteIfExists(target)
                    if (Files.isRegularFile(previous)) moveWithRetry(previous, target)
                }
                throw failure
            }
            log(log, "JAR update replaced target")
            if (options.containsKey("--restart")) restart(target, data)
        } catch (failure: Exception) {
            log(log, "JAR update failed: ${failure.message}")
            runCatching {
                if (Files.isRegularFile(previous) && !Files.isRegularFile(target)) {
                    moveWithRetry(previous, target)
                }
            }
            exitProcess(1)
        }
    }
}

private fun parseOptions(args: Array<String>): Map<String, String> {
    val options = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val key = args[index]
        if (!key.startsWith("--")) {
            index++
            continue
        }
        val value = args.getOrNull(index + 1)
        if (value != null && !value.startsWith("--")) {
            options[key] = value
            index += 2
        } else {
            options[key] = ""
            index++
        }
    }
    return options
}

private fun requireOption(
    options: Map<String, String>,
    name: String,
): String = options[name]?.takeIf { it.isNotBlank() } ?: error("missing $name")

private fun waitForOldProcesses(value: String) {
    val ids = value.split(',').mapNotNull { it.trim().toLongOrNull() }.distinct()
    for (id in ids) {
        val handle = ProcessHandle.of(id).orElse(null)
        if (handle != null && handle.isAlive) {
            try {
                // Future.get 正常返回时结果永不为 null，原来的 check(... != null) 永远成立；
                // 超时只会抛出 message 为 null 的 TimeoutException，直接落到统一失败分支
                // 会把日志写成 “JAR update failed: null”，所以在这一层转成带原因的失败。
                handle.onExit().get(WAIT_SECONDS, TimeUnit.SECONDS)
            } catch (timeout: TimeoutException) {
                // 旧进程超时未退出：保留原有失败路径（回滚、非零码退出），但日志可读。
                throw IllegalStateException("old process $id did not exit in time", timeout)
            }
        }
    }
}

@Suppress("TooGenericExceptionCaught") // 文件替换的失败类型随文件系统和锁状态变化，统一重试后回滚。
private fun moveWithRetry(source: Path, target: Path) {
    var last: Throwable? = null
    repeat(REPLACE_RETRY_COUNT) { attempt ->
        try {
            runCatching {
                Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse { atomicFailure ->
                if (atomicFailure !is AtomicMoveNotSupportedException) throw atomicFailure
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
            return
        } catch (failure: Exception) {
            last = failure
            Thread.sleep(REPLACE_RETRY_MILLIS)
        }
    }
    throw last ?: IllegalStateException("replace failed")
}

private fun restart(
    target: Path,
    data: Path,
) {
    val executable = Path.of(System.getProperty("java.home"), "bin", javaExecutable())
    val command =
        listOf(
            executable.toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-Dfile.encoding=UTF-8",
            "-Dmolifox.data.dir=$data",
            "-jar",
            target.toString(),
        )
    ProcessBuilder(command)
        .directory(target.parent.toFile())
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}

private fun javaExecutable(): String =
    if (System.getProperty("os.name").lowercase().contains("windows")) "java.exe" else "java"

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(Files.newInputStream(path), digest).use { input ->
        input.transferTo(OutputStream.nullOutputStream())
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun log(
    path: Path,
    message: String,
) {
    runCatching {
        Files.writeString(
            path,
            "${LocalDateTime.now()} $message\n",
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
    }
}
