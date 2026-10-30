// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证游戏及加载器与 Java 版本的兼容判断的行为与边界。

package dev.molifox.core

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaCompatibilityTest {
    private fun runtime(
        major: Int,
        arch: String = "amd64",
    ) = JavaRuntime("java", major, "Test", arch)

    @Test
    fun `modern vanilla accepts compatible system Java but rejects wrong bitness and old Java`() {
        val profile =
            ResolvedProfile(
                "modern",
                "modern",
                JsonParser.parseString("""{"javaVersion":{"majorVersion":17}}""").asJsonObject,
            )
        val requirement = JavaRequirement.from(profile)
        assertNull(requirement.problem(runtime(21), "x64"))
        assertNull(requirement.problem(runtime(17, "x86_64"), "amd64"))
        assertNotNull(requirement.problem(runtime(17, "x86"), "x64"))
        assertNotNull(requirement.problem(runtime(8), "x64"))
        assertFalse(requirement.exactAutomatic)
    }

    @Test
    fun `legacy LaunchWrapper selects a compatible Java conservatively`() {
        val profile =
            ResolvedProfile(
                "legacy",
                "legacy",
                JsonParser
                    .parseString(
                        """{"javaVersion":{"majorVersion":8},"libraries":[{"name":"net.minecraft:launchwrapper:1.12"},{"name":"net.minecraftforge:forge:1.12.2"}]}""",
                    ).asJsonObject,
            )
        val requirement = JavaRequirement.from(profile)
        assertNull(requirement.problem(runtime(8), "x64"))
        assertNotNull(requirement.problem(runtime(17), "x64"))
        assertTrue(requirement.exactAutomatic)
    }
}
