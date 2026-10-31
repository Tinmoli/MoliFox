// LAN 组件测试的共享工具：回环 UDP 收发、轮询等待与假 ping 实现。
package dev.molifox.multiplayer.lan

import dev.molifox.multiplayer.common.LanWorld
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.fail

internal const val TEST_HOST = "127.0.0.1"
internal const val TEST_TIMEOUT_MILLIS = 3_000L
internal const val TEST_POLL_MILLIS = 10L
internal const val TEST_RECEIVE_TIMEOUT_MILLIS = 2_000
internal const val TEST_BROADCAST_INTERVAL_MILLIS = 50L

internal const val TEST_WORLD_NAME = "测试世界"
internal const val TEST_VERSION = "1.20.1"

/** 轮询直到条件成立；返回条件最终是否成立，避免用固定 sleep 撞时序。 */
internal fun awaitCondition(
    timeoutMillis: Long = TEST_TIMEOUT_MILLIS,
    condition: () -> Boolean,
): Boolean {
    val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
    while (System.nanoTime() < deadline) {
        if (condition()) {
            return true
        }
        Thread.sleep(TEST_POLL_MILLIS)
    }
    return condition()
}

/** 等待条件成立，超时让测试失败。 */
internal fun awaitTrue(
    timeoutMillis: Long = TEST_TIMEOUT_MILLIS,
    condition: () -> Boolean,
) {
    if (!awaitCondition(timeoutMillis, condition)) {
        fail("条件在 ${timeoutMillis}ms 内未成立")
    }
}

/** 断言条件在观察窗口内始终不成立，用于验证“不再更新”。 */
internal fun assertStaysFalse(
    observeMillis: Long = OBSERVE_MILLIS,
    condition: () -> Boolean,
) {
    if (awaitCondition(observeMillis, condition)) {
        fail("条件在 ${observeMillis}ms 观察窗口内意外成立")
    }
}

/** 启动一个监听随机端口的发现实例，并保证测试结束时关闭。 */
internal inline fun <T> withDiscovery(
    pinger: MinecraftStatusPinger,
    noinline onError: (Throwable) -> Unit = {},
    block: (MinecraftLanDiscovery, Int) -> T,
): T {
    val discovery = MinecraftLanDiscovery(listenPort = 0, pinger = pinger, onError = onError)
    try {
        val port = discovery.start().getOrThrow()
        return block(discovery, port)
    } finally {
        discovery.close()
    }
}

/** 记录调用次数、按端口返回固定结果的假 ping 实现。 */
internal class FakePinger(
    private val result: (Int) -> LanWorld? = { port ->
        LanWorld(TEST_WORLD_NAME, TEST_VERSION, port)
    },
) : MinecraftStatusPinger {
    private val invocations = CopyOnWriteArrayList<Int>()

    val calls: List<Int>
        get() = invocations.toList()

    override fun ping(
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): LanWorld? {
        invocations += port
        return result(port)
    }
}

internal fun lanPacket(
    motd: String,
    port: Int,
): String = "[MOTD]$motd[/MOTD][AD]$port[/AD]"

internal fun sendLanPacket(
    targetPort: Int,
    raw: String,
) {
    DatagramSocket().use { sender ->
        val payload = raw.encodeToByteArray()
        sender.send(
            DatagramPacket(payload, payload.size, InetAddress.getByName(TEST_HOST), targetPort),
        )
    }
}

internal fun openLanReceiver(): DatagramSocket =
    DatagramSocket(0, InetAddress.getByName(TEST_HOST)).apply {
        soTimeout = TEST_RECEIVE_TIMEOUT_MILLIS
    }

internal fun receiveLanText(receiver: DatagramSocket): String {
    val buffer = ByteArray(LAN_RECEIVE_BUFFER_BYTES)
    val packet = DatagramPacket(buffer, buffer.size)
    receiver.receive(packet)
    return String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
}

/** 排空 stop 前可能已在途的报文，之后再做“不再发送”的否定断言。 */
internal fun drainLanPackets(receiver: DatagramSocket) {
    val buffer = ByteArray(LAN_RECEIVE_BUFFER_BYTES)
    receiver.soTimeout = DRAIN_TIMEOUT_MILLIS
    try {
        while (true) {
            receiver.receive(DatagramPacket(buffer, buffer.size))
        }
    } catch (_: SocketTimeoutException) {
        // 没有更多在途报文，符合预期。
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
private const val OBSERVE_MILLIS = 300L
private const val DRAIN_TIMEOUT_MILLIS = 100
private const val LAN_RECEIVE_BUFFER_BYTES = 2048
