// 联机后端：编排世界发现、房主/房客会话与 EasyTier 运行时，向上只暴露中性状态。
package dev.molifox.multiplayer.lobby

import dev.molifox.core.i18n.I18n
import dev.molifox.multiplayer.common.CoreStatus
import dev.molifox.multiplayer.common.LobbyPhase
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.common.MultiplayerState
import dev.molifox.multiplayer.common.NatDiagnostics
import dev.molifox.multiplayer.common.customRelays
import dev.molifox.multiplayer.common.normalized
import dev.molifox.multiplayer.data.MultiplayerSettingsRepository
import dev.molifox.multiplayer.domain.MultiplayerBackend
import dev.molifox.multiplayer.easytier.EasyTierInstaller
import dev.molifox.multiplayer.easytier.EasyTierNatTester
import dev.molifox.multiplayer.easytier.EasyTierNode
import dev.molifox.multiplayer.easytier.EasyTierRuntime
import dev.molifox.multiplayer.easytier.NatTester
import dev.molifox.multiplayer.protocol.RoomCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.Path

/**
 * PCL CE 等价联机后端。
 *
 * 一个实例同时只承载一个房间：房主或房客。所有会改变房间的操作在 [commandMutex]
 * 内串行执行；世界发现只在空闲阶段运行，建房/加入前先停掉，避免和房客的局域网
 * 广播抢占 4445 端口。
 *
 * 实现完整联机后端门面，方法数由接口与“后台安装/取消”编排决定；拆分类只会多一层转发。
 */
@Suppress("TooManyFunctions")
class PclLobbyBackend(
    private val settingsRepository: MultiplayerSettingsRepository,
    private val installer: EasyTierInstaller,
    private val machineIdStore: MachineIdStore,
    private val logger: (message: String, error: Boolean) -> Unit,
    private val playerName: () -> String,
    private val runtimeFactory: (Path, Path) -> EasyTierNode = { core, cli ->
        EasyTierRuntime(core, cli, logger)
    },
    discoveryFactory: LanDiscoveryFactory = LanDiscoveryFactory.DEFAULT,
    hostSessionFactory: LobbyHostSessionFactory = DefaultLobbyHostSessionFactory,
    guestSessionFactory: LobbyGuestSessionFactory = DefaultLobbyGuestSessionFactory,
    probe: MinecraftWorldProbe = TcpMinecraftWorldProbe(),
    private val installRunner: (suspend ((Float?) -> Unit) -> CoreStatus)? = null,
    private val importRunner: (suspend (String, (Float?) -> Unit) -> CoreStatus)? = null,
    private val natTester: NatTester = EasyTierNatTester(),
    scanCheckIntervalMillis: Long = DEFAULT_SCAN_CHECK_INTERVAL_MILLIS,
) : MultiplayerBackend {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    private val runtimeMutex = Mutex()
    private val installGuard = Any()
    private var installTask: Deferred<Result<Unit>>? = null
    private val store = LobbyStateStore()
    private val mutableSettings = MutableStateFlow(MultiplayerSettings())
    private var machineId = ""

    @Volatile
    private var closed = false

    /** 会话异常关闭（存档退出、房主断开、EasyTier 退出）：统一清理并回到空闲。 */
    private val onSessionClosed: (String) -> Unit = { message ->
        scope.launch {
            commandMutex.withLock {
                if (closed || store.current.role == null) return@withLock
                coordinator.teardown()
                store.update {
                    it.copy(
                        phase = LobbyPhase.Idle,
                        role = null,
                        roomCode = null,
                        localAddress = null,
                        players = emptyList(),
                        error = message,
                    )
                }
                scanner.sync(mutableSettings.value.enabled)
            }
        }
    }

    private val scanner =
        LobbyWorldScanner(
            factory = discoveryFactory,
            probe = probe,
            logger = logger,
            scanCheckIntervalMillis = scanCheckIntervalMillis,
            worldsProvider = { store.current.worlds },
            onDiscovered = { incoming ->
                store.update { current ->
                    current.copy(worlds = mergeWorlds(current.worlds, incoming))
                }
            },
            onPruned = { alive -> store.update { it.copy(worlds = alive) } },
        )
    private val coordinator =
        LobbyRoomCoordinator(
            installer = installer,
            runtimeFactory = runtimeFactory,
            hostFactory = hostSessionFactory,
            guestFactory = guestSessionFactory,
            logger = logger,
            probe = probe,
            scope = scope,
            store = store,
            machineId = { machineId },
            playerName = { displayName(mutableSettings.value, playerName()) },
            relays = { mutableSettings.value.customRelays() },
            tuning = { mutableSettings.value.toEasyTierTuning() },
            onSessionClosed = { message -> onSessionClosed(message) },
        )

    override val state: StateFlow<MultiplayerState> = store.state
    override val settings: StateFlow<MultiplayerSettings> = mutableSettings.asStateFlow()

    override suspend fun initialize(): Result<Unit> =
        runCatching {
            mutableSettings.value = settingsRepository.load()
            machineId = withContext(Dispatchers.IO) { machineIdStore.read() }
            val status = withContext(Dispatchers.IO) { installer.inspect() }
            store.update { it.copy(runtime = status) }
            scanner.sync(mutableSettings.value.enabled)
        }

    override suspend fun inspectRuntime(): Result<CoreStatus> =
        runCatching {
            val status = withContext(Dispatchers.IO) { installer.inspect() }
            store.update { it.copy(runtime = status) }
            status
        }

    override suspend fun testNat(): Result<NatDiagnostics> =
        withContext(Dispatchers.IO) { natTester.test(installer.cliExecutable()) }

    /** 下载/安装统一在后台作用域执行：切换页面不会中断，只有显式取消或关闭启动器才会停。 */
    override suspend fun prepareRuntime(): Result<Unit> =
        startRuntimeInstall(I18n.t("multiplayer.pclLobbyBackend.1")) { onProgress ->
            installRunner?.invoke(onProgress)
                ?: withContext(Dispatchers.IO) { installer.install(onProgress).getOrThrow() }
        }.await()

    override fun cancelRuntimeInstall() {
        synchronized(installGuard) { installTask?.cancel() }
    }

    private fun startRuntimeInstall(
        failurePrefix: String,
        install: suspend ((Float?) -> Unit) -> CoreStatus,
    ): Deferred<Result<Unit>> =
        synchronized(installGuard) {
            installTask?.takeIf { it.isActive }?.let { return it }
            val task =
                scope.async {
                    runtimeMutex.withLock {
                        performRuntimeInstall(
                            store = store,
                            scanner = scanner,
                            discoveryEnabled = mutableSettings.value.enabled,
                            failurePrefix = failurePrefix,
                            install = install,
                        )
                    }
                }
            installTask = task
            task.invokeOnCompletion {
                synchronized(installGuard) {
                    if (installTask === task) installTask = null
                }
            }
            task
        }

    override suspend fun refreshWorlds(): Result<Unit> =
        runCatching {
            if (store.current.connected || store.current.busy) {
                throw IOException(I18n.t("multiplayer.pclLobbyBackend.2"))
            }
            store.update {
                it.copy(
                    phase = LobbyPhase.DiscoveringWorld,
                    worlds = emptyList(),
                    error = null,
                )
            }
            scanner.start()
            withTimeoutOrNull(DISCOVERY_SETTLE_TIMEOUT_MILLIS) {
                store.state.first { it.worlds.isNotEmpty() }
            }
            store.update { it.copy(phase = LobbyPhase.Idle) }
        }

    override suspend fun host(worldPort: Int?): Result<Unit> =
        commandMutex.withLock {
            if (store.current.connected || store.current.busy) {
                return@withLock store.fail(I18n.t("multiplayer.pclLobbyBackend.3"))
            }
            if (store.current.runtime?.installed != true) {
                return@withLock store.fail(I18n.t("multiplayer.pclLobbyBackend.4"))
            }
            val world =
                resolveWorld(store.current.worlds, worldPort)
                    ?: return@withLock store.fail(worldSelectionMessage(worldPort))
            scanner.stop()
            coordinator.openRoom(world).also { result ->
                if (result.isFailure) scanner.sync(mutableSettings.value.enabled)
            }
        }

    override suspend fun join(roomCode: String): Result<Unit> =
        commandMutex.withLock {
            if (store.current.connected || store.current.busy) {
                return@withLock store.fail(I18n.t("multiplayer.pclLobbyBackend.3"))
            }
            val code =
                RoomCode.parse(roomCode)
                    ?: return@withLock store.fail(I18n.t("multiplayer.pclLobbyBackend.5"))
            if (store.current.runtime?.installed != true) {
                return@withLock store.fail(I18n.t("multiplayer.pclLobbyBackend.4"))
            }
            scanner.stop()
            coordinator.joinRoom(code).also { result ->
                if (result.isFailure) scanner.sync(mutableSettings.value.enabled)
            }
        }

    override suspend fun leave() {
        commandMutex.withLock {
            coordinator.teardown()
            store.update {
                it.copy(
                    phase = LobbyPhase.Idle,
                    role = null,
                    roomCode = null,
                    localAddress = null,
                    connectionRoute = null,
                    players = emptyList(),
                    error = null,
                    progress = null,
                )
            }
            scanner.sync(mutableSettings.value.enabled)
        }
    }

    override suspend fun importRuntime(archivePath: String): Result<Unit> =
        startRuntimeInstall(I18n.t("multiplayer.pclLobbyBackend.6")) { onProgress ->
            importRunner?.invoke(archivePath, onProgress)
                ?: withContext(Dispatchers.IO) {
                    installer.installFromArchive(Path.of(archivePath), onProgress).getOrThrow()
                }
        }.await()

    override suspend fun saveSettings(settings: MultiplayerSettings): Result<Unit> =
        runCatching {
            val normalized = settings.normalized()
            settingsRepository.save(normalized)
            mutableSettings.value = normalized
            when {
                store.current.connected || store.current.busy -> Unit
                else -> scanner.sync(normalized.enabled)
            }
        }

    override suspend fun close() {
        closed = true
        scanner.close()
        commandMutex.withLock { coordinator.close() }
        scope.cancel()
    }

    private companion object {
        const val DEFAULT_SCAN_CHECK_INTERVAL_MILLIS = 5_000L
        const val DISCOVERY_SETTLE_TIMEOUT_MILLIS = 2_500L
    }
}
