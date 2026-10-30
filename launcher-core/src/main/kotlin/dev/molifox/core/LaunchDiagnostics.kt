// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责启动失败诊断和诊断包导出。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

private const val EVIDENCE_MAX_LENGTH = 1000
private const val MIN_REDACTED_SECRET_LENGTH = 5
private const val RETAINED_SESSION_COUNT = 10L
private const val OUTPUT_LOG_MAX_BYTES = 16L * 1024 * 1024
private const val RECENT_LOG_LIMIT = 20L
private const val FRESH_LOG_WINDOW_MS = 2000L
private const val GAME_LOG_MAX_BYTES = 8L * 1024 * 1024
private const val BUNDLE_SESSION_LIMIT = 3L
private const val BUNDLE_ENTRY_MAX_BYTES = 16L * 1024 * 1024

data class LaunchIssue(
    val title: String,
    val suggestion: String,
    val evidence: String,
    val directory: String,
)

internal fun isGameError(line: String): Boolean =
    Regex(
        "(?i)(?:\\bERROR\\b|\\bFATAL\\b|Exception in thread|Caused by:|" +
            "\\b[\\w.]+(?:Exception|Error)(?::|$)|^\\s+at [\\w.$]+\\()",
    ).containsMatchIn(line)

/** 启动失败的常见特征与处理建议；声明顺序即匹配优先级。 */
private val FAILURE_PATTERNS: List<Triple<Regex, String, String>> =
    listOf(
        Triple(
            Regex(
                "SSLHandshakeException|Remote host terminated the handshake|" +
                    "TLS 握手|证书校验失败|SSL peer shut down",
                RegexOption.IGNORE_CASE,
            ),
            "启动准备时安全网络连接失败",
            "检查网络、代理与系统时间后重试；游戏下载可使用自动源。此错误不表示游戏主类或模组缺失，无需先删除实例或关闭证书校验。",
        ),
        Triple(Regex("启动前命令"), "启动前命令执行失败", "检查命令路径、参数、退出码及命令日志；启动前命令成功后才会运行游戏。"),
        Triple(
            Regex(
                "ReadOnlyFileSystemException|AccessDeniedException|" +
                    "Permission denied|the jar file .+ can't be written",
                RegexOption.IGNORE_CASE,
            ),
            "游戏目录或缓存无法写入",
            "检查所选游戏目录的写入权限，确认 Java 能在该目录创建和修改缓存文件。",
        ),
        Triple(
            Regex(
                "UnsupportedClassVersionError|Unsupported class file major|" +
                    "class file version|Unrecognized VM option",
                RegexOption.IGNORE_CASE,
            ),
            "Java 版本或参数不兼容",
            "在版本设置中改用自动选择 Java，并检查附加 JVM 参数。",
        ),
        Triple(
            Regex(
                "OutOfMemoryError|Could not reserve enough space|" +
                    "insufficient memory|Invalid maximum heap",
                RegexOption.IGNORE_CASE,
            ),
            "游戏内存不足或分配失败",
            "检查最大内存、Java 位数和系统可用内存；降低资源占用后重试。",
        ),
        Triple(
            Regex(
                "Incompatible mod set|Mod resolution|requires .+ version|" +
                    "Missing mandatory dependencies|Missing or unsupported mandatory",
                RegexOption.IGNORE_CASE,
            ),
            "模组依赖或版本不兼容",
            "检查日志列出的模组、前置依赖和加载器版本，恢复最近改动后重试。",
        ),
        Triple(
            Regex(
                "couldn't locate the game|Could not find or load main class|" +
                    "ClassNotFoundException|ZipException|游戏文件自动补全失败|缺少游戏主文件",
                RegexOption.IGNORE_CASE,
            ),
            "游戏文件或启动依赖可能不完整",
            "使用版本维护中的修复功能；外部版本请确认原始游戏目录及加载器文件完整。",
        ),
        Triple(
            Regex(
                "GLFW error|does not support OpenGL|" +
                    "Failed to create window|Pixel format not accelerated",
                RegexOption.IGNORE_CASE,
            ),
            "图形环境可能不兼容",
            "检查显卡驱动及当前桌面的 OpenGL 支持；远程或虚拟桌面也可能限制图形能力。",
        ),
    )

internal fun diagnoseGameFailure(
    text: String,
    directory: Path,
): LaunchIssue {
    for ((pattern, title, suggestion) in FAILURE_PATTERNS) {
        val line = text.lineSequence().firstOrNull { pattern.containsMatchIn(it) } ?: continue
        return LaunchIssue(
            title,
            suggestion,
            redactSecrets(line).take(EVIDENCE_MAX_LENGTH),
            directory.toString(),
        )
    }
    return LaunchIssue(
        "游戏启动失败或异常退出",
        "尚未确定原因，可导出诊断包查看完整日志。",
        redactSecrets(
            text
                .lineSequence()
                .filter { it.isNotBlank() }
                .lastOrNull()
                .orEmpty(),
        ).take(EVIDENCE_MAX_LENGTH),
        directory.toString(),
    )
}

internal class LaunchSession(
    private val store: LauncherStore,
    private val instance: GameInstance,
) {
    private val started = System.currentTimeMillis()
    val directory: Path =
        store.dataDirectory
            .resolve("logs/games/$started-${UUID.randomUUID().toString().take(8)}")
            .apply { createDirectories() }
    private val output = directory.resolve("output.log")
    private val metadata =
        linkedMapOf<String, Any>(
            "startedAt" to Instant.now().toString(),
            "instance" to instance.name,
            "gameVersion" to instance.gameVersion,
            "loader" to instance.loader.name,
            "loaderVersion" to instance.loaderVersion,
            "os" to store.platform.osName,
            "architecture" to store.platform.architecture,
            "gameDirectory" to store.instancePath(instance.id).toString(),
        )
    private var secret = ""
    private var extraSecrets: List<String> = emptyList()

    fun stage(name: String) {
        metadata["stage"] = name
        writeJson(directory.resolve("session.json"), metadata)
        store.log("启动准备：$name")
    }

    fun clean(text: String): String =
        redactSecrets(
            (extraSecrets + secret)
                .filter { it.length > MIN_REDACTED_SECRET_LENGTH }
                .sortedByDescending { it.length }
                .fold(text) { current, value -> current.replace(value, "[已隐藏]") },
        )

    init {
        writeJson(directory.resolve("session.json"), metadata)
        // Retain a bounded set of recent sessions, never touch game directories.
        Files.list(directory.parent).use { entries ->
            entries
                .filter {
                    it.isDirectory(LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(it) &&
                        it.name.matches(Regex("\\d+-[a-f0-9]{8}"))
                }.sorted(Comparator.reverseOrder())
                .skip(RETAINED_SESSION_COUNT)
                .forEach { old ->
                    runCatching {
                        Files.walk(old).use { paths ->
                            paths.sorted(Comparator.reverseOrder()).forEach {
                                Files.deleteIfExists(it)
                            }
                        }
                    }
                }
        }
    }

    fun prepared(
        plan: LaunchPlan,
        credentials: GameCredentials,
    ) {
        secret = credentials.accessToken
        extraSecrets = environmentSecrets(plan.environment)
        metadata["java"] = plan.executable
        // Do not persist arguments: @files and modded options may carry credentials.
        writeJson(directory.resolve("session.json"), metadata)
    }

    fun commandResult(
        phase: String,
        result: String,
    ) {
        metadata["command$phase"] = result
        writeJson(directory.resolve("session.json"), metadata)
    }

    @Synchronized
    fun append(line: String) {
        runCatching {
            if (output.exists() && output.fileSize() >= OUTPUT_LOG_MAX_BYTES) {
                val previous = directory.resolve("output.previous.log")
                Files.move(output, previous, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            Files.writeString(
                output,
                clean(line) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    fun finish(
        exit: Int? = null,
        failure: Throwable? = null,
    ) {
        if (failure != null) append(failure.stackTraceToString())
        metadata["finishedAt"] = Instant.now().toString()
        metadata["result"] = if (failure == null && exit == 0) "completed" else "failed"
        exit?.let { metadata["exitCode"] = it }
        attachRecentLogs()
        reportLaunchFailure(exit, failure)
        writeJson(directory.resolve("session.json"), metadata)
    }

    /** 复制本次启动期间产生的游戏日志与崩溃报告，供诊断包使用。 */
    private fun attachRecentLogs() {
        val game = store.instancePath(instance.id)
        val candidates = mutableListOf(game.resolve("logs/latest.log"))
        for (folder in listOf(game, game.resolve("crash-reports"))) {
            if (!folder.isDirectory() || Files.isSymbolicLink(folder)) continue
            Files.list(folder).use { entries ->
                entries
                    .filter { isRecentLogName(folder, it) }
                    .limit(RECENT_LOG_LIMIT)
                    .forEach { candidates.add(it) }
            }
        }
        for (file in candidates) {
            runCatching {
                if (isFreshLog(file, started)) {
                    directory.resolve(file.name).writeText(clean(file.readText()))
                }
            }
        }
    }

    /** 失败退出时汇总日志、生成诊断结论并通知界面。 */
    private fun reportLaunchFailure(
        exit: Int?,
        failure: Throwable?,
    ) {
        if (failure == null && exit == 0) return
        val issue = diagnoseGameFailure(failureText(failure), directory)
        metadata["issue"] = issue
        store.updateState { it.copy(launchIssue = issue) }
        store.log("${issue.title}：${issue.suggestion}", true)
        store.notify(issue.title)
    }

    private fun failureText(failure: Throwable?): String =
        buildString {
            val logs =
                listOf(
                    directory.resolve("output.previous.log"),
                    output,
                    directory.resolve("latest.log"),
                )
            for (file in logs) {
                if (file.isRegularFile()) append(file.readText())
            }
            failure?.let { append(clean(it.stackTraceToString())) }
        }
}

internal fun exportDiagnosticBundle(
    store: LauncherStore,
    destination: Path,
) {
    val root = store.dataDirectory.toAbsolutePath().normalize()
    val target = destination.toAbsolutePath().normalize()
    val sessions = root.resolve("logs/games")
    require(!target.startsWith(sessions)) { "请将诊断包保存到游戏日志目录外" }
    val files = mutableListOf(store.directories.launcherLog, store.directories.previousLog)
    if (sessions.isDirectory()) {
        Files.list(sessions).use { entries ->
            entries
                .filter { it.isDirectory(LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                .sorted(Comparator.reverseOrder())
                .limit(BUNDLE_SESSION_LIMIT)
                .forEach { addSessionFiles(it, files) }
        }
    }
    target.parent.createDirectories()
    val temporary = target.resolveSibling("${target.fileName}.${UUID.randomUUID()}.part")
    try {
        ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
            writeBundleEntries(zip, root, files)
        }
        Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.deleteIfExists()
    }
}

private fun writeBundleEntries(
    zip: ZipOutputStream,
    root: Path,
    files: List<Path>,
) {
    for (file in files.distinct()) {
        if (
            !file.isRegularFile(LinkOption.NOFOLLOW_LINKS) ||
            file.fileSize() > BUNDLE_ENTRY_MAX_BYTES
        ) {
            continue
        }
        zip.putNextEntry(ZipEntry(root.relativize(file).joinToString("/")))
        zip.write(redactSecrets(file.readText()).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}

private fun addSessionFiles(
    session: Path,
    files: MutableList<Path>,
) {
    Files.list(session).use { children -> children.forEach { files.add(it) } }
}

private fun isRecentLogName(
    folder: Path,
    entry: Path,
): Boolean =
    entry.name.startsWith("hs_err_pid") ||
        (folder.name == "crash-reports" && entry.extension == "txt")

private fun isFreshLog(
    file: Path,
    since: Long,
): Boolean =
    file.isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
        file.getLastModifiedTime().toMillis() >= since - FRESH_LOG_WINDOW_MS &&
        file.fileSize() <= GAME_LOG_MAX_BYTES
