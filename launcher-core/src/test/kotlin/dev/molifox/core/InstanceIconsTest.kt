// 负责验证实例图标导入、优先级解析和共享资源回收的行为与边界。

package dev.molifox.core

import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstanceIconsTest {
    @TempDir lateinit var temporary: Path

    private fun image(
        path: Path = temporary.resolve("icon.png"),
        width: Int = 32,
        height: Int = 32,
    ): Path = path.apply {
        parent.createDirectories()
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, 0xFF8866CC.toInt())
        ImageIO.write(image, "png", toFile())
    }

    private fun fixture(): Pair<LauncherStore, GameInstance> {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("launcher"))
        val instance =
            GameInstance(
                name = "Icon",
                versionId = "1.20.1",
                gameVersion = "1.20.1",
                installed = true,
                isolation = VersionIsolation.Enabled,
            )
        store.mutate { it.copy(instances = listOf(instance)) }
        store.instancePath(instance.id).createDirectories()
        return store to instance
    }

    @Test
    fun `custom icon is durable independent from source and reset removes only its asset`(): Unit =
        runBlocking {
            val (store, instance) = fixture()
            val icons = InstanceIcons(store)
            val source = image()
            val original = source.readBytes()
            icons.setCustom(instance.id, source)
            val configured = store.instance(instance.id)
            assertEquals(InstanceIconStyle.Custom, configured.iconStyle)
            val texture = requireNotNull(icons.texture(configured))
            assertTrue(texture.startsWith(store.dataDirectory))
            assertEquals(configured.iconId, digest(texture, "SHA-256"))
            assertContentEquals(original, source.readBytes())
            source.deleteExisting()
            assertNotNull(InstanceIcons(store).texture(configured))
            icons.setPreset(instance.id, InstanceIconStyle.Auto)
            assertFalse(texture.exists())
            assertEquals("", store.instance(instance.id).iconId)
        }

    @Test
    fun `copied instances share immutable icon and resetting one preserves other`(): Unit =
        runBlocking {
            val (store, instance) = fixture()
            val icons = InstanceIcons(store)
            icons.setCustom(instance.id, image())
            val original = store.instance(instance.id)
            val shared = requireNotNull(icons.texture(original))
            val copy =
                InstanceTools(store)
                    .copyInstance(instance.id, "Copy", context = JobContext(store, "copy"))
            assertEquals(original.iconId, copy.iconId)
            icons.setPreset(instance.id, InstanceIconStyle.Grass)
            assertTrue(shared.exists())
            assertNotNull(icons.texture(store.instance(copy.id)))
            icons.setPreset(copy.id, InstanceIconStyle.Quilt)
            assertFalse(shared.exists())
            assertEquals(InstanceIconStyle.Quilt, store.instance(copy.id).iconStyle)
        }

    @Test
    fun `existing external icon is read by reference and explicit preset overrides it`(): Unit =
        runBlocking {
            val (store, original) = fixture()
            val root = temporary.resolve("external")
            val externalIcon = image(root.resolve("versions/custom/icon.png"))
            val directory = GameDirectory(path = root.toString())
            val instance =
                original.copy(
                    externalProfile = true,
                    gameDirectoryId = directory.id,
                    versionId = "custom",
                )
            store.mutate {
                it.copy(gameDirectories = listOf(directory), instances = listOf(instance))
            }
            val icons = InstanceIcons(store)
            assertEquals(externalIcon, icons.texture(instance))
            assertTrue(store.dataDirectory.resolve("icons").listDirectoryEntries().isEmpty())
            icons.setPreset(instance.id, InstanceIconStyle.Fabric)
            assertNull(icons.texture(store.instance(instance.id)))
            assertTrue(externalIcon.exists())
            icons.setPreset(instance.id, InstanceIconStyle.Auto)
            assertEquals(externalIcon, icons.texture(store.instance(instance.id)))
        }

    @Test
    fun `invalid image dimensions corrupt assets and forged IDs cannot alter existing icons`():
        Unit = runBlocking {
        val (store, instance) = fixture()
        val icons = InstanceIcons(store)
        icons.setCustom(instance.id, image())
        val valid = store.instance(instance.id)
        val fake = temporary.resolve("fake.png").apply { writeText("invalid") }
        assertFailsWith<IllegalArgumentException> { icons.setCustom(instance.id, fake) }
        assertFailsWith<IllegalArgumentException> {
            icons.setCustom(instance.id, image(temporary.resolve("rectangle.png"), 32, 16))
        }
        assertEquals(valid, store.instance(instance.id))
        val asset = requireNotNull(icons.texture(valid))
        asset.writeText("broken")
        assertNull(icons.texture(valid))
        assertNull(icons.texture(valid.copy(iconId = "../../../fake")))
        assertEquals("invalid", fake.readText())
        assertTrue(store.dataDirectory.resolve("icons").listDirectoryEntries("*.tmp").isEmpty())
    }

    @Test
    fun `older instance JSON retains automatic icon defaults`() {
        val json =
            gson.toJsonTree(GameInstance()).asJsonObject.apply {
                remove("iconStyle")
                remove("iconId")
            }
        val loaded = gson.fromJson(json, GameInstance::class.java)
        assertEquals(InstanceIconStyle.Auto, loaded.iconStyle)
        assertEquals("", loaded.iconId)
    }
}
