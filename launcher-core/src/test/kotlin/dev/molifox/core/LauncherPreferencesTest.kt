// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证启动器个性化与页面偏好的保存的行为与边界。

package dev.molifox.core

import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LauncherPreferencesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `instance launch preferences persist atomically and reject invalid overrides`() {
        LauncherController(dataDirectory = directory).use { controller ->
            // 新架构下实例来自 versions/ 扫描；先放一个最小可识别版本。
            val folder =
                controller.store.minecraftRoot.resolve("versions/Fixture").createDirectories()
            writeJson(
                folder.resolve("Fixture.json"),
                mapOf(
                    "id" to "Fixture",
                    "type" to "release",
                    "mainClass" to "fixture.Main",
                    "libraries" to emptyList<Any>(),
                ),
            )
            folder.resolve("Fixture.jar").writeBytes(byteArrayOf())
            controller.store.refreshInstances()
            val instance = controller.store.instance("Fixture")
            val configured =
                instance.copy(
                    memoryMb = 2048,
                    initialMemoryMb = 768,
                    width = 1600,
                    height = 900,
                    windowMode = "fullscreen",
                    extraJvmArgs = "-Dfixture.option=value",
                    notes = "A personal version",
                )
            controller.saveInstanceConfiguration(configured)
            controller.toggleFavorite(instance.id)
            controller.saveSettings(
                controller.store.data.settings.copy(
                    themeMode = "dark",
                    uiScale = 1.1f,
                    surfaceOpacity = .78f,
                    backgroundBlur = 25f,
                    fontChoice = "system:SansSerif",
                    pages = PagePreferences(versions = VersionsPagePreferences(view = "grid")),
                )
            )
            val reloadedStore = LauncherStore(controller.platform, directory)
            val loaded = reloadedStore.data
            val reloaded = reloadedStore.instance("Fixture")
            assertEquals(2048, reloaded.memoryMb)
            assertEquals(768, reloaded.initialMemoryMb)
            assertEquals(1600, reloaded.width)
            assertEquals(900, reloaded.height)
            assertEquals("fullscreen", reloaded.windowMode)
            assertEquals("-Dfixture.option=value", reloaded.extraJvmArgs)
            assertEquals("A personal version", reloaded.notes)
            assertTrue(reloaded.favorite)
            assertEquals(configured.name, reloaded.name)
            assertEquals("dark", loaded.settings.themeMode)
            assertEquals(1.1f, loaded.settings.uiScale)
            assertEquals(.78f, loaded.settings.surfaceOpacity)
            assertEquals(25f, loaded.settings.backgroundBlur)
            assertEquals("system:SansSerif", loaded.settings.fontChoice)
            assertEquals("grid", loaded.settings.pages.versions.view)
            val before = controller.store.instance(instance.id)
            assertFailsWith<IllegalArgumentException> {
                controller.saveInstanceConfiguration(
                    before.copy(extraJvmArgs = "-jar unrelated.jar")
                )
            }
            assertFailsWith<IllegalArgumentException> {
                controller.saveInstanceConfiguration(before.copy(initialMemoryMb = 4096))
            }
            assertFailsWith<IllegalArgumentException> {
                controller.saveInstanceConfiguration(before.copy(initialMemoryMb = 12))
            }
            assertFailsWith<IllegalArgumentException> {
                controller.saveSettings(controller.store.data.settings.copy(uiScale = Float.NaN))
            }
            assertFailsWith<IllegalArgumentException> {
                controller.saveSettings(controller.store.data.settings.copy(surfaceOpacity = .1f))
            }
            assertFailsWith<IllegalArgumentException> {
                controller.saveSettings(
                    controller.store.data.settings.copy(backgroundBlur = Float.NaN)
                )
            }
            assertEquals(before, controller.store.instance(instance.id))
        }
    }

    @Test
    fun `font imports are deduplicated and removing a portable copy preserves the source`() {
        val invalid = directory.resolve("invalid.ttf").apply { writeText("not a font") }
        assertFailsWith<IllegalArgumentException> {
            importAppearanceFont(directory.resolve("profile"), invalid)
        }
        val candidates =
            listOfNotNull(
                System.getenv("WINDIR")?.let { Path.of(it, "Fonts", "arial.ttf") },
                Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
            )
        val systemFont = candidates.firstOrNull { it.isRegularFile() } ?: return
        val source = directory.resolve("source.ttf").apply { systemFont.copyTo(this) }
        val originalHash = digest(source, "SHA-256")
        val data = directory.resolve("profile")
        val font = importAppearanceFont(data, source)
        assertEquals(font, importAppearanceFont(data, source))
        assertEquals(1, data.resolve("appearance/fonts").listDirectoryEntries().size)
        removeAppearanceFont(data, font)
        assertTrue(data.resolve("appearance/fonts").listDirectoryEntries().isEmpty())
        assertEquals(originalHash, digest(source, "SHA-256"))
        removeAppearanceFont(data, LauncherFont("outside", source.toString()))
        assertTrue(source.isRegularFile())
    }

    @Test
    fun `background import keeps one portable copy and preserves source on rejection`() {
        val original = directory.resolve("source.png")
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", original.toFile())
        val originalBytes = original.readBytes()
        val data = directory.resolve("profile")
        val first = importAppearanceBackground(data, original)
        assertContentEquals(originalBytes, first.readBytes())
        val replacement = directory.resolve("replacement.png")
        ImageIO.write(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", replacement.toFile())
        assertEquals(first, importAppearanceBackground(data, replacement))
        assertContentEquals(replacement.readBytes(), first.readBytes())
        val invalid = directory.resolve("not-an-image.png").apply { writeText("not a picture") }
        assertFailsWith<IllegalArgumentException> { importAppearanceBackground(data, invalid) }
        assertContentEquals(replacement.readBytes(), first.readBytes())
        assertContentEquals(originalBytes, original.readBytes())
        assertEquals(
            listOf("background.img"),
            data.resolve("appearance/backgrounds").listDirectoryEntries().map { it.name },
        )
    }
}
