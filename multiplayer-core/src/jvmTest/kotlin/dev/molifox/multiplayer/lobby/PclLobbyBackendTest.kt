// 后端编排测试：初始化、世界发现、建房/加入、离开与会话异常回收。
package dev.molifox.multiplayer.lobby

import dev.molifox.multiplayer.common.LanWorld
import dev.molifox.multiplayer.common.LobbyPhase
import dev.molifox.multiplayer.common.LobbyRole
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.data.MultiplayerSettingsRepository
import dev.molifox.multiplayer.easytier.EasyTierCatalog
import dev.molifox.multiplayer.easytier.EasyTierInstaller
import dev.molifox.multiplayer.protocol.RoomCode
import dev.molifox.multiplayer.protocol.RoomCodeRandom
import dev.molifox.multiplayer.scaffolding.ScaffoldingPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PclLobbyBackendTest {
    private val code = RoomCode.generate(RoomCodeRandom { ByteArray(BYTES) { 5 } })

    @Test
    fun initializeStartsDiscoveryAndInspectsRuntime() =
        runBlocking {
            withHarness { harness ->
                harness.backend.initialize().getOrThrow()
                val state = harness.backend.state.value
                assertEquals(true, state.runtime?.installed)
                assertEquals(1, harness.discovery.created.size)
                assertEquals(1, harness.discovery.latest.starts)
            }
        }

    @Test
    fun refreshWorldsPublishesDiscoveredWorlds() =
        runBlocking {
            withHarness { harness ->
                harness.backend.initialize().getOrThrow()
                harness.backend.refreshWorlds()
                harness.discovery.latest.mutable.value = listOf(WORLD)
                awaitUntil { harness.backend.state.value.worlds == listOf(WORLD) }
                assertEquals(LobbyPhase.Idle, harness.backend.state.value.phase)
            }
        }

    @Test
    fun hostOpensRoomAndStopsDiscovery() =
        runBlocking {
            withHarness { harness ->
                harness.backend.initialize().getOrThrow()
                harness.discovery.latest.mutable.value = listOf(WORLD)
                awaitUntil {
                    harness.backend.state.value.worlds
                        .isNotEmpty()
                }
                val result = harness.backend.host(WORLD.port)
                assertTrue(result.isSuccess, "建房失败：${result.exceptionOrNull()}")
                val state = harness.backend.state.value
                assertEquals(LobbyPhase.RoomOpen, state.phase)
                assertEquals(LobbyRole.Host, state.role)
                assertTrue(
                    state.roomCode?.matches(ROOM_CODE_PATTERN.toRegex()) == true,
                    "邀请码：${state.roomCode}",
                )
                assertEquals(WORLD, harness.hostContext?.world)
                assertEquals(1, harness.discovery.latest.closes)
            }
        }

    @Test
    fun hostWithoutDiscoveredWorldFails() =
        runBlocking {
            withHarness { harness ->
                harness.backend.initialize().getOrThrow()
                val result = harness.backend.host(null)
                assertTrue(result.isFailure)
                val message =
                    harness.backend.state.value.error
                        .orEmpty()
                assertTrue(message.contains("局域网世界"), message)
            }
        }

    @Test
    fun joinConnectsGuestAndRejectsInvalidCode() =
        runBlocking {
            withHarness { harness ->
                harness.backend.initialize().getOrThrow()
                val invalid = harness.backend.join("not-a-code")
                assertTrue(invalid.isFailure)
                val result = harness.backend.join(code.code)
                assertTrue(result.isSuccess, "加入失败：${result.exceptionOrNull()}")
                val state = harness.backend.state.value
                assertEquals(LobbyPhase.Connected, state.phase)
                assertEquals(LobbyRole.Guest, state.role)
                assertEquals(GUEST_ADDRESS, state.localAddress)
                assertEquals(code.networkName, harness.guestContext?.code?.networkName)
            }
        }

    @Test
    fun leaveClosesRoomAndRestartsDiscovery() =
        runBlocking {
            withHarness { harness ->
                harness.backend.initialize().getOrThrow()
                harness.discovery.latest.mutable.value = listOf(WORLD)
                awaitUntil {
                    harness.backend.state.value.worlds
                        .isNotEmpty()
                }
                harness.backend.host(WORLD.port).getOrThrow()
                harness.backend.leave()
                val state = harness.backend.state.value
                assertEquals(LobbyPhase.Idle, state.phase)
                assertEquals(null, state.roomCode)
                assertEquals(1, harness.hostSessions.single().closes)
                assertEquals(1, harness.nodes.single().stops)
                assertTrue(harness.discovery.created.size >= 2)
            }
        }

    @Test
    fun hostSessionFailureReturnsToIdleWithReason() =
        runBlocking {
            withHarness { harness ->
                harness.backend.initialize().getOrThrow()
                harness.discovery.latest.mutable.value = listOf(WORLD)
                awaitUntil {
                    harness.backend.state.value.worlds
                        .isNotEmpty()
                }
                harness.backend.host(WORLD.port).getOrThrow()
                harness.hostContext?.onClosed?.invoke("存档已退出，房间已关闭。")
                awaitUntil {
                    val state = harness.backend.state.value
                    state.phase == LobbyPhase.Idle &&
                        state.error == "存档已退出，房间已关闭。"
                }
                assertEquals(1, harness.hostSessions.single().closes)
            }
        }

    @Test
    fun saveSettingsValidatesRelayAndPersists() =
        runBlocking {
            withHarness { harness ->
                harness.backend.initialize().getOrThrow()
                val invalid =
                    harness.backend.saveSettings(
                        MultiplayerSettings(relayServer = "public.easytier.top:11010"),
                    )
                assertTrue(invalid.isFailure)
                val valid =
                    harness.backend.saveSettings(
                        MultiplayerSettings(
                            nickname = " Bob ",
                            relayServer = "tcp://relay.example:11010",
                        ),
                    )
                assertTrue(valid.isSuccess)
                assertEquals("Bob", harness.repository.saved?.nickname)
                assertEquals("tcp://relay.example:11010", harness.repository.saved?.relayServer)
            }
        }

    private suspend fun withHarness(block: suspend (Harness) -> Unit) {
        val directory = Files.createTempDirectory(Path.of("..", ".work"), "lobby-backend-test")
        try {
            val harness = Harness(directory)
            block(harness)
            harness.backend.close()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private class Harness(
        directory: Path,
    ) {
        val repository = FakeSettingsRepository()
        val discovery = FakeDiscoveryFactory()
        val nodes = mutableListOf<FakeEasyTierNode>()
        val hostSessions = mutableListOf<FakeHostSession>()
        val guestSessions = mutableListOf<FakeGuestSession>()
        var hostContext: LobbyHostContext? = null
        var guestContext: LobbyGuestContext? = null

        private val installer = EasyTierInstaller(directory.resolve("runtimes"))
        private val installed = FakeRuntimeStatus.install(installer)

        val backend =
            PclLobbyBackend(
                settingsRepository = repository,
                installer = installer,
                machineIdStore = MachineIdStore(directory.resolve("config/machine-id")),
                logger = { _, _ -> },
                playerName = { "Account" },
                runtimeFactory = { _, _ ->
                    FakeEasyTierNode().also { nodes += it }
                },
                discoveryFactory = discovery.factory,
                hostSessionFactory =
                    LobbyHostSessionFactory { context ->
                        hostContext = context
                        FakeHostSession().also { hostSessions += it }
                    },
                guestSessionFactory =
                    LobbyGuestSessionFactory { context ->
                        guestContext = context
                        FakeGuestSession().also { guestSessions += it }
                    },
                probe = { true },
                scanCheckIntervalMillis = SCAN_INTERVAL_MILLIS,
            )

        init {
            check(installed) { "无法为测试准备 EasyTier 安装目录" }
        }
    }

    private class FakeSettingsRepository : MultiplayerSettingsRepository {
        var saved: MultiplayerSettings? = null

        override suspend fun load(): MultiplayerSettings = MultiplayerSettings()

        override suspend fun save(settings: MultiplayerSettings) {
            saved = settings
        }
    }

    private class FakeDiscovery : LobbyWorldDiscovery {
        val mutable = MutableStateFlow<List<LanWorld>>(emptyList())
        var starts = 0
        var closes = 0

        override val worlds: StateFlow<List<LanWorld>> = mutable

        override fun start(): Result<Unit> {
            starts++
            return Result.success(Unit)
        }

        override fun close() {
            closes++
        }
    }

    private class FakeDiscoveryFactory {
        val created = mutableListOf<FakeDiscovery>()
        val latest: FakeDiscovery get() = created.last()

        val factory =
            LanDiscoveryFactory { _ ->
                FakeDiscovery().also { created += it }
            }
    }

    private class FakeHostSession : LobbyHostSessionApi {
        private val mutablePlayers = MutableStateFlow<List<ScaffoldingPlayer>>(emptyList())
        var closes = 0

        override val players: StateFlow<List<ScaffoldingPlayer>> = mutablePlayers
        override val scaffoldingPort: Int = SCAFFOLDING_PORT

        override suspend fun start(): Result<Unit> {
            mutablePlayers.value = listOf(ScaffoldingPlayer("host-machine", "Alice"))
            return Result.success(Unit)
        }

        override fun close() {
            closes++
        }
    }

    private class FakeGuestSession : LobbyGuestSessionApi {
        private val mutablePlayers = MutableStateFlow<List<ScaffoldingPlayer>>(emptyList())
        var closes = 0

        override val players: StateFlow<List<ScaffoldingPlayer>> = mutablePlayers
        override val localAddress: String = GUEST_ADDRESS
        override val hostName: String = "Alice"

        override suspend fun start(): Result<Unit> = Result.success(Unit)

        override fun close() {
            closes++
        }
    }

    private companion object {
        const val BYTES = 16
        const val SCAFFOLDING_PORT = 25_565
        const val SCAN_INTERVAL_MILLIS = 50L
        const val GUEST_ADDRESS = "127.0.0.1:52341"
        const val ROOM_CODE_PATTERN = "U/[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}"
        val WORLD = LanWorld(name = "测试世界", version = "1.21", port = 25_565)
    }
}

/** 在当前平台安装目录写入占位文件，让 [EasyTierInstaller.inspect] 认为运行时已就绪。 */
private object FakeRuntimeStatus {
    fun install(installer: EasyTierInstaller): Boolean {
        val packageInfo = EasyTierCatalog.current() ?: return false
        val directory = installer.directory(packageInfo)
        Files.createDirectories(directory)
        packageInfo.requiredFiles.forEach { name ->
            Files.writeString(directory.resolve(name), "stub")
        }
        return installer.inspect(packageInfo).installed
    }
}
