// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证存档提交在临时占用、永久拒绝和原子移动不支持时的文件结果。

package dev.molifox.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.fail

class SaveFileCommitterTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `temporary Windows denial eventually commits the complete save`() {
        val source = temporary.resolve("staging").createDirectory()
        source.resolve("level.dat").writeText("world")
        val target = temporary.resolve("World")
        var attempts = 0
        val delays = mutableListOf<Long>()
        commitSavePath(
            source,
            target,
            windows = true,
            move = { from, to, _ ->
                if (++attempts < 3) throw AccessDeniedException(from.toString())
                Files.move(from, to)
            },
            pause = { delays.add(it) },
        )
        assertEquals(3, attempts)
        assertEquals(listOf(50L, 100L), delays)
        assertEquals("world", target.resolve("level.dat").readText())
        assertFalse(source.exists())
    }

    @Test
    fun `permanent denial preserves staging and reports the original error`() {
        val source = temporary.resolve("staging").apply { writeText("world") }
        val target = temporary.resolve("World")
        val failure = AccessDeniedException(source.toString())
        var attempts = 0
        val thrown =
            assertFailsWith<AccessDeniedException> {
                commitSavePath(
                    source,
                    target,
                    windows = true,
                    move = { _, _, _ ->
                        attempts++
                        throw failure
                    },
                    pause = {},
                )
            }
        assertSame(failure, thrown)
        assertEquals(4, attempts)
        assertEquals("world", source.readText())
        assertFalse(target.exists())
    }

    @Test
    fun `existing destination is never retried or overwritten`() {
        val source = temporary.resolve("staging").apply { writeText("new") }
        val target = temporary.resolve("World").apply { writeText("existing") }
        var attempts = 0
        assertFailsWith<FileAlreadyExistsException> {
            commitSavePath(
                source,
                target,
                windows = true,
                move = { from, to, _ ->
                    attempts++
                    Files.move(from, to)
                },
                pause = { fail("Existing files must not be retried") },
            )
        }
        assertEquals(1, attempts)
        assertEquals("new", source.readText())
        assertEquals("existing", target.readText())
    }

    @Test
    fun `unsupported atomic move falls back to an ordinary move`() {
        val source = temporary.resolve("staging").apply { writeText("world") }
        val target = temporary.resolve("World")
        val modes = mutableListOf<Boolean>()
        commitSavePath(
            source,
            target,
            move = { from, to, atomic ->
                modes.add(atomic)
                if (atomic) {
                    throw AtomicMoveNotSupportedException(from.toString(), to.toString(), "fixture")
                }
                Files.move(from, to)
            },
        )
        assertEquals(listOf(true, false), modes)
        assertEquals("world", target.readText())
        assertFalse(source.exists())
    }

    @Test
    fun `non Windows access denial is reported without retry`() {
        var attempts = 0
        assertFailsWith<AccessDeniedException> {
            commitSavePath(
                temporary.resolve("source"),
                temporary.resolve("target"),
                windows = false,
                move = { from, _, _ ->
                    attempts++
                    throw AccessDeniedException(from.toString())
                },
                pause = { fail("Non-Windows denial must not be retried") },
            )
        }
        assertEquals(1, attempts)
    }
}
