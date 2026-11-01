// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证启动器个性化与页面偏好的保存的行为与边界。

package dev.molifox.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LauncherPreferencesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `instance launch preferences persist atomically and reject invalid overrides`() {
        LauncherController(dataDirectory = directory).use { controller ->
            val instance = createFixtureInstance(controller)
            val configured = saveFixturePreferences(controller, instance)
            assertPersistedPreferences(controller, configured)
            assertInvalidOverridesRejected(controller, instance)
        }
    }

    /** 造一个可扫描的最小版本，并返回控制器识别出的实例。 */
    private fun createFixtureInstance(controller: LauncherController): GameInstance {
        // 新架构下实例来自 versions/ 扫描；先放一个最小可识别版本。
        val folder =
            controller.store.minecraftRoot
                .resolve("versions/Fixture")
                .createDirectories()
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
        return controller.store.instance("Fixture")
    }

    /** 保存一份合法的实例启动配置与页面偏好，返回提交的实例。 */
    private fun saveFixturePreferences(
        controller: LauncherController,
        instance: GameInstance,
    ): GameInstance {
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
            ),
        )
        return configured
    }

    /** 重新从磁盘读一遍，实例与页面偏好都必须落盘。 */
    private fun assertPersistedPreferences(
        controller: LauncherController,
        configured: GameInstance,
    ) {
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
    }

    /** 非法覆盖必须整次拒绝，且不留下任何部分写入。 */
    private fun assertInvalidOverridesRejected(
        controller: LauncherController,
        instance: GameInstance,
    ) {
        val before = controller.store.instance(instance.id)
        assertFailsWith<IllegalArgumentException> {
            controller.saveInstanceConfiguration(
                before.copy(extraJvmArgs = "-jar unrelated.jar"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            controller.saveInstanceConfiguration(before.copy(initialMemoryMb = 4096))
        }
        assertFailsWith<IllegalArgumentException> {
            controller.saveInstanceConfiguration(before.copy(initialMemoryMb = 12))
        }
        assertFailsWith<IllegalArgumentException> {
            controller.saveSettings(
                controller.store.data.settings
                    .copy(uiScale = Float.NaN),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            controller.saveSettings(
                controller.store.data.settings
                    .copy(surfaceOpacity = .1f),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            controller.saveSettings(
                controller.store.data.settings
                    .copy(backgroundBlur = Float.NaN),
            )
        }
        assertEquals(before, controller.store.instance(instance.id))
    }

    @Test
    fun `font imports are deduplicated and removing a portable copy preserves the source`() {
        val invalid = directory.resolve("invalid.ttf").apply { writeText("not a font") }
        assertFailsWith<IllegalArgumentException> {
            importAppearanceFont(directory.resolve("profile"), invalid)
        }
        val systemFont = firstSystemFont() ?: return
        val source = directory.resolve("source.ttf").apply { systemFont.copyTo(this) }
        val originalHash = digest(source, "SHA-256")
        val data = directory.resolve("profile")
        val font = importAppearanceFont(data, source)
        assertEquals(font, importAppearanceFont(data, source))
        assertPortableCopyKeepsSourceHash(data, source, font, originalHash)
        assertEquals(1, data.resolve("appearance/fonts").listDirectoryEntries().size)
        removeAppearanceFont(data, font)
        assertTrue(data.resolve("appearance/fonts").listDirectoryEntries().isEmpty())
        assertEquals(originalHash, digest(source, "SHA-256"))
        removeAppearanceFont(data, LauncherFont("outside", source.toString()))
        assertTrue(source.isRegularFile())
    }

    /** 找一个真实系统字体；当前机器没有就跳过依赖字体的断言。 */
    private fun firstSystemFont(): Path? =
        listOfNotNull(
            System.getenv("WINDIR")?.let { Path.of(it, "Fonts", "arial.ttf") },
            Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
        ).firstOrNull { it.isRegularFile() }

    /** 源文件移走后副本仍能以同一哈希加载；断言结束恢复源文件位置。 */
    private fun assertPortableCopyKeepsSourceHash(
        data: Path,
        source: Path,
        font: LauncherFont,
        originalHash: String,
    ) {
        val ownedFont = Path.of(font.path)
        assertTrue(ownedFont.startsWith(data))
        assertTrue(
            !java.nio.file.Files
                .isSameFile(source, ownedFont),
        )
        val movedSource = source.resolveSibling("moved-source.ttf")
        java.nio.file.Files
            .move(source, movedSource)
        try {
            assertEquals(originalHash, digest(ownedFont, "SHA-256"))
            val loaded =
                java.nio.file.Files.newInputStream(ownedFont).use {
                    java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, it)
                }
            assertTrue(loaded.family.isNotBlank())
        } finally {
            java.nio.file.Files
                .move(movedSource, source)
        }
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
        java.nio.file.Files
            .delete(original)
        java.nio.file.Files
            .delete(replacement)
        assertEquals(4, ImageIO.read(first.toFile()).width)
    }
}
