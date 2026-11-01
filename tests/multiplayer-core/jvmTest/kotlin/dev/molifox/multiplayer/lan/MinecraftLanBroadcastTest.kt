// LAN 广播测试：临时回环接收端断言报文内容、stop 后停发与重复 start/stop。
package dev.molifox.multiplayer.lan

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MinecraftLanBroadcastTest {
    @Test
    fun `sends lan packet to loopback target`() {
        openLanReceiver().use { receiver ->
            val broadcast = newBroadcast(receiver.localPort)
            try {
                broadcast.start().getOrThrow()
                assertEquals(
                    "[MOTD]MoliFox 世界[/MOTD][AD]25566[/AD]",
                    receiveLanText(receiver),
                )
            } finally {
                broadcast.close()
            }
        }
    }

    @Test
    fun `packet source is loopback so minecraft connects to 127_0_0_1`() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = RECEIVE_TIMEOUT_MILLIS
            val broadcast = newBroadcast(receiver.localPort)
            try {
                broadcast.start().getOrThrow()
                val buffer = ByteArray(512)
                val packet = DatagramPacket(buffer, buffer.size)
                receiver.receive(packet)
                assertTrue(packet.address.isLoopbackAddress, "实际源地址：${packet.address}")
            } finally {
                broadcast.close()
            }
        }
    }

    @Test
    fun `stops sending after stop`() {
        openLanReceiver().use { receiver ->
            val broadcast = newBroadcast(receiver.localPort)
            try {
                broadcast.start().getOrThrow()
                receiveLanText(receiver)
                broadcast.stop()
                // stop() 与发送共用一把锁，返回前在途报文已经入队，这里只排空它们。
                drainLanPackets(receiver)
                receiver.soTimeout = STOP_OBSERVE_TIMEOUT_MILLIS
                assertFailsWith<SocketTimeoutException> { receiveLanText(receiver) }
            } finally {
                broadcast.close()
            }
        }
    }

    @Test
    fun `start and stop are idempotent`() {
        openLanReceiver().use { receiver ->
            val broadcast = newBroadcast(receiver.localPort)
            try {
                assertTrue(broadcast.start().isSuccess)
                assertTrue(broadcast.start().isSuccess)
                broadcast.stop()
                broadcast.stop()
                assertTrue(broadcast.start().isSuccess)
            } finally {
                broadcast.close()
            }
            assertTrue(broadcast.start().isFailure)
        }
    }

    private fun newBroadcast(targetPort: Int): MinecraftLanBroadcast =
        MinecraftLanBroadcast(
            description = "MoliFox 世界",
            localPort = 25566,
            targetPort = targetPort,
            intervalMillis = TEST_BROADCAST_INTERVAL_MILLIS,
            multicastEnabled = false,
        )
}

private const val STOP_OBSERVE_TIMEOUT_MILLIS = 300
private const val RECEIVE_TIMEOUT_MILLIS = 2_000
