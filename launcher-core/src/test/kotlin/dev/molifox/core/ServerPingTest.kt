// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证服务器地址解析、状态查询和 MOTD 转换的行为与边界。

package dev.molifox.core

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * [ServerPing] 的本地回归：地址解析、MOTD 样式、现代 / 旧版两种 ping 与离线兜底。
 *
 * 服务器都是测试线程里启动的本地 [ServerSocket]，不访问外网，也不写入任何文件。
 */
class ServerPingTest {
    @Test
    fun `MOTD keeps line breaks and formatting across both lines`() {
        val motd =
            parseMotd(
                JsonParser.parseString(
                    """{"text":"First line\r\n","color":"red","extra":[{"text":"Second line","color":"green"}]}"""
                )
            )
        assertEquals("First line\nSecond line", motd.joinToString("") { it.text })
        assertEquals(2, motd.size)
        assertNotEquals(motd.first().color, motd.last().color)
    }

    @Test
    fun `address parsing defaults to 25565 and rejects invalid input`() {
        assertEquals(ServerAddress("mc.example.com", 25565), parseServerAddress("mc.example.com"))
        assertEquals(
            ServerAddress("mc.example.com", 25566),
            parseServerAddress("mc.example.com:25566"),
        )
        assertEquals(ServerAddress("::1", 25565), parseServerAddress("[::1]"))
        assertEquals(ServerAddress("::1", 25567), parseServerAddress("[::1]:25567"))
        assertEquals(ServerAddress("2001:db8::1", 25565), parseServerAddress("2001:db8::1"))
        assertNull(parseServerAddress(""))
        assertNull(parseServerAddress("host:"))
        assertNull(parseServerAddress("host:0"))
        assertNull(parseServerAddress("host:70000"))
        assertNull(parseServerAddress("bad host"))
        assertNull(parseServerAddress("[::1"))
        assertNull(parseServerAddress("host:abc"))
    }

    @Test
    fun `json description resolves nested components and inherits styles`() {
        val description =
            JsonParser.parseString(
                """{"text":"hello ","color":"gold","bold":true,"extra":[""" +
                    """{"text":"world","color":"#123456","strikethrough":true},""" +
                    """{"text":"§a green","bold":false}]}"""
            )
        val segments = parseMotd(description)
        assertEquals(3, segments.size)
        assertEquals(MotdSegment("hello ", 0xFFAA00, true, false, false, false), segments[0])
        // 子组件没写 bold，继承父级的 bold；颜色与删除线来自自己。
        assertEquals(MotdSegment("world", 0x123456, true, false, false, true), segments[1])
        // §a 换色并清掉格式，显式 false 覆盖父级 bold。
        assertEquals(MotdSegment(" green", 0x55FF55, false, false, false, false), segments[2])
    }

    @Test
    fun `json description accepts plain strings arrays and degrades safely`() {
        val fromString = parseMotd(JsonParser.parseString("\"§cRed §lBold\""))
        assertEquals(2, fromString.size)
        assertEquals(MotdSegment("Red ", 0xFF5555, false, false, false, false), fromString[0])
        assertEquals(MotdSegment("Bold", 0xFF5555, true, false, false, false), fromString[1])

        val fromArray = parseMotd(JsonParser.parseString("""[{"text":"A","color":"red"},"B"]"""))
        assertEquals(2, fromArray.size)
        assertEquals(MotdSegment("A", 0xFF5555, false, false, false, false), fromArray[0])
        assertEquals(MotdSegment("B", null, false, false, false, false), fromArray[1])

        // 未知结构、null、数字都不是文字：返回空列表而不是抛异常。
        assertTrue(parseMotd(JsonParser.parseString("42")).isEmpty())
        assertTrue(parseMotd(null).isEmpty())
        assertTrue(parseMotdText("").isEmpty())
        // 未知 § 代码按字面量保留，已知代码生效。
        assertEquals(
            listOf(MotdSegment("§z", null, false, false, false, false)),
            parseMotdText("§z"),
        )
    }

    @Test
    fun `modern ping returns motd players version and latency`() = runBlocking {
        val json =
            """{"version":{"name":"1.20.6","protocol":766},"players":{"max":20,"online":3},""" +
                """"description":{"text":"","extra":[{"text":"Moli","color":"aqua"},""" +
                """{"text":"Fox","bold":true}]}}"""
        FakeServer { socket -> serveStatus(socket, json) }
            .use { server ->
                val status = ping("127.0.0.1", server.port, 3_000)
                assertNotNull(status)
                assertTrue(status.online)
                assertEquals(3, status.players)
                assertEquals(20, status.maxPlayers)
                assertEquals("1.20.6", status.version)
                assertEquals(
                    listOf(
                        MotdSegment("Moli", 0x55FFFF, false, false, false, false),
                        MotdSegment("Fox", null, true, false, false, false),
                    ),
                    status.motd,
                )
                assertTrue(status.ping >= 0)
            }
    }

    @Test
    fun `legacy ping is used when the modern handshake fails`() = runBlocking {
        val connections = AtomicInteger()
        FakeServer { socket ->
            if (connections.getAndIncrement() == 0) {
                // 第一次连接不回数据直接断开，模拟拒绝现代协议的服务端。
                socket.getOutputStream().close()
            } else {
                serveLegacy(socket, "1.5.2", "§6Moli§r Fox", 4, 12)
            }
        }
            .use { server ->
                val status = ping("127.0.0.1", server.port, 2_000)
                assertNotNull(status)
                assertEquals(4, status.players)
                assertEquals(12, status.maxPlayers)
                assertEquals("1.5.2", status.version)
                assertEquals(
                    listOf(
                        MotdSegment("Moli", 0xFFAA00, false, false, false, false),
                        MotdSegment(" Fox", null, false, false, false, false),
                    ),
                    status.motd,
                )
            }
    }

    @Test
    fun `unreachable server and invalid host return null`() = runBlocking {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertNull(ping("127.0.0.1", port, 500))
        assertNull(ping("", DEFAULT_SERVER_PORT))
        assertNull(ping("127.0.0.1", 0))
        assertNull(pingAddress("bad host"))
    }

    // ---- 本地测试服务端：只实现「握手一帧 + 状态请求一帧 + 状态响应」

    private class FakeServer(private val handler: (Socket) -> Unit) : Closeable {
        private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val port: Int = server.localPort
        private val worker =
            thread(isDaemon = true, name = "fake-mc-server") {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    try {
                        handler(socket)
                    } catch (_: Exception) {
                        // 让客户端按失败处理，测试断言会给出原因。
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            }

        override fun close() {
            runCatching { server.close() }
            worker.join(2_000)
        }
    }

    private fun serveStatus(socket: Socket, json: String) {
        val input = DataInputStream(socket.getInputStream())
        readFrame(input) // 握手
        readFrame(input) // 状态请求
        val text = json.toByteArray(Charsets.UTF_8)
        val payload = ByteArrayOutputStream()
        writeVarInt(payload, 0x00)
        writeVarInt(payload, text.size)
        payload.write(text)
        val bytes = payload.toByteArray()
        val output = DataOutputStream(socket.getOutputStream())
        writeVarInt(output, bytes.size)
        output.write(bytes)
        output.flush()
    }

    private fun serveLegacy(socket: Socket, version: String, motd: String, online: Int, max: Int) {
        val input = DataInputStream(socket.getInputStream())
        assertEquals(0xFE, input.readUnsignedByte())
        assertEquals(0x01, input.readUnsignedByte())
        val text =
            "§1\u0000" +
                "127" +
                "\u0000" +
                version +
                "\u0000" +
                motd +
                "\u0000" +
                online +
                "\u0000" +
                max
        val output = DataOutputStream(socket.getOutputStream())
        output.writeByte(0xFF)
        val chars = text.length
        output.writeShort(chars)
        output.write(text.toByteArray(Charsets.UTF_16BE))
        output.flush()
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        val length = readVarInt(input)
        if (length < 0) throw EOFException("帧长度无效")
        return ByteArray(length).also { input.readFully(it) }
    }

    private fun readVarInt(input: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) throw EOFException()
            result = result or ((byte and 0x7F) shl shift)
            if ((byte and 0x80) == 0) return result
            shift += 7
            if (shift > 28) throw EOFException()
        }
    }

    private fun writeVarInt(output: DataOutputStream, value: Int) {
        var remaining = value
        while (true) {
            if ((remaining and 0x7F.inv()) == 0) {
                output.write(remaining)
                return
            }
            output.write((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
    }

    private fun writeVarInt(output: ByteArrayOutputStream, value: Int) {
        var remaining = value
        while (true) {
            if ((remaining and 0x7F.inv()) == 0) {
                output.write(remaining)
                return
            }
            output.write((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
    }
}
