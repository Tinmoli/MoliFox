// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 所有递归删除入口都必须只删链接本身：符号链接与 Windows 目录联接指向的内容属于别处。

package dev.molifox.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/** 统一删除入口的链接安全回归：删除目录只清空该目录，不越过链接。 */
class DirectoryLinkDeleteTest {
    @TempDir
    lateinit var temporary: Path

    /** 在 [owner] 里放一个指向 [external] 的目录链接，并写入外部敏感文件。 */
    private fun prepare(name: String): Triple<Path, Path, Path> {
        val external = temporary.resolve("$name-outside/data")
        external.createDirectories()
        external.resolve("keep.txt").writeText("user data")
        external.resolve("nested").createDirectories()
        external.resolve("nested/deep.txt").writeText("user data")
        val owner = temporary.resolve("$name-owner")
        owner.createDirectories()
        val link = owner.resolve("linked")
        createTestDirectoryLink(link, external)
        return Triple(owner, external, link)
    }

    private fun assertExternalKept(
        label: String,
        owner: Path,
        external: Path,
    ) {
        assertTrue(!owner.exists(), "$label：删除入口应清掉自己的目录")
        assertTrue(external.exists(), "$label：链接指向的目录不能被删")
        assertTrue(external.resolve("keep.txt").isRegularFile(), "$label：外部文件必须保留")
        assertTrue(external.resolve("nested/deep.txt").isRegularFile(), "$label：外部子目录必须保留")
    }

    @Test
    fun `pack delete helper keeps linked content`() {
        val (owner, external, _) = prepare("packs")
        deleteTreeOrFile(owner)
        assertExternalKept("deleteTreeOrFile", owner, external)
    }

    @Test
    fun `java runtime staging delete keeps linked content`() {
        val (owner, external, _) = prepare("java")
        deleteTree(owner)
        assertExternalKept("deleteTree", owner, external)
    }

    @Test
    fun `migration manifest delete keeps linked content`() {
        val (owner, external, _) = prepare("migration")
        MigrationManifest.deleteTree(owner)
        assertExternalKept("MigrationManifest.deleteTree", owner, external)
    }

    @Test
    fun `instance deletion keeps linked content`() {
        val (owner, external, _) = prepare("instance")
        deletionDeleteTree(owner)
        assertExternalKept("deletionDeleteTree", owner, external)
    }

    @Test
    fun `linking a single file inside the tree is removed as a link`() {
        // 单文件链接：应删链接，不动真实文件。
        // Windows 普通账户默认没有创建文件符号链接的特权；建不出来就跳过，不把环境限制当成通过。
        val target = temporary.resolve("linked-file-outside/real.txt")
        target.parent.createDirectories()
        target.writeText("real content")
        val owner = temporary.resolve("file-owner")
        owner.createDirectories()
        val link = owner.resolve("linked.txt")
        val created = runCatching { Files.createSymbolicLink(link, target) }.isSuccess
        if (!created) {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                false,
                "当前账户无法创建文件符号链接，跳过该用例",
            )
        }

        deletionDeleteTree(owner)
        assertTrue(!owner.exists())
        assertTrue(target.isRegularFile(), "文件链接指向的真实文件必须保留")
    }
}
