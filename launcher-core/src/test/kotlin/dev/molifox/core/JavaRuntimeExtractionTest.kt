// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证 Java 运行时解压的健壮性：只读同名文件、文件系统错误分类与重试提示。

package dev.molifox.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.setAttribute
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaRuntimeExtractionTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `extraction overwrites a pre-created read-only target file`() {
        val archive = temporary.resolve("zulu25.zip")
        writeZip(
            archive,
            mapOf(
                "zulu25.38.109-ca-jre25.0.5.0.3-win_x64/bin/java.exe" to "java",
                "zulu25.38.109-ca-jre25.0.5.0.3-win_x64/bin/msvcp140.dll" to "runtime",
            ),
        )
        val root = temporary.resolve("unpack").createDirectories()
        // 模拟上次解压残留/安全软件留下的同名只读文件：不先清属性，Windows 上写入会 AccessDenied。
        val dll = root.resolve("zulu25.38.109-ca-jre25.0.5.0.3-win_x64/bin/msvcp140.dll")
        dll.parent.createDirectories()
        dll.writeText("stale")
        markReadOnly(dll)

        extractZipRuntime(archive, root)

        assertEquals("runtime", dll.readText())
        assertEquals(
            "java",
            root.resolve("zulu25.38.109-ca-jre25.0.5.0.3-win_x64/bin/java.exe").readText(),
        )
    }

    @Test
    fun `blocked target is reported as a filesystem error, not a network error`() {
        val archive = temporary.resolve("zulu25.zip")
        writeZip(archive, mapOf("zulu25/bin/msvcp140.dll" to "runtime"))
        val root = temporary.resolve("unpack").createDirectories()
        val blocked = root.resolve("zulu25/bin/msvcp140.dll")
        // 用非空同名目录占位：删除与写入都会失败，模拟被安全软件占用/权限不足的文件。
        blocked.createDirectories().resolve("occupied.tmp").writeText("occupied")

        val logs = mutableListOf<String>()
        val failure =
            assertFailsWith<RuntimeFileWriteException> {
                extractZipRuntime(archive, root) { logs += it }
            }

        assertEquals(blocked.toAbsolutePath().normalize(), failure.file)
        assertTrue(logs.isNotEmpty(), "失败必须留下日志")
        assertTrue(keepsExtractionDiagnostics(failure), "文件系统错误要保留 .part 目录")
        assertFalse(
            keepsExtractionDiagnostics(java.io.IOException("下载中断")),
            "网络错误不应保留解压目录",
        )
        val message = javaInstallFailureReason(failure, 25, root)
        assertTrue(message.contains("无法写入"), message)
        assertTrue(message.contains("杀毒软件"), message)
        assertTrue(message.contains("MoliFox\\runtimes"), message)
        assertTrue(!message.contains("网络错误"), message)
        assertTrue(!message.contains("安装失败（网络错误）"), message)
    }

    @Test
    fun `network class failures keep the network reason`() {
        val message =
            javaInstallFailureReason(
                DownloadVerificationException("SHA-256 校验失败"),
                21,
            )
        assertTrue(message.contains("校验失败"), message)
    }

    private fun markReadOnly(target: Path) {
        runCatching { target.toFile().setWritable(false) }
        if (System.getProperty("os.name").lowercase().contains("win")) {
            runCatching { Files.setAttribute(target, "dos:readonly", true) }
        } else {
            runCatching {
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--r--r--"))
            }
        }
    }

    private fun writeZip(
        archive: Path,
        entries: Map<String, String>,
    ) {
        ZipOutputStream(archive.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
