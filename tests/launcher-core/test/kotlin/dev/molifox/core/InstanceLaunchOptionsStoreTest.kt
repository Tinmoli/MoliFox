// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证实例级启动设置的保存、回填与非法值拒绝。

package dev.molifox.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceLaunchOptionsStoreTest : InstanceLaunchOptionsTestSupport() {
    @Test
    fun `empty window fields follow global and round-trip through the store`() {
        LauncherController(dataDirectory = temporary.resolve("windows")).use { controller ->
            val instance = createVersionInstance(controller, "WindowCfg")
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
            assertBlankWindowInputRoundTrip(controller, instance)
            assertWindowFollowsGlobal(controller, instance, settings)
        }
    }

    @Test
    fun `only manual memory policy applies and switching away clears the stored value`() {
        LauncherController(dataDirectory = temporary.resolve("memory")).use { controller ->
            val instance = createVersionInstance(controller, "MemoryCfg")
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
            assertMemorySliderRounding()
            val stored = assertManualMemoryPolicy(controller, instance, settings)
            assertPolicySwitchClearsValue(controller, stored, settings)
        }
    }

    @Test
    fun `controller saves launch options and rejects invalid values without touching saved data`() {
        LauncherController(dataDirectory = temporary.resolve("controller")).use { controller ->
            val instance = createVersionInstance(controller, "Cfg")
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
            assertInvalidLaunchOptionsRejected(controller, saved)
        }
    }

    /** 空白与 0 都是「跟随全局」；输入框回填时不再显示字面 0，写回后仍是 0。 */
    private fun assertBlankWindowInputRoundTrip(
        controller: LauncherController,
        instance: GameInstance,
    ) {
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
    }

    /** 存盘的 0 在解析时换成全局窗口，重新加载配置后也不会变成伪显式值。 */
    private fun assertWindowFollowsGlobal(
        controller: LauncherController,
        instance: GameInstance,
        settings: LauncherSettings,
    ) {
        val stored = controller.store.instance(instance.id)
        assertEquals(0, stored.width)
        assertEquals(0, stored.height)
        val resolved =
            resolveInstanceLaunchOptions(settings, stored, null, 16384, 8192, "x64")
        assertEquals(1600, resolved.width)
        assertEquals(900, resolved.height)
        val reloaded = LauncherStore(controller.platform, temporary.resolve("windows"))
        assertEquals(0, reloaded.instanceSettings("WindowCfg")?.width)
        assertEquals(0, reloaded.instanceSettings("WindowCfg")?.height)
    }

    /** 滑块按 256 MB 步进，并限制在本机预算内。 */
    private fun assertMemorySliderRounding() {
        assertEquals(2048, instanceMemorySliderValue(2100f, 16384))
        assertEquals(
            instanceMemoryLimitMb(16384),
            instanceMemorySliderValue(99999f, 16384),
        )
    }

    /** 手动内存写盘后按显式值生效；返回写盘后的实例供后续策略切换使用。 */
    private fun assertManualMemoryPolicy(
        controller: LauncherController,
        instance: GameInstance,
        settings: LauncherSettings,
    ): GameInstance {
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
        return stored
    }

    /** 自动/继承策略清掉显式值，切回手动态从当前默认值起步，不复活旧值。 */
    private fun assertPolicySwitchClearsValue(
        controller: LauncherController,
        stored: GameInstance,
        settings: LauncherSettings,
    ) {
        val autoStored = assertAutomaticPolicyClearsValue(controller, stored, settings)
        assertInheritPolicyClearsValue(controller, autoStored, settings)
    }

    /** 自动策略清掉显式值，切回手动态从当前默认值起步；返回写盘后的实例。 */
    private fun assertAutomaticPolicyClearsValue(
        controller: LauncherController,
        stored: GameInstance,
        settings: LauncherSettings,
    ): GameInstance {
        val automatic =
            applyInstanceMemoryPolicy(
                stored,
                InstanceMemoryPolicy.Automatic,
                settings.memoryMb,
            )
        assertEquals(0, automatic.memoryMb)
        assertEquals(true, automatic.autoMemory)
        controller.saveInstanceConfiguration(automatic)
        val autoStored = controller.store.instance(stored.id)
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
        return autoStored
    }

    /** 继承全局：同样清空显式值，解析成全局 2048。 */
    private fun assertInheritPolicyClearsValue(
        controller: LauncherController,
        autoStored: GameInstance,
        settings: LauncherSettings,
    ) {
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
                controller.store.instance(autoStored.id),
                null,
                16384,
                8192,
            )
        assertEquals(2048, inherited.memory.maximumMb)
        assertFalse(inherited.memory.automatic)
    }

    /** 非法值全部在写盘前拒绝，已保存的数据保持不变。 */
    private fun assertInvalidLaunchOptionsRejected(
        controller: LauncherController,
        saved: GameInstance,
    ) {
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
        assertEquals("--demo", controller.store.instance(saved.id).extraGameArgs)
        assertEquals(2048, controller.store.instance(saved.id).memoryMb)
    }
}
