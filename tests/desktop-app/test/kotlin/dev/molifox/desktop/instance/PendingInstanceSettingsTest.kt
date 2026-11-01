// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop.instance

import dev.molifox.core.GameInstance
import dev.molifox.core.LauncherController
import dev.molifox.core.PlatformServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PendingInstanceSettingsTest {
    @TempDir lateinit var temporary: Path

    private class TestPlatform(override val defaultDataDirectory: Path) : PlatformServices {
        var onMemoryRead: () -> Unit = {}
        override val osName = "windows"
        override val architecture = "x86_64"
        override val javaExecutable = "java.exe"
        override val classpathSeparator = ";"
        override val totalMemoryMb: Int
            get() {
                onMemoryRead()
                return 8192
            }

        override fun openFolder(directory: Path) = Unit

        override fun openUrl(url: String) = Unit

        override fun moveToTrash(file: Path) = error("Unexpected trash operation")
    }

    private fun fixture(check: suspend (LauncherController, TestPlatform, GameInstance) -> Unit) = runBlocking {
        val platform = TestPlatform(temporary.resolve("profile"))
        LauncherController(platform).use { controller ->
            val instance = GameInstance(name = "Current", versionId = "1.20.1", gameVersion = "1.20.1")
            controller.store.mutate { it.copy(instances = listOf(instance)) }
            check(controller, platform, instance)
        }
    }

    @Test
    fun `pending settings save preserves current name`() = fixture { controller, _, instance ->
        val draft = instance.copy(name = "Old name", notes = "new notes")
        assertTrue(savePendingInstanceSettings(controller, draft).getOrThrow())
        assertEquals("Current", controller.store.instance(instance.id).name)
        assertEquals("new notes", controller.store.instance(instance.id).notes)
    }

    @Test
    fun `deleted drafts are skipped and strict commands still fail`() = fixture { controller, _, instance ->
        controller.store.mutate { it.copy(instances = emptyList()) }
        assertFalse(savePendingInstanceSettings(controller, instance.copy(notes = "late")).getOrThrow())
        assertTrue(controller.store.data.instances.isEmpty())
        assertTrue(controller.store.data.instanceSettings.isEmpty())
        assertFailsWith<IllegalStateException> { controller.saveInstanceConfiguration(instance, false) }
    }

    @Test
    fun `deletion between validation and write cannot resurrect the draft`() =
        fixture { controller, platform, instance ->
            platform.onMemoryRead = {
                controller.store.mutate { it.copy(instances = emptyList()) }
            }
            assertFalse(savePendingInstanceSettings(controller, instance.copy(notes = "late")).getOrThrow())
            assertTrue(controller.store.data.instances.isEmpty())
            assertTrue(controller.store.data.instanceSettings.isEmpty())
        }

    @Test
    fun `invalid settings and IO failures remain failures without writing`() =
        fixture { controller, platform, instance ->
            assertIs<IllegalArgumentException>(
                savePendingInstanceSettings(controller, instance.copy(width = -1)).exceptionOrNull(),
            )
            platform.onMemoryRead = { throw IOException("fixture read failure") }
            assertIs<IOException>(
                savePendingInstanceSettings(controller, instance.copy(notes = "fail")).exceptionOrNull(),
            )
            assertEquals(instance, controller.store.instance(instance.id))
        }

    @Test
    fun `cancellation from saving propagates`() = fixture { controller, platform, instance ->
        platform.onMemoryRead = { throw CancellationException("fixture cancellation") }
        assertFailsWith<CancellationException> {
            savePendingInstanceSettings(controller, instance.copy(notes = "cancel"))
        }
        assertEquals(instance, controller.store.instance(instance.id))
    }
}
