// 校验 EasyTier 运行时：端口分配、启动、等 CLI、退出回调、失败清理与幂等停止。
package dev.molifox.multiplayer.easytier

import dev.molifox.multiplayer.protocol.ScaffoldingCode
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EasyTierRuntimeTest {
    @Test
    fun `starts node waits for cli and stops once`() =
        runBlocking<Unit> {
            val handle = FakeProcessHandle()
            val launcher = FakeLauncher(handle)
            val runner = ScriptedRunner(failure = 1, output = PEERS_JSON)
            val runtime = runtime(launcher, runner)

            val cli = runtime.start(HOST_REQUEST) { _, _ -> }.getOrThrow()

            assertTrue(runtime.running)
            assertEquals(RPC_PORT, runtime.currentRpcPort)
            assertNotNull(runtime.cli)
            assertEquals(2, runner.calls)
            assertTrue(
                launcher.commands.single().containsAll(
                    listOf("--rpc-portal", "127.0.0.1:$RPC_PORT"),
                ),
            )
            assertTrue(cli.peers().isSuccess)

            runtime.stop()
            runtime.stop()
            assertEquals(1, handle.stopCalls)
            assertFalse(runtime.running)
            assertEquals(null, runtime.currentRpcPort)
        }

    @Test
    fun `fails start when process exits before cli is ready`() =
        runBlocking<Unit> {
            val handle = FakeProcessHandle(alive = false)
            val runtime =
                runtime(FakeLauncher(handle), ScriptedRunner(failure = 0, output = PEERS_JSON))

            val result = runtime.start(HOST_REQUEST) { _, _ -> }

            assertTrue(result.isFailure)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("立即退出"),
            )
            assertEquals(1, handle.stopCalls)
        }

    @Test
    fun `fails start when cli never becomes available`() =
        runBlocking<Unit> {
            val handle = FakeProcessHandle()
            val runtime =
                runtime(
                    launcher = FakeLauncher(handle),
                    runner = ScriptedRunner(failure = Int.MAX_VALUE, output = ""),
                    timeoutMillis = 60,
                    intervalMillis = 10,
                )

            val result = runtime.start(HOST_REQUEST) { _, _ -> }

            assertTrue(result.isFailure)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("不可用"),
            )
            assertEquals(1, handle.stopCalls)
            assertEquals(null, runtime.currentRpcPort)
        }

    @Test
    fun `reports unexpected exit only while running`() =
        runBlocking<Unit> {
            val launcher = FakeLauncher(FakeProcessHandle())
            val runtime = runtime(launcher, ScriptedRunner(failure = 0, output = PEERS_JSON))
            val exits = mutableListOf<Int>()
            runtime.start(HOST_REQUEST) { code, _ -> exits += code }.getOrThrow()

            launcher.exitWith(3)
            assertEquals(listOf(3), exits)

            runtime.stop()
            launcher.exitWith(0)
            assertEquals(listOf(3), exits)
        }

    @Test
    fun `launcher failure and invalid request leave no rpc port`() =
        runBlocking<Unit> {
            val failingLauncher =
                EasyTierProcessLauncher {
                    _,
                    _,
                    _,
                    _,
                    ->
                    Result.failure(IOException("启动失败"))
                }
            val first = runtime(failingLauncher, ScriptedRunner(failure = 0, output = PEERS_JSON))
            assertTrue(first.start(HOST_REQUEST) { _, _ -> }.isFailure)
            assertEquals(null, first.currentRpcPort)

            val second =
                runtime(
                    FakeLauncher(FakeProcessHandle()),
                    ScriptedRunner(failure = 0, output = PEERS_JSON),
                )
            assertTrue(second.start(HOST_REQUEST.copy(networkName = " ")) { _, _ -> }.isFailure)
            assertEquals(null, second.currentRpcPort)
        }

    @Test
    fun `refuses to start twice on the same runtime`() =
        runBlocking<Unit> {
            val runtime =
                runtime(
                    FakeLauncher(FakeProcessHandle()),
                    ScriptedRunner(failure = 0, output = PEERS_JSON),
                )
            runtime.start(HOST_REQUEST) { _, _ -> }.getOrThrow()

            assertTrue(runtime.start(HOST_REQUEST) { _, _ -> }.isFailure)
            runtime.stop()
        }

    private fun runtime(
        launcher: EasyTierProcessLauncher,
        runner: EasyTierCommandRunner,
        timeoutMillis: Long = 2_000,
        intervalMillis: Long = 10,
    ): EasyTierRuntime =
        EasyTierRuntime(
            coreExecutable = Path.of("easytier-core.exe"),
            cliExecutable = Path.of("easytier-cli.exe"),
            logger = { _, _ -> },
            launcher = launcher,
            commandRunner = runner,
            portAllocator = LoopbackPortAllocator { RPC_PORT },
            cliReadyTimeoutMillis = timeoutMillis,
            cliReadyIntervalMillis = intervalMillis,
        )

    private class FakeProcessHandle(
        alive: Boolean = true,
    ) : EasyTierProcessHandle {
        @Volatile
        private var aliveState = alive
        var stopCalls = 0
            private set

        override val alive: Boolean
            get() = aliveState

        override fun stop() {
            stopCalls++
            aliveState = false
        }
    }

    private class FakeLauncher(
        private val handle: FakeProcessHandle,
    ) : EasyTierProcessLauncher {
        val commands = mutableListOf<List<String>>()

        private var exit: ((Int) -> Unit)? = null

        override fun launch(
            command: List<String>,
            workingDirectory: Path,
            onOutput: (String) -> Unit,
            onExit: (Int) -> Unit,
        ): Result<EasyTierProcessHandle> {
            commands += command
            exit = onExit
            return Result.success(handle)
        }

        fun exitWith(code: Int) {
            exit?.invoke(code)
        }
    }

    /** 前 [failure] 次调用失败，之后返回固定 peer JSON。 */
    private class ScriptedRunner(
        private val failure: Int,
        private val output: String,
    ) : EasyTierCommandRunner {
        var calls = 0
            private set

        override fun run(
            command: List<String>,
            timeoutMillis: Long,
        ): Result<EasyTierCommandOutput> {
            calls++
            return if (calls <= failure) {
                Result.failure(IOException("not ready"))
            } else {
                Result.success(EasyTierCommandOutput(0, output))
            }
        }
    }

    private companion object {
        const val RPC_PORT = 54_321
        const val PEERS_JSON =
            """[{"hostname":"MoliFox-server-45678","ipv4":"10.114.51.41"}]"""
        val HOST_REQUEST =
            EasyTierNodeRequest.host(
                code =
                    ScaffoldingCode(
                        code = "U/YNZE-U61D-2206-HXRG",
                        networkName = "MoliFox-YNZE-U61D",
                        networkSecret = "2206-HXRG",
                    ),
                machineId = "machine-0001",
                scaffoldingPort = 45_678,
                minecraftPort = 25_565,
            )
    }
}
