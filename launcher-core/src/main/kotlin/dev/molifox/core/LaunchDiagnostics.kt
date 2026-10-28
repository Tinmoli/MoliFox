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
import kotlin.io.path.*

data class LaunchIssue(
    val title: String,
    val suggestion: String,
    val evidence: String,
    val directory: String,
)

internal fun isGameError(line: String): Boolean =
    Regex(
            "(?i)(?:\\bERROR\\b|\\bFATAL\\b|Exception in thread|Caused by:|\\b[\\w.]+(?:Exception|Error)(?::|$)|^\\s+at [\\w.$]+\\()"
        )
        .containsMatchIn(line)

internal fun diagnoseGameFailure(text: String, directory: Path): LaunchIssue {
    val patterns =
        listOf(
            Triple(Regex("启动前命令"), "启动前命令执行失败", "检查命令路径、参数、退出码及命令日志；启动前命令成功后才会运行游戏。"),
            Triple(
                Regex(
                    "ReadOnlyFileSystemException|AccessDeniedException|Permission denied|the jar file .+ can't be written",
                    RegexOption.IGNORE_CASE,
                ),
                "游戏目录或缓存无法写入",
                "检查所选游戏目录的写入权限，确认 Java 能在该目录创建和修改缓存文件。",
            ),
            Triple(
                Regex(
                    "UnsupportedClassVersionError|Unsupported class file major|class file version|Unrecognized VM option",
                    RegexOption.IGNORE_CASE,
                ),
                "Java 版本或参数不兼容",
                "在版本设置中改用自动选择 Java，并检查附加 JVM 参数。",
            ),
            Triple(
                Regex(
                    "OutOfMemoryError|Could not reserve enough space|insufficient memory|Invalid maximum heap",
                    RegexOption.IGNORE_CASE,
                ),
                "游戏内存不足或分配失败",
                "检查最大内存、Java 位数和系统可用内存；降低资源占用后重试。",
            ),
            Triple(
                Regex(
                    "Incompatible mod set|Mod resolution|requires .+ version|Missing mandatory dependencies|Missing or unsupported mandatory",
                    RegexOption.IGNORE_CASE,
                ),
                "模组依赖或版本不兼容",
                "检查日志列出的模组、前置依赖和加载器版本，恢复最近改动后重试。",
            ),
            Triple(
                Regex(
                    "couldn't locate the game|Could not find or load main class|ClassNotFoundException|ZipException|游戏文件自动补全失败|缺少游戏主文件",
                    RegexOption.IGNORE_CASE,
                ),
                "游戏文件或启动依赖可能不完整",
                "使用版本维护中的修复功能；外部版本请确认原始游戏目录及加载器文件完整。",
            ),
            Triple(
                Regex(
                    "GLFW error|does not support OpenGL|Failed to create window|Pixel format not accelerated",
                    RegexOption.IGNORE_CASE,
                ),
                "图形环境可能不兼容",
                "检查显卡驱动及当前桌面的 OpenGL 支持；远程或虚拟桌面也可能限制图形能力。",
            ),
        )
    for ((pattern, title, suggestion) in patterns) {
        val line = text.lineSequence().firstOrNull { pattern.containsMatchIn(it) } ?: continue
        return LaunchIssue(title, suggestion, redactSecrets(line).take(1000), directory.toString())
    }
    return LaunchIssue(
        "游戏启动失败或异常退出",
        "尚未确定原因，可导出诊断包查看完整日志。",
        redactSecrets(text.lineSequence().filter { it.isNotBlank() }.lastOrNull().orEmpty())
            .take(1000),
        directory.toString(),
    )
}

internal class LaunchSession(private val store: LauncherStore, private val instance: GameInstance) {
    private val started = System.currentTimeMillis()
    val directory: Path =
        store.dataDirectory
            .resolve("logs/games/${started}-${UUID.randomUUID().toString().take(8)}")
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

    fun clean(text: String): String =
        redactSecrets(
            (extraSecrets + secret)
                .filter { it.length > 5 }
                .sortedByDescending { it.length }
                .fold(text) { current, value -> current.replace(value, "[已隐藏]") }
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
                }
                .sorted(Comparator.reverseOrder())
                .skip(10)
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

    fun prepared(plan: LaunchPlan, credentials: GameCredentials) {
        secret = credentials.accessToken
        extraSecrets = environmentSecrets(plan.environment)
        metadata["java"] = plan.executable
        // Do not persist arguments: @files and modded options may carry credentials.
        writeJson(directory.resolve("session.json"), metadata)
    }

    fun commandResult(phase: String, result: String) {
        metadata["command$phase"] = result
        writeJson(directory.resolve("session.json"), metadata)
    }

    @Synchronized
    fun append(line: String) {
        runCatching {
            if (output.exists() && output.fileSize() >= 16L * 1024 * 1024) {
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

    fun finish(exit: Int? = null, failure: Throwable? = null) {
        if (failure != null) append(failure.stackTraceToString())
        metadata["finishedAt"] = Instant.now().toString()
        metadata["result"] = if (failure == null && exit == 0) "completed" else "failed"
        exit?.let { metadata["exitCode"] = it }
        val game = store.instancePath(instance.id)
        val candidates = mutableListOf(game.resolve("logs/latest.log"))
        for (folder in listOf(game, game.resolve("crash-reports"))) {
            if (folder.isDirectory() && !Files.isSymbolicLink(folder))
                Files.list(folder).use { entries ->
                    entries
                        .filter {
                            it.name.startsWith("hs_err_pid") ||
                                (folder.name == "crash-reports" && it.extension == "txt")
                        }
                        .limit(20)
                        .forEach { candidates.add(it) }
                }
        }
        for (file in candidates) runCatching {
            if (
                file.isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
                    file.getLastModifiedTime().toMillis() >= started - 2000 &&
                    file.fileSize() <= 8L * 1024 * 1024
            ) {
                directory.resolve(file.name).writeText(clean(file.readText()))
            }
        }
        if (failure != null || exit != 0) {
            val text = buildString {
                for (file in
                    listOf(
                        directory.resolve("output.previous.log"),
                        output,
                        directory.resolve("latest.log"),
                    )) {
                    if (file.isRegularFile()) append(file.readText())
                }
                failure?.let { append(clean(it.stackTraceToString())) }
            }
            val issue = diagnoseGameFailure(text, directory)
            metadata["issue"] = issue
            store.updateState { it.copy(launchIssue = issue) }
            store.log("${issue.title}：${issue.suggestion}", true)
            store.notify(issue.title)
        }
        writeJson(directory.resolve("session.json"), metadata)
    }
}

internal fun exportDiagnosticBundle(store: LauncherStore, destination: Path) {
    val root = store.dataDirectory.toAbsolutePath().normalize()
    val target = destination.toAbsolutePath().normalize()
    val sessions = root.resolve("logs/games")
    require(!target.startsWith(sessions)) { "请将诊断包保存到游戏日志目录外" }
    val files = mutableListOf(store.directories.launcherLog, store.directories.previousLog)
    if (sessions.isDirectory())
        Files.list(sessions).use { entries ->
            entries
                .filter { it.isDirectory(LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                .sorted(Comparator.reverseOrder())
                .limit(3)
                .forEach { session ->
                    Files.list(session).use { children -> children.forEach { files.add(it) } }
                }
        }
    target.parent.createDirectories()
    val temporary = target.resolveSibling("${target.fileName}.${UUID.randomUUID()}.part")
    try {
        ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
            for (file in files.distinct()) {
                if (
                    !file.isRegularFile(LinkOption.NOFOLLOW_LINKS) ||
                        file.fileSize() > 16L * 1024 * 1024
                )
                    continue
                zip.putNextEntry(ZipEntry(root.relativize(file).joinToString("/")))
                zip.write(redactSecrets(file.readText()).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.deleteIfExists()
    }
}
