// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证实例级启动设置的解析、校验与真实命令行行为。

package dev.molifox.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.writeBytes
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceLaunchOptionsTest {
    @TempDir lateinit var temporary: Path

    private fun settings(
        memoryMb: Int = 2048,
        autoMemory: Boolean = false,
        width: Int = 1280,
        height: Int = 720,
        fullscreen: Boolean = false,
        windowMode: String = "custom",
        extraJvmArgs: String = "",
    ): LauncherSettings =
        LauncherSettings(
            memoryMb = memoryMb,
            autoMemory = autoMemory,
            initialMemoryMb = 512,
            width = width,
            height = height,
            fullscreen = fullscreen,
            gameWindowMode = windowMode,
            extraJvmArgs = extraJvmArgs,
        )

    private fun instance(
        memoryMb: Int = 0,
        width: Int = 0,
        height: Int = 0,
        windowMode: String = "inherit",
        extraJvmArgs: String = "",
        extraGameArgs: String = "",
    ): GameInstance =
        GameInstance(
            name = "Fixture",
            gameVersion = "1.21.1",
            versionId = "fixture",
            memoryMb = memoryMb,
            width = width,
            height = height,
            windowMode = windowMode,
            extraJvmArgs = extraJvmArgs,
            extraGameArgs = extraGameArgs,
        )

    private fun profile(): ResolvedProfile {
        val json =
            JsonParser
                .parseString(
                    """{"id":"fixture","type":"release","mainClass":"fixture.Main","libraries":[],"minecraftArguments":"--username ${'$'}{auth_player_name}"}""",
                ).asJsonObject
        return ResolvedProfile("fixture", "1.21.1", json)
    }

    private fun plan(
        instance: GameInstance,
        settings: LauncherSettings,
    ): LaunchPlan =
        runBlocking {
            val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
            store.mutate { it.copy(settings = settings, instances = listOf(instance)) }
            LaunchPlanner(store)
                .plan(
                    instance,
                    profile(),
                    JavaRuntime("java", 21, "Test", "amd64"),
                    GameCredentials("Player", "0".repeat(32), "0", "legacy"),
                    prepareFiles = false,
                )
        }

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

    @Test
    fun `empty window fields follow global and round-trip through the store`() {
        LauncherController(dataDirectory = temporary.resolve("windows")).use { controller ->
            val folder =
                controller.store.minecraftRoot
                    .resolve("versions/WindowCfg")
                    .createDirectories()
            writeJson(
                folder.resolve("WindowCfg.json"),
                mapOf(
                    "id" to "WindowCfg",
                    "type" to "release",
                    "mainClass" to "fixture.Main",
                    "libraries" to emptyList<Any>(),
                ),
            )
            folder.resolve("WindowCfg.jar").writeBytes(byteArrayOf())
            controller.store.refreshInstances()
            val instance = controller.store.instance("WindowCfg")
            val settings =
                controller.store.data.settings.copy(
                    autoMemory = false,
                    memoryMb = 2048,
                    initialMemoryMb = 512,
                    width = 1600,
                    height = 900,
                    gameWindowMode = "custom",
                    fullscreen = false,
                )
            controller.saveSettings(settings)
            // 空白与 0 都是「跟随全局」；输入框回填时不再显示字面 0。
            assertEquals(0, parseInstanceLaunchNumberInput(""))
            assertEquals(0, parseInstanceLaunchNumberInput("   "))
            assertEquals(0, parseInstanceLaunchNumberInput("0"))
            assertEquals("", normalizeInstanceLaunchNumberInput("0"))
            assertEquals("", normalizeInstanceLaunchNumberInput(""))
            assertEquals("1280", normalizeInstanceLaunchNumberInput("1280"))
            assertEquals("", instanceLaunchNumberField(0))
            assertEquals("1280", instanceLaunchNumberField(1280))
            controller.saveInstanceConfiguration(
                instance.copy(
                    width = parseInstanceLaunchNumberInput("") ?: -1,
                    height = parseInstanceLaunchNumberInput("  ") ?: -1,
                ),
            )
            val stored = controller.store.instance(instance.id)
            assertEquals(0, stored.width)
            assertEquals(0, stored.height)
            val resolved =
                resolveInstanceLaunchOptions(settings, stored, null, 16384, 8192, "x64")
            assertEquals(1600, resolved.width)
            assertEquals(900, resolved.height)
            // 重新加载配置后仍是「跟随全局」，没有被写成伪显式值。
            val reloaded = LauncherStore(controller.platform, temporary.resolve("windows"))
            assertEquals(0, reloaded.instanceSettings("WindowCfg")?.width)
            assertEquals(0, reloaded.instanceSettings("WindowCfg")?.height)
        }
    }

    @Test
    fun `only manual memory policy applies and switching away clears the stored value`() {
        LauncherController(dataDirectory = temporary.resolve("memory")).use { controller ->
            val folder =
                controller.store.minecraftRoot
                    .resolve("versions/MemoryCfg")
                    .createDirectories()
            writeJson(
                folder.resolve("MemoryCfg.json"),
                mapOf(
                    "id" to "MemoryCfg",
                    "type" to "release",
                    "mainClass" to "fixture.Main",
                    "libraries" to emptyList<Any>(),
                ),
            )
            folder.resolve("MemoryCfg.jar").writeBytes(byteArrayOf())
            controller.store.refreshInstances()
            val instance = controller.store.instance("MemoryCfg")
            val settings =
                controller.store.data.settings.copy(
                    autoMemory = false,
                    memoryMb = 2048,
                    initialMemoryMb = 512,
                    width = 1280,
                    height = 720,
                    gameWindowMode = "custom",
                    fullscreen = false,
                )
            controller.saveSettings(settings)
            // 滑块按 256 MB 步进，并限制在本机预算内。
            assertEquals(2048, instanceMemorySliderValue(2100f, 16384))
            assertEquals(
                instanceMemoryLimitMb(16384),
                instanceMemorySliderValue(99999f, 16384),
            )
            val manual =
                applyInstanceMemoryPolicy(
                    instance,
                    InstanceMemoryPolicy.Manual,
                    settings.memoryMb,
                )
            assertEquals(false, manual.autoMemory)
            assertEquals(2048, manual.memoryMb)
            controller.saveInstanceConfiguration(manual.copy(memoryMb = 3072))
            val stored = controller.store.instance(instance.id)
            assertEquals(3072, stored.memoryMb)
            assertEquals(
                3072,
                resolveInstanceLaunchOptions(settings, stored, null, 16384, 8192)
                    .memory
                    .maximumMb,
            )
            // 自动分配清掉显式值：旧 3072 不再生效。
            val automatic =
                applyInstanceMemoryPolicy(
                    stored,
                    InstanceMemoryPolicy.Automatic,
                    settings.memoryMb,
                )
            assertEquals(0, automatic.memoryMb)
            assertEquals(true, automatic.autoMemory)
            controller.saveInstanceConfiguration(automatic)
            val autoStored = controller.store.instance(instance.id)
            assertEquals(0, autoStored.memoryMb)
            val autoResolved =
                resolveInstanceLaunchOptions(settings, autoStored, null, 16384, 8192)
            assertTrue(autoResolved.memory.automatic)
            assertNotEquals(3072, autoResolved.memory.maximumMb)
            // 切回手动不会带回旧值，而是从当前默认值起步。
            assertEquals(
                4096,
                applyInstanceMemoryPolicy(
                    autoStored,
                    InstanceMemoryPolicy.Manual,
                    4096,
                ).memoryMb,
            )
            // 继承全局：同样清空显式值，解析成全局 2048。
            val inherit =
                applyInstanceMemoryPolicy(
                    autoStored,
                    InstanceMemoryPolicy.Inherit,
                    settings.memoryMb,
                )
            assertNull(inherit.autoMemory)
            assertEquals(0, inherit.memoryMb)
            controller.saveInstanceConfiguration(inherit)
            val inherited =
                resolveInstanceLaunchOptions(
                    settings,
                    controller.store.instance(instance.id),
                    null,
                    16384,
                    8192,
                )
            assertEquals(2048, inherited.memory.maximumMb)
            assertFalse(inherited.memory.automatic)
        }
    }

    @Test
    fun `controller saves launch options and rejects invalid values without touching saved data`() {
        LauncherController(dataDirectory = temporary.resolve("controller")).use { controller ->
            val folder =
                controller.store.minecraftRoot
                    .resolve("versions/Cfg")
                    .createDirectories()
            writeJson(
                folder.resolve("Cfg.json"),
                mapOf(
                    "id" to "Cfg",
                    "type" to "release",
                    "mainClass" to "fixture.Main",
                    "libraries" to emptyList<Any>(),
                ),
            )
            folder.resolve("Cfg.jar").writeBytes(byteArrayOf())
            controller.store.refreshInstances()
            val instance = controller.store.instance("Cfg")
            controller.saveInstanceConfiguration(
                instance.copy(
                    memoryMb = 2048,
                    width = 1920,
                    height = 1080,
                    windowMode = "fullscreen",
                    extraJvmArgs = "-Dfixture.option=value",
                    extraGameArgs = "--demo",
                ),
            )
            val saved = controller.store.instance(instance.id)
            assertEquals(2048, saved.memoryMb)
            assertEquals(1920, saved.width)
            assertEquals(1080, saved.height)
            assertEquals("fullscreen", saved.windowMode)
            assertEquals("-Dfixture.option=value", saved.extraJvmArgs)
            assertEquals("--demo", saved.extraGameArgs)
            val reloaded = LauncherStore(controller.platform, temporary.resolve("controller"))
            assertEquals("--demo", reloaded.instanceSettings("Cfg")?.extraGameArgs)

            assertFailsWith<IllegalArgumentException> {
                controller.saveInstanceConfiguration(saved.copy(extraGameArgs = "--width 800"))
            }
            assertFailsWith<IllegalArgumentException> {
                controller.saveInstanceConfiguration(saved.copy(memoryMb = 100))
            }
            assertFailsWith<IllegalArgumentException> {
                controller.saveInstanceConfiguration(saved.copy(width = 100))
            }
            assertFailsWith<IllegalArgumentException> {
                controller.saveInstanceConfiguration(saved.copy(extraJvmArgs = "-Xmx2G"))
            }
            assertEquals("--demo", controller.store.instance(instance.id).extraGameArgs)
            assertEquals(2048, controller.store.instance(instance.id).memoryMb)
        }
    }
}
