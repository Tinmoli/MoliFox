// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证更新报告在改名、禁用及重名情况下的规则，不依赖界面或外部服务。

package dev.molifox.core

import dev.molifox.core.domain.mods.completeModUpdateReport
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class CompleteModUpdateTest {
    private val candidate =
        ModUpdateCandidate(
            projectId = "fixture",
            projectTitle = "Fixture",
            versionId = "new",
            versionNumber = "2.0",
            filename = "fixture-new.jar",
            downloadUrl = "https://fixture.invalid/file.jar",
            size = 10,
            sha512 = "",
            sha1 = "",
            gameVersions = listOf("1.20.1"),
            loaders = listOf("fabric"),
            provider = "modrinth",
        )

    private fun pending(filename: String = "fixture-old.jar") =
        ModUpdateItem(
            filename = filename,
            title = "Fixture",
            currentVersion = "1.0",
            enabled = !filename.endsWith(".disabled", ignoreCase = true),
            size = 8,
            modified = 0,
            status = ModUpdateStatus.Updatable,
            latestVersion = "2.0",
            candidate = candidate,
            possibleMatch = true,
            applicable = true,
            warning = "confirm source",
        )

    private fun report(vararg items: ModUpdateItem) =
        ModUpdateReport("fixture", "1.20.1", LoaderType.Fabric, items.toList())

    @Test
    fun `completed update renames the mod and preserves the original report`() {
        val completed = pending()
        val other = pending("unrelated.jar")
        val original = report(completed, other)
        val updated = checkNotNull(completeModUpdateReport(original, completed))
        val first = updated.items.first()
        assertEquals("fixture-new.jar", first.filename)
        assertEquals("2.0", first.currentVersion)
        assertEquals(ModUpdateStatus.Latest, first.status)
        assertEquals("", first.latestVersion)
        assertNull(first.candidate)
        assertFalse(first.applicable)
        assertFalse(first.possibleMatch)
        assertEquals("", first.warning)
        assertSame(other, updated.items.last())
        assertEquals("fixture-old.jar", original.items.first().filename)
        assertSame(candidate, original.items.first().candidate)
    }

    @Test
    fun `a disabled mod keeps its disabled suffix after an update`() {
        val completed = pending("fixture-old.jar.DISABLED")
        val updated = checkNotNull(completeModUpdateReport(report(completed), completed))
        assertEquals("fixture-new.jar.disabled", updated.items.single().filename)
        assertFalse(updated.items.single().enabled)
    }

    @Test
    fun `missing candidates and missing reports do not invent update results`() {
        val completed = pending().copy(candidate = null)
        val original = report(completed)
        assertSame(original, completeModUpdateReport(original, completed))
        assertNull(completeModUpdateReport(null, pending()))
    }

    @Test
    fun `colliding destination names retain the first report entry`() {
        val completed = pending()
        val existing = pending("fixture-new.jar")
        val updated = checkNotNull(completeModUpdateReport(report(completed, existing), completed))
        assertEquals(1, updated.items.size)
        assertEquals(ModUpdateStatus.Latest, updated.items.single().status)
    }
}
