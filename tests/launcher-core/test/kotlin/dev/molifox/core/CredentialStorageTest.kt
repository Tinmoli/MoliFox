// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证凭据目录解析与信封保护方式（Windows DPAPI、其他平台不加密）的行为。

package dev.molifox.core

import com.sun.jna.platform.win32.Crypt32Util
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun isWindowsHost() = System.getProperty("os.name").startsWith("Windows")

class CredentialStorageTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `windows credentials live under LOCALAPPDATA`() {
        val home = temporary.resolve("home")
        val local = temporary.resolve("local-app-data")
        assertEquals(
            local.resolve("MoliFox").resolve("credentials"),
            credentialDirectory("windows", mapOf("LOCALAPPDATA" to local.toString()), home),
        )
        assertEquals(
            home
                .resolve("AppData")
                .resolve("Local")
                .resolve("MoliFox")
                .resolve("credentials"),
            credentialDirectory("windows", emptyMap(), home),
        )
    }

    @Test
    fun `other platforms use XDG_DATA_HOME and fall back to the user share folder`() {
        val home = temporary.resolve("home")
        val data = temporary.resolve("xdg-data")
        assertEquals(
            data.resolve("molifox").resolve("credentials"),
            credentialDirectory("linux", mapOf("XDG_DATA_HOME" to data.toString()), home),
        )
        assertEquals(
            home
                .resolve(".local")
                .resolve("share")
                .resolve("molifox")
                .resolve("credentials"),
            credentialDirectory("linux", emptyMap(), home),
        )
        assertEquals(
            home
                .resolve(".local")
                .resolve("share")
                .resolve("molifox")
                .resolve("credentials"),
            credentialDirectory("macos", mapOf("XDG_DATA_HOME" to " "), home),
        )
    }

    @Test
    fun `windows protection is DPAPI-backed while other platforms are plain`() {
        val folder = temporary.resolve("credentials")
        val windows = defaultCredentialProtection("windows", folder)
        assertTrue(windows is DpapiKeyProtection)
        assertTrue(windows.protectsAtRest)
        val linux = defaultCredentialProtection("linux", folder)
        assertTrue(linux is PlainCredentialProtection)
        assertFalse(linux.protectsAtRest)
    }

    @Test
    fun `DPAPI user key is protected on disk and only opens its own account`() {
        assumeTrue(isWindowsHost(), "DPAPI requires Windows")
        val keyFile = temporary.resolve("store").resolve("user-key.bin")
        val protection = DpapiKeyProtection(keyFile)
        val id = UUID.randomUUID().toString()
        val plain = "refresh-token".toByteArray()
        val sealed = protection.seal(id, plain)
        assertEquals("molifox-dpapi-aesgcm-v1", sealed.string("protection"))
        assertEquals(id, sealed.string("account"))
        assertContentEquals(plain, protection.open(id, sealed))
        assertNull(protection.open(UUID.randomUUID().toString(), sealed))
        assertEquals(32, Crypt32Util.cryptUnprotectData(keyFile.readBytes()).size)
    }

    @Test
    fun `unreadable user key is quarantined and replaced`() {
        assumeTrue(isWindowsHost(), "DPAPI requires Windows")
        val folder = temporary.resolve("store")
        folder.createDirectories()
        val keyFile = folder.resolve("user-key.bin")
        keyFile.writeText("not a protected key")
        val protection = DpapiKeyProtection(keyFile)
        val id = UUID.randomUUID().toString()
        val plain = "fresh-refresh".toByteArray()
        val sealed = protection.seal(id, plain)
        assertContentEquals(plain, protection.open(id, sealed))
        assertTrue(folder.listDirectoryEntries().any { it.name.startsWith("user-key.bin.broken-") })
        assertTrue(keyFile.isRegularFile())
    }

    @Test
    fun `plain envelopes round-trip and reject foreign accounts or broken payloads`() {
        val protection = PlainCredentialProtection()
        val id = UUID.randomUUID().toString()
        val plain = "plain-refresh".toByteArray()
        val sealed = protection.seal(id, plain)
        assertEquals("molifox-plaintext-v1", sealed.string("protection"))
        assertFalse(sealed.has("nonce"))
        assertContentEquals(plain, protection.open(id, sealed))
        assertNull(protection.open(UUID.randomUUID().toString(), sealed))
        val broken = sealed.deepCopy().apply { addProperty("payload", "***") }
        assertNull(protection.open(id, broken))
        assertFalse(protection.protectsAtRest)
        assertFalse(temporary.resolve("user-key.bin").exists())
    }
}
