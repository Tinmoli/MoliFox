// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证目录查询分层、按需读取、错误传播和移动入口策略，不访问外部服务。

package dev.molifox.core

import dev.molifox.core.data.instance.FileInstanceWorkspaceRepository
import dev.molifox.core.domain.instance.GameFolder
import dev.molifox.core.domain.instance.InstanceWorkspaceQueries
import dev.molifox.core.domain.instance.instanceOperationAvailability
import dev.molifox.core.platform.files.FileMetadata
import dev.molifox.core.platform.files.FileSystemAccess
import dev.molifox.core.platform.files.NioFileSystemAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InstanceWorkspaceTest {
    @TempDir lateinit var temporary: Path

    private fun queries(): InstanceWorkspaceQueries =
        InstanceWorkspaceQueries(
            FileInstanceWorkspaceRepository({ temporary }, NioFileSystemAccess()) { _, _ -> },
        )

    @Test
    fun `shortcuts contain only existing directories in defined order`() =
        runBlocking {
            Files.createDirectories(temporary.resolve("mods"))
            Files.createDirectories(temporary.resolve("logs"))
            Files.writeString(temporary.resolve("screenshots"), "a file is not a folder")
            assertEquals(
                listOf(GameFolder.Root, GameFolder.Mods, GameFolder.Logs),
                queries().existingFolders("fixture").getOrThrow(),
            )
        }

    @Test
    fun `missing content folders are created once and existing ones are left alone`() =
        runBlocking {
            val useCase = queries()
            assertTrue(useCase.ensureFolder("fixture", GameFolder.ShaderPacks).isSuccess)
            assertTrue(Files.isDirectory(temporary.resolve("shaderpacks")))
            val marker = Files.writeString(temporary.resolve("shaderpacks/kept.txt"), "keep")
            assertTrue(useCase.ensureFolder("fixture", GameFolder.ShaderPacks).isSuccess)
            assertTrue(Files.exists(marker))
            assertTrue(useCase.ensureFolder("fixture", GameFolder.Root).isSuccess)
            assertEquals(
                listOf(GameFolder.Root, GameFolder.ShaderPacks),
                useCase.existingFolders("fixture").getOrThrow(),
            )
        }

    @Test
    fun `browsing sorts directories first and reads fresh metadata every time`() =
        runBlocking {
            val root = Files.createDirectories(temporary.resolve("schematics"))
            Files.createDirectories(root.resolve("Z-folder"))
            Files.writeString(root.resolve("B.litematic"), "one")
            val useCase = queries()
            val first = useCase.listDirectory("fixture", "schematics", emptyList()).getOrThrow()
            assertEquals(
                listOf("Z-folder", "B.litematic"),
                first.map { it.path.fileName.toString() },
            )
            assertEquals(3L, first.last().sizeBytes)
            Files.writeString(root.resolve("B.litematic"), "changed")
            val second = useCase.listDirectory("fixture", "schematics", emptyList()).getOrThrow()
            assertEquals(7L, second.last().sizeBytes)
            assertEquals(3L, first.last().sizeBytes)
        }

    @Test
    fun `missing directories return empty and parent traversal is rejected`(): Unit =
        runBlocking {
            assertTrue(
                queries()
                    .listDirectory(
                        "fixture",
                        "schematics",
                        emptyList(),
                    ).getOrThrow()
                    .isEmpty(),
            )
            val escaped = queries().listDirectory("fixture", "schematics", listOf("..", ".."))
            assertIs<IllegalArgumentException>(escaped.exceptionOrNull())
        }

    @Test
    fun `platform failures remain available to the caller and run off the calling thread`() =
        runBlocking {
            val callingThread = Thread.currentThread()
            var readThread: Thread? = null
            val failure = IOException("fixture read denied")
            val files =
                object : FileSystemAccess {
                    override fun metadata(path: Path): Result<FileMetadata> =
                        Result.failure(failure)

                    override fun children(directory: Path): Result<List<Path>> {
                        readThread = Thread.currentThread()
                        return Result.failure(failure)
                    }

                    override fun createDirectory(directory: Path): Result<Path> =
                        Result.failure(failure)
                }
            val repository = FileInstanceWorkspaceRepository({ temporary }, files) { _, _ -> }
            val result =
                InstanceWorkspaceQueries(
                    repository,
                ).listDirectory("fixture", "mods", emptyList())
            assertEquals(failure, result.exceptionOrNull())
            assertNotEquals(callingThread, readThread)
        }

    @Test
    fun `cancelled queries perform no platform reads`() =
        runBlocking {
            val reads = AtomicInteger()
            val files =
                object : FileSystemAccess {
                    override fun metadata(path: Path): Result<FileMetadata> {
                        reads.incrementAndGet()
                        return Result.success(FileMetadata(true, 0, Instant.EPOCH))
                    }

                    override fun children(directory: Path): Result<List<Path>> {
                        reads.incrementAndGet()
                        return Result.success(emptyList())
                    }

                    override fun createDirectory(directory: Path): Result<Path> {
                        reads.incrementAndGet()
                        return Result.success(directory)
                    }
                }
            val useCase =
                InstanceWorkspaceQueries(
                    FileInstanceWorkspaceRepository({ temporary }, files) { _, _ -> },
                )
            assertFailsWith<CancellationException> {
                withContext(Job().apply { cancel() }) { useCase.existingFolders("fixture") }
            }
            assertEquals(0, reads.get())
        }

    @Test
    fun `move is enabled only for an idle isolated managed instance`() {
        for (isolated in listOf(false, true)) {
            for (external in listOf(false, true)) {
                for (busy in listOf(false, true)) {
                    val result = instanceOperationAvailability(isolated, external, busy)
                    assertEquals(isolated && !external && !busy, result.move)
                    assertEquals(isolated && !busy, result.maintain)
                    assertEquals(!result.move, result.moveBlockedNote.isNotBlank())
                }
            }
        }
        assertFalse(instanceOperationAvailability(true, true, false).move)
    }
}
