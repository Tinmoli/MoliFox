// 为联机页面点击验收提供内存后端，不访问外部服务、进程或玩家数据。
package dev.molifox.desktop.multiplayer

import dev.molifox.multiplayer.common.CoreStatus
import dev.molifox.multiplayer.common.LanWorld
import dev.molifox.multiplayer.common.LobbyPhase
import dev.molifox.multiplayer.common.LobbyRole
import dev.molifox.multiplayer.common.MultiplayerPolicy
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.common.MultiplayerState
import dev.molifox.multiplayer.common.NatDiagnostics
import dev.molifox.multiplayer.common.NatType
import dev.molifox.multiplayer.common.PlayerKind
import dev.molifox.multiplayer.common.RoomPlayer
import dev.molifox.multiplayer.domain.MultiplayerBackend
import dev.molifox.multiplayer.protocol.RoomCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

internal const val FIXTURE_ROOM_CODE = "U/YNZE-U61D-2206-HXRG"
internal const val FIXTURE_GUEST_ADDRESS = "127.0.0.1:25565"
internal const val FIXTURE_FIRST_WORLD_PORT = 25565

private const val FIXTURE_SECOND_WORLD_PORT = 25566
private const val FIXTURE_RUNTIME_BYTES = 31_000_000L
private const val FIXTURE_STEP_DELAY_MILLIS = 80L
private const val FIXTURE_DOWNLOAD_DELAY_MILLIS = 150L
private const val FIXTURE_DOWNLOAD_HALF = 0.5f

internal class MultiplayerUiFixture(
    private val root: Path,
) {
    val downloads = AtomicInteger()

    /** 网络诊断调用次数，用于确认防抖没有重复触发。 */
    val natTests = AtomicInteger()

    /** 置为 true 时下一次 prepareRuntime 只模拟失败，用于验收“失败后重试下载”入口。 */
    var failNextDownload = false

    /** 记录界面导入的本地 zip 路径，验证“从本地 zip 安装”入口把路径交给了后端。 */
    var lastImportedArchive: String? = null

    /** 可配置的本地导入结果；默认成功，需要验证失败提示时改成 failure。 */
    var importResult: Result<Unit> = Result.success(Unit)

    /** 模拟下载耗时；调大后界面会停在下载阶段，便于验收「取消下载」按钮。 */
    var downloadDelayMillis: Long = FIXTURE_DOWNLOAD_DELAY_MILLIS

    /** 记录界面请求取消运行时安装的次数，验证「取消下载」确实调用 backend.cancelRuntimeInstall。 */
    val cancelInstallCalls = AtomicInteger()

    /** 当前挂起的模拟安装；cancelRuntimeInstall 用它结束安装，模拟后端取消后台任务。 */
    private var pendingInstall: CompletableDeferred<Unit>? = null

    /** 记录界面传给 host 的世界端口，验证房主确实按选中的世界建房。 */
    var hostedPort: Int? = null
    private var installed = false
    private val worlds =
        listOf(
            LanWorld(name = "茉狐的小屋", version = "1.21.4", port = FIXTURE_FIRST_WORLD_PORT),
            LanWorld(name = "第二存档", version = "1.21.4", port = FIXTURE_SECOND_WORLD_PORT),
        )
    private val mutableState = MutableStateFlow(MultiplayerState(runtime = runtimeStatus(false)))
    private val mutableSettings = MutableStateFlow(MultiplayerSettings())

    val backend: MultiplayerBackend =
        // 函数数量由 MultiplayerBackend 接口决定，匿名实现无法再拆分。
        @Suppress("TooManyFunctions")
        object : MultiplayerBackend {
            override val state: StateFlow<MultiplayerState> = mutableState.asStateFlow()
            override val settings: StateFlow<MultiplayerSettings> = mutableSettings.asStateFlow()

            override suspend fun initialize(): Result<Unit> {
                // 真实后端初始化只读取配置并探测运行时，局域网世界由扫描器随后异步发现。
                mutableState.update {
                    it.copy(phase = LobbyPhase.Idle, runtime = runtimeStatus(installed))
                }
                return Result.success(Unit)
            }

            override suspend fun inspectRuntime(): Result<CoreStatus> {
                val status = runtimeStatus(installed)
                mutableState.update { it.copy(runtime = status) }
                return Result.success(status)
            }

            override suspend fun prepareRuntime(): Result<Unit> {
                downloads.incrementAndGet()
                if (failNextDownload) {
                    failNextDownload = false
                    val failure = IllegalStateException("模拟 EasyTier 下载失败")
                    // 与真实后端一致：整个安装失败后回到大厅并留下可读错误，由界面提供“重试下载”。
                    mutableState.update {
                        it.copy(
                            phase = LobbyPhase.Idle,
                            progress = null,
                            error = "下载 EasyTier 失败：${failure.message}",
                        )
                    }
                    return Result.failure(failure)
                }
                mutableState.update {
                    it.copy(
                        phase = LobbyPhase.DownloadingRuntime,
                        progress = FIXTURE_DOWNLOAD_HALF,
                        error = null,
                    )
                }
                awaitSimulatedInstall()
                installed = true
                mutableState.update {
                    it.copy(
                        phase = LobbyPhase.Idle,
                        progress = null,
                        runtime = runtimeStatus(true),
                    )
                }
                return Result.success(Unit)
            }

            override fun cancelRuntimeInstall() {
                cancelInstallCalls.incrementAndGet()
                pendingInstall?.complete(Unit)
            }

            override suspend fun refreshWorlds(): Result<Unit> {
                mutableState.update {
                    it.copy(phase = LobbyPhase.DiscoveringWorld, error = null)
                }
                delay(FIXTURE_STEP_DELAY_MILLIS)
                mutableState.update { it.copy(phase = LobbyPhase.Idle, worlds = worlds) }
                return Result.success(Unit)
            }

            override suspend fun host(worldPort: Int?): Result<Unit> {
                hostedPort = worldPort
                mutableState.update {
                    it.copy(
                        phase = LobbyPhase.CreatingRoom,
                        role = LobbyRole.Host,
                        error = null,
                    )
                }
                delay(FIXTURE_STEP_DELAY_MILLIS)
                mutableState.update {
                    it.copy(
                        phase = LobbyPhase.RoomOpen,
                        roomCode = FIXTURE_ROOM_CODE,
                        players = listOf(RoomPlayer("host", "茉狐房主", PlayerKind.HOST)),
                    )
                }
                return Result.success(Unit)
            }

            override suspend fun join(roomCode: String): Result<Unit> {
                if (RoomCode.parse(roomCode) == null) {
                    return Result.failure(IllegalArgumentException("邀请码格式不正确"))
                }
                mutableState.update {
                    it.copy(
                        phase = LobbyPhase.JoiningRoom,
                        role = LobbyRole.Guest,
                        error = null,
                    )
                }
                delay(FIXTURE_STEP_DELAY_MILLIS)
                mutableState.update {
                    it.copy(
                        phase = LobbyPhase.Connected,
                        localAddress = FIXTURE_GUEST_ADDRESS,
                        players = listOf(RoomPlayer("guest", "联机房客", PlayerKind.LOCAL)),
                    )
                }
                return Result.success(Unit)
            }

            override suspend fun leave() {
                mutableState.update { it.idleAfterLeave() }
            }

            override suspend fun saveSettings(settings: MultiplayerSettings): Result<Unit> {
                mutableSettings.value = settings
                return Result.success(Unit)
            }

            override suspend fun importRuntime(archivePath: String): Result<Unit> {
                lastImportedArchive = archivePath
                // 与真实后端一致：导入期间处于下载阶段（busy），结束后回大厅并更新运行时。
                mutableState.update {
                    it.copy(phase = LobbyPhase.DownloadingRuntime, progress = null, error = null)
                }
                delay(FIXTURE_DOWNLOAD_DELAY_MILLIS)
                return importResult.fold(
                    onSuccess = {
                        installed = true
                        mutableState.update {
                            it.copy(
                                phase = LobbyPhase.Idle,
                                progress = null,
                                runtime = runtimeStatus(true),
                                error = null,
                            )
                        }
                        Result.success(Unit)
                    },
                    onFailure = { failure ->
                        mutableState.update {
                            it.copy(
                                phase = LobbyPhase.Idle,
                                progress = null,
                                error = "安装本地 EasyTier 安装包失败：${failure.message}",
                            )
                        }
                        Result.failure(failure)
                    },
                )
            }

            override suspend fun testNat(): Result<NatDiagnostics> {
                natTests.incrementAndGet()
                delay(FIXTURE_STEP_DELAY_MILLIS)
                return Result.success(
                    NatDiagnostics(
                        udpNatType = NatType.Symmetric,
                        tcpNatType = NatType.PortRestricted,
                        supportsIpv6 = false,
                        publicIps = emptyList(),
                    ),
                )
            }

            override suspend fun close() = Unit
        }

    /** 回到大厅但保留发现到的世界，方便继续点“创建房间”。 */
    private fun MultiplayerState.idleAfterLeave(): MultiplayerState =
        copy(
            phase = LobbyPhase.Idle,
            role = null,
            progress = null,
            roomCode = null,
            localAddress = null,
            players = emptyList(),
            error = null,
        )

    private fun runtimeStatus(present: Boolean): CoreStatus =
        CoreStatus(
            installed = present,
            version = MultiplayerPolicy.CORE_VERSION,
            platform = "windows-x86_64",
            downloadBytes = FIXTURE_RUNTIME_BYTES,
            directory =
                root
                    .resolve("runtimes/easytier/${MultiplayerPolicy.CORE_VERSION}/windows-x86_64")
                    .toString(),
        )

    /**
     * 模拟后台安装等待：到点算成功；cancelRuntimeInstall 触发取消时回大厅并按取消结束等待，
     * 与真实后端“安装跑在 backend 作用域、取消后 await 抛 CancellationException”一致。
     */
    private suspend fun awaitSimulatedInstall() {
        val cancellation = CompletableDeferred<Unit>()
        pendingInstall = cancellation
        try {
            withTimeoutOrNull(downloadDelayMillis) { cancellation.await() }
        } finally {
            if (pendingInstall === cancellation) pendingInstall = null
        }
        if (cancellation.isCompleted) {
            mutableState.update { it.copy(phase = LobbyPhase.Idle, progress = null) }
            throw CancellationException("模拟用户取消运行时安装")
        }
    }
}
