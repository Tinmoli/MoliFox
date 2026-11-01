// 校验 easytier-cli 门面拼装的命令、退出码判断与 peer 解析。
package dev.molifox.multiplayer.easytier

import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EasyTierCliTest {
    @Test
    fun `peers uses rpc portal and json output`() {
        val runner = RecordingRunner(EasyTierCommandOutput(0, PEERS_JSON))
        val cli = EasyTierCli(Path.of(CLI_PATH), RPC_PORT, runner = runner)

        val peers = cli.peers().getOrThrow()

        assertEquals(
            listOf("--rpc-portal", "127.0.0.1:$RPC_PORT", "-o", "json", "peer"),
            runner.commands.single(),
        )
        assertEquals("MoliFox-server-45678", peers.single().hostname)
    }

    @Test
    fun `peers fails on non zero exit or unparseable output`() {
        val failedExit =
            EasyTierCli(
                Path.of(CLI_PATH),
                RPC_PORT,
                runner = RecordingRunner(EasyTierCommandOutput(1, "")),
            )
        assertTrue(failedExit.peers().isFailure)

        val garbage =
            EasyTierCli(
                Path.of(CLI_PATH),
                RPC_PORT,
                runner = RecordingRunner(EasyTierCommandOutput(0, "no json")),
            )
        assertTrue(garbage.peers().isFailure)
    }

    @Test
    fun `adds tcp and udp port forwards through rpc portal`() {
        val runner = RecordingRunner(EasyTierCommandOutput(0, ""))
        val cli = EasyTierCli(Path.of(CLI_PATH), RPC_PORT, runner = runner)

        assertTrue(
            cli.addPortForward(EasyTierForwardProtocol.TCP, 30_001, HOST_IP, 45_678).isSuccess,
        )
        assertTrue(
            cli.addPortForward(EasyTierForwardProtocol.UDP, 30_001, HOST_IP, 25_565).isSuccess,
        )

        assertEquals(TCP_FORWARD_COMMAND, runner.commands[0])
        assertEquals(UDP_FORWARD_COMMAND, runner.commands[1])
    }

    @Test
    fun `port forward reports failure on non zero exit`() {
        val runner = RecordingRunner(EasyTierCommandOutput(2, "bad address"))
        val cli = EasyTierCli(Path.of(CLI_PATH), RPC_PORT, runner = runner)

        val result = cli.addPortForward(EasyTierForwardProtocol.TCP, 30_001, HOST_IP, 45_678)

        assertTrue(result.isFailure)
        assertTrue(
            result
                .exceptionOrNull()
                ?.message
                .orEmpty()
                .contains("端口转发"),
        )
    }

    @Test
    fun `runner failure is returned as failure`() {
        val runner = EasyTierCommandRunner { _, _ -> Result.failure(IOException("无法运行")) }
        val cli = EasyTierCli(Path.of(CLI_PATH), RPC_PORT, runner = runner)

        assertTrue(cli.peers().isFailure)
    }

    private class RecordingRunner(
        private val result: EasyTierCommandOutput,
    ) : EasyTierCommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(
            command: List<String>,
            timeoutMillis: Long,
        ): Result<EasyTierCommandOutput> {
            commands += command.drop(1)
            return Result.success(result)
        }
    }

    private companion object {
        const val CLI_PATH = "C:/MoliFox/runtimes/easytier/2.6.4/windows-x86_64/easytier-cli.exe"
        const val RPC_PORT = 54_321
        const val HOST_IP = "10.114.51.41"
        const val PEERS_JSON =
            """[{"hostname":"MoliFox-server-45678","ipv4":"10.114.51.41"}]"""
        val TCP_FORWARD_COMMAND =
            listOf(
                "--rpc-portal",
                "127.0.0.1:$RPC_PORT",
                "port-forward",
                "add",
                "tcp",
                "127.0.0.1:30001",
                "$HOST_IP:45678",
            )
        val UDP_FORWARD_COMMAND =
            listOf(
                "--rpc-portal",
                "127.0.0.1:$RPC_PORT",
                "port-forward",
                "add",
                "udp",
                "127.0.0.1:30001",
                "$HOST_IP:25565",
            )
    }
}
