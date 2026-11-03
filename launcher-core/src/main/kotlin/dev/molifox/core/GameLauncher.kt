// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏启动计划、参数组装和 Java 子进程管理。

package dev.molifox.core

import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.io.path.bufferedReader
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.forEachLine
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.reader
import kotlin.io.path.useLines

/** 每个命令行参数额外占用的引号与空格。 */
private const val ARGUMENT_QUOTE_OVERHEAD = 3

/** 启动参数在 Windows 上的长度上限：超过后改用 @argfile，主类与游戏参数另算。 */
private const val WINDOWS_ARGUMENT_LENGTH_LIMIT = 24_000

/** 完整启动命令（含 Java 可执行文件路径）在 Windows 上的上限。 */
private const val WINDOWS_COMMAND_LENGTH_LIMIT = 30_000

/** natives 依赖包解压后的体积上限（512 MiB）。 */
private const val MAX_NATIVES_BYTES = 512L * 1024 * 1024

/** 模块化加载器模板里的忽略列表参数前缀。 */
private const val IGNORE_LIST_PREFIX = "-DignoreList="

/** Java 从哪个主版本开始支持 @argfile：更老的版本只能把长命令留在命令行上。 */
private const val MIN_ARGUMENT_FILE_JAVA_VERSION = 9

/** 毫秒换算成秒（游戏时长以秒记录）。 */
private const val MILLIS_PER_SECOND = 1000

/** 启动准备阶段的进度节点：检查文件、选 Java、验证账户、组装参数、启动前命令。 */
private const val FILE_CHECK_START_PROGRESS = 0.01f
private const val FILE_CHECK_END_PROGRESS = 0.35f
private const val JAVA_SELECT_PROGRESS = 0.36f
private const val ACCOUNT_PROGRESS = 0.4f
private const val ARGUMENT_PROGRESS = 0.7f
private const val PRELAUNCH_PROGRESS = 0.85f

/** 游戏运行期间轮询 quick play 记录的间隔（毫秒）。 */
private const val ACTIVITY_POLL_INTERVAL_MS = 750L

/** 设置进程优先级的外部命令超时、输出读取等待与失败输出预览长度。 */
private const val PRIORITY_COMMAND_TIMEOUT_SECONDS = 15L
private const val PRIORITY_READER_JOIN_MILLIS = 500L
private const val PRIORITY_OUTPUT_PREVIEW_CHARS = 300

data class LaunchPlan(
    val executable: String,
    val arguments: List<String>,
    val gameDirectory: Path,
    val environment: Map<String, String> = emptyMap(),
    /** 启动日志里的一行有效值摘要，例如「启动参数：内存 4096 MB，窗口 1920×1080，额外 JVM -XX…」。 */
    val summary: String = "",
    /** 启动时被回退的实例设置；只记警告，不阻止启动。 */
    val warnings: List<String> = emptyList(),
    /** 启动后要写入游戏窗口的自定义标题；空串表示保持游戏默认标题。 */
    val windowTitle: String = "",
)

/** 一次启动计划解析出的路径：游戏目录、natives、主文件与依赖 classpath。 */
private class PreparedGameFiles(
    val root: Path,
    val game: Path,
    val natives: Path,
    val jar: Path,
    val classpathValue: String,
    val assets: Path,
    val gameAssets: Path,
    val indexId: String,
)

/** 一次启动真正生效的设置与窗口/内存参数。 */
private class LaunchParameters(
    val settings: LauncherSettings,
    val advanced: AdvancedLaunchSettings,
    val options: ResolvedLaunchOptions,
)

/** 版本模板需要的占位符、feature 开关与已展开的游戏参数。 */
private class LaunchArguments(
    val values: Map<String, String>,
    val features: Map<String, Boolean>,
    val gameArguments: List<String>,
    val targetArguments: List<String>,
    /** 已替换占位符的自定义窗口标题；空串表示不改窗口标题。 */
    val windowTitle: String = "",
)

/** 实例设置与占位符：把实例配置和版本模板解析成能拼进启动命令的值。 */
private class LaunchParameterResolver(
    private val store: LauncherStore,
) {
    /** 有效启动设置与真正进入命令的窗口/内存参数：实例值优先，无效值回退并记警告。 */
    fun resolveLaunchParameters(
        instance: GameInstance,
        runtime: JavaRuntime,
    ): LaunchParameters {
        val settings = store.data.settings
        val advanced = effectiveLaunchSettings(settings, instance)
        validateAdvancedLaunchSettings(advanced, store.platform.osName)
        val options =
            resolveInstanceLaunchOptions(
                settings,
                instance.copy(modCount = countInstanceMods(store.gamePath(instance))),
                store.launcherWindowSize,
                store.platform.totalMemoryMb,
                store.platform.availableMemoryMb,
                runtime.architecture,
                advanced,
            )
        return LaunchParameters(settings, advanced, options)
    }

    /** 版本占位符、feature 开关与游戏参数：quick play 目标在这里进入模板。 */
    fun resolveArguments(
        instance: GameInstance,
        files: PreparedGameFiles,
        parameters: LaunchParameters,
        profile: ResolvedProfile,
        credentials: GameCredentials,
        runtimeDirectory: Path?,
        target: GameLaunchTarget?,
    ): LaunchArguments {
        val quickPlay = supportsQuickPlay(profile, instance.gameVersion)
        val recording = runtimeDirectory?.resolve("quick-play.json")
        val targetArguments = quickPlayArguments(target, quickPlay, recording, instance.gameVersion)
        requireLaunchWorld(target, files.game)
        val features = launchFeatures(quickPlay, recording, target)
        val values =
            accountArgumentValues(
                credentials,
                parameters.settings,
                profile,
                parameters.advanced,
            ) +
                directoryArgumentValues(files) +
                windowArgumentValues(parameters.options, recording, target)
        val windowTitle =
            if (parameters.advanced.windowTitle.isBlank()) {
                ""
            } else {
                resolveLaunchText(
                    parameters.advanced.windowTitle,
                    instance.name,
                    credentials.name,
                    profile.id,
                    LocalDate.now().toString(),
                    LocalTime.now().withNano(0).toString(),
                )
            }
        return LaunchArguments(
            values,
            features,
            resolveGameArguments(profile, values, store.platform, features),
            targetArguments,
            windowTitle,
        )
    }

    /** 指定存档启动时目标世界必须已解压就位。 */
    private fun requireLaunchWorld(
        target: GameLaunchTarget?,
        game: Path,
    ) {
        if (target?.kind != EntryKind.World) return
        val world = safePath(game.resolve("saves"), target.key)
        require(world.resolve("level.dat").isRegularFile()) {
            I18n.t("core.gameLauncher.1")
        }
    }

    /** 账户与版本占位符：登录令牌、客户端 ID 与启动器版本。 */
    private fun accountArgumentValues(
        credentials: GameCredentials,
        settings: LauncherSettings,
        profile: ResolvedProfile,
        advanced: AdvancedLaunchSettings,
    ): Map<String, String> =
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
            "version_type" to
                effectiveVersionType(advanced, profile.json.string("type", "release")),
            "launcher_name" to "MoliFox",
            "launcher_version" to LAUNCHER_VERSION,
        )

    /** 目录与 classpath 占位符。 */
    private fun directoryArgumentValues(files: PreparedGameFiles): Map<String, String> =
        mapOf(
            "game_directory" to files.game.toString(),
            "assets_root" to files.assets.toString(),
            "assets_index_name" to files.indexId,
            "game_assets" to files.gameAssets.toString(),
            "natives_directory" to files.natives.toString(),
            "library_directory" to files.root.resolve("libraries").toString(),
            "classpath" to files.classpathValue,
            "classpath_separator" to store.platform.classpathSeparator,
        )

    /** 窗口与 quick play 占位符：目标写进对应参数，其余留空。 */
    private fun windowArgumentValues(
        options: ResolvedLaunchOptions,
        recording: Path?,
        target: GameLaunchTarget?,
    ): Map<String, String> =
        mapOf(
            "resolution_width" to options.width.toString(),
            "resolution_height" to options.height.toString(),
            "quickPlayPath" to recording?.toAbsolutePath()?.toString().orEmpty(),
            "quickPlaySingleplayer" to
                target?.takeIf { it.kind == EntryKind.World }?.key.orEmpty(),
            "quickPlayMultiplayer" to
                target?.takeIf { it.kind == EntryKind.Server }?.key.orEmpty(),
            "quickPlayRealms" to "",
        )

    /** 版本 JSON 的 feature 开关：窗口尺寸与 quick play 能力声明。 */
    private fun launchFeatures(
        quickPlay: Boolean,
        recording: Path?,
        target: GameLaunchTarget?,
    ): Map<String, Boolean> =
        mapOf(
            "has_custom_resolution" to true,
            "is_demo_user" to false,
            "has_quick_plays_support" to (quickPlay && recording != null),
            "is_quick_play_singleplayer" to (target?.kind == EntryKind.World),
            "is_quick_play_multiplayer" to (target?.kind == EntryKind.Server),
            "is_quick_play_realms" to false,
        )

    /** 现代版本用 arguments.game，旧版本用 minecraftArguments 并逐个替换占位符。 */
    private fun resolveGameArguments(
        profile: ResolvedProfile,
        values: Map<String, String>,
        platform: PlatformServices,
        features: Map<String, Boolean>,
    ): List<String> =
        if (profile.json.string("minecraftArguments").isNotBlank()) {
            splitArguments(profile.json.string("minecraftArguments")).map { substitute(it, values) }
        } else {
            expandArguments(profile.json.obj("arguments").array("game"), values, platform, features)
        }
}

/** 命令行组装：JVM 前缀、模板参数、@argfile 改写与整条命令的长度校验。 */
private class LaunchCommandAssembler(
    private val store: LauncherStore,
    /** 外置认证账户需要的 authlib-injector；离线与微软账户不会使用。 */
    private val authlibInjector: AuthlibInjector? = null,
) {
    /** 固定 JVM 前缀与模板 JVM 参数：模板里的 -Xmx/-Xms 会被去掉，避免与实例设置重复。 */
    private suspend fun baseCommand(
        files: PreparedGameFiles,
        parameters: LaunchParameters,
        profile: ResolvedProfile,
        values: Map<String, String>,
        platform: PlatformServices,
        features: Map<String, Boolean>,
        credentials: GameCredentials,
    ): MutableList<String> {
        val command = jvmRuntimeFlags(parameters.options.memory)
        val logging = profile.json.obj("logging").obj("client")
        if (
            logging.string("argument").isNotBlank() &&
            logging.obj("file").string("id").isNotBlank()
        ) {
            val config =
                safePath(
                    files.root.resolve("assets/log_configs"),
                    logging.obj("file").string("id"),
                )
            command += logging.string("argument").replace("\${path}", config.toString())
        }
        command += jvmArguments(profile, values, platform, features, files)
        command += splitArguments(parameters.options.extraJvmArgs)
        // 外置认证：在生成 @argfile 与长度校验之前加入，确保长参数也被正确转义。
        if (credentials.authlibServer.isNotBlank()) command += authlibArguments(credentials)
        return command
    }

    /** 固定 JVM 前缀：内存、UTF-8 编码与关闭远程 codebase 的安全参数。 */
    private fun jvmRuntimeFlags(memory: GameMemoryAllocation): MutableList<String> =
        mutableListOf(
            "-Xms${memory.initialMb}M",
            "-Xmx${memory.maximumMb}M",
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-Dlog4j2.formatMsgNoLookups=true",
            "-Djava.rmi.server.useCodebaseOnly=true",
            "-Dcom.sun.jndi.rmi.object.trustURLCodebase=false",
        )

    /** 外置认证的 javaagent 参数：注入器缺失时按下载失败提示重启。 */
    private suspend fun authlibArguments(credentials: GameCredentials): List<String> {
        val injector =
            authlibInjector
                ?: throw AuthlibException(
                    AuthlibError.DownloadFailed,
                    I18n.t("core.gameLauncher.2"),
                )
        return injector.jvmArguments(
            store.dataDirectory.resolve("cache"),
            credentials.authlibServer,
        )
    }

    /** 模板 JVM 参数：旧版本回落到 library.path 与 classpath，模块化加载器修正忽略列表。 */
    private fun jvmArguments(
        profile: ResolvedProfile,
        values: Map<String, String>,
        platform: PlatformServices,
        features: Map<String, Boolean>,
        files: PreparedGameFiles,
    ): List<String> {
        val modern = profile.json.obj("arguments")
        val expanded =
            if (modern.array("jvm").size() > 0) {
                expandArguments(modern.array("jvm"), values, platform, features)
            } else {
                listOf("-Djava.library.path=${files.natives}", "-cp", files.classpathValue)
            }
        return expanded
            .map { argument ->
                ignoreListArgument(argument, profile.id, files.jar.fileName.toString())
            }.filterNot { it.startsWith("-Xmx") || it.startsWith("-Xms") }
    }

    /** 模块化加载器必须忽略实际继承的 JAR：模板里的 `<版本 ID>.jar` 换成真实主文件名。 */
    private fun ignoreListArgument(
        argument: String,
        profileId: String,
        jarName: String,
    ): String {
        if (!argument.startsWith(IGNORE_LIST_PREFIX)) return argument
        val entries = argument.removePrefix(IGNORE_LIST_PREFIX).split(',')
        val mapped = entries.joinToString(",") { if (it == "$profileId.jar") jarName else it }
        return IGNORE_LIST_PREFIX + mapped
    }

    /** Windows 命令行超长时改写 @argfile：Java 9+ 才支持，且必须能用当前编码写完。 */
    private fun argumentFileInvocation(
        command: MutableList<String>,
        directory: Path,
        prepareFiles: Boolean,
        platform: PlatformServices,
        runtime: JavaRuntime,
    ): MutableList<String> {
        val length = command.sumOf { it.length + ARGUMENT_QUOTE_OVERHEAD }
        val tooLong =
            prepareFiles &&
                platform.osName == "windows" &&
                length > WINDOWS_ARGUMENT_LENGTH_LIMIT
        if (!tooLong) return command
        require(runtime.majorVersion >= MIN_ARGUMENT_FILE_JAVA_VERSION) {
            I18n.t("core.gameLauncher.3")
        }
        val argumentFile = directory.resolve(".molifox-jvm.args")
        // The native Java launcher parses @files before file.encoding takes effect.
        val charset =
            java.nio.charset.Charset
                .forName(System.getProperty("native.encoding", "UTF-8"))
        val text = command.joinToString("\n") { quoteJavaArgument(it) }
        require(charset.newEncoder().canEncode(text)) {
            I18n.t("core.gameLauncher.4")
        }
        Files.writeString(argumentFile, text, charset)
        return mutableListOf("@${argumentFile.toAbsolutePath()}")
    }

    /** 主类、quick play、窗口与额外游戏参数；Windows 上再校验整条命令长度。 */
    suspend fun buildPlan(
        files: PreparedGameFiles,
        parameters: LaunchParameters,
        profile: ResolvedProfile,
        arguments: LaunchArguments,
        credentials: GameCredentials,
        prepareFiles: Boolean,
        runtime: JavaRuntime,
        runtimeDirectory: Path?,
    ): LaunchPlan {
        val platform = store.platform
        val command =
            baseCommand(
                files,
                parameters,
                profile,
                arguments.values,
                platform,
                arguments.features,
                credentials,
            )
        val invocation =
            argumentFileInvocation(
                command,
                runtimeDirectory ?: files.game,
                prepareFiles,
                platform,
                runtime,
            )
        invocation += profile.mainClass
        val gameArguments =
            arguments.gameArguments + windowArguments(parameters.options, arguments.gameArguments) +
                parameters.options.extraGameArgs
        // 显式点击的服务器/存档是本次启动目标，不能被模板中的空值或旧值覆盖。
        invocation += appendMissingQuickPlay(gameArguments, arguments.targetArguments)
        requireWindowsCommandLength(invocation, prepareFiles, platform, runtime.path)
        return LaunchPlan(
            selectExecutable(platform, runtime, parameters.advanced),
            invocation,
            files.game,
            parameters.advanced.environmentVariables.toMap(),
            summary = parameters.options.summary,
            warnings = parameters.options.warnings,
            windowTitle = arguments.windowTitle,
        )
    }

    /** 窗口参数：模板已给出 --width 时不再追加，避免重复；全屏同理。 */
    private fun windowArguments(
        options: ResolvedLaunchOptions,
        gameArguments: List<String>,
    ): List<String> {
        val arguments = mutableListOf<String>()
        if (!gameArguments.contains("--width")) {
            arguments +=
                listOf("--width", options.width.toString(), "--height", options.height.toString())
        }
        if (options.fullscreen && "--fullscreen" !in gameArguments) arguments += "--fullscreen"
        return arguments
    }

    /** Windows 命令总长度有限：超长时提示缩短游戏目录或附加游戏参数。 */
    private fun requireWindowsCommandLength(
        invocation: List<String>,
        prepareFiles: Boolean,
        platform: PlatformServices,
        runtimePath: String,
    ) {
        if (!prepareFiles || platform.osName != "windows") return
        val length = invocation.sumOf { it.length + ARGUMENT_QUOTE_OVERHEAD } + runtimePath.length
        require(length < WINDOWS_COMMAND_LENGTH_LIMIT) {
            I18n.t("core.gameLauncher.5")
        }
    }

    /** Windows 上勾选「显示 Java 控制台」且当前是 javaw.exe 时改用 java.exe。 */
    private fun selectExecutable(
        platform: PlatformServices,
        runtime: JavaRuntime,
        advanced: AdvancedLaunchSettings,
    ): String =
        if (
            platform.osName == "windows" &&
            advanced.useJavaConsoleExecutable &&
            Path
                .of(runtime.path)
                .fileName
                .toString()
                .equals("javaw.exe", true)
        ) {
            Path
                .of(runtime.path)
                .resolveSibling("java.exe")
                .also { require(it.isRegularFile()) { I18n.t("core.gameLauncher.6") } }
                .toString()
        } else {
            runtime.path
        }
}

class LaunchPlanner(
    private val store: LauncherStore,
    /** 外置认证账户需要的 authlib-injector；离线与微软账户不会使用。 */
    authlibInjector: AuthlibInjector? = null,
) {
    private val parameterResolver = LaunchParameterResolver(store)
    private val commandAssembler = LaunchCommandAssembler(store, authlibInjector)

    /**
     * 组装一次启动的全部参数：补齐 natives 与资源、解析实例设置与账户，再展开版本模板。
     *
     * 各步骤拆在 `prepareGameFiles`、`resolveLaunchParameters`、`resolveArguments`、`buildPlan`
     * 里，调用顺序、进度回调与异常点与原实现一致。
     */
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
            val files = prepareGameFiles(instance, profile, runtimeDirectory, prepareFiles)
            val parameters = parameterResolver.resolveLaunchParameters(instance, runtime)
            val arguments =
                parameterResolver.resolveArguments(
                    instance,
                    files,
                    parameters,
                    profile,
                    credentials,
                    runtimeDirectory,
                    target,
                )
            commandAssembler.buildPlan(
                files,
                parameters,
                profile,
                arguments,
                credentials,
                prepareFiles,
                runtime,
                runtimeDirectory,
            )
        }

    /** 游戏目录、natives、主文件与依赖 classpath：缺文件时提示「先修复游戏」。 */
    private suspend fun prepareGameFiles(
        instance: GameInstance,
        profile: ResolvedProfile,
        runtimeDirectory: Path?,
        prepareFiles: Boolean,
    ): PreparedGameFiles {
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
        extractNatives(libraries, resolver, natives, prepareFiles)
        val jar = root.resolve("versions/${profile.jarId}/${profile.jarId}.jar")
        if (prepareFiles) require(jar.isRegularFile()) { I18n.t("core.gameLauncher.7") }
        val libraryPaths = classpathLibraries(libraries, resolver, prepareFiles)
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
        return PreparedGameFiles(
            root = root,
            game = game,
            natives = natives,
            jar = jar,
            classpathValue = classpathValue,
            assets = assets,
            gameAssets = prepareGameAssets(assets, game, indexId, prepareFiles),
            indexId = indexId,
        )
    }

    /** 解压原版 natives 依赖：只处理平台清单里标为 native 的产物。 */
    private suspend fun extractNatives(
        libraries: List<JsonObject>,
        resolver: VersionResolver,
        natives: Path,
        prepareFiles: Boolean,
    ) {
        for (library in if (prepareFiles) libraries else emptyList()) {
            extractLibraryNatives(library, resolver, natives)
        }
    }

    /** 单个依赖的 natives 包：排除项与安装流程一致，体积上限拦住异常压缩包。 */
    private suspend fun extractLibraryNatives(
        library: JsonObject,
        resolver: VersionResolver,
        natives: Path,
    ) {
        val excluded = library.obj("extract").array("exclude").map { it.asString } + "META-INF/"
        for ((artifact, native) in resolver.libraryArtifacts(library)) {
            if (!native) continue
            require(artifact.target.isRegularFile()) { I18n.t("core.gameLauncher.8") }
            ZipFile(artifact.target.toFile()).use { zip -> copyNatives(zip, excluded, natives) }
        }
    }

    /** 解压一个 natives 包：[excluded] 里的模板文件跳过，其余按清单路径落盘。 */
    private suspend fun copyNatives(
        zip: ZipFile,
        excluded: List<String>,
        natives: Path,
    ) {
        for ((entry, target) in validateArchive(zip, "", natives, MAX_NATIVES_BYTES)) {
            currentCoroutineContext().ensureActive()
            if (excluded.any { entry.name.startsWith(it) }) continue
            target.parent.createDirectories()
            zip.getInputStream(entry).use {
                Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** classpath 里真正需要加载的依赖；[prepareFiles] 时要求磁盘上都在。 */
    private fun classpathLibraries(
        libraries: List<JsonObject>,
        resolver: VersionResolver,
        prepareFiles: Boolean,
    ): List<Path> {
        val paths =
            libraries
                .filter { resolver.hasClasspathArtifact(it) }
                .map { resolver.libraryPath(it) }
        if (prepareFiles) {
            require(paths.all { it.isRegularFile() }) { I18n.t("core.gameLauncher.9") }
        }
        return paths
    }

    /** 声明了 virtual / map_to_resources 的版本从独立目录读资源：先建硬链接，失败退回复制。 */
    private suspend fun prepareGameAssets(
        assets: Path,
        game: Path,
        indexId: String,
        prepareFiles: Boolean,
    ): Path {
        val indexPath = safePath(assets.resolve("indexes"), "$indexId.json")
        if (!indexPath.exists()) return assets
        val index = readObject(indexPath)
        val virtual = index.boolean("virtual")
        val resources = index.boolean("map_to_resources")
        if (!virtual && !resources) return assets
        val gameAssets =
            if (virtual) safePath(assets.resolve("virtual"), indexId) else game.resolve("resources")
        for ((name, value) in if (prepareFiles) index.obj("objects").entrySet() else emptySet()) {
            currentCoroutineContext().ensureActive()
            val hash = value.asJsonObject.string("hash")
            val from = assets.resolve("objects/${hash.take(2)}/$hash")
            val to = safePath(gameAssets, name)
            if (!to.exists()) {
                to.parent.createDirectories()
                runCatching { Files.createLink(to, from) }
                    .getOrElse { Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING) }
            }
        }
        return gameAssets
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
internal data class ProcessPriorityOutcome(
    val message: String?,
    val warning: Boolean = false,
)

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
    if (processId <= 0) return ProcessPriorityOutcome(I18n.t("core.gameLauncher.10", processId), true)
    val name = windowsProcessPriorityName(priority)
    var reason = I18n.t("core.gameLauncher.11")
    for (command in priorityCommands(processId, name)) {
        try {
            execute(command)
            return ProcessPriorityOutcome(I18n.t("core.gameLauncher.12", priority.title, processId))
        } catch (failure: IOException) {
            reason = failureReason(failure)
        } catch (failure: IllegalStateException) {
            reason = failureReason(failure)
        } catch (failure: InterruptedException) {
            reason = failureReason(failure)
        }
    }
    return ProcessPriorityOutcome(I18n.t("core.gameLauncher.13", reason), true)
}

/** 失败原因只取一行可读文本：异常消息优先，没有时退回类型名。 */
private fun failureReason(failure: Exception): String =
    failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName

/** 取消必须原样抛出，不能按普通启动失败记录（单独成函数以免被当作无意义的重抛）。 */
private fun rethrowCancellation(cancelled: CancellationException): Nothing = throw cancelled

/** wmic 输出简单、进程启动快；Windows 11 已移除 wmic 时退回 PowerShell 的 PriorityClass 赋值。 */
private fun priorityCommands(
    processId: Long,
    name: String,
): List<List<String>> =
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
            }.onFailure { output.append(it.javaClass.simpleName) }
        }.apply {
            isDaemon = true
            start()
        }
    if (!process.waitFor(PRIORITY_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        error(I18n.t("core.gameLauncher.14"))
    }
    reader.join(PRIORITY_READER_JOIN_MILLIS)
    check(process.exitValue() == 0) {
        output.lineSequence().firstOrNull { it.isNotBlank() }?.take(PRIORITY_OUTPUT_PREVIEW_CHARS)
            ?: I18n.t("core.gameLauncher.15", process.exitValue())
    }
}

/** 一次启动的运行期上下文：命令执行器与日志会话随启动创建一次。 */
private class LaunchRun(
    store: LauncherStore,
    val runId: String,
    val instanceId: String,
    val instance: GameInstance,
    val settings: AdvancedLaunchSettings,
    val runtimeDirectory: Path,
) {
    val commands = LaunchCommandRunner(store)
    val session = LaunchSession(store, instance)
}

/** 启动前解析好的版本、Java 与账户凭据。 */
private class LaunchInputs(
    val profile: ResolvedProfile,
    val runtime: JavaRuntime,
    val credentials: GameCredentials,
)

/** 已组装的启动计划与它使用的 Java 运行时。 */
private class PreparedLaunch(
    val plan: LaunchPlan,
    val runtime: JavaRuntime,
)

/** 启动成功后写回状态的事件与计时起点，收尾时用来回填退出码与游玩时长。 */
private class RunMark(
    val startedAt: Long,
    val event: GameRunEvent,
)

/** Windows 显卡偏好只是尽力而为：成功或失败都只写日志。 */
private fun applyGpuPreference(
    store: LauncherStore,
    run: LaunchRun,
    plan: LaunchPlan,
) {
    applyHighPerformanceGpu(
        store.platform.osName,
        plan.executable,
        run.settings.highPerformanceGpu,
    )?.let(store::log)
}

/** 游戏窗口出现后替换标题；其他平台或超时只记一条警告。 */
private fun watchGameWindowTitle(
    store: LauncherStore,
    scope: CoroutineScope,
    plan: LaunchPlan,
    process: Process,
) {
    if (plan.windowTitle.isBlank()) return
    scope.launch(Dispatchers.IO) {
        val applied = applyWindowTitle(process, plan.windowTitle, store.platform.osName)
        if (!applied && process.isAlive) {
            store.log(
                I18n.t("miscA1.gameLauncher.windowTitleUnsupported"),
                level = LogLevel.Warn,
            )
        }
    }
}

/** 进程优先级尽力而为：失败只记一条警告。 */
private fun applyProcessPriority(
    store: LauncherStore,
    process: Process,
) {
    val priority =
        applyGameProcessPriority(store.data.settings.processPriority, process.pid())
    priority.message?.let { message ->
        store.log(
            message,
            level = if (priority.warning) LogLevel.Warn else LogLevel.Info,
        )
    }
}

/** 一局游戏的进程与输出：登记进程、监视输出、启动后命令与收尾写回。 */
private class GameRunSupervisor(
    private val store: LauncherStore,
    private val scope: CoroutineScope,
    private val processes: ConcurrentHashMap<String, Pair<String, Process>>,
    private val runners: ConcurrentHashMap<String, LaunchCommandRunner>,
    private val runtimes: GameRuntimeDirectories,
) {
    /** 启动 Java 子进程：继承本次运行的环境变量，并登记进程、优先级与运行缓存。 */
    fun startGameProcess(
        run: LaunchRun,
        prepared: PreparedLaunch,
    ): Process {
        val plan = prepared.plan
        applyGpuPreference(store, run, plan)
        val process =
            ProcessBuilder(listOf(plan.executable) + plan.arguments)
                .directory(plan.gameDirectory.toFile())
                .redirectErrorStream(true)
                .apply {
                    // The game may outlive the launcher. Never inherit its per-run UI temp
                    // directory, which the native launcher cleans after the UI process exits.
                    val temporary = runtimes.temporary(run.runtimeDirectory).toString()
                    environment()
                        .putAll(
                            mapOf(
                                "TEMP" to temporary,
                                "TMP" to temporary,
                                "TMPDIR" to temporary,
                            ),
                        )
                    environment().putAll(plan.environment)
                }.start()
        processes[run.runId] = run.instanceId to process
        runners[run.runId] = run.commands
        watchGameWindowTitle(store, scope, plan, process)
        applyProcessPriority(store, process)
        runCatching { runtimes.attach(run.runtimeDirectory, process) }
            .onFailure {
                store.log(
                    I18n.t("miscA1.gameLauncher.runtimeRecordFailed", it.javaClass.simpleName),
                    level = LogLevel.Warn,
                )
            }
        return process
    }

    /** 游戏输出与活动：读线程随进程退出结束，收尾交给 [monitorGame]。 */
    fun watchGameProcess(
        run: LaunchRun,
        prepared: PreparedLaunch,
        process: Process,
        mark: RunMark,
    ) {
        scope.launch(Dispatchers.IO) {
            val activity =
                GameActivityTracker(
                    store,
                    run.instance,
                    run.runtimeDirectory.resolve("quick-play.json"),
                )
            val activityPoller =
                launch {
                    while (isActive && process.isAlive) {
                        activity.pollQuickPlay()
                        delay(ACTIVITY_POLL_INTERVAL_MS)
                    }
                }
            val decoder =
                GameLogDecoder { entry ->
                    activity.accept(entry.message)
                    store.log(entry.message, error = entry.error, game = true)
                }
            monitorGame(run, prepared, mark, process, activity, activityPoller, decoder)
        }
    }

    /** 转发游戏输出直到进程退出；退出后命令失败只通知一次，不影响这局游戏的收尾。 */
    private suspend fun monitorGame(
        run: LaunchRun,
        prepared: PreparedLaunch,
        mark: RunMark,
        process: Process,
        activity: GameActivityTracker,
        activityPoller: Job,
        decoder: GameLogDecoder,
    ) {
        var exitCode: Int? = null
        var postFailed = false
        try {
            val exit = forwardGameOutput(process, run.session, decoder)
            exitCode = exit
            store.log(I18n.t("miscA1.gameLauncher.exitedWithCode", exit), exit != 0)
            postFailed = runPostExitCommand(run, prepared.plan, exit)
            run.session.finish(exit)
        } catch (error: IOException) {
            run.session.finish(failure = error)
        } catch (error: IllegalStateException) {
            run.session.finish(failure = error)
        } catch (error: InterruptedException) {
            run.session.finish(failure = error)
        } finally {
            finishRun(
                run,
                mark,
                exitCode,
                postFailed,
                process,
                activity,
                activityPoller,
                decoder,
            )
        }
    }

    /** 读游戏输出：清理密钥后写入会话并交给统一日志解码器，返回退出码。 */
    private fun forwardGameOutput(
        process: Process,
        session: LaunchSession,
        decoder: GameLogDecoder,
    ): Int {
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val cleaned = session.clean(line)
                session.append(cleaned)
                decoder.accept(cleaned)
            }
        }
        decoder.flush()
        return process.waitFor()
    }

    /** 退出后命令：取消原样抛出；其余失败只通知一次，不影响已结束的这局游戏。 */
    private suspend fun runPostExitCommand(
        run: LaunchRun,
        plan: LaunchPlan,
        exitCode: Int,
    ): Boolean =
        try {
            run.commands.run(
                run.settings.postExitCommand,
                run.settings.commandTimeoutSeconds,
                LaunchCommandPhase.After,
                run.instance,
                plan,
                run.session,
                exitCode,
            )
            false
        } catch (cancelled: CancellationException) {
            rethrowCancellation(cancelled)
        } catch (error: IllegalArgumentException) {
            reportPostExitFailure(error)
        } catch (error: IllegalStateException) {
            reportPostExitFailure(error)
        } catch (error: IOException) {
            reportPostExitFailure(error)
        }

    /** 退出后命令失败：只提示这一次，游戏本身的退出结果不变。 */
    private fun reportPostExitFailure(error: Exception): Boolean {
        val message =
            I18n.t(
                "core.gameLauncher.16",
                error.message ?: I18n.t("miscA1.gameLauncher.postExitCommandFailed"),
            )
        store.log(message, true)
        store.notify(message)
        return true
    }

    /** 收尾：停掉活动轮询、回收运行缓存、回填退出码与游玩时长；取消也要执行。 */
    private suspend fun finishRun(
        run: LaunchRun,
        mark: RunMark,
        exitCode: Int?,
        postFailed: Boolean,
        process: Process,
        activity: GameActivityTracker,
        activityPoller: Job,
        decoder: GameLogDecoder,
    ) {
        withContext(NonCancellable) {
            activityPoller.cancelAndJoin()
            activity.pollQuickPlay()
            activity.finish()
        }
        decoder.flush()
        processes.remove(run.runId)
        runners.remove(run.runId)
        if (!process.isAlive) runCatching { runtimes.release(run.runtimeDirectory) }
        store.updateState {
            val remaining = it.runningGames.filterNot { gameRun -> gameRun.id == run.runId }
            it.copy(
                runningInstanceId = remaining.lastOrNull()?.instanceId,
                runningGames = remaining,
                gameRun =
                    mark.event.copy(
                        finished = true,
                        exitCode = exitCode,
                        postCommandFailed = postFailed,
                    ),
            )
        }
        store.updateInstance(run.instanceId) {
            it.copy(
                playTimeSeconds =
                    it.playTimeSeconds +
                        (System.currentTimeMillis() - mark.startedAt) / MILLIS_PER_SECOND,
            )
        }
    }

    /** 取消也要收拢本次的运行缓存；取消本身原样抛出，不按启动失败记录。 */
    fun cancelLaunch(run: LaunchRun) {
        run.session.append(I18n.t("core.gameLauncher.17"))
        releaseRuntime(run)
    }

    /** 启动失败的统一出口：写诊断、回收运行缓存后原样抛出，界面负责提示。 */
    fun failLaunch(
        run: LaunchRun,
        error: Throwable,
    ): Nothing {
        run.session.finish(failure = error)
        releaseRuntime(run)
        // 启动中止：本次启动不会再有后续输出，档位记为 FATAL，与可继续的操作失败区分。
        store.log(
            I18n.t("miscA1.gameLauncher.launchAborted", error.message ?: error.javaClass.simpleName),
            level = LogLevel.Fatal,
        )
        throw error
    }

    /** 进程还没登记时回收本次的运行缓存；已登记的由进程收尾负责。 */
    private fun releaseRuntime(run: LaunchRun) {
        if (processes.containsKey(run.runId)) return
        runCatching { runtimes.release(run.runtimeDirectory) }
    }
}

class GameLauncher(
    private val store: LauncherStore,
    private val installer: MinecraftInstaller,
    private val java: JavaService,
    private val accounts: AccountService,
    scope: CoroutineScope,
) {
    private val processes = ConcurrentHashMap<String, Pair<String, Process>>()
    private val runners = ConcurrentHashMap<String, LaunchCommandRunner>()
    private val runtimes = GameRuntimeDirectories(store).also { it.prune() }
    private val runs = GameRunSupervisor(store, scope, processes, runners, runtimes)
    val isRunning: Boolean
        get() =
            store.state.value.runningGames
                .isNotEmpty()

    fun isRunning(instanceId: String): Boolean =
        store.state.value.runningGames
            .any { it.instanceId == instanceId }

    /** 未安装完成的实例不能启动；提示语保持原样。 */
    private fun requireInstalled(instance: GameInstance) {
        check(instance.installed) { I18n.t("core.gameLauncher.18") }
    }

    /** 写回运行态与开始时间：进程已起来，界面据此显示「游戏中」。 */
    private fun markRunStarted(
        run: LaunchRun,
        prepared: PreparedLaunch,
        process: Process,
    ): RunMark {
        val started = System.currentTimeMillis()
        val event =
            GameRunEvent(
                run.runId,
                run.instanceId,
                run.settings.launcherVisibility,
                run.settings.closeLauncherAfterGameExit,
                processId = process.pid(),
            )
        store.updateState {
            it.copy(
                runningInstanceId = run.instanceId,
                gameRun = event,
                runningGames = it.runningGames + event,
            )
        }
        store.updateInstance(run.instanceId) { it.copy(lastPlayed = Instant.now().toString()) }
        store.log(I18n.t("miscA1.gameLauncher.started", run.instance.name, prepared.runtime.majorVersion))
        return RunMark(started, event)
    }

    suspend fun launch(
        instanceId: String,
        context: JobContext,
        target: GameLaunchTarget? = null,
    ) {
        val runId = UUID.randomUUID().toString()
        val runtimeDirectory = store.temporaryDirectory.resolve("games").resolve(runId)
        val instance = store.instance(instanceId)
        val launchSettings =
            effectiveLaunchSettings(store.data.settings, instance).let {
                it.copy(
                    environmentVariables = it.environmentVariables.toMap(),
                )
            }
        requireInstalled(instance)
        validateAdvancedLaunchSettings(
            effectiveLaunchSettings(store.data.settings, instance),
            store.platform.osName,
        )
        store.updateState { it.copy(launchIssue = null) }
        val run = LaunchRun(store, runId, instanceId, instance, launchSettings, runtimeDirectory)
        runCatching {
            val prepared = prepareLaunch(run, context, target)
            val process = runs.startGameProcess(run, prepared)
            val mark = markRunStarted(run, prepared, process)
            runs.watchGameProcess(run, prepared, process, mark)
        }.onFailure { error ->
            // 启动链路上会抛出参数校验、文件 IO、JSON 解析与外置认证等多类异常，
            // 这里是唯一的记录出口：取消先回收本次运行缓存并原样抛出，其它失败写诊断、记 FATAL 后原样抛出，
            // 收窄类型会漏掉未知失败。
            if (error is CancellationException) {
                runs.cancelLaunch(run)
                throw error
            }
            runs.failLaunch(run, error)
        }
    }

    /** 启动前解析版本、Java 与账户凭据；任何一步失败都让本次启动中止。 */
    private suspend fun resolveLaunchInputs(
        run: LaunchRun,
        context: JobContext,
    ): LaunchInputs {
        run.session.stage(I18n.t("core.gameLauncher.19"))
        val profile = installer.resolveForLaunch(run.instance, context)
        run.session.stage(I18n.t("core.gameLauncher.20"))
        context.progress(FILE_CHECK_START_PROGRESS, I18n.t("core.gameLauncher.21"))
        installer.installDependencies(
            profile,
            context,
            store.minecraftRoot(run.instance),
            FILE_CHECK_START_PROGRESS,
            FILE_CHECK_END_PROGRESS,
            verifyHashes = run.settings.verifyFileHashes,
        )
        context.progress(JAVA_SELECT_PROGRESS, I18n.t("core.gameLauncher.22", profile.requiredJava))
        run.session.stage(I18n.t("core.gameLauncher.23"))
        val runtime =
            java.resolve(
                profile,
                run.instance.javaPath.ifBlank { store.data.settings.javaPath },
                context,
            )
        context.progress(ACCOUNT_PROGRESS, I18n.t("core.gameLauncher.24"))
        run.session.stage(I18n.t("core.gameLauncher.25"))
        return LaunchInputs(profile, runtime, accounts.credentials())
    }

    /** 组装启动计划并执行启动前命令；进度与日志顺序与原启动流程一致。 */
    private suspend fun prepareLaunch(
        run: LaunchRun,
        context: JobContext,
        target: GameLaunchTarget?,
    ): PreparedLaunch {
        val inputs = resolveLaunchInputs(run, context)
        context.checkCancelled()
        context.progress(ARGUMENT_PROGRESS, I18n.t("core.gameLauncher.26"))
        run.session.stage(I18n.t("core.gameLauncher.27"))
        runtimes.create(run.runId)
        val plan =
            LaunchPlanner(store, accounts.authlibInjector)
                .plan(
                    run.instance.copy(advancedLaunch = run.settings),
                    inputs.profile,
                    inputs.runtime,
                    inputs.credentials,
                    runtimeDirectory = run.runtimeDirectory,
                    target = target,
                )
        run.session.prepared(plan, inputs.credentials)
        logLaunchTarget(target)
        // 一行有效参数摘要，便于对照日志确认实例设置真的进入了命令；坏值回退单独记警告。
        plan.warnings.forEach { store.log(it, level = LogLevel.Warn) }
        if (plan.summary.isNotBlank()) store.log(redactSecrets(plan.summary))
        // 工作目录就是解析后的有效隔离结果；来源写进日志，推断值不冒充用户选择。
        store.log(I18n.t("core.gameLauncher.28", plan.gameDirectory, store.isolationSource(run.instance).logTitle))
        context.progress(PRELAUNCH_PROGRESS, I18n.t("core.gameLauncher.29"))
        run.commands.run(
            run.settings.preLaunchCommand,
            run.settings.commandTimeoutSeconds,
            LaunchCommandPhase.Before,
            run.instance,
            plan,
            run.session,
        )
        context.checkCancelled()
        applyGameLanguage(store, run.instance)
        return PreparedLaunch(plan, inputs.runtime)
    }

    /** 服务器/存档直达来源只记一行，避免 `prepareLaunch` 被日志分支撑长。 */
    private fun logLaunchTarget(target: GameLaunchTarget?) {
        if (target == null) return
        store.log(
            I18n.t(
                if (target.kind == EntryKind.Server) "core.launchTarget.server" else "core.launchTarget.world",
                target.key,
            ),
        )
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
