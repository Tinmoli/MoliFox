// 陶瓦联机后端：负责读取配置、下载核心与对外暴露房间命令；状态与进程细节在协作类里。
package dev.molifox.multiplayer.data

import com.google.gson.JsonParser
import dev.molifox.multiplayer.common.CoreStatus
import dev.molifox.multiplayer.common.MultiplayerPhase
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.common.MultiplayerState
import dev.molifox.multiplayer.common.normalized
import dev.molifox.multiplayer.domain.MultiplayerBackend
import dev.molifox.multiplayer.platform.TerracottaCore
import dev.molifox.multiplayer.platform.TerracottaProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Path

/**
 * 基于陶瓦（Terracotta）核心的联机实现。
 *
 * - 核心只在需要时启动：首次创建/加入房间前若未安装，先下载安装包并校验。
 * - 房间状态全部来自本机控制接口 `/state`：房主拿到邀请码，房客拿到备用联机地址。
 * - 轮询发现核心上报 `exception` 时立即结束当前房间；房主退出存档后核心会报 `ping_server_rst`，
 *   这里据此关闭房间并提示“存档已退出，房间已关闭”。
 */
class JvmTerracottaBackend(
    private val settingsRepository: MultiplayerSettingsRepository,
    private val logger: (message: String, error: Boolean) -> Unit,
    playerName: () -> String,
    nodeSource: () -> List<String> = TerracottaNodes::fetch,
    private val installer: TerracottaInstaller,
    coreFactory: (Path) -> TerracottaCore = { executable -> TerracottaProcess(executable, logger) },
) : MultiplayerBackend {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val room = MultiplayerRoomState(logger)
    private val runner =
        TerracottaCoreRunner(scope, logger, room, { installer.inspect() }, coreFactory)
    private val mutableSettings = MutableStateFlow(MultiplayerSettings())
    private val commandMutex = Mutex()
    private var acceptedTerms: String = ""
    private val operations =
        TerracottaRoomOperations(
            installer = installer,
            runner = runner,
            room = room,
            settings = { mutableSettings.value },
            acceptedTerms = { acceptedTerms },
            playerName = playerName,
            nodeSource = nodeSource,
        )

    override val state: StateFlow<MultiplayerState> = room.flow
    override val settings: StateFlow<MultiplayerSettings> = mutableSettings.asStateFlow()

    override suspend fun initialize(): Result<Unit> =
        runCatching {
            val loaded = settingsRepository.load()
            mutableSettings.value = loaded
            acceptedTerms = loaded.acceptedTermsRevision
            val status = withContext(Dispatchers.IO) { installer.inspect() }
            room.update { it.copy(core = status) }
            if (loaded.enabled && status.installed) operations.ensureCore()
        }

    override suspend fun inspectCore(): Result<CoreStatus> =
        runCatching {
            withContext(Dispatchers.IO) { installer.inspect() }.also { status ->
                room.update { it.copy(core = status) }
            }
        }

    override suspend fun prepareCore(): Result<Unit> =
        runCatching {
            check(mutableSettings.value.enabled) { "联机功能已关闭，请先在联机设置中开启" }
            room.update {
                it.copy(
                    phase = MultiplayerPhase.Downloading,
                    progress = null,
                    error = null,
                    fatality = null,
                )
            }
            val status =
                withContext(Dispatchers.IO) {
                    installer
                        .install { progress ->
                            room.update { it.copy(progress = progress) }
                        }.getOrThrow()
                }
            room.update { it.copy(core = status, progress = null) }
            operations.ensureCore()
        }.onFailure { room.putFailure(it, MultiplayerPhase.Failed) }

    override suspend fun host(): Result<Unit> =
        runCatching { commandMutex.withLock { operations.host() } }
            .onFailure { room.reportFailure(it) }

    override suspend fun join(roomCode: String): Result<Unit> =
        runCatching { commandMutex.withLock { operations.join(roomCode) } }
            .onFailure { room.reportFailure(it) }

    override suspend fun leave() {
        commandMutex.withLock { operations.leave() }
    }

    override suspend fun reset() = leave()

    override suspend fun saveSettings(settings: MultiplayerSettings): Result<Unit> =
        runCatching {
            val normalized = settings.normalized()
            settingsRepository.save(normalized)
            mutableSettings.value = normalized
            acceptedTerms = normalized.acceptedTermsRevision
        }

    override suspend fun close() {
        runner.stop()
        scope.cancel()
    }
}

/** 陶瓦官方节点清单；失败返回空列表，核心会用内置节点。 */
internal object TerracottaNodes {
    private const val NODE_LIST_URL = "https://terracotta.glavo.site/nodes"
    private const val TIMEOUT_MILLIS = 8_000
    private const val HTTPS_PREFIX = "https://"

    @Volatile
    private var cached: List<String>? = null

    fun fetch(): List<String> {
        cached?.let { return it }
        val fetched =
            runCatching {
                val connection = URI(NODE_LIST_URL).toURL().openConnection() as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MILLIS
                connection.readTimeout = TIMEOUT_MILLIS
                connection.setRequestProperty("User-Agent", "MoliFox-Launcher")
                try {
                    if (connection.responseCode !in 200..299) emptyList() else readNodes(connection)
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(emptyList())
        if (fetched.isNotEmpty()) cached = fetched
        return fetched
    }

    private fun readNodes(connection: HttpURLConnection): List<String> {
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        return JsonParser.parseString(text).asJsonArray.mapNotNull { element ->
            runCatching {
                element.asJsonObject
                    .get("url")
                    ?.asString
                    ?.takeIf { it.startsWith(HTTPS_PREFIX) }
            }.getOrNull()
        }
    }
}
