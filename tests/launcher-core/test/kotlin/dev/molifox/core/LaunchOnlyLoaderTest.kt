// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// LiteLoader/Cleanroom 只支持识别和启动，不提供加载器升级候选。
package dev.molifox.core

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class LaunchOnlyLoaderTest {
    private fun instance(
        loader: LoaderType,
        loaderVersion: String = "1.0",
    ) = GameInstance(
        name = loader.name,
        installed = true,
        gameVersion = "1.12.2",
        loader = loader,
        loaderVersion = loaderVersion,
    )

    @Test
    fun `launch-only loaders have no upgrade candidates`() {
        val available = listOf(LoaderVersion("2.0"), LoaderVersion("1.0"))
        listOf(LoaderType.LiteLoader, LoaderType.Cleanroom).forEach { loader ->
            assertTrue(loader.launchOnly)
            assertTrue(loaderUpgradeCandidates(instance(loader), available).isEmpty())
        }
    }

    @Test
    fun `regular loaders still have upgrade candidates`() {
        val available = listOf(LoaderVersion("2.0"), LoaderVersion("1.0"))
        val candidates = loaderUpgradeCandidates(instance(LoaderType.Forge), available)
        assertTrue(candidates.isNotEmpty())
    }
}
