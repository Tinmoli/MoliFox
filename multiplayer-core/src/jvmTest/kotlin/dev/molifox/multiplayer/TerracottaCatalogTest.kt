// 校验陶瓦核心版本清单：平台识别、校验值与下载地址顺序。
package dev.molifox.multiplayer

import dev.molifox.multiplayer.data.TerracottaCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerracottaCatalogTest {
    @Test
    fun `windows and linux x64 are supported`() {
        assertEquals("windows-x86_64", TerracottaCatalog.classify("Windows 11", "amd64"))
        assertEquals("linux-x86_64", TerracottaCatalog.classify("Linux", "x86_64"))
    }

    @Test
    fun `unsupported platforms are reported as missing`() {
        assertNull(TerracottaCatalog.classify("Mac OS X", "x86_64"))
        assertNull(TerracottaCatalog.classify("Linux", "aarch64"))
        assertNull(TerracottaCatalog.classify("Windows 10", "x86"))
    }

    @Test
    fun `package metadata carries sha512 values for every file`() {
        val pkg = assertNotNull(TerracottaCatalog.current())
        assertEquals(128, pkg.archiveSha512.length)
        assertTrue(pkg.downloadBytes > 0)
        assertTrue(pkg.files.isNotEmpty())
        pkg.files.values.forEach { assertEquals(128, it.length) }
    }

    @Test
    fun `mirror first puts national mirrors before github`() {
        val classifier = TerracottaCatalog.currentClassifier()
        if (classifier == null) return
        val mirrorFirst = TerracottaCatalog.downloadUrls(preferMirror = true)
        val githubFirst = TerracottaCatalog.downloadUrls(preferMirror = false)
        assertTrue(mirrorFirst.first().startsWith("https://gitee.com/"))
        assertTrue(githubFirst.first().startsWith("https://github.com/"))
        assertEquals(mirrorFirst.size, githubFirst.size)
        assertTrue(mirrorFirst.last().startsWith("https://github.com/"))
    }
}
