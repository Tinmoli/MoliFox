// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证 SRV 记录的选择、缓存与 ping 目标回退规则。

package dev.molifox.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerSrvTest {
    @Test
    fun `selects lowest priority then highest weight and strips root dot`() {
        val target =
            selectSrvTarget(
                listOf(
                    "10 5 25566 backup.example.com.",
                    "0 10 25565 primary.example.com.",
                    "0 20 25567 preferred.example.com.",
                ),
            )
        assertEquals(SrvTarget("preferred.example.com", 25567), target)
    }

    @Test
    fun `ignores unavailable malformed and out of range records`() {
        assertNull(selectSrvTarget(emptyList()))
        assertNull(selectSrvTarget(listOf(".", "0 0 .")))
        assertNull(selectSrvTarget(listOf("0 0 0 mc.example.com", "bad record here now")))
        assertNull(selectSrvTarget(listOf("0 0 70000 mc.example.com")))
        assertNull(selectSrvTarget(listOf("0 0 25565 ")))
        assertEquals(
            SrvTarget("mc.example.com", 25565),
            selectSrvTarget(listOf("0 0 70000 mc.example.com", "0 0 25565 mc.example.com")),
        )
    }

    @Test
    fun `resolver caches positive and negative answers`() {
        var now = 1_000L
        var queries = 0
        val resolver =
            SrvResolver(
                query = {
                    queries++
                    listOf("0 0 25566 target.example.com")
                },
                clock = { now },
            )
        assertEquals(SrvTarget("target.example.com", 25566), resolver.resolve("MC.Example.COM"))
        assertEquals(1, queries)
        // 命中缓存后不再查询。
        assertEquals(SrvTarget("target.example.com", 25566), resolver.resolve("mc.example.com"))
        assertEquals(1, queries)
        // 超过 5 分钟重新查询。
        now += 5 * 60_000L + 1
        resolver.resolve("mc.example.com")
        assertEquals(2, queries)
    }

    @Test
    fun `resolver caches missing records briefly`() {
        var now = 0L
        var queries = 0
        val resolver =
            SrvResolver(
                query = {
                    queries++
                    emptyList()
                },
                clock = { now },
            )
        assertNull(resolver.resolve("mc.example.com"))
        assertNull(resolver.resolve("mc.example.com"))
        assertEquals(1, queries)
        now += 60_000L + 1
        assertNull(resolver.resolve("mc.example.com"))
        assertEquals(2, queries)
    }

    @Test
    fun `ping target uses srv only for default hostname`() {
        val srv = { _: String -> SrvTarget("srv.example.net", 25570) }
        assertEquals(
            ServerAddress("srv.example.net", 25570),
            resolvePingTarget(ServerAddress("play.example.com"), srv),
        )
        // 显式端口原样连接，和原版客户端一致。
        assertEquals(
            ServerAddress("play.example.com", 25566),
            resolvePingTarget(ServerAddress("play.example.com", 25566), srv),
        )
        // IP 字面量不做 SRV 查询。
        assertEquals(
            ServerAddress("127.0.0.1", 25565),
            resolvePingTarget(ServerAddress("127.0.0.1"), srv),
        )
        assertEquals(
            ServerAddress("::1", 25565),
            resolvePingTarget(ServerAddress("::1"), srv),
        )
        // 没有 SRV 记录时回退原地址。
        assertEquals(
            ServerAddress("play.example.com", 25565),
            resolvePingTarget(ServerAddress("play.example.com")) { null },
        )
        // 查询抛异常也不能影响 ping，走回退。
        assertEquals(
            ServerAddress("play.example.com", 25565),
            resolvePingTarget(ServerAddress("play.example.com")) {
                throw IllegalStateException("dns failed")
            },
        )
    }
}
