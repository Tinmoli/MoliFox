// 本机 TCP 转发测试：回环 echo server 验证双向传输、并发与 close 后端口释放。
package dev.molifox.multiplayer.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalPortForwardTest {
    @Test
    fun `forwards bytes in both directions`() {
        val echo = LoopbackEchoServer()
        try {
            val forward = LocalPortForward(targetPort = echo.port)
            try {
                val localPort = forward.start().getOrThrow()
                assertEquals("hello world", exchange(localPort, "hello world"))
            } finally {
                forward.close()
            }
        } finally {
            echo.close()
        }
    }

    @Test
    fun `supports concurrent connections`() {
        val echo = LoopbackEchoServer()
        try {
            val forward = LocalPortForward(targetPort = echo.port)
            try {
                val localPort = forward.start().getOrThrow()
                val replies =
                    runBlocking {
                        (1..CONCURRENT_CONNECTIONS)
                            .map { index ->
                                async(Dispatchers.IO) { exchange(localPort, "payload-$index") }
                            }.awaitAll()
                    }
                assertEquals(
                    (1..CONCURRENT_CONNECTIONS).map { index -> "payload-$index" },
                    replies,
                )
                assertEquals(CONCURRENT_CONNECTIONS, echo.acceptedConnections)
            } finally {
                forward.close()
            }
        } finally {
            echo.close()
        }
    }

    @Test
    fun `close releases listening port for immediate rebind`() {
        val echo = LoopbackEchoServer()
        try {
            val first = LocalPortForward(targetPort = echo.port)
            val localPort = first.start().getOrThrow()
            first.close()
            val second = LocalPortForward(targetPort = echo.port, listenPort = localPort)
            try {
                assertEquals(localPort, second.start().getOrThrow())
            } finally {
                second.close()
            }
        } finally {
            echo.close()
        }
    }

    @Test
    fun `close tears down active connections`() {
        val echo = LoopbackEchoServer()
        try {
            val forward = LocalPortForward(targetPort = echo.port)
            val localPort = forward.start().getOrThrow()
            Socket().use { client ->
                connectTestSocket(client, localPort)
                client.getOutputStream().apply {
                    write("ping".encodeToByteArray())
                    flush()
                }
                readExactly(client, "ping".length)
                forward.close()
                assertEquals(-1, client.getInputStream().read())
            }
        } finally {
            echo.close()
        }
    }

    @Test
    fun `rejects invalid configuration`() {
        assertFailsWith<IllegalArgumentException> { LocalPortForward(targetPort = 0) }
        assertFailsWith<IllegalArgumentException> {
            LocalPortForward(targetPort = 25565, listenPort = 70_000)
        }
        assertFailsWith<IllegalArgumentException> {
            LocalPortForward(targetPort = 25565, connectTimeoutMillis = 0)
        }
    }

    @Test
    fun `reports start after close and double start`() {
        val echo = LoopbackEchoServer()
        try {
            val forward = LocalPortForward(targetPort = echo.port)
            try {
                val localPort = forward.start().getOrThrow()
                assertEquals(localPort, forward.start().getOrThrow())
                assertEquals(localPort, forward.port)
            } finally {
                forward.close()
            }
            assertTrue(forward.start().isFailure)
            assertFailsWith<IllegalStateException> { forward.port }
        } finally {
            echo.close()
        }
    }
}

private const val CONCURRENT_CONNECTIONS = 4
