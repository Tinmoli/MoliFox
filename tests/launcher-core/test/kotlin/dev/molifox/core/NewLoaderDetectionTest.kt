// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证新增加载器（OptiFine/LiteLoader/LegacyFabric/Cleanroom）的识别与远古版本 Java 规则。

package dev.molifox.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NewLoaderDetectionTest {
    @Test
    fun `legacy fabric keeps its loader version from the fabric coordinate`() {
        assertEquals(
            LoaderType.LegacyFabric to "0.20.1",
            detectLoader("legacy-fabric-1.8.9", listOf("net.fabricmc:fabric-loader:0.20.1")),
        )
    }

    @Test
    fun `optifine is detected from its library coordinate`() {
        assertEquals(
            LoaderType.OptiFine to "1.8.9_HD_U_M5",
            detectLoader("1.8.9", listOf("optifine:OptiFine:1.8.9_HD_U_M5")),
        )
    }

    @Test
    fun `liteloader is detected from its library coordinate`() {
        assertEquals(
            LoaderType.LiteLoader to "1.12.2-SNAPSHOT",
            detectLoader("1.12.2", listOf("com.mumfrey:liteloader:1.12.2-SNAPSHOT")),
        )
    }

    @Test
    fun `cleanroom is detected from its library coordinate`() {
        assertEquals(
            LoaderType.Cleanroom to "0.7.14-alpha",
            detectLoader("1.12.2", listOf("com.cleanroommc:cleanroom:0.7.14-alpha")),
        )
    }

    @Test
    fun `old alpha and beta versions use java 8`() {
        assertEquals(8, gameJavaMajor("a1.2.6"))
        assertEquals(8, gameJavaMajor("b1.7.3"))
        assertEquals(8, gameJavaMajor("c0.30_01c"))
    }
}
