// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证服务器状态查询每次调用都重新读取，失败结果不被缓存。

package dev.molifox.core

import dev.molifox.core.domain.server.ServerStatusQuery
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServerStatusQueryTest {
    private fun status(version: String): ServerStatus =
        ServerStatus(
            online = true,
            players = 1,
            maxPlayers = 20,
            motd = listOf(MotdSegment("fixture", null, false, false, false, false)),
            version = version,
            ping = 1,
        )

    @Test
    fun `two consecutive reads query the injected source twice`(): Unit =
        runBlocking {
            val calls = AtomicInteger()
            val query =
                ServerStatusQuery { address ->
                    val attempt = calls.incrementAndGet()
                    assertEquals("fixture.invalid:25565", address)
                    status("attempt-$attempt")
                }
            assertEquals("attempt-1", query.read("fixture.invalid:25565")?.version)
            assertEquals("attempt-2", query.read("fixture.invalid:25565")?.version)
            assertEquals(2, calls.get())
        }

    @Test
    fun `a failed read is surfaced and the next read queries again`(): Unit =
        runBlocking {
            val calls = AtomicInteger()
            var failing = true
            val query =
                ServerStatusQuery {
                    calls.incrementAndGet()
                    if (failing) throw IOException("fixture offline") else null
                }
            assertFailsWith<IOException> { query.read("fixture.invalid:25565") }
            failing = false
            // 空结果同样不缓存：第二次仍旧真的查询一次，而不是复用上次的失败状态。
            assertNull(query.read("fixture.invalid:25565"))
            assertEquals(2, calls.get())
        }
}
