// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证启动目录与便携存储的迁移初始化的行为与边界。

package dev.molifox.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StartupStorageTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `access denial reports chosen directory without trying another profile`() {
        val target = directory.resolve("explicit")
        val attempts = mutableListOf<Path>()
        val resolver =
            StartupStorageResolver { candidate ->
                attempts.add(candidate)
                throw AccessDeniedException(candidate.resolve("instance.lock").toString())
            }
        val error = assertFailsWith<IOException> { resolver.resolve(target) }
        assertEquals(listOf(target), attempts)
        assertTrue(checkNotNull(error.message).contains(target.toString()))
        assertIs<AccessDeniedException>(error.cause)
        assertTrue(directory.listDirectoryEntries().isEmpty())
    }

    @Test
    fun `startup validates portable data directory without leaving probe files`() {
        val target = directory.resolve("MoliFox")
        assertEquals(target, StartupStorageResolver().resolve(target))
        assertTrue(target.resolve("instance.lock").isRegularFile())
        assertEquals(listOf("instance.lock"), target.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `security error retains actionable path and original cause`() {
        val target = directory.resolve("restricted")
        val error =
            assertFailsWith<IOException> {
                StartupStorageResolver { throw SecurityException("denied") }.resolve(target)
            }
        assertTrue(checkNotNull(error.message).contains(target.toString()))
        assertIs<SecurityException>(error.cause)
    }

    @Test
    fun `existing configuration credentials and lock contents remain unchanged`() {
        val target = directory.resolve("MoliFox").createDirectories()
        val metadata = """{"settings":{"gameRoot":"/existing/game/folder"},"accounts":[]}"""
        target.resolve("launcher.json").writeText(metadata)
        target.resolve("credentials").createDirectories()
        target.resolve("credentials/account.bin").writeBytes(byteArrayOf(1, 2, 3))
        target.resolve("instance.lock").writeText("existing lock")
        StartupStorageResolver().resolve(target)
        assertEquals(metadata, target.resolve("launcher.json").readText())
        assertEquals("existing lock", target.resolve("instance.lock").readText())
        assertContentEquals(
            byteArrayOf(1, 2, 3),
            target.resolve("credentials/account.bin").readBytes(),
        )
    }

    @Test
    fun `file occupying data directory fails without overwriting it`() {
        val target = directory.resolve("MoliFox").apply { writeText("keep me") }
        assertFailsWith<IOException> { StartupStorageResolver().resolve(target) }
        assertEquals("keep me", target.readText())
    }
}
