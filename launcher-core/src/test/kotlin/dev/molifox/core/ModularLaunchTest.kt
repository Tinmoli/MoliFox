// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证模块化加载器的继承参数与类路径的行为与边界。

// Verifies that modular loader exclusions refer to the actual game JAR after profile renaming.
package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModularLaunchTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `renamed inherited profile excludes its base jar without changing game version argument`() =
        runBlocking {
            val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
            val instance =
                GameInstance(
                    name = "Renamed pack",
                    gameVersion = "1.21.1",
                    versionId = "renamed-neoforge",
                )
            store.mutate { it.copy(instances = listOf(instance)) }
            val json =
                com.google.gson.JsonParser
                    .parseString(
                        """{"mainClass":"cpw.mods.bootstraplauncher.BootstrapLauncher","arguments":{"jvm":["-cp","${'$'}{classpath}","-DignoreList=client-extra,${'$'}{version_name}.jar"],"game":["--version","${'$'}{version_name}"]}}""",
                    ).asJsonObject
            val plan =
                LaunchPlanner(store)
                    .plan(
                        instance,
                        ResolvedProfile(instance.versionId, "1.21.1", json),
                        JavaRuntime("java", 21, "Test", "amd64"),
                        GameCredentials("Player", "0".repeat(32), "0", "legacy"),
                        prepareFiles = false,
                    )
            assertContains(plan.arguments, "-DignoreList=client-extra,1.21.1.jar")
            assertEquals(
                instance.versionId,
                plan.arguments[plan.arguments.indexOf("--version") + 1],
            )
            assertTrue(plan.arguments[plan.arguments.indexOf("-cp") + 1].endsWith("1.21.1.jar"))
        }

    @Test
    fun `explicit library exclusions are kept when the actual game jar has another name`() =
        runBlocking {
            val store = LauncherStore(DesktopPlatform(), temporary.resolve("explicit"))
            val instance =
                GameInstance(name = "Explicit", gameVersion = "1.21.1", versionId = "custom")
            store.mutate { it.copy(instances = listOf(instance)) }
            val json =
                com.google.gson.JsonParser
                    .parseString(
                        """{"mainClass":"Fixture","arguments":{"jvm":["-DignoreList=client-extra,explicit.jar,custom.jar-suffix"],"game":[]}}""",
                    ).asJsonObject
            val plan =
                LaunchPlanner(store)
                    .plan(
                        instance,
                        ResolvedProfile("custom", "1.21.1", json),
                        JavaRuntime("java", 21, "Test", "amd64"),
                        GameCredentials("Player", "0".repeat(32), "0", "legacy"),
                        prepareFiles = false,
                    )
            assertContains(
                plan.arguments,
                "-DignoreList=client-extra,explicit.jar,custom.jar-suffix",
            )
        }
}
