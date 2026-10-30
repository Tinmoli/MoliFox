// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证实例级启动设置的解析、校验与真实命令行行为。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstanceLaunchOptionsTest : InstanceLaunchOptionsTestSupport() {
    @Test
    fun `explicit instance memory beats global and zero falls back`() {
        val global = settings(memoryMb = 2048)
        val explicit =
            resolveInstanceLaunchOptions(
                global,
                instance(memoryMb = 4096),
                null,
                16384,
                8192,
                "x64",
            )
        assertEquals(4096, explicit.memory.maximumMb)
        val inherited =
            resolveInstanceLaunchOptions(global, instance(memoryMb = 0), null, 16384, 8192, "x64")
        assertEquals(2048, inherited.memory.maximumMb)
        val automatic =
            resolveInstanceLaunchOptions(
                global.copy(autoMemory = true),
                instance(memoryMb = 0),
                null,
                16384,
                8192,
                "x64",
            )
        assertTrue(automatic.memory.automatic)
        assertTrue(automatic.memory.maximumMb in 512..16384)
    }

    @Test
    fun `launch command emits exactly one Xmx with the instance value`() {
        val plan =
            plan(
                instance(memoryMb = 4096, extraJvmArgs = "-Dinstance.flag=1"),
                settings(memoryMb = 2048, extraJvmArgs = "-Dglobal.flag=1"),
            )
        assertEquals(1, plan.arguments.count { it.startsWith("-Xmx") })
        assertEquals(1, plan.arguments.count { it.startsWith("-Xms") })
        assertContains(plan.arguments, "-Xmx4096M")
        assertContains(plan.arguments, "-Dinstance.flag=1")
        assertFalse(plan.arguments.contains("-Dglobal.flag=1"))
        assertTrue(
            plan.arguments.indexOf("-Dinstance.flag=1") < plan.arguments.indexOf("fixture.Main"),
        )
        assertEquals("启动参数：内存 4096 MB，窗口 1280×720，额外 JVM -Dinstance.flag=1", plan.summary)
    }

    @Test
    fun `window and extra game args go through the normal game argument mechanism`() {
        val plan =
            plan(
                instance(
                    memoryMb = 0,
                    width = 1920,
                    height = 1080,
                    windowMode = "fullscreen",
                    extraGameArgs = "--demo --name \"my world\"",
                ),
                settings(memoryMb = 2048),
            )
        val main = plan.arguments.indexOf("fixture.Main")
        val width = plan.arguments.indexOf("--width")
        assertEquals("1920", plan.arguments[width + 1])
        assertEquals("1080", plan.arguments[width + 3])
        val fullscreen = plan.arguments.indexOf("--fullscreen")
        assertTrue(width > main && fullscreen > width)
        val demo = plan.arguments.indexOf("--demo")
        assertTrue(demo > fullscreen)
        assertEquals("my world", plan.arguments[plan.arguments.indexOf("--name") + 1])
        assertEquals("--name", plan.arguments[demo + 1])
    }

    @Test
    fun `fullscreen and window fall back to the global window settings`() {
        val inherited =
            plan(
                instance(windowMode = "inherit", width = 0, height = 0),
                settings(fullscreen = true, width = 1024, height = 768),
            )
        assertEquals("1024", inherited.arguments[inherited.arguments.indexOf("--width") + 1])
        assertEquals("768", inherited.arguments[inherited.arguments.indexOf("--height") + 1])
        assertContains(inherited.arguments, "--fullscreen")
        val windowed =
            plan(instance(windowMode = "windowed"), settings(fullscreen = true))
        assertFalse(windowed.arguments.contains("--fullscreen"))
        assertLauncherSizedFallback()
    }

    @Test
    fun `invalid launch values are rejected by the shared validator`() {
        fun rejected(
            memory: Int,
            width: Int,
            height: Int,
            mode: String,
            jvm: String,
            game: String,
        ) {
            assertFailsWith<IllegalArgumentException> {
                validateInstanceLaunchOptions(memory, width, height, mode, jvm, game)
            }
        }
        rejected(256, 0, 0, "inherit", "", "")
        rejected(32769, 0, 0, "inherit", "", "")
        rejected(0, 319, 0, "inherit", "", "")
        rejected(0, 0, 7681, "inherit", "", "")
        rejected(0, 0, 0, "bogus", "", "")
        rejected(0, 0, 0, "inherit", "-Xmx2G", "")
        rejected(0, 0, 0, "inherit", "", "--width 800")
        assertFailsWith<IllegalArgumentException> {
            validateInstanceLaunchOptions(32768, 0, 0, "inherit", "", "", totalMemoryMb = 8192)
        }
    }

    @Test
    fun `broken persisted values fall back to global with warnings and never block launch`() {
        val plan =
            plan(
                instance(
                    memoryMb = 999_999,
                    width = -4,
                    height = 99_999,
                    windowMode = "bogus",
                    extraJvmArgs = "-Xmx8G",
                    extraGameArgs = "--width 800",
                ),
                settings(memoryMb = 2048, width = 1280, height = 720),
            )
        assertEquals(1, plan.arguments.count { it.startsWith("-Xmx") })
        assertContains(plan.arguments, "-Xmx2048M")
        assertEquals("1280", plan.arguments[plan.arguments.indexOf("--width") + 1])
        assertEquals("720", plan.arguments[plan.arguments.indexOf("--height") + 1])
        assertFalse(plan.arguments.contains("--width 800"))
        assertFalse(plan.arguments.contains("-Xmx8G"))
        assertFalse(plan.arguments.contains("--fullscreen"))
        assertTrue(plan.warnings.isNotEmpty())
        assertTrue(plan.warnings.any { it.contains("内存") })
        assertTrue(plan.warnings.any { it.contains("游戏窗口") })
        assertTrue(plan.warnings.any { it.contains("JVM") })
        assertTrue(plan.warnings.any { it.contains("游戏参数") })
    }

    @Test
    fun `extra game args are stored in the per instance settings map`() {
        val root = temporary.resolve("persist")
        val store = LauncherStore(DesktopPlatform(), root)
        val instance = instance(extraGameArgs = "--demo")
        store.mutate { it.copy(instances = listOf(instance)) }
        assertEquals("--demo", store.data.instanceSettings["fixture"]?.extraGameArgs)
        val reloaded = LauncherStore(DesktopPlatform(), root)
        assertEquals("--demo", reloaded.instanceSettings("fixture")?.extraGameArgs)
    }

    /** 窗口模式为 launcher 时沿用上次启动器窗口尺寸，而不是 1280×720 默认值。 */
    private fun assertLauncherSizedFallback() {
        val launcherSized =
            runBlocking {
                val store = LauncherStore(DesktopPlatform(), temporary.resolve("launcher-size"))
                val inheritedInstance = instance(memoryMb = 0)
                store.mutate {
                    it.copy(
                        settings = settings(windowMode = "launcher"),
                        instances = listOf(inheritedInstance),
                    )
                }
                store.launcherWindowSize = 1512 to 982
                LaunchPlanner(store)
                    .plan(
                        inheritedInstance,
                        profile(),
                        JavaRuntime("java", 21, "Test", "amd64"),
                        GameCredentials("Player", "0".repeat(32), "0", "legacy"),
                        prepareFiles = false,
                    )
            }
        assertEquals(
            "1512",
            launcherSized.arguments[launcherSized.arguments.indexOf("--width") + 1],
        )
        assertEquals(
            "982",
            launcherSized.arguments[launcherSized.arguments.indexOf("--height") + 1],
        )
    }
}
