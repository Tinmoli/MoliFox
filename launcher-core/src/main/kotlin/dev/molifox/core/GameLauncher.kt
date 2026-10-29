// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏启动计划、参数组装和 Java 子进程管理。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlinx.coroutines.*

data class LaunchPlan(
    val executable: String,
    val arguments: List<String>,
    val gameDirectory: Path,
    val environment: Map<String, String> = emptyMap(),
    /** 启动日志里的一行有效值摘要，例如「启动参数：内存 4096 MB，窗口 1920×1080，额外 JVM -XX…」。 */
    val summary: String = "",
    /** 启动时被回退的实例设置；只记警告，不阻止启动。 */
    val warnings: List<String> = emptyList(),
)

class LaunchPlanner(
    private val store: LauncherStore,
    /** 外置认证账户需要的 authlib-injector；离线与微软账户不会使用。 */
    private val authlibInjector: AuthlibInjector? = null,
) {
    suspend fun plan(
        instance: GameInstance,
        profile: ResolvedProfile,
        runtime: JavaRuntime,
        credentials: GameCredentials,
        prepareFiles: Boolean = true,
        runtimeDirectory: Path? = null,
        target: GameLaunchTarget? = null,
    ): LaunchPlan =
        withContext(Dispatchers.IO) {
            val platform = store.platform
            val root = store.minecraftRoot(instance)
            val game =
                store.instancePath(instance.id).apply { if (prepareFiles) createDirectories() }
            val resolver = VersionResolver(root, platform)
            val libraries = resolver.libraries(profile)
            val natives =
                (runtimeDirectory ?: game).resolve("natives").apply {
                    if (prepareFiles) createDirectories()
                }
            for (library in if (prepareFiles) libraries else emptyList()) {
                val excluded =
                    library.obj("extract").array("exclude").map { it.asString } + "META-INF/"
                for ((artifact, native) in resolver.libraryArtifacts(library)) {
                    if (!native) continue
                    require(artifact.target.isRegularFile()) { "缺少本地依赖，请先修复游戏文件" }
                    ZipFile(artifact.target.toFile()).use { zip ->
                        for ((entry, target) in
                            validateArchive(zip, "", natives, 512L * 1024 * 1024)) {
                            ensureActive()
                            if (excluded.any { entry.name.startsWith(it) }) continue
                            target.parent.createDirectories()
                            zip.getInputStream(entry).use {
                                Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    }
                }
            }
            val jar = root.resolve("versions/${profile.jarId}/${profile.jarId}.jar")
            if (prepareFiles) require(jar.isRegularFile()) { "缺少游戏主文件，请先修复游戏" }
            val libraryPaths =
                libraries
                    .filter { resolver.hasClasspathArtifact(it) }
                    .map { resolver.libraryPath(it) }
            if (prepareFiles)
                require(libraryPaths.all { it.isRegularFile() }) { "游戏依赖不完整，请先修复游戏文件" }
            val classpath = libraryPaths.distinct() + listOf(jar)
            // Loaders inspect java.class.path themselves. A manifest-only wrapper
            // lets Java load Knot but hides Minecraft from Fabric's game provider.
            val classpathValue =
                classpath.joinToString(platform.classpathSeparator) {
                    it.toAbsolutePath().toString()
                }
            val assets = root.resolve("assets")
            val indexId =
                profile.json
                    .obj("assetIndex")
                    .string("id", profile.json.string("assets", instance.gameVersion))
            var gameAssets = assets
            val indexPath = safePath(assets.resolve("indexes"), "$indexId.json")
            if (indexPath.exists()) {
                val index = readObject(indexPath)
                val virtual = index.boolean("virtual")
                val resources = index.boolean("map_to_resources")
                if (virtual || resources) {
                    gameAssets =
                        if (virtual) safePath(assets.resolve("virtual"), indexId)
                        else game.resolve("resources")
                    for ((name, value) in
                        if (prepareFiles) index.obj("objects").entrySet() else emptySet()) {
                        ensureActive()
                        val hash = value.asJsonObject.string("hash")
                        val from = assets.resolve("objects/${hash.take(2)}/$hash")
                        val to = safePath(gameAssets, name)
                        if (!to.exists()) {
                            to.parent.createDirectories()
                            runCatching { Files.createLink(to, from) }
                                .getOrElse {
                                    Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
                                }
                        }
                    }
                }
            }
            val settings = store.data.settings
            val advanced = effectiveLaunchSettings(settings, instance)
            validateAdvancedLaunchSettings(advanced, platform.osName)
            // 实例自己的启动设置优先；0 / 空值跟随全局，无效值回退并记警告，不阻止启动。
            val launchOptions =
                resolveInstanceLaunchOptions(
                    settings,
                    instance,
                    store.launcherWindowSize,
                    platform.totalMemoryMb,
                    platform.availableMemoryMb,
                    runtime.architecture,
                    advanced,
                )
            val width = launchOptions.width
            val height = launchOptions.height
            val memory = launchOptions.memory
            val maxMemory = memory.maximumMb
            val initialMemory = memory.initialMb
            val fullscreen = launchOptions.fullscreen
            val quickPlay = supportsQuickPlay(profile, instance.gameVersion)
            val recording = runtimeDirectory?.resolve("quick-play.json")
            val targetArguments =
                quickPlayArguments(target, quickPlay, recording, instance.gameVersion)
            if (target?.kind == EntryKind.World) {
                val world = safePath(game.resolve("saves"), target.key)
                require(world.resolve("level.dat").isRegularFile()) { "此存档不存在或仍为压缩包，请先解压到 saves" }
            }
            val values =
                mapOf(
                    "auth_player_name" to credentials.name,
                    "auth_uuid" to credentials.uuid,
                    "auth_access_token" to credentials.accessToken,
                    "auth_session" to "token:${credentials.accessToken}:${credentials.uuid}",
                    "user_type" to credentials.userType,
                    "user_properties" to "{}",
                    "auth_xuid" to "",
                    "clientid" to settings.microsoftClientId,
                    "version_name" to profile.id,
                    "version_type" to profile.json.string("type", "release"),
                    "game_directory" to game.toString(),
                    "assets_root" to assets.toString(),
                    "assets_index_name" to indexId,
                    "game_assets" to gameAssets.toString(),
                    "natives_directory" to natives.toString(),
                    "library_directory" to root.resolve("libraries").toString(),
                    "classpath" to classpathValue,
                    "classpath_separator" to platform.classpathSeparator,
                    "launcher_name" to "MoliFox",
                    "launcher_version" to LAUNCHER_VERSION,
                    "resolution_width" to width.toString(),
                    "resolution_height" to height.toString(),
                    "quickPlayPath" to recording?.toAbsolutePath()?.toString().orEmpty(),
                    "quickPlaySingleplayer" to
                        target?.takeIf { it.kind == EntryKind.World }?.key.orEmpty(),
                    "quickPlayMultiplayer" to
                        target?.takeIf { it.kind == EntryKind.Server }?.key.orEmpty(),
                    "quickPlayRealms" to "",
                )
            val features =
                mapOf(
                    "has_custom_resolution" to true,
                    "is_demo_user" to false,
                    "has_quick_plays_support" to (quickPlay && recording != null),
                    "is_quick_play_singleplayer" to (target?.kind == EntryKind.World),
                    "is_quick_play_multiplayer" to (target?.kind == EntryKind.Server),
                    "is_quick_play_realms" to false,
                )
            val modern = profile.json.obj("arguments")
            // 实例/全局解析出的内存始终是唯一的 -Xmx/-Xms 来源；模板里的同名参数会被去掉，避免重复。
            val jvm =
                (if (modern.array("jvm").size() > 0)
                        expandArguments(modern.array("jvm"), values, platform, features).map {
                            argument ->
                            // Modular loaders must ignore the actual inherited JAR, even for
                            // renamed profiles.
                            if (argument.startsWith("-DignoreList=")) {
                                "-DignoreList=" +
                                    argument.removePrefix("-DignoreList=").split(',').joinToString(
                                        ","
                                    ) { entry ->
                                        if (entry == "${profile.id}.jar") jar.fileName.toString()
                                        else entry
                                    }
                            } else argument
                        }
                    else listOf("-Djava.library.path=$natives", "-cp", classpathValue))
                    .filterNot { it.startsWith("-Xmx") || it.startsWith("-Xms") }
            val gameArguments =
                if (profile.json.string("minecraftArguments").isNotBlank())
                    splitArguments(profile.json.string("minecraftArguments")).map {
                        substitute(it, values)
                    }
                else expandArguments(modern.array("game"), values, platform, features)
            val extras = splitArguments(launchOptions.extraJvmArgs)
            val command =
                mutableListOf(
                    "-Xms${initialMemory}M",
                    "-Xmx${maxMemory}M",
                    "-Dfile.encoding=UTF-8",
                    "-Dstdout.encoding=UTF-8",
                    "-Dstderr.encoding=UTF-8",
                    "-Dlog4j2.formatMsgNoLookups=true",
                    "-Djava.rmi.server.useCodebaseOnly=true",
                    "-Dcom.sun.jndi.rmi.object.trustURLCodebase=false",
                )
            val logging = profile.json.obj("logging").obj("client")
            if (
                logging.string("argument").isNotBlank() &&
                    logging.obj("file").string("id").isNotBlank()
            ) {
                val config =
                    safePath(root.resolve("assets/log_configs"), logging.obj("file").string("id"))
                command += logging.string("argument").replace("\${path}", config.toString())
            }
            command += jvm
            command += extras
            // 外置认证：在生成 @argfile 与长度校验之前加入，确保长参数也被正确转义。
            if (credentials.authlibServer.isNotBlank()) {
                val injector =
                    authlibInjector
                        ?: throw AuthlibException(
                            AuthlibError.DownloadFailed,
                            "外置认证组件不可用，请重启启动器后重试",
                        )
                command +=
                    injector.jvmArguments(
                        store.dataDirectory.resolve("cache"),
                        credentials.authlibServer,
                    )
            }
            val invocation =
                if (
                    prepareFiles &&
                        platform.osName == "windows" &&
                        command.sumOf { it.length + 3 } > 24_000
                ) {
                    require(runtime.majorVersion >= 9) {
                        "启动参数超过 Java 8 的 Windows 长度限制，请将游戏目录改为更短的路径"
                    }
                    val argumentFile = (runtimeDirectory ?: game).resolve(".molifox-jvm.args")
                    // The native Java launcher parses @files before file.encoding takes effect.
                    val charset =
                        java.nio.charset.Charset.forName(
                            System.getProperty("native.encoding", "UTF-8")
                        )
                    val text = command.joinToString("\n") { quoteJavaArgument(it) }
                    require(charset.newEncoder().canEncode(text)) {
                        "当前 Java 无法编码此游戏目录，请缩短目录或使用对应版本的 UTF-8 Java 环境"
                    }
                    Files.writeString(argumentFile, text, charset)
                    mutableListOf("@${argumentFile.toAbsolutePath()}")
                } else command
            invocation += profile.mainClass
            invocation += appendMissingQuickPlay(gameArguments, targetArguments)
            if (!gameArguments.contains("--width"))
                invocation +=
                    listOf(
                        "--width",
                        width.toString(),
                        "--height",
                        height.toString(),
                    )
            if (fullscreen && "--fullscreen" !in gameArguments) invocation += "--fullscreen"
            // 额外游戏参数最后追加，位于标准参数（宽度/高度/全屏）之后。
            invocation += launchOptions.extraGameArgs
            if (prepareFiles && platform.osName == "windows")
                require(invocation.sumOf { it.length + 3 } + runtime.path.length < 30_000) {
                    "启动命令过长，请缩短附加游戏参数或游戏目录"
                }
            val executable =
                if (
                    platform.osName == "windows" &&
                        advanced.useJavaConsoleExecutable &&
                        Path.of(runtime.path).fileName.toString().equals("javaw.exe", true)
                ) {
                    Path.of(runtime.path)
                        .resolveSibling("java.exe")
                        .also { require(it.isRegularFile()) { "此 Java 安装中没有 java.exe" } }
                        .toString()
                } else runtime.path
            LaunchPlan(
                executable,
                invocation,
                game,
                advanced.environmentVariables.toMap(),
                summary = launchOptions.summary,
                warnings = launchOptions.warnings,
            )
        }
}

internal fun quoteJavaArgument(value: String): String =
    "\"" +
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n") +
        "\""

/** 优先级到 Windows PriorityClass 名称的映射；最高只到 High，绝不使用 RealTime。 */
internal fun windowsProcessPriorityName(priority: ProcessPriority): String =
    when (priority) {
        ProcessPriority.Low -> "BelowNormal"
        ProcessPriority.Normal -> "Normal"
        ProcessPriority.High -> "AboveNormal"
        ProcessPriority.VeryHigh -> "High"
    }

/** [message] 是要写进日志的一行（非 Windows 为 null），[warning] 表示设置失败但启动不受影响。 */
internal data class ProcessPriorityOutcome(val message: String?, val warning: Boolean = false)

/**
 * 给已启动的游戏进程设置优先级：只处理 Windows，先试 wmic，失败后回退 PowerShell。
 * 任何失败都只变成一条警告文本，不影响已经开始的游戏进程。
 */
internal fun applyGameProcessPriority(
    priority: ProcessPriority,
    processId: Long,
    osName: String = System.getProperty("os.name").orEmpty(),
    execute: (List<String>) -> Unit = ::runPriorityCommand,
): ProcessPriorityOutcome {
    if (!osName.startsWith("Windows", ignoreCase = true)) return ProcessPriorityOutcome(null)
    if (processId <= 0) return ProcessPriorityOutcome("设置优先级失败：无效的进程号 $processId", true)
    val name = windowsProcessPriorityName(priority)
    var reason = "未知原因"
    for (command in priorityCommands(processId, name)) {
        try {
            execute(command)
            return ProcessPriorityOutcome("游戏进程优先级：${priority.title}（pid $processId）")
        } catch (failure: Exception) {
            reason = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
        }
    }
    return ProcessPriorityOutcome("设置优先级失败：$reason", true)
}

/** wmic 输出简单、进程启动快；Windows 11 已移除 wmic 时退回 PowerShell 的 PriorityClass 赋值。 */
private fun priorityCommands(processId: Long, name: String): List<List<String>> =
    listOf(
        listOf("wmic", "process", "where", "processid=$processId", "CALL", "setpriority", name),
        listOf(
            "powershell",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "(Get-Process -Id $processId).PriorityClass='$name'",
        ),
    )

/** 执行外部命令；退出码非 0 或超时都抛异常，由 [applyGameProcessPriority] 转成一条警告。 */
private fun runPriorityCommand(command: List<String>) {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    // 输出必须边跑边读，命令卡住或输出偏多时不会堵在管道上。
    val output = StringBuilder()
    val reader =
        Thread {
                runCatching {
                        process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
                    }
                    .onFailure { output.append(it.javaClass.simpleName) }
            }
            .apply {
                isDaemon = true
                start()
            }
    if (!process.waitFor(15, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        error("命令超时")
    }
    reader.join(500)
    check(process.exitValue() == 0) {
        output.lineSequence().firstOrNull { it.isNotBlank() }?.take(300) ?: "退出码 ${process.exitValue()}"
    }
}

class GameLauncher(
    private val store: LauncherStore,
    private val installer: MinecraftInstaller,
    private val java: JavaService,
    private val accounts: AccountService,
    private val scope: CoroutineScope,
) {
    private val processes = ConcurrentHashMap<String, Pair<String, Process>>()
    private val runners = ConcurrentHashMap<String, LaunchCommandRunner>()
    private val runtimes = GameRuntimeDirectories(store).also { it.prune() }
    val isRunning: Boolean
        get() = store.state.value.runningGames.isNotEmpty()

    fun isRunning(instanceId: String): Boolean =
        store.state.value.runningGames.any { it.instanceId == instanceId }

    suspend fun launch(instanceId: String, context: JobContext, target: GameLaunchTarget? = null) {
        val runId = UUID.randomUUID().toString()
        val runtimeDirectory = store.temporaryDirectory.resolve("games").resolve(runId)
        val commands = LaunchCommandRunner(store)
        val instance = store.instance(instanceId)
        val launchSettings =
            effectiveLaunchSettings(store.data.settings, instance).let {
                it.copy(environmentVariables = it.environmentVariables.toMap())
            }
        check(instance.installed) { "实例尚未安装完成，请先修复" }
        validateAdvancedLaunchSettings(
            effectiveLaunchSettings(store.data.settings, instance),
            store.platform.osName,
        )
        store.updateState { it.copy(launchIssue = null) }
        val session = LaunchSession(store, instance)
        try {
            session.stage("解析游戏版本")
            val profile = installer.resolveForLaunch(instance, context)
            session.stage("检查和补全游戏文件")
            context.progress(.01f, "检查并补全游戏文件…")
            installer.installDependencies(
                profile,
                context,
                store.minecraftRoot(instance),
                .01f,
                .35f,
                verifyHashes = launchSettings.verifyFileHashes,
            )
            context.progress(.36f, "选择 Java ${profile.requiredJava}…")
            session.stage("选择 Java")
            val runtime =
                java.resolve(
                    profile,
                    instance.javaPath.ifBlank { store.data.settings.javaPath },
                    context,
                )
            context.progress(.4f, "验证账户…")
            session.stage("验证和刷新账户")
            val credentials = accounts.credentials()
            context.checkCancelled()
            context.progress(.7f, "准备本地依赖和启动参数…")
            session.stage("组装启动参数")
            runtimes.create(runId)
            val plan =
                LaunchPlanner(store, accounts.authlibInjector)
                    .plan(
                        instance.copy(advancedLaunch = launchSettings),
                        profile,
                        runtime,
                        credentials,
                        runtimeDirectory = runtimeDirectory,
                        target = target,
                    )
            session.prepared(plan, credentials)
            // 一行有效参数摘要，便于对照日志确认实例设置真的进入了命令；坏值回退单独记警告。
            plan.warnings.forEach { store.log(it, level = LogLevel.Warn) }
            if (plan.summary.isNotBlank()) store.log(redactSecrets(plan.summary))
            // 工作目录就是解析后的有效隔离结果；来源写进日志，推断值不冒充用户选择。
            store.log(
                "工作目录：${plan.gameDirectory}（来源：${store.isolationSource(instance).logTitle}）"
            )
            context.progress(.85f, "执行启动前命令…")
            commands.run(
                launchSettings.preLaunchCommand,
                launchSettings.commandTimeoutSeconds,
                LaunchCommandPhase.Before,
                instance,
                plan,
                session,
            )
            context.checkCancelled()
            applyGameLanguage(store, instance)
            val process =
                ProcessBuilder(listOf(plan.executable) + plan.arguments)
                    .directory(plan.gameDirectory.toFile())
                    .redirectErrorStream(true)
                    .apply {
                        // The game may outlive the launcher. Never inherit its per-run UI temp
                        // directory, which the native launcher cleans after the UI process exits.
                        val temporary = store.temporaryDirectory.toString()
                        environment()
                            .putAll(
                                mapOf(
                                    "TEMP" to temporary,
                                    "TMP" to temporary,
                                    "TMPDIR" to temporary,
                                )
                            )
                        environment().putAll(plan.environment)
                    }
                    .start()
            processes[runId] = instanceId to process
            runners[runId] = commands
            // 进程已经跑起来，优先级只是尽力而为：失败也只记一条警告。
            val priority = applyGameProcessPriority(store.data.settings.processPriority, process.pid())
            priority.message?.let { message ->
                store.log(
                    message,
                    level = if (priority.warning) LogLevel.Warn else LogLevel.Info,
                )
            }
            runCatching { runtimes.attach(runtimeDirectory, process) }
                .onFailure {
                    store.log(
                        "游戏已启动，但运行缓存记录未写入：${it.javaClass.simpleName}",
                        level = LogLevel.Warn,
                    )
                }
            val started = System.currentTimeMillis()
            val visibility = launchSettings.launcherVisibility
            val runEvent =
                GameRunEvent(
                    runId,
                    instanceId,
                    visibility,
                    launchSettings.closeLauncherAfterGameExit,
                    processId = process.pid(),
                )
            store.updateState {
                it.copy(
                    runningInstanceId = instanceId,
                    gameRun = runEvent,
                    runningGames = it.runningGames + runEvent,
                )
            }
            store.updateInstance(instanceId) { it.copy(lastPlayed = Instant.now().toString()) }
            store.log("游戏已启动：${instance.name} · Java ${runtime.majorVersion}")
            scope.launch(Dispatchers.IO) {
                val activity =
                    GameActivityTracker(
                        store,
                        instance,
                        runtimeDirectory.resolve("quick-play.json"),
                    )
                val activityPoller = launch {
                    while (isActive && process.isAlive) {
                        activity.pollQuickPlay()
                        delay(750)
                    }
                }
                val decoder = GameLogDecoder { entry ->
                    store.log(entry.message, error = entry.error, game = true)
                }
                var exitCode: Int? = null
                var postFailed = false
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val cleaned = session.clean(line)
                            session.append(cleaned)
                            activity.accept(cleaned)
                            decoder.accept(cleaned)
                        }
                    }
                    decoder.flush()
                    val exit = process.waitFor()
                    exitCode = exit
                    store.log("游戏已退出，退出码 $exit", exit != 0)
                    try {
                        commands.run(
                            launchSettings.postExitCommand,
                            launchSettings.commandTimeoutSeconds,
                            LaunchCommandPhase.After,
                            instance,
                            plan,
                            session,
                            exit,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        postFailed = true
                        val message = "游戏已退出，但${error.message ?: "退出后命令执行失败"}"
                        store.log(message, true)
                        store.notify(message)
                    }
                    session.finish(exit)
                } catch (error: Exception) {
                    session.finish(failure = error)
                } finally {
                    withContext(NonCancellable) {
                        activityPoller.cancelAndJoin()
                        activity.pollQuickPlay()
                        activity.finish()
                    }
                    decoder.flush()
                    processes.remove(runId)
                    runners.remove(runId)
                    if (!process.isAlive) runCatching { runtimes.release(runtimeDirectory) }
                    store.updateState {
                        val remaining = it.runningGames.filterNot { run -> run.id == runId }
                        it.copy(
                            runningInstanceId = remaining.lastOrNull()?.instanceId,
                            runningGames = remaining,
                            gameRun =
                                runEvent.copy(
                                    finished = true,
                                    exitCode = exitCode,
                                    postCommandFailed = postFailed,
                                ),
                        )
                    }
                    store.updateInstance(instanceId) {
                        it.copy(
                            playTimeSeconds =
                                it.playTimeSeconds + (System.currentTimeMillis() - started) / 1000
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            session.append("启动已取消")
            if (!processes.containsKey(runId)) runCatching { runtimes.release(runtimeDirectory) }
            throw cancelled
        } catch (error: Exception) {
            session.finish(failure = error)
            if (!processes.containsKey(runId)) runCatching { runtimes.release(runtimeDirectory) }
            // 启动中止：本次启动不会再有后续输出，档位记为 FATAL，与可继续的操作失败区分。
            store.log(
                "启动中止：${error.message ?: error.javaClass.simpleName}",
                level = LogLevel.Fatal,
            )
            throw error
        }
    }

    fun stop(instanceId: String? = null) {
        processes.forEach { (id, entry) ->
            if (instanceId == null || entry.first == instanceId) {
                entry.second.destroy()
                runners[id]?.stop()
            }
        }
    }
}
