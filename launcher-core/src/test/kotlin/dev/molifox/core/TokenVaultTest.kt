package dev.molifox.core

import com.sun.jna.platform.win32.Crypt32Util
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class TokenVaultTest {
    @TempDir lateinit var temporary: Path
    private val id = UUID.randomUUID().toString()
    private val none =
        object : LegacyTokenStore {
            override fun read(id: String): String? = null

            override fun remove(id: String) {}
        }

    private fun store(path: Path = temporary.resolve("profile"), os: String = "windows") =
        LauncherStore(
            object : PlatformServices by DesktopPlatform() {
                override val osName = os
            },
            path,
        )

    @Test
    fun `portable credential survives restart without readable tokens and uses a fresh nonce`() {
        val store = store()
        val vault = TokenVault(store, none)
        val token = "private-refresh-fixture-令牌"
        vault.save(id, token)
        val file = store.dataDirectory.resolve("credentials/$id.json")
        val first = file.readText()
        assertFalse(first.contains(token))
        assertFalse(first.contains("private-refresh-fixture"))
        assertEquals(token, TokenVault(store, none).read(id))
        vault.save(id, token)
        assertNotEquals(first, file.readText())
        assertEquals(token, TokenVault(store, none).read(id))
        assertTrue(file.parent.listDirectoryEntries().none { it.extension == "tmp" })
    }

    @Test
    fun `copied credential works at another path and simulated platform`() {
        val original = store()
        TokenVault(original, none).save(id, "portable-refresh")
        val moved = store(temporary.resolve("moved-profile"), "linux")
        val destination = moved.dataDirectory.resolve("credentials/$id.json")
        destination.parent.createDirectories()
        Files.copy(original.dataDirectory.resolve("credentials/$id.json"), destination)
        assertEquals("portable-refresh", TokenVault(moved, none).read(id))
    }

    @Test
    fun `tampered payload or copied account identity is rejected without old token fallback`() {
        val store = store()
        val vault = TokenVault(store, none)
        vault.save(id, "test-refresh")
        val file = store.dataDirectory.resolve("credentials/$id.json")
        val other = UUID.randomUUID().toString()
        Files.copy(file, file.resolveSibling("$other.json"))
        assertNull(TokenVault(store, none).read(other))
        val envelope = readObject(file)
        val payload = envelope.string("payload").toCharArray()
        payload[0] = if (payload[0] == 'A') 'B' else 'A'
        envelope.addProperty("payload", payload.concatToString())
        writeJson(file, envelope)
        var reads = 0
        val legacy =
            object : LegacyTokenStore {
                override fun read(id: String): String {
                    reads++
                    return "old-refresh"
                }

                override fun remove(id: String) {}
            }
        assertNull(TokenVault(store, legacy).read(id))
        assertEquals(0, reads)
        assertEquals(envelope, readObject(file))
    }

    @Test
    fun `legacy migration writes portable file before retiring the old entry`() {
        val store = store()
        var removed = false
        val legacy =
            object : LegacyTokenStore {
                override fun read(id: String) = "old-refresh"

                override fun remove(id: String) {
                    assertEquals("old-refresh", TokenVault(store, none).read(id))
                    removed = true
                }
            }
        assertEquals("old-refresh", TokenVault(store, legacy).read(id))
        assertTrue(removed)
    }

    @Test
    fun `failed migration keeps legacy entry`() {
        val store = store()
        store.dataDirectory.resolve("credentials").writeText("directory collision")
        var removed = false
        val legacy =
            object : LegacyTokenStore {
                override fun read(id: String) = "old-refresh"

                override fun remove(id: String) {
                    removed = true
                }
            }
        assertFails { TokenVault(store, legacy).read(id) }
        assertFalse(removed)
    }

    @Test
    fun `remove clears portable data and session cache`() {
        val store = store()
        val vault = TokenVault(store, none)
        vault.save(id, "private-refresh")
        vault.remove(id)
        assertNull(vault.read(id))
        assertNull(TokenVault(store, none).read(id))
        assertFalse(store.dataDirectory.resolve("credentials/$id.json").exists())
    }

    @Test
    fun `Windows DPAPI entry migrates to portable JSON`() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        val store = store()
        val legacy = store.dataDirectory.resolve("credentials/$id.bin")
        legacy.parent.createDirectories()
        legacy.writeBytes(Crypt32Util.cryptProtectData("old-dpapi-refresh".toByteArray()))
        assertEquals("old-dpapi-refresh", TokenVault(store).read(id))
        assertFalse(legacy.exists())
        assertEquals("old-dpapi-refresh", TokenVault(store, none).read(id))
    }

    @Test
    fun `unsupported format and malformed input stay unchanged`() {
        val store = store()
        val file = store.dataDirectory.resolve("credentials/$id.json")
        file.parent.createDirectories()
        for (input in
            listOf(
                "{broken",
                """{"formatVersion":999,"protection":"unknown"}""",
                "x".repeat(65537),
            )) {
            file.writeText(input)
            assertNull(TokenVault(store, none).read(id))
            assertEquals(input, file.readText())
        }
        assertFails { TokenVault(store, none).save("../outside", "value") }
        assertFalse(store.dataDirectory.resolve("outside.json").exists())
    }
}
