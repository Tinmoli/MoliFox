// 负责配置持久化、实例路径和界面状态。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import kotlin.io.path.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LauncherStore(
    val platform: PlatformServices,
    val dataDirectory: Path = platform.defaultDataDirectory,
) {
    val directories = LauncherDirectories(dataDirectory)
    val recentUsage: RecentUsage by lazy { RecentUsage(this) }
    private val config = directories.configuration
    private val mutableState: MutableStateFlow<LauncherState>
    val state: StateFlow<LauncherState>
        get() = mutableState.asStateFlow()

    val data: LauncherData
        get() = mutableState.value.data

    val minecraftRoot: Path
        get() = Path.of(data.settings.gameRoot)

    /** 下载缓存目录：用户自定义 downloadCachePath，为空时使用数据目录下的 cache/。 */
    val cacheDirectory: Path
        get() =
            data.settings.downloadCachePath.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: dataDirectory.resolve("cache")

    val downloadDirectory: Path
        get() = directories.cache(data.settings.downloadCachePath).downloads

    val metadataDirectory: Path
        get() = directories.cache(data.settings.downloadCachePath).metadata

    val temporaryDirectory: Path
        get() = directories.cache(data.settings.downloadCachePath).temporary

    /** 界面在窗口尺寸变化时上报的当前窗口大小，供「与启动器一样大」的游戏窗口模式使用。 */
    @Volatile var launcherWindowSize: Pair<Int, Int>? = null

    fun minecraftRoot(instance: GameInstance): Path =
        instance.gameDirectoryId?.let { directoryId ->
            Path.of(
                data.gameDirectories.find { it.id == directoryId }?.path
                    ?: error("此版本的游戏目录已移除，请重新选择目录")
            )
        } ?: minecraftRoot

    fun isolated(instance: GameInstance): Boolean =
        when (instance.isolation) {
            VersionIsolation.Inherit -> data.settings.isolateVersions
            VersionIsolation.Enabled -> true
            VersionIsolation.Disabled -> false
        }

    private val backup = directories.previousConfiguration

    init {
        val relocations = directories.initialize()
        var recoveryMessage: String? = null
        val defaults =
            LauncherData(
                settings =
                    LauncherSettings(
                        // 默认游戏目录 = 启动器所在目录下的 .minecraft（数据目录默认是启动器旁的 MoliFox/）。
                        // 数据目录本身就在 .minecraft 里、或这一层就是游戏根时直接用这一层，不再套一层。
                        gameRoot =
                            defaultGameRoot(dataDirectory.parent ?: dataDirectory).toString(),
                        // 新建配置直接使用新目录语义，不参与旧配置迁移。
                        gameRootSemantics = 1,
                        proxyMode = "system",
                        memoryMb = minOf(4096, maxOf(1024, platform.totalMemoryMb / 4 / 256 * 256)),
                        autoMemory = true,
                    )
            )
        val loaded =
            if (config.exists())
                try {
                    readData(config)
                } catch (denied: java.nio.file.AccessDeniedException) {
                    throw denied
                } catch (denied: SecurityException) {
                    throw denied
                } catch (_: Exception) {
                    Files.copy(
                        config,
                        directories.configurationBackups.resolve(
                            "launcher.damaged-${System.currentTimeMillis()}-${UUID.randomUUID()}.json"
                        ),
                    )
                    pruneDamagedConfigurations()
                    runCatching { readData(backup) }
                        .getOrElse { defaults }
                        .also { recoveryMessage = "原配置已保留，已恢复备份或默认配置。" }
                }
            else defaults
        if (config.exists() && recoveryMessage == null)
            Files.copy(config, backup, StandardCopyOption.REPLACE_EXISTING)
        var migrated =
            if (loaded.settings.microsoftClientId.isBlank())
                loaded.copy(
                    settings =
                        loaded.settings.copy(microsoftClientId = MoliFoxIdentity.microsoftClientId)
                )
            else loaded
        if (config.exists() && loaded.settings.gameRootSemantics == 0) {
            // 旧配置的 gameRoot 之下还有一层 minecraft/；根目录语义已改为直接使用 gameRoot，
            // 统一下移一级并写入标记，保持各实例实际使用的目录不变，且只迁移一次。
            migrated =
                migrated.copy(
                    settings =
                        migrated.settings.copy(
                            gameRoot =
                                Path.of(migrated.settings.gameRoot).resolve("minecraft").toString(),
                            gameRootSemantics = 1,
                        )
                )
        }
        fun relocated(path: String): String {
            if (path.isBlank()) return path
            val original =
                runCatching { Path.of(path).toAbsolutePath().normalize() }.getOrNull()
                    ?: return path
            if (!original.startsWith(directories.root)) return path
            val replacement =
                relocations[directories.root.relativize(original).joinToString("/")] ?: return path
            return safePath(directories.root, replacement).takeIf { it.isRegularFile() }?.toString()
                ?: path
        }
        migrated =
            migrated.copy(
                settings =
                    migrated.settings.copy(
                        backgroundPath = relocated(migrated.settings.backgroundPath)
                    ),
                instances = migrated.instances.map { it.copy(packFile = relocated(it.packFile)) },
            )
        directories.cache().initialize()
        if (migrated.settings.downloadCachePath.isNotBlank())
            directories.cache(migrated.settings.downloadCachePath).initialize()
        if (migrated != loaded || !config.exists() || recoveryMessage != null)
            writeJson(config, migrated)
        mutableState = MutableStateFlow(LauncherState(migrated))
        recoveryMessage?.let { log(it, true) }
    }

    private fun readData(path: Path): LauncherData {
        val json = readObject(path)
        require(
            json.int("schemaVersion") == 1 &&
                json.has("accounts") &&
                json.has("instances") &&
                json.has("settings")
        ) {
            "配置格式无效"
        }
        val value = gson.fromJson(json, LauncherData::class.java)
        require(value.settings.gameRoot.isNotBlank() && Path.of(value.settings.gameRoot).isAbsolute)
        value.instances.forEach { require(runCatching { UUID.fromString(it.id) }.isSuccess) }
        return value
    }

    @Synchronized
    fun mutate(block: (LauncherData) -> LauncherData) {
        val next = block(data)
        if (config.isRegularFile()) Files.copy(config, backup, StandardCopyOption.REPLACE_EXISTING)
        writeJson(config, next)
        mutableState.value = mutableState.value.copy(data = next)
    }

    @Synchronized
    internal fun updateState(block: (LauncherState) -> LauncherState) {
        mutableState.value = block(mutableState.value)
    }

    fun updateInstance(id: String, block: (GameInstance) -> GameInstance) = mutate { current ->
        require(current.instances.any { it.id == id }) { "找不到游戏实例" }
        current.copy(instances = current.instances.map { if (it.id == id) block(it) else it })
    }

    fun instance(id: String): GameInstance = data.instances.find { it.id == id } ?: error("找不到游戏实例")

    fun instancePath(id: String): Path = gamePath(instance(id))

    fun gamePath(instance: GameInstance): Path {
        require(runCatching { UUID.fromString(instance.id) }.isSuccess) { "实例标识无效" }
        if (!isolated(instance)) return minecraftRoot(instance)
        if (
            instance.gameDirectoryId != null &&
                instance.managedGameDirectory &&
                !instance.externalProfile
        )
            return safePath(minecraftRoot(instance).resolve("instances"), instance.id)
        if (instance.gameDirectoryId != null)
            return safePath(
                minecraftRoot(instance).resolve("versions"),
                safeFilename(instance.versionId),
            )
        return ownedInstancePath(instance.id)
    }

    /** Only this directory belongs exclusively to an internally created instance. */
    fun ownedInstancePath(id: String): Path {
        require(runCatching { UUID.fromString(id) }.isSuccess) { "实例标识无效" }
        data.instances
            .find { it.id == id }
            ?.takeIf {
                it.managedGameDirectory && it.gameDirectoryId != null && !it.externalProfile
            }
            ?.let {
                return safePath(minecraftRoot(it).resolve("instances"), id)
            }
        return safePath(Path.of(data.settings.gameRoot).resolve("instances"), id)
    }

    @Synchronized
    fun log(message: String, error: Boolean = false, game: Boolean = false) {
        val line =
            LogLine(message = redactSecrets(message).take(8000), isError = error, isGame = game)
        mutableState.value =
            mutableState.value.copy(logs = (mutableState.value.logs + line).takeLast(600))
        runCatching {
            val file = directories.launcherLog
            if (file.exists() && file.fileSize() > 8 * 1024 * 1024)
                Files.move(
                    file,
                    directories.previousLog,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            Files.writeString(
                file,
                "${line.time} [${if (error) "ERROR" else if (game) "GAME" else "INFO"}] ${line.message}\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }

    private fun pruneDamagedConfigurations() {
        directories.configurationBackups
            .listDirectoryEntries("launcher.damaged-*.json")
            .sortedByDescending { it.name }
            .drop(3)
            .forEach { Files.deleteIfExists(it) }
    }

    fun notify(message: String) {
        updateState { it.copy(toast = message) }
    }

    fun dismissNotification() {
        updateState { it.copy(toast = null) }
    }
}

/**
 * 默认游戏根：启动器目录旁的 `.minecraft`。若这一层本身就是 `.minecraft`（启动器装在游戏里）， 或已经是 versions/libraries/assets
 * 齐全的游戏根，就直接用这一层，避免再套一层不存在的目录。
 */
private fun defaultGameRoot(launcherDirectory: Path): Path =
    when {
        launcherDirectory.fileName?.toString().equals(".minecraft", ignoreCase = true) ->
            launcherDirectory
        listOf("versions", "libraries", "assets").all {
            launcherDirectory.resolve(it).isDirectory()
        } -> launcherDirectory
        else -> launcherDirectory.resolve(".minecraft")
    }
