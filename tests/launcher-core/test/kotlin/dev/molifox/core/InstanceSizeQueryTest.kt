// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证实例占用大小每次由控制器重新统计，不跨实例或跨调用复用旧值。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class InstanceSizeQueryTest {
    @TempDir lateinit var temporary: Path

    private fun version(
        controller: LauncherController,
        name: String,
        payloadBytes: Int,
    ): Path {
        val folder =
            controller.store.minecraftRoot
                .resolve("versions/$name")
                .createDirectories()
        folder.resolve("$name.json").writeText(
            """{"id":"$name","type":"release","mainClass":"fixture.Main","libraries":[]}""",
        )
        folder.resolve("$name.jar").writeText("")
        folder.resolve("payload.bin").writeText("x".repeat(payloadBytes))
        return folder
    }

    private fun bytesOf(folder: Path): Long =
        Files.walk(folder).use { files ->
            files
                .filter { Files.isRegularFile(it) }
                .mapToLong { Files.size(it) }
                .sum()
        }

    @Test
    fun `each instance is measured on its own and changed files produce a new size`(): Unit =
        runBlocking {
            LauncherController(dataDirectory = temporary.resolve("profile")).use { controller ->
                val alpha = version(controller, "Alpha", 1024)
                val beta = version(controller, "Beta", 4096)
                controller.store.refreshInstances()
                val alphaId = controller.store.instance("Alpha").id
                val betaId = controller.store.instance("Beta").id

                // 两行各自统计自己的目录：第二个实例不会拿到第一个实例的值。
                val alphaSize = controller.instanceDirectorySize(alphaId)
                val betaSize = controller.instanceDirectorySize(betaId)
                assertEquals(bytesOf(alpha), alphaSize)
                assertEquals(bytesOf(beta), betaSize)
                assertNotEquals(alphaSize, betaSize)

                // 目录内容变化后重新统计，不沿用上一次的结果。
                Files.write(alpha.resolve("added.bin"), ByteArray(2048))
                assertEquals(bytesOf(alpha), controller.instanceDirectorySize(alphaId))
            }
        }
}
