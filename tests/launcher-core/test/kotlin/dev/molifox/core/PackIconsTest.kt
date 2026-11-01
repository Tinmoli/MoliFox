// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证整合包内图标的读取与导出的行为与边界。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackIconsTest {
    @TempDir lateinit var temporary: Path

    private fun image(color: Int): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
            for (x in 0 until 32) for (y in 0 until 32) image.setRGB(x, y, color)
            ImageIO.write(image, "png", bytes)
            bytes.toByteArray()
        }

    private fun pack(
        name: String,
        files: Map<String, ByteArray>,
    ): Path =
        temporary.resolve(name).apply {
            ZipOutputStream(outputStream()).use { zip ->
                files.forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

    private suspend fun fixture(action: suspend (LauncherStore, GameInstance, InstanceIcons) -> Unit) {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("MoliFox"))
        val instance =
            GameInstance(name = "Pack", gameVersion = "26.3", versionId = "26.3", installed = true)
        store.mutate { it.copy(instances = listOf(instance)) }
        val folder = store.instancePath(instance.id).apply { createDirectories() }
        folder.resolve("26.3.json").writeText(
            gson.toJson(
                mapOf(
                    "id" to "26.3",
                    "type" to "release",
                    "mainClass" to "fixture.Main",
                    "libraries" to emptyList<Any>(),
                ),
            ),
        )
        folder.resolve("26.3.jar").writeBytes(byteArrayOf())
        store.refreshInstances()
        action(store, instance, InstanceIcons(store))
    }

    @Test
    fun `pack artwork is durable and user choice wins until reset`() =
        runBlocking {
            fixture { store, instance, icons ->
                val archive = pack("pack.zip", mapOf("icon.png" to image(0xff228866.toInt())))
                ZipFile(archive.toFile()).use {
                    applyPackIcon(
                        store,
                        instance.id,
                        it,
                        PackManifest(null, InstallRequest("Pack", "26.3"), emptyList(), 0),
                    )
                }
                val packId = store.instance(instance.id).packIconId
                assertTrue(packId.isNotBlank())
                val supplied = requireNotNull(icons.texture(store.instance(instance.id)))
                assertEquals(packId, digest(supplied, "SHA-256"))
                archive.deleteExisting()
                val chosen =
                    temporary.resolve("mine.png").apply { writeBytes(image(0xffaa3366.toInt())) }
                icons.setCustom(instance.id, chosen)
                val custom = requireNotNull(icons.texture(store.instance(instance.id)))
                assertNotEquals(supplied, custom)
                assertEquals(0xffaa3366.toInt(), ImageIO.read(custom.toFile()).getRGB(0, 0))
                icons.setPreset(instance.id, InstanceIconStyle.Auto)
                assertEquals(supplied, icons.texture(store.instance(instance.id)))
                assertTrue(supplied.exists())
                assertFalse(custom.exists())
                assertEquals(
                    packId,
                    LauncherStore(DesktopPlatform(), store.dataDirectory)
                        .instance("26.3")
                        .packIconId,
                )
            }
        }

    @Test
    fun `Prism iconKey resolves only a bundled icon in its instance prefix`() =
        runBlocking {
            fixture { store, instance, icons ->
                val archive =
                    pack(
                        "prism.zip",
                        mapOf(
                            "Example/mmc-pack.json" to "{}".toByteArray(),
                            "Example/instance.cfg" to "[General]\niconKey=custom\n".toByteArray(),
                            "Example/icons/custom.png" to image(0xff7755cc.toInt()),
                        ),
                    )
                ZipFile(archive.toFile()).use {
                    applyPackIcon(
                        store,
                        instance.id,
                        it,
                        PackManifest(
                            null,
                            InstallRequest("Pack", "26.3"),
                            emptyList(),
                            0,
                            payloadPrefix = "Example/.minecraft/",
                        ),
                    )
                }
                assertEquals(
                    0xff7755cc.toInt(),
                    ImageIO
                        .read(requireNotNull(icons.texture(store.instance(instance.id))).toFile())
                        .getRGB(0, 0),
                )
            }
        }

    @Test
    fun `bad or escaping pack icons cannot replace current icon or leave staging files`() =
        runBlocking {
            fixture { store, instance, icons ->
                val current =
                    temporary.resolve("current.png").apply { writeBytes(image(0xff2266aa.toInt())) }
                icons.setCustom(instance.id, current)
                val before = store.instance(instance.id)
                val manifest = PackManifest(null, InstallRequest("Pack", "26.3"), emptyList(), 0)
                val bad = pack("bad.zip", mapOf("icon.png" to "broken".toByteArray()))
                ZipFile(bad.toFile()).use { zip ->
                    assertFailsWith<IllegalArgumentException> {
                        applyPackIcon(store, instance.id, zip, manifest)
                    }
                }
                val escape =
                    pack(
                        "escape.zip",
                        mapOf("mcbbs.packmeta" to """{"icon":"../outside.png"}""".toByteArray()),
                    )
                ZipFile(escape.toFile()).use { zip ->
                    assertFailsWith<IllegalArgumentException> {
                        applyPackIcon(store, instance.id, zip, manifest)
                    }
                }
                assertEquals(before, store.instance(instance.id))
                assertTrue(store.temporaryDirectory.listDirectoryEntries("pack-icon-*").isEmpty())
                assertContentEquals(image(0xff2266aa.toInt()), current.readBytes())
            }
        }

    @Test
    fun `MCBBS export is an actual standard archive with icon hashes and permission checks`() =
        runBlocking {
            fixture { store, instance, icons ->
                val root = prepareExportSources(store, instance, icons)
                NetworkClient().use { network ->
                    val service =
                        ContentService(
                            store,
                            network,
                            MinecraftInstaller(store, network, JavaService(store, network)),
                        )
                    val plan =
                        service.previewMrpackExport(
                            instance.id,
                            MrpackExportMetadata("Pack", "1.0"),
                            false,
                        )
                    val selected = plan.files.map { it.path }.toSet()
                    val target = temporary.resolve("out.zip")
                    exportMcbbsArchive(service, plan, target, store, selected)
                    assertMcbbsArchive(target)
                    assertEquals("NEVER_EXPORT", root.resolve("credentials/secret.json").readText())
                }
            }
        }

    /** 准备导出源：普通配置与模组、不可导出的凭据，以及一张自定义图标。 */
    private suspend fun prepareExportSources(
        store: LauncherStore,
        instance: GameInstance,
        icons: InstanceIcons,
    ): Path {
        val root = store.instancePath(instance.id)
        root
            .resolve("config")
            .createDirectory()
            .resolve("settings.txt")
            .writeText("configuration")
        root
            .resolve("mods")
            .createDirectory()
            .resolve("local.jar")
            .writeText("mod bytes")
        root
            .resolve("credentials")
            .createDirectory()
            .resolve("secret.json")
            .writeText("NEVER_EXPORT")
        val source =
            temporary.resolve("icon.png").apply { writeBytes(image(0xffcc9966.toInt())) }
        icons.setCustom(instance.id, source)
        return root
    }

    /** 无本地文件授权时必须拒绝；授权后导出同一份计划成功。 */
    private suspend fun exportMcbbsArchive(
        service: ContentService,
        plan: MrpackExportPlan,
        target: Path,
        store: LauncherStore,
        selected: Set<String>,
    ) {
        assertFailsWith<IllegalArgumentException> {
            service.exportMrpack(
                plan,
                target,
                JobContext(store, "export"),
                selected,
                format = PackExportFormat.Mcbbs,
            )
        }
        assertFalse(target.exists())
        service.exportMrpack(
            plan,
            target,
            JobContext(store, "export"),
            selected,
            setOf("mods/local.jar"),
            PackExportFormat.Mcbbs,
        )
    }

    /** 导出的应是标准 MCBBS 归档：packmeta、overrides 与图标，且不含凭据文件。 */
    private fun assertMcbbsArchive(target: Path) {
        ZipFile(target.toFile()).use { zip ->
            assertNotNull(zip.getEntry("mcbbs.packmeta"))
            assertNull(zip.getEntry("modrinth.index.json"))
            assertNotNull(zip.getEntry("overrides/mods/local.jar"))
            assertNotNull(zip.getEntry("icon.png"))
            assertFalse(zip.entries().asSequence().any { it.name.contains("secret") })
            val parsed = readPackManifest(zip, temporary.resolve("preview"), "windows")
            assertEquals("26.3", parsed.request.gameVersion)
            assertEquals("Pack", parsed.request.name)
            assertEquals(2, parsed.checkedFiles.size)
        }
    }

    @Test
    fun `MultiMC export carries the instance icon under icons and imports it back`() =
        runBlocking {
            fixture { store, instance, icons ->
                prepareExportSources(store, instance, icons)
                NetworkClient().use { network ->
                    val service =
                        ContentService(
                            store,
                            network,
                            MinecraftInstaller(store, network, JavaService(store, network)),
                        )
                    val plan =
                        service.previewMrpackExport(
                            instance.id,
                            MrpackExportMetadata("Pack", "1.0"),
                            false,
                        )
                    val target = temporary.resolve("prism.zip")
                    service.exportMrpack(
                        plan,
                        target,
                        JobContext(store, "export"),
                        plan.files.map { it.path }.toSet(),
                        setOf("mods/local.jar"),
                        PackExportFormat.MultiMc,
                    )
                    assertMultiMcIcon(store, instance, icons, target)
                }
            }
        }

    /** 图标必须以 icons/pack.png 落盘并被 iconKey 引用，导入后能还原同样像素。 */
    private suspend fun assertMultiMcIcon(
        store: LauncherStore,
        instance: GameInstance,
        icons: InstanceIcons,
        target: Path,
    ) {
        ZipFile(target.toFile()).use { zip ->
            assertNotNull(zip.getEntry("icons/pack.png"))
            assertNull(zip.getEntry("icon.png"))
            val config =
                zip
                    .getInputStream(requireNotNull(zip.getEntry("instance.cfg")))
                    .use { it.readBytes().toString(Charsets.UTF_8) }
            assertTrue(config.contains("iconKey=pack"))
            icons.setPreset(instance.id, InstanceIconStyle.Auto)
            applyPackIcon(
                store,
                instance.id,
                zip,
                readPackManifest(zip, temporary.resolve("preview"), "windows"),
            )
            assertEquals(
                0xffcc9966.toInt(),
                ImageIO
                    .read(requireNotNull(icons.texture(store.instance(instance.id))).toFile())
                    .getRGB(0, 0),
            )
        }
    }
}
