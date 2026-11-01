// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证「远程资源」开关：关闭时保留本地文件并直接写入，不再查询远程来源。

package dev.molifox.desktop

import dev.molifox.core.PackExportOptions
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportRemoteResourcesTest {
    @Test
    fun `remote resources off keeps local files for direct export`() {
        val options =
            exportEffectiveOptions(
                PackExportOptions(includeLocalResources = true, remoteResources = false),
                selectedEntries = setOf("mods", "resourcepacks"),
                includeLogs = false,
            )
        assertFalse(options.remoteResources)
        assertTrue(options.includeLocalResources)
    }

    @Test
    fun `remote resources on keeps remote lookup enabled`() {
        val options =
            exportEffectiveOptions(
                PackExportOptions(includeLocalResources = true, remoteResources = true),
                selectedEntries = setOf("mods"),
                includeLogs = false,
            )
        assertTrue(options.remoteResources)
        assertTrue(options.includeLocalResources)
    }
}
