// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证一键更新的逐项隔离、取消语义与结果汇总。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContentUpdateBatchTest {
    private val temporary: Path = workTestDirectory("launcher-core-content-batch")

    @AfterEach
    fun cleanUp() {
        removeTestTree(temporary)
    }

    @Test
    fun `a failing item does not stop the rest of the batch`() =
        runBlocking {
            val context = JobContext(store(), "batch-job")
            val applied = mutableListOf<String>()
            val tasks =
                listOf(
                    ContentUpdateTask("a") { applied += "a" },
                    ContentUpdateTask("b") { throw IllegalStateException("下载失败") },
                    ContentUpdateTask("c") { applied += "c" },
                )
            val result = applyContentUpdateBatch(context, tasks)
            assertEquals(2, result.updated)
            assertEquals(listOf("a", "c"), applied)
            assertEquals(1, result.failures.size)
            assertTrue(result.failures.single().startsWith("b："))
            assertTrue(result.summary().contains("已更新 2 项"))
            assertTrue(result.summary().contains("1 项失败"))
        }

    @Test
    fun `cancellation stops the batch and keeps completed items`() =
        runBlocking {
            val context = JobContext(store(), "batch-job")
            val applied = mutableListOf<String>()
            val tasks =
                listOf(
                    ContentUpdateTask("a") { applied += "a" },
                    ContentUpdateTask("b") { throw CancellationException("用户取消") },
                    ContentUpdateTask("c") { applied += "c" },
                )
            assertFailsWith<CancellationException> {
                applyContentUpdateBatch(context, tasks)
            }
            assertEquals(listOf("a"), applied)
        }

    @Test
    fun `summary lists every failed item`() {
        val result =
            ContentUpdateBatchResult(
                updated = 1,
                failures = listOf("FoxPack.zip：更新文件哈希不符", "FoxShader.zip：网络中断"),
            )
        assertEquals(3, result.total)
        val summary = result.summary()
        assertTrue(summary.contains("已更新 1 项"))
        assertTrue(summary.contains("FoxPack.zip：更新文件哈希不符"))
        assertTrue(summary.contains("FoxShader.zip：网络中断"))
    }

    @Test
    fun `an empty batch reports no work and no failure`() =
        runBlocking {
            val context = JobContext(store(), "batch-job")
            val result = applyContentUpdateBatch(context, emptyList())
            assertEquals(0, result.total)
            assertEquals("已更新 0 项", result.summary())
        }

    @Test
    fun `progress reaches completion after the last item`() =
        runBlocking {
            val context = JobContext(store(), "batch-job")
            val observed = mutableListOf<Float>()
            context.progressObserver = { value, _ -> observed += value }
            applyContentUpdateBatch(
                context,
                listOf(
                    ContentUpdateTask("a") {},
                    ContentUpdateTask("b") {},
                ),
            )
            assertEquals(1f, observed.last())
        }

    private fun store(): LauncherStore =
        LauncherStore(
            object : PlatformServices by DesktopPlatform() {
                override val defaultDataDirectory =
                    temporary.resolve("profile-${UUID.randomUUID()}")
            },
        )
}
