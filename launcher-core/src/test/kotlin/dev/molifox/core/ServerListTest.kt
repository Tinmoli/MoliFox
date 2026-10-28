package dev.molifox.core

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test

class ServerListTest {
    private val png: ByteArray =
        ByteArrayOutputStream()
            .also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png", it) }
            .toByteArray()

    private fun writeServers(
        directory: Path,
        compressed: Boolean = true,
        block: TestNbtWriter.() -> Unit,
    ) {
        val bytes = TestNbtWriter().root(block)
        directory.resolve("servers.dat").writeBytes(if (compressed) gzipBytes(bytes) else bytes)
    }

    @Test
    fun `listServers reads name address and base64 icon without touching the file`() =
        withTempDirectory("molifox-servers-") { directory ->
            writeServers(directory) {
                list("servers", 10, 3) {
                    compoundElement {
                        string("name", "MoliFox 生存")
                        string("ip", "mc.molifox.dev")
                        string("icon", Base64.getEncoder().encodeToString(png))
                    }
                    compoundElement {
                        string("name", "旧布局")
                        string("ip", "old.example.com:25565")
                    }
                    compoundElement { string("ip", "nameless.example.com") }
                }
            }
            val before = directory.resolve("servers.dat").readBytes()

            val servers = listServers(directory)
            assertEquals(3, servers.size)
            assertEquals("MoliFox 生存", servers[0].name)
            assertEquals("mc.molifox.dev", servers[0].ip)
            assertNotNull(servers[0].icon)
            assertContentEquals(png, servers[0].icon)
            // 旧布局只有 name 与 ip；缺少 name 时回退成地址。
            assertEquals("旧布局", servers[1].name)
            assertNull(servers[1].icon)
            assertEquals("nameless.example.com", servers[2].name)
            assertNull(servers[2].icon)
            assertContentEquals(before, directory.resolve("servers.dat").readBytes())
        }

    @Test
    fun `invalid icon is dropped but the server row is kept`() =
        withTempDirectory("molifox-servers-") { directory ->
            writeServers(directory, compressed = false) {
                list("servers", 10, 3) {
                    compoundElement {
                        string("name", "坏图标")
                        string("ip", "bad-icon.example.com")
                        string("icon", Base64.getEncoder().encodeToString("不是图片".toByteArray()))
                    }
                    compoundElement {
                        string("name", "坏 base64")
                        string("ip", "bad-base64.example.com")
                        string("icon", "&&&")
                    }
                    compoundElement {
                        string("name", "没有图标")
                        string("ip", "no-icon.example.com")
                    }
                }
            }

            val servers = listServers(directory)
            assertEquals(3, servers.size)
            assertTrue(servers.all { it.icon == null })
        }

    @Test
    fun `malformed missing or empty servers dat returns an empty list`() =
        withTempDirectory("molifox-servers-") { directory ->
            assertTrue(listServers(directory).isEmpty())

            val file = directory.resolve("servers.dat")
            file.writeText("这不是 NBT")
            assertTrue(listServers(directory).isEmpty())

            file.writeBytes(ByteArray(0))
            assertTrue(listServers(directory).isEmpty())

            // 是合法 NBT 但没有 servers 列表，也按空列表处理。
            file.writeBytes(TestNbtWriter().root { string("other", "value") })
            assertTrue(listServers(directory).isEmpty())
        }
}