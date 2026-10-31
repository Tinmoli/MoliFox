// 校验 EasyTier 目录数据与官方发布资产（v2.6.4）一致，且只声明支持的平台。
package dev.molifox.multiplayer.easytier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EasyTierCatalogTest {
    @Test
    fun `pins version and license`() {
        assertEquals("2.6.4", EasyTierCatalog.VERSION)
        assertEquals("LGPL-3.0", EasyTierCatalog.LICENSE)
    }

    @Test
    fun `windows package matches verified release asset`() {
        val pkg = assertNotNull(EasyTierCatalog.packageFor("windows-x86_64"))
        assertEquals(
            "https://github.com/EasyTier/EasyTier/releases/download/v2.6.4/" +
                "easytier-windows-x86_64-v2.6.4.zip",
            pkg.url,
        )
        assertEquals(
            "27af91e270e554709b048bd32327fefd2dfce5062ae1e8701af7550c6f525f84",
            pkg.sha256,
        )
        assertEquals(32_657_720L, pkg.downloadBytes)
        assertEquals("easytier-windows-x86_64", pkg.rootPrefix)
        assertEquals("easytier-core.exe", pkg.coreFile)
        assertEquals("easytier-cli.exe", pkg.cliFile)
        assertEquals(listOf("easytier-core.exe", "easytier-cli.exe"), pkg.requiredFiles)
    }

    @Test
    fun `linux package matches verified release asset`() {
        val pkg = assertNotNull(EasyTierCatalog.packageFor("linux-x86_64"))
        assertEquals(
            "https://github.com/EasyTier/EasyTier/releases/download/v2.6.4/" +
                "easytier-linux-x86_64-v2.6.4.zip",
            pkg.url,
        )
        assertEquals(
            "61b659eaedba658fa66fe47d17e1426cdd77e5d02fa15fed447bb4357c09dfd6",
            pkg.sha256,
        )
        assertEquals(25_506_523L, pkg.downloadBytes)
        assertEquals("easytier-linux-x86_64", pkg.rootPrefix)
        assertEquals("easytier-core", pkg.coreFile)
        assertEquals("easytier-cli", pkg.cliFile)
    }

    @Test
    fun `every package uses a full lowercase sha256`() {
        listOf("windows-x86_64", "linux-x86_64").forEach { classifier ->
            val pkg = assertNotNull(EasyTierCatalog.packageFor(classifier))
            assertTrue(
                Regex("[0-9a-f]{64}").matches(pkg.sha256),
                "$classifier 的 SHA-256 格式不正确：${pkg.sha256}",
            )
            assertTrue(pkg.downloadBytes > 1_000_000L)
            assertTrue(pkg.url.startsWith("https://github.com/EasyTier/EasyTier/"))
        }
    }

    @Test
    fun `classifies only desktop x86_64 platforms`() {
        assertEquals("windows-x86_64", EasyTierCatalog.classify("Windows 11", "amd64"))
        assertEquals("windows-x86_64", EasyTierCatalog.classify("Windows 10", "x86_64"))
        assertEquals("linux-x86_64", EasyTierCatalog.classify("Linux", "amd64"))
        assertEquals("linux-x86_64", EasyTierCatalog.classify("Linux", "x86_64"))
        assertNull(EasyTierCatalog.classify("Mac OS X", "x86_64"))
        assertNull(EasyTierCatalog.classify("Linux", "aarch64"))
        assertNull(EasyTierCatalog.classify("Windows 11", "aarch64"))
        assertNull(EasyTierCatalog.packageFor("macos-arm64"))
    }
}
