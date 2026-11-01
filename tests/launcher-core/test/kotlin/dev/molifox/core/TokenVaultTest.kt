// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证账户凭据的保存位置、落盘保护与读取的边界行为。

package dev.molifox.core

import com.sun.jna.platform.win32.Crypt32Util
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun isWindowsHost() = System.getProperty("os.name").startsWith("Windows")

private fun testStore(
    root: Path,
    os: String = "windows",
) = LauncherStore(
    object : PlatformServices by DesktopPlatform() {
        override val osName = os
    },
    root.resolve("profile"),
)

private fun credentialFolder(root: Path) = root.resolve("credentials-${UUID.randomUUID()}")

class TokenVaultTest {
    @TempDir lateinit var temporary: Path
    private val id = UUID.randomUUID().toString()
    private val token = "private-refresh-fixture-令牌"
    private val none =
        object : LegacyTokenStore {
            override fun read(id: String): String? = null

            override fun remove(id: String) = Unit
        }

    private fun plainVault(
        store: LauncherStore,
        folder: Path,
    ) = TokenVault(store, none, folder, PlainCredentialProtection())

    private fun dpapiVault(
        store: LauncherStore,
        folder: Path,
    ) = TokenVault(store, none, folder, DpapiKeyProtection(folder.resolve("user-key.bin")))

    @Test
    fun `windows credential is encrypted outside the portable folder`() {
        assumeTrue(isWindowsHost(), "DPAPI requires Windows")
        val store = testStore(temporary)
        val folder = credentialFolder(temporary)
        dpapiVault(store, folder).save(id, token)
        val file = folder.resolve("$id.json")
        assertTrue(file.isRegularFile())
        assertFalse(store.dataDirectory.resolve("credentials/$id.json").exists())
        assertFalse(file.readText().contains(token))
        assertFalse(file.readText().contains("private-refresh-fixture"))
        assertEquals(32, Crypt32Util.cryptUnprotectData(folder.resolve("user-key.bin").readBytes()).size)
        assertEquals(token, dpapiVault(store, folder).read(id))
    }

    @Test
    fun `saving again uses a fresh nonce and leaves no temporary files`() {
        assumeTrue(isWindowsHost(), "DPAPI requires Windows")
        val store = testStore(temporary)
        val folder = credentialFolder(temporary)
        val vault = dpapiVault(store, folder)
        vault.save(id, token)
        val file = folder.resolve("$id.json")
        val first = file.readText()
        vault.save(id, token)
        assertNotEquals(first, file.readText())
        assertEquals(token, dpapiVault(store, folder).read(id))
        assertTrue(folder.listDirectoryEntries().none { it.extension == "tmp" })
    }

    @Test
    fun `plain protection moves the credential out of the portable folder`() {
        val store = testStore(temporary, "linux")
        val folder = credentialFolder(temporary)
        plainVault(store, folder).save(id, "plain-refresh")
        val file = folder.resolve("$id.json")
        assertTrue(file.isRegularFile())
        assertFalse(store.dataDirectory.resolve("credentials/$id.json").exists())
        assertFalse(file.readText().contains("plain-refresh"))
        assertFalse(folder.resolve("user-key.bin").exists())
        assertEquals("plain-refresh", plainVault(store, folder).read(id))
    }

    @Test
    fun `copied credential stays unreadable without the original user key`() {
        assumeTrue(isWindowsHost(), "DPAPI requires Windows")
        val store = testStore(temporary)
        val source = credentialFolder(temporary)
        dpapiVault(store, source).save(id, "copied-refresh")
        val destination = credentialFolder(temporary)
        destination.createDirectories()
        Files.copy(source.resolve("$id.json"), destination.resolve("$id.json"))
        assertNull(dpapiVault(store, destination).read(id))
    }

    @Test
    fun `credential copied to another account id is rejected`() {
        val store = testStore(temporary, "linux")
        val folder = credentialFolder(temporary)
        plainVault(store, folder).save(id, "account-bound")
        val other = UUID.randomUUID().toString()
        Files.copy(folder.resolve("$id.json"), folder.resolve("$other.json"))
        assertNull(plainVault(store, folder).read(other))
        assertEquals("account-bound", plainVault(store, folder).read(id))
    }

    @Test
    fun `tampered payload is rejected without falling back to the legacy entry`() {
        assumeTrue(isWindowsHost(), "DPAPI requires Windows")
        val store = testStore(temporary)
        val folder = credentialFolder(temporary)
        dpapiVault(store, folder).save(id, "test-refresh")
        val file = folder.resolve("$id.json")
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

                override fun remove(id: String) = Unit
            }
        val vault = TokenVault(store, legacy, folder, DpapiKeyProtection(folder.resolve("user-key.bin")))
        assertNull(vault.read(id))
        assertEquals(0, reads)
        assertEquals(envelope, readObject(file))
    }

    @Test
    fun `legacy migration writes the local file before retiring the old entry`() {
        val store = testStore(temporary, "linux")
        val folder = credentialFolder(temporary)
        var removed = false
        val legacy =
            object : LegacyTokenStore {
                override fun read(id: String) = "old-refresh"

                override fun remove(id: String) {
                    assertEquals("old-refresh", plainVault(store, folder).read(id))
                    removed = true
                }
            }
        assertEquals("old-refresh", TokenVault(store, legacy, folder, PlainCredentialProtection()).read(id))
        assertTrue(removed)
        assertTrue(folder.resolve("$id.json").isRegularFile())
    }

    @Test
    fun `failed migration keeps the legacy entry`() {
        val store = testStore(temporary, "linux")
        val blocked = credentialFolder(temporary)
        blocked.createDirectories()
        blocked.resolve("occupied").writeText("not a directory")
        var removed = false
        val legacy =
            object : LegacyTokenStore {
                override fun read(id: String) = "old-refresh"

                override fun remove(id: String) {
                    removed = true
                }
            }
        val target = blocked.resolve("occupied")
        assertFails { TokenVault(store, legacy, target, PlainCredentialProtection()).read(id) }
        assertFalse(removed)
    }

    @Test
    fun `remove clears the stored file and the session cache`() {
        val store = testStore(temporary, "linux")
        val folder = credentialFolder(temporary)
        val vault = plainVault(store, folder)
        vault.save(id, "private-refresh")
        vault.remove(id)
        assertNull(vault.read(id))
        assertNull(plainVault(store, folder).read(id))
        assertFalse(folder.resolve("$id.json").exists())
    }

    @Test
    fun `unsupported format and malformed input stay unchanged`() {
        val store = testStore(temporary, "linux")
        val folder = credentialFolder(temporary)
        folder.createDirectories()
        val file = folder.resolve("$id.json")
        val inputs =
            listOf(
                "{broken",
                """{"formatVersion":999,"protection":"unknown"}""",
                "x".repeat(65537),
            )
        for (input in inputs) {
            file.writeText(input)
            assertNull(plainVault(store, folder).read(id))
            assertEquals(input, file.readText())
        }
        assertFails { plainVault(store, folder).save("../outside", "value") }
        assertFalse(folder.resolve("outside.json").exists())
    }
}
