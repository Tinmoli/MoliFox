// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例删除不得跟随目录链接（Windows 目录联接）删掉链接指向的外部文件。

package dev.molifox.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/** 删除路径必须只删链接本身，绝不进入链接指向的真实目录。 */
class InstanceDeletionLinkTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `permanent deletion keeps files behind a directory link`() {
        // 模拟用户把存档/资源目录用目录联接放进实例，真实内容在实例之外。
        val external = temporary.resolve("outside/important")
        external.createDirectories()
        external.resolve("world.dat").writeText("precious user data")
        external.resolve("nested").createDirectories()
        external.resolve("nested/config.txt").writeText("still needed")

        val owner = temporary.resolve("owner")
        owner.createDirectories()
        val link = owner.resolve("linked-saves")
        createTestDirectoryLink(link, external)
        assertTrue(link.isDirectory())

        deletionDeleteTree(owner)

        assertTrue(!owner.exists(), "链接所在的实例目录应被删除")
        assertTrue(external.exists(), "链接指向的真实目录不能被删掉")
        assertTrue(external.resolve("world.dat").isRegularFile(), "真实文件必须保留")
        assertTrue(external.resolve("nested/config.txt").isRegularFile(), "真实子目录内容必须保留")
    }

    @Test
    fun `link nested deeper in the tree is also unlinked without following`() {
        val external = temporary.resolve("outside2/data")
        external.createDirectories()
        external.resolve("keep.txt").writeText("keep me")

        val owner = temporary.resolve("owner2")
        val inner = owner.resolve("version/sub")
        inner.createDirectories()
        inner.resolve("normal.txt").writeText("deleted normally")
        val link = inner.resolve("external")
        createTestDirectoryLink(link, external)

        deletionDeleteTree(owner)

        assertTrue(!owner.exists())
        assertTrue(external.resolve("keep.txt").isRegularFile(), "深层链接指向的内容必须保留")
    }

    @Test
    fun `directory size counting does not follow links`() {
        val external = temporary.resolve("outside3/big")
        external.createDirectories()
        val blob = external.resolve("blob.bin")
        Files.write(blob, ByteArray(4096))

        val owner = temporary.resolve("owner3")
        owner.createDirectories()
        val localFile = owner.resolve("local.txt")
        Files.write(localFile, ByteArray(16))
        val link = owner.resolve("external")
        createTestDirectoryLink(link, external)

        // 只统计实例自己的文件；链接背后的 4096 字节不能算进来。
        val counted = deletionBytesOf(owner)
        assertTrue(counted < 4096, "目录链接指向的内容不应计入实例占用：$counted")
    }
}
