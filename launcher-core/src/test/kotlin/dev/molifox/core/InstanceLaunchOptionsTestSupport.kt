// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例启动设置测试共用的夹具：默认设置、实例、解析用的版本档案与真实规划器入口。

package dev.molifox.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

/**
 * 两个启动设置测试类共用的夹具。
 *
 * 只放构造数据的辅助方法，不放断言与 @Test，避免测试被继承后重复执行。
 */
abstract class InstanceLaunchOptionsTestSupport {
    @TempDir lateinit var temporary: Path

    protected fun settings(
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

    protected fun instance(
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

    protected fun profile(): ResolvedProfile {
        val json =
            JsonParser
                .parseString(
                    """
                    {
                      "id": "fixture",
                      "type": "release",
                      "mainClass": "fixture.Main",
                      "libraries": [],
                      "minecraftArguments": "--username ${'$'}{auth_player_name}"
                    }
                    """,
                ).asJsonObject
        return ResolvedProfile("fixture", "1.21.1", json)
    }

    protected fun plan(
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

    /** 写入一个最小的可解析版本目录，注册后返回对应实例；三个控制器测试共用。 */
    protected fun createVersionInstance(
        controller: LauncherController,
        id: String,
    ): GameInstance {
        val folder =
            controller.store.minecraftRoot
                .resolve("versions/$id")
                .createDirectories()
        writeJson(
            folder.resolve("$id.json"),
            mapOf(
                "id" to id,
                "type" to "release",
                "mainClass" to "fixture.Main",
                "libraries" to emptyList<Any>(),
            ),
        )
        folder.resolve("$id.jar").writeBytes(byteArrayOf())
        controller.store.refreshInstances()
        return controller.store.instance(id)
    }
}
