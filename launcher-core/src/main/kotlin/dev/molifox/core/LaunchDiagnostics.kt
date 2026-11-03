// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责启动失败诊断和诊断包导出。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Comparator
import java.util.UUID
import java.util.zip.GZIPInputStream
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

internal const val EVIDENCE_MAX_LENGTH = 1000
private const val MIN_REDACTED_SECRET_LENGTH = 5
private const val RETAINED_SESSION_COUNT = 10L
private const val OUTPUT_LOG_MAX_BYTES = 16L * 1024 * 1024
private const val RECENT_LOG_LIMIT = 20L
private const val FRESH_LOG_WINDOW_MS = 2000L
private const val GAME_LOG_MAX_BYTES = 8L * 1024 * 1024
private const val BUNDLE_SESSION_LIMIT = 3L
private const val BUNDLE_ENTRY_MAX_BYTES = 16L * 1024 * 1024
private const val CRASH_REPORT_MAX_BYTES = 4L * 1024 * 1024

private val CRASH_REPORT_REFERENCE =
    Regex("#@!@# Game crashed! Crash report saved to: #@!@# (.+)")
private val JVM_FATAL_ERROR =
    Regex("A fatal error has been detected by the Java Runtime Environment")
private val OUT_OF_MEMORY =
    Regex(
        "(?i)(java\\.lang\\.OutOfMemoryError|Java heap space|" +
            "Out of Memory Error|insufficient memory for the Java Runtime)",
    )

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

/** 启动失败的常见特征与处理建议；声明顺序即匹配优先级。标题与建议存 key，使用时按当前语言取值。 */
private val FAILURE_PATTERNS: List<Triple<Regex, String, String>> =
    listOf(
        // 账户/登录问题常常让启动在“解析凭据”阶段就中止，日志里只有这一条清晰原因，优先识别。
        Triple(
            Regex(
                "登录凭据不可用|登录已失效|请重新登录|请先添加并选择游戏账户|" +
                    "登入認證無法使用|登入已失效|請重新登入|請先新增並選擇遊戲帳戶|" +
                    "credentials for this account are unavailable|sign-in has expired|" +
                    "sign in again|Add and select a game account first",
                RegexOption.IGNORE_CASE,
            ),
            "diagA2.launchDiagnostics.accountTitle",
            "diagA2.launchDiagnostics.accountSuggestion",
        ),
        Triple(
            Regex(
                "SSLHandshakeException|Remote host terminated the handshake|" +
                    "TLS 握手|证书校验失败|SSL peer shut down|TLS handshake|certificate verification failed",
                RegexOption.IGNORE_CASE,
            ),
            "diagA2.launchDiagnostics.tlsTitle",
            "diagA2.launchDiagnostics.tlsSuggestion",
        ),
        Triple(
            Regex("启动前命令|Running pre-launch command"),
            "diagA2.launchDiagnostics.preCommandTitle",
            "diagA2.launchDiagnostics.preCommandSuggestion",
        ),
        Triple(
            Regex(
                "ReadOnlyFileSystemException|AccessDeniedException|" +
                    "Permission denied|the jar file .+ can't be written",
                RegexOption.IGNORE_CASE,
            ),
            "diagA2.launchDiagnostics.readOnlyTitle",
            "diagA2.launchDiagnostics.readOnlySuggestion",
        ),
        Triple(
            Regex(
                "UnsupportedClassVersionError|Unsupported class file major|" +
                    "class file version|Unrecognized VM option",
                RegexOption.IGNORE_CASE,
            ),
            "diagA2.launchDiagnostics.javaVersionTitle",
            "diagA2.launchDiagnostics.javaVersionSuggestion",
        ),
        Triple(
            Regex(
                "OutOfMemoryError|Could not reserve enough space|" +
                    "insufficient memory|Invalid maximum heap",
                RegexOption.IGNORE_CASE,
            ),
            "diagA2.launchDiagnostics.memoryTitle",
            "diagA2.launchDiagnostics.memorySuggestion",
        ),
        Triple(
            Regex(
                "Incompatible mod set|Mod resolution|requires .+ version|" +
                    "Missing mandatory dependencies|Missing or unsupported mandatory",
                RegexOption.IGNORE_CASE,
            ),
            "diagA2.launchDiagnostics.modDependencyTitle",
            "diagA2.launchDiagnostics.modDependencySuggestion",
        ),
        Triple(
            Regex(
                "couldn't locate the game|Could not find or load main class|" +
                    "ClassNotFoundException|ZipException|游戏文件自动补全失败|缺少游戏主文件|" +
                    "Automatic game file completion failed|The main game file is missing",
                RegexOption.IGNORE_CASE,
            ),
            "diagA2.launchDiagnostics.missingFilesTitle",
            "diagA2.launchDiagnostics.missingFilesSuggestion",
        ),
        Triple(
            Regex(
                "GLFW error|does not support OpenGL|" +
                    "Failed to create window|Pixel format not accelerated",
                RegexOption.IGNORE_CASE,
            ),
            "diagA2.launchDiagnostics.graphicsTitle",
            "diagA2.launchDiagnostics.graphicsSuggestion",
        ),
    )

/**
 * 启动器主动中止启动时写入的 FATAL 行（“启动中止：原因”）；这一行的原因就是本次失败的真实原因，
 * 直接拿它做判断和证据，避免整段日志里更早的旧错误盖住真正原因。
 */
private val LAUNCH_ABORTED = Regex("启动中止|啟動中止|Launch aborted", RegexOption.IGNORE_CASE)

internal fun diagnoseGameFailure(
    text: String,
    directory: Path,
): LaunchIssue {
    // 游戏崩溃时优先读取 crash report：启动器输出里混着大量 INFO/WARN，
    // 在其中扫关键词会把普通网络警告误判成崩溃原因（例如 TLS）。
    val crashReport = readCrashReport(text)
    val evidence = crashReport ?: selectFailureEvidence(text)
    // 启动器主动中止时，“启动中止：原因”那一行才是本次失败的原因，先按它判断。
    val aborted =
        if (crashReport == null) {
            text.lineSequence().firstOrNull { LAUNCH_ABORTED.containsMatchIn(it) }
        } else {
            null
        }
    val memoryMatch = if (aborted != null) OUT_OF_MEMORY.find(aborted) else OUT_OF_MEMORY.find(evidence)
    // 有「启动中止」行时只在该行上找匹配，避免整段历史日志里的旧错误盖住真正原因。
    val evidenceLines = if (aborted != null) sequenceOf(aborted) else evidence.lineSequence()
    val matched =
        FAILURE_PATTERNS.firstNotNullOfOrNull { (pattern, titleKey, suggestionKey) ->
            val line = evidenceLines.firstOrNull { pattern.containsMatchIn(it) }
            if (line == null) null else Triple(line, titleKey, suggestionKey)
        }
    val jvmFatal = crashReport == null && JVM_FATAL_ERROR.containsMatchIn(text)
    val fontFailure = if (aborted == null) diagnoseFontAssertion(evidence, directory) else null
    return when {
        memoryMatch != null ->
            issue(
                "diagA2.launchDiagnostics.memoryTitle",
                "diagA2.launchDiagnostics.memorySuggestion",
                memoryMatch.value,
                directory,
            )

        jvmFatal -> genericIssue(text, directory)

        fontFailure != null -> fontFailure

        matched != null -> issue(matched.second, matched.third, matched.first, directory)

        aborted != null ->
            issue(
                "diagA2.launchDiagnostics.abortedTitle",
                "diagA2.launchDiagnostics.abortedSuggestion",
                aborted,
                directory,
            )

        else -> genericIssue(evidence, directory)
    }
}

private fun issue(
    titleKey: String,
    suggestionKey: String,
    evidence: String,
    directory: Path,
): LaunchIssue =
    LaunchIssue(
        I18n.t(titleKey),
        I18n.t(suggestionKey),
        redactSecrets(evidence).take(EVIDENCE_MAX_LENGTH),
        directory.toString(),
    )

/** 从游戏输出里找到崩溃报告路径并读取；缺失、越界或不是普通文件时返回 null。 */
private fun readCrashReport(text: String): String? =
    runCatching {
        val path =
            CRASH_REPORT_REFERENCE
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
        val file = Path.of(path)
        if (
            !file.isRegularFile() ||
            Files.isSymbolicLink(file) ||
            file.fileSize() > CRASH_REPORT_MAX_BYTES
        ) {
            return null
        }
        file.readText()
    }.getOrNull()

private fun genericIssue(
    text: String,
    directory: Path,
): LaunchIssue {
    // 兜底证据：优先异常消息行；堆栈帧只说明调用链，不能当原因展示。
    val lines =
        text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
    val message =
        lines.firstOrNull { line ->
            val head = line.substringBefore(':', "")
            val detail = line.substringAfter(':', "")
            (head.endsWith("Exception") || head.endsWith("Error")) && detail.isNotBlank()
        }
    return LaunchIssue(
        I18n.t("diagA2.launchDiagnostics.genericTitle"),
        I18n.t("diagA2.launchDiagnostics.genericSuggestion"),
        redactSecrets(message ?: lines.lastOrNull().orEmpty()).take(EVIDENCE_MAX_LENGTH),
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
        store.log(I18n.t("diagA2.launchDiagnostics.stageLog", name))
    }

    fun clean(text: String): String =
        redactSecrets(
            (extraSecrets + secret)
                .filter { it.length > MIN_REDACTED_SECRET_LENGTH }
                .sortedByDescending { it.length }
                .fold(text) { current, value -> current.replace(value, I18n.t("core.advancedLaunchSettings.18")) },
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
        // 只记录直达目标，不写整条命令；完整参数可能包含账户令牌。
        for (option in listOf("--quickPlayMultiplayer", "--quickPlaySingleplayer", "--server", "--port")) {
            val index = plan.arguments.lastIndexOf(option)
            if (index >= 0) metadata[option.removePrefix("--")] = plan.arguments.getOrNull(index + 1).orEmpty()
        }
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
    require(!target.startsWith(sessions)) { I18n.t("diagA2.launchDiagnostics.bundleOutsideLogs") }
    val files = mutableListOf(store.directories.launcherLog, store.directories.previousLog)
    if (store.directories.archivedLogs.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
        Files.list(store.directories.archivedLogs).use { entries ->
            entries
                .filter { it.name.endsWith(".log.gz") }
                .sorted(Comparator.reverseOrder())
                .limit(2)
                .forEach(files::add)
        }
    }
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
        val compressed = file.name.endsWith(".gz")
        val entryName = root.relativize(file).joinToString("/").removeSuffix(".gz")
        val text =
            if (compressed) {
                GZIPInputStream(Files.newInputStream(file)).bufferedReader().use { it.readText() }
            } else {
                file.readText()
            }
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(redactSecrets(text).toByteArray(Charsets.UTF_8))
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
