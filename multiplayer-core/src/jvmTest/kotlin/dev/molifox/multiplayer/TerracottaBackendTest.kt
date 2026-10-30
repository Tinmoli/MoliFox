// 用假核心驱动联机状态机：建房、加入、存档退出关房与下载失败。
package dev.molifox.multiplayer

import dev.molifox.multiplayer.common.CoreStatus
import dev.molifox.multiplayer.common.MultiplayerPhase
import dev.molifox.multiplayer.common.MultiplayerPolicy
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.common.PlayerKind
import dev.molifox.multiplayer.common.RoomPlayer
import dev.molifox.multiplayer.common.RoomRole
import dev.molifox.multiplayer.common.TerracottaFatality
import dev.molifox.multiplayer.common.TerracottaJoinFailure
import dev.molifox.multiplayer.data.JvmTerracottaBackend
import dev.molifox.multiplayer.data.MultiplayerSettingsRepository
import dev.molifox.multiplayer.data.TerracottaInstaller
import dev.molifox.multiplayer.data.TerracottaPackage
import dev.molifox.multiplayer.platform.RemoteProfile
import dev.molifox.multiplayer.platform.TerracottaControl
import dev.molifox.multiplayer.platform.TerracottaCore
import dev.molifox.multiplayer.platform.TerracottaSnapshot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerracottaBackendTest {
    @Test
    fun `host publishes room code and players when core reports host-ok`() =
        runBlocking {
            val control = FakeControl()
            withBackend(control) { backend ->
                backend.initialize().getOrThrow()
                awaitPhase(backend, MultiplayerPhase.Waiting)
                backend.host().getOrThrow()
                assertEquals(MultiplayerPhase.HostScanning, backend.state.value.phase)
                assertEquals("Tester", control.scans.single().first)
                control.current =
                    TerracottaSnapshot(
                        index = 2,
                        state = "host-ok",
                        room = "U/ROOM-CODE",
                        profiles = listOf(RemoteProfile("m1", "Tester", "HOST")),
                    )
                awaitPhase(backend, MultiplayerPhase.HostReady)
                assertEquals("U/ROOM-CODE", backend.state.value.roomCode)
                assertEquals(RoomRole.Host, backend.state.value.role)
                assertEquals(
                    listOf(RoomPlayer("m1", "Tester", PlayerKind.HOST)),
                    backend.state.value.players,
                )
            }
        }

    @Test
    fun `save exit closes room and reports ping server reset`() =
        runBlocking {
            val control = FakeControl()
            withBackend(control) { backend ->
                backend.initialize().getOrThrow()
                awaitPhase(backend, MultiplayerPhase.Waiting)
                backend.host().getOrThrow()
                control.current =
                    TerracottaSnapshot(
                        index = 2,
                        state = "host-ok",
                        room = "U/ROOM-CODE",
                        profiles = listOf(RemoteProfile("m1", "Tester", "HOST")),
                    )
                awaitPhase(backend, MultiplayerPhase.HostReady)
                control.current =
                    TerracottaSnapshot(index = 3, state = "exception", exceptionType = 4)
                awaitPhase(backend, MultiplayerPhase.Failed)
                assertEquals(TerracottaJoinFailure.PING_SERVER_RST, backend.state.value.failure)
                assertTrue(
                    backend.state.value.error
                        .orEmpty()
                        .contains("存档已退出"),
                )
            }
        }

    @Test
    fun `join reports guest address when core reports guest-ok`() =
        runBlocking {
            val control = FakeControl()
            withBackend(control) { backend ->
                backend.initialize().getOrThrow()
                awaitPhase(backend, MultiplayerPhase.Waiting)
                backend.join(" U/CODE ").getOrThrow()
                assertEquals(MultiplayerPhase.GuestConnecting, backend.state.value.phase)
                assertEquals("U/CODE", control.joins.single().first)
                control.current =
                    TerracottaSnapshot(
                        index = 3,
                        state = "guest-ok",
                        url = "127.0.0.1:25565",
                        profiles = listOf(RemoteProfile("m9", "Friend", "GUEST")),
                    )
                awaitPhase(backend, MultiplayerPhase.GuestReady)
                assertEquals(RoomRole.Guest, backend.state.value.role)
                assertEquals("127.0.0.1:25565", backend.state.value.joinUrl)
            }
        }

    @Test
    fun `leave returns to waiting and asks core for idle`() =
        runBlocking {
            val control = FakeControl()
            withBackend(control) { backend ->
                backend.initialize().getOrThrow()
                awaitPhase(backend, MultiplayerPhase.Waiting)
                backend.host().getOrThrow()
                control.current = TerracottaSnapshot(2, "host-ok", room = "U/ROOM")
                awaitPhase(backend, MultiplayerPhase.HostReady)
                backend.leave()
                awaitPhase(backend, MultiplayerPhase.Waiting)
                assertEquals(1, control.idleCount)
                assertNull(backend.state.value.roomCode)
            }
        }

    @Test
    fun `download failure is reported as network fatality`() =
        runBlocking {
            val control = FakeControl()
            val networkFailure = IOException("下载联机核心失败：网络不可用")
            withBackend(control, installed = false, installFailure = networkFailure) { backend ->
                backend.initialize().getOrThrow()
                val failure = backend.prepareCore().exceptionOrNull()
                assertNotNull(failure)
                assertEquals(MultiplayerPhase.Failed, backend.state.value.phase)
                assertEquals(TerracottaFatality.NETWORK, backend.state.value.fatality)
                assertTrue(
                    backend.state.value.error
                        .orEmpty()
                        .contains("网络不可用"),
                )
            }
        }

    @Test
    fun `settings are normalized and persisted`() =
        runBlocking {
            val control = FakeControl()
            val repository = MemorySettings()
            withBackend(control, repository = repository) { backend ->
                backend.initialize().getOrThrow()
                backend.saveSettings(backend.settings.value.copy(nickname = "  小狐  ")).getOrThrow()
                assertEquals("小狐", repository.value.nickname)
                assertEquals("小狐", backend.settings.value.nickname)
            }
        }

    @Test
    fun `host without installed core explains how to fix`() =
        runBlocking {
            val control = FakeControl()
            withBackend(control, installed = false) { backend ->
                backend.initialize().getOrThrow()
                backend.host()
                assertTrue(
                    backend.state.value.error
                        .orEmpty()
                        .contains("尚未安装"),
                )
                assertEquals(MultiplayerPhase.Idle, backend.state.value.phase)
            }
        }

    private suspend fun withBackend(
        control: FakeControl,
        installed: Boolean = true,
        installFailure: Throwable? = null,
        repository: MemorySettings = MemorySettings(),
        block: suspend (JvmTerracottaBackend) -> Unit,
    ) {
        val root = Files.createTempDirectory("terra-backend-")
        val backend =
            JvmTerracottaBackend(
                settingsRepository = repository,
                logger = { _, _ -> },
                playerName = { "Tester" },
                nodeSource = { listOf("https://node1") },
                installer = FakeInstaller(root, installed, installFailure),
                coreFactory = { FakeCore(control) },
            )
        try {
            block(backend)
        } finally {
            backend.close()
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun awaitPhase(
        backend: JvmTerracottaBackend,
        phase: MultiplayerPhase,
    ) {
        withTimeout(5_000) { backend.state.first { it.phase == phase } }
    }
}

private class FakeControl : TerracottaControl {
    @Volatile
    var current: TerracottaSnapshot = TerracottaSnapshot(0, "waiting")

    val scans = mutableListOf<Pair<String, List<String>>>()
    val joins = mutableListOf<Pair<String, Pair<String, List<String>>>>()

    @Volatile
    var idleCount: Int = 0

    override fun snapshot(): TerracottaSnapshot = current

    override fun startScanning(
        player: String,
        nodes: List<String>,
    ) {
        scans += player to nodes
    }

    override fun startGuesting(
        room: String,
        player: String,
        nodes: List<String>,
    ) {
        joins += room to (player to nodes)
    }

    override fun idle() {
        idleCount++
    }
}

private class FakeCore(
    private val control: FakeControl,
) : TerracottaCore {
    override val api: TerracottaControl get() = control

    @Volatile
    private var alive = false

    override fun start(): TerracottaControl {
        alive = true
        return control
    }

    override fun isAlive(): Boolean = alive

    override fun stop() {
        alive = false
    }

    override fun recentOutput(): String = "fake core output"
}

private class FakeInstaller(
    root: Path,
    private val installed: Boolean,
    private val failure: Throwable?,
) : TerracottaInstaller(runtimesRoot = root) {
    override fun inspect(): CoreStatus =
        CoreStatus(
            installed = installed,
            version = MultiplayerPolicy.CORE_VERSION,
            platform = "windows-x86_64",
            downloadBytes = 8_135_914L,
            directory = runtimesRoot.toString(),
        )

    override fun packageInfo(): TerracottaPackage =
        TerracottaPackage(
            version = MultiplayerPolicy.CORE_VERSION,
            classifier = "windows-x86_64",
            archiveSha512 = "0".repeat(128),
            downloadBytes = 8_135_914L,
            files = mapOf("fake-core" to "0".repeat(128)),
        )

    override fun executable(pkg: TerracottaPackage): Path = runtimesRoot.resolve("fake-core")

    override suspend fun install(onProgress: (Float?) -> Unit): Result<CoreStatus> =
        failure?.let { Result.failure(it) } ?: Result.success(inspect())
}

private class MemorySettings(
    @Volatile var value: MultiplayerSettings =
        MultiplayerSettings(acceptedTermsRevision = MultiplayerPolicy.TERMS_REVISION),
) : MultiplayerSettingsRepository {
    override suspend fun load(): MultiplayerSettings = value

    override suspend fun save(settings: MultiplayerSettings) {
        value = settings
    }
}
