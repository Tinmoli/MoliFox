// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证版本目录迁移、全局隔离、语言跟随、内容图标与组件备份。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopRefinementsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `encyclopedia resolves provider identity and reuses the result`() =
        runBlocking {
            NetworkClient().use { network ->
                var calls = 0
                val resolver =
                    EncyclopediaLinks(network) { url ->
                        calls++
                        when {
                            url.contains("search.mcmod.cn") ->
                                "<a href=\"https://www.mcmod.cn/class/10.html\">Similar</a>" +
                                    "<a href=\"https://www.mcmod.cn/class/11.html\">Correct</a>"
                            url.endsWith("/10.html") ->
                                "<a href=\"https://modrinth.com/mod/another-project\">Modrinth</a>"
                            else ->
                                "<a href=\"//link.mcmod.cn/target/" +
                                    "${java.util.Base64.getEncoder().encodeToString(
                                        "https://modrinth.com/mod/correct-project".toByteArray(),
                                    )}\">Modrinth</a>"
                        }
                    }
                val hit = ContentHit("p", "correct-project", "Common title", "", "", 0, "mod", null)
                assertEquals("https://www.mcmod.cn/class/11.html", resolver.resolve(hit))
                assertEquals("https://www.mcmod.cn/class/11.html", resolver.resolve(hit))
                assertEquals(3, calls)
            }
        }

    @Test
    fun `a title-only encyclopedia match is hidden`() =
        runBlocking {
            NetworkClient().use { network ->
                val resolver =
                    EncyclopediaLinks(network) { url ->
                        if (url.contains("search.mcmod.cn")) {
                            "<a href=\"/class/10.html\">Exact title</a>"
                        } else {
                            "<a href=\"https://example.com\">Different project</a>"
                        }
                    }
                assertNull(
                    resolver.resolve(
                        ContentHit("p", "correct-project", "Exact title", "", "", 0, "mod", null),
                    ),
                )
            }
        }

    private fun store() = LauncherStore(DesktopPlatform(), directory.resolve("profile"))

    private fun instance(store: LauncherStore): GameInstance {
        val instance =
            GameInstance(
                name = "茉狐测试",
                versionId = "1.21.1",
                gameVersion = "1.21.1",
                managedGameDirectory = true,
            )
        store.mutate { it.copy(instances = listOf(instance)) }
        return instance
    }

    @Test
    fun `per instance isolation wins over the global default and inherit follows it`() {
        val store = store()
        val instance = instance(store)
        store.mutate { it.copy(settings = it.settings.copy(isolateVersions = false)) }
        // 实例显式开启：全局关闭时仍使用 versions 目录。
        assertEquals(
            store.minecraftRoot.resolve("versions/1.21.1").toAbsolutePath(),
            store.gamePath(instance.copy(isolation = VersionIsolation.Enabled)),
        )
        store.mutate { it.copy(settings = it.settings.copy(isolateVersions = true)) }
        // 实例显式关闭：全局开启时仍使用游戏根。
        assertEquals(
            store.minecraftRoot,
            store.gamePath(instance.copy(isolation = VersionIsolation.Disabled)),
        )
        // 继承：跟随全局开关。
        assertEquals(
            store.minecraftRoot.resolve("versions/1.21.1").toAbsolutePath(),
            store.gamePath(instance.copy(isolation = VersionIsolation.Inherit)),
        )
        store.mutate { it.copy(settings = it.settings.copy(isolateVersions = false)) }
        assertEquals(
            store.minecraftRoot,
            store.gamePath(instance.copy(isolation = VersionIsolation.Inherit)),
        )
    }

    @Test
    fun `language follows launcher only when enabled and preserves unrelated options`() {
        val store = store()
        val instance = instance(store)
        val options =
            store.instancePath(instance.id).resolve("options.txt").apply {
                parent.createDirectories()
                writeText("lang:en_us\r\nmusic:0.4\r\n")
            }
        applyGameLanguage(store, instance)
        assertTrue(options.readText().contains("lang:en_us"))
        store.mutate { it.copy(settings = it.settings.copy(gameLanguageFollowsLauncher = true)) }
        applyGameLanguage(store, instance)
        assertEquals("music:0.4\r\nlang:zh_cn\r\n", options.readText())
        applyGameLanguage(store, instance)
        assertEquals(1, options.readText().lineSequence().count { it.startsWith("lang:") })
    }

    @Test
    fun `legacy Minecraft receives the older language code`() {
        val store = store()
        val instance = instance(store).copy(gameVersion = "1.10.2")
        store.mutate { it.copy(settings = it.settings.copy(gameLanguageFollowsLauncher = true)) }
        applyGameLanguage(store, instance)
        assertEquals("lang:zh_CN\n", store.gamePath(instance).resolve("options.txt").readText())
    }

    @Test
    fun `pack and world icons come from their own PNG and malformed data is ignored`() {
        val png =
            ByteArrayOutputStream().use { output ->
                ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", output)
                output.toByteArray()
            }
        val world = directory.resolve("World").apply { createDirectories() }
        world.resolve("icon.png").writeBytes(png)
        assertContentEquals(png, localContentIcon(world, true))
        val pack = directory.resolve("pack.zip")
        ZipOutputStream(pack.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("pack.png"))
            zip.write(png)
            zip.closeEntry()
        }
        assertContentEquals(png, localContentIcon(pack, false))
        world.resolve("icon.png").writeText("invalid")
        assertNull(localContentIcon(world, true))
    }

    @Test
    fun `shader pack previews use icon preview screenshot or shaders png`() {
        val png =
            ByteArrayOutputStream().use { output ->
                ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", output)
                output.toByteArray()
            }
        // 明确命名的图标可用，普通光影纹理不当作头像。
        val folder = directory.resolve("ShaderPack").apply { createDirectories() }
        folder.resolve("shaders").createDirectories()
        folder.resolve("shaders/pack.png").writeBytes(png)
        assertContentEquals(png, shaderPackIcon(folder))
        folder.resolve("preview.png").writeBytes(png)
        assertContentEquals(png, shaderPackIcon(folder))
        // ZIP 与文件夹使用同样的明确名称规则。
        val zip = directory.resolve("Shader.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            fun put(name: String) {
                out.putNextEntry(ZipEntry(name))
                out.write(png)
                out.closeEntry()
            }
            put("shaders/icon.png")
        }
        assertContentEquals(png, shaderPackIcon(zip))
        assertNull(shaderPackIcon(directory.resolve("missing")))
    }

    @Test
    fun `component backup restores custom version JSON exactly and refuses a different instance`() {
        val store = store()
        val instance = instance(store)
        val json =
            store.versionGamePath(instance).resolve("${instance.versionId}.json").apply {
                parent.createDirectories()
                writeText("{\"id\":\"1.21.1\",\"custom\":\"保留\"}")
            }
        val original = json.readBytes()
        val backups = ComponentBackup(store)
        val id = backups.capture(instance)
        json.writeText("{\"id\":\"changed\"}")
        backups.restore(id, instance)
        assertContentEquals(original, json.readBytes())
        assertFails {
            backups.restore(
                id,
                instance.copy(
                    id =
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                ),
            )
        }
    }

    @Test
    fun `external links have no search fallback and a declared encyclopedia page is direct`() {
        val hit = ContentHit("p", "test", "Test", "", "", 0, "mod", null)
        val project =
            com.google.gson.JsonObject().apply {
                addProperty("wiki_url", "https://www.mcmod.cn/class/123.html")
            }
        val links = contentLinks(hit, project)
        assertTrue(links.any { it.title == "MC 百科" && it.url.endsWith("/123.html") })
        assertFalse(links.any { it.url.contains("search.mcmod") })
        assertNull(directEncyclopediaUrl("https://www.mcmod.cn.evil.example/class/123.html"))
        assertFalse(contentLinks(hit, com.google.gson.JsonObject()).any { it.title == "MC 百科" })
    }
}
