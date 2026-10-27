package dev.molifox.core

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ScreenshotManagerTest {
    @TempDir lateinit var temporary: Path

    private fun fixture(): Triple<LauncherStore, GameInstance, Path> {
        val platform =
            object : PlatformServices by DesktopPlatform() {
                override fun moveToTrash(file: Path) {
                    val target = temporary.resolve("recycled/${UUID.randomUUID()}-${file.fileName}")
                    target.parent.createDirectories()
                    Files.move(file, target)
                }
            }
        val store = LauncherStore(platform, temporary.resolve("launcher"))
        val instance =
            GameInstance(
                name = "Screenshots",
                versionId = "fixture",
                gameVersion = "fixture",
                isolation = VersionIsolation.Enabled,
            )
        store.mutate { it.copy(instances = listOf(instance)) }
        return Triple(
            store,
            instance,
            store.instancePath(instance.id).apply { createDirectories() },
        )
    }

    private fun image(path: Path, width: Int = 320, height: Int = 180): Path = path.apply {
        parent.createDirectories()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", toFile())
    }

    @Test
    fun `empty gallery does not create a screenshot folder and lists only direct images`(): Unit =
        runBlocking {
            val (store, instance, root) = fixture()
            val manager = ScreenshotManager(store)
            assertTrue(manager.list(instance.id).isEmpty())
            assertFalse(root.resolve("screenshots").exists())
            image(root.resolve("screenshots/shot.png"))
            image(root.resolve("screenshots/nested/hidden.png"))
            root.resolve("screenshots/notes.txt").writeText("keep")
            root.resolve("screenshots/broken.png").writeText("bad png")
            val entries = manager.list(instance.id)
            assertEquals(setOf("shot.png", "broken.png"), entries.map { it.name }.toSet())
            assertTrue(entries.first { it.name == "shot.png" }.previewable)
            assertFalse(entries.first { it.name == "broken.png" }.previewable)
        }

    @Test
    fun `thumbnail sampling preserves original and stays within requested size`(): Unit =
        runBlocking {
            val (store, instance, root) = fixture()
            val original = image(root.resolve("screenshots/large.png"), 1920, 1080)
            val before = digest(original, "SHA-256")
            val manager = ScreenshotManager(store)
            val shot = manager.list(instance.id).single()
            val thumbnail = ImageIO.read(manager.thumbnail(shot, 128).inputStream())
            assertTrue(thumbnail.width <= 128 && thumbnail.height <= 128)
            assertEquals(before, digest(original, "SHA-256"))
            assertEquals(1920, shot.width)
            assertEquals(1080, shot.height)
        }

    @Test
    fun `exports keep existing files and automatically number conflicts`(): Unit = runBlocking {
        val (store, instance, root) = fixture()
        val original = image(root.resolve("screenshots/shot.png"))
        val manager = ScreenshotManager(store)
        val destination = temporary.resolve("exported").apply { createDirectories() }
        destination.resolve("shot.png").writeText("existing")
        val written = manager.export(manager.list(instance.id), destination)
        assertEquals("shot (2).png", written.single().name)
        assertEquals("existing", destination.resolve("shot.png").readText())
        assertContentEquals(original.readBytes(), written.single().readBytes())
        assertTrue(destination.listDirectoryEntries("*.tmp").isEmpty())
    }

    @Test
    fun `changed files and migrated directories invalidate old selections`(): Unit = runBlocking {
        val (store, instance, root) = fixture()
        val original = image(root.resolve("screenshots/shot.png"))
        val manager = ScreenshotManager(store)
        val shot = manager.list(instance.id).single()
        original.writeText("changed")
        assertFailsWith<IllegalArgumentException> { manager.resolve(shot) }
        assertFailsWith<IllegalArgumentException> {
            manager.export(listOf(shot), temporary.resolve("out"))
        }
        assertFailsWith<IllegalArgumentException> { manager.remove(listOf(shot)) }
        assertEquals("changed", original.readText())
        val refreshed = manager.list(instance.id).single()
        store.updateInstance(instance.id) { it.copy(isolation = VersionIsolation.Disabled) }
        assertFailsWith<IllegalArgumentException> { manager.resolve(refreshed) }
    }

    @Test
    fun `selected deletion moves only current screenshots to the recovery sink`(): Unit =
        runBlocking {
            val (store, instance, root) = fixture()
            image(root.resolve("screenshots/first.png"))
            val remaining = image(root.resolve("screenshots/second.png"))
            root.resolve("screenshots/note.txt").writeText("user file")
            val manager = ScreenshotManager(store)
            manager.remove(manager.list(instance.id).filter { it.name == "first.png" })
            assertFalse(root.resolve("screenshots/first.png").exists())
            assertTrue(remaining.exists())
            assertEquals("user file", root.resolve("screenshots/note.txt").readText())
            assertEquals(1, temporary.resolve("recycled").listDirectoryEntries().size)
        }

    @Test
    fun `forged traversal duplicate selections and export into source are rejected`(): Unit =
        runBlocking {
            val (store, instance, root) = fixture()
            image(root.resolve("screenshots/shot.png"))
            val manager = ScreenshotManager(store)
            val shot = manager.list(instance.id).single()
            val sentinel = root.resolve("keep.png").apply { writeText("keep") }
            assertFailsWith<IllegalArgumentException> {
                manager.resolve(shot.copy(name = "../keep.png"))
            }
            assertFailsWith<IllegalArgumentException> {
                manager.remove(listOf(shot, shot.copy(width = 1)))
            }
            assertFailsWith<IllegalArgumentException> {
                manager.export(listOf(shot), root.resolve("screenshots"))
            }
            val cancelled = Job().apply { cancel() }
            assertFailsWith<CancellationException> {
                withContext(cancelled) {
                    manager.export(listOf(shot), temporary.resolve("cancelled"))
                }
            }
            assertEquals("keep", sentinel.readText())
            assertTrue(root.resolve("screenshots/shot.png").exists())
        }
}
