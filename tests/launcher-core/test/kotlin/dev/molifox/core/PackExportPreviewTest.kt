// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PackExportPreviewTest : PackExportTestSupport() {
    private suspend fun ContentService.previewMrpackExport(
        id: String,
        metadata: MrpackExportMetadata,
        format: PackExportFormat,
        remoteResources: Boolean,
    ): MrpackExportPlan = prepareRequestedExport(
        PackExportRequest(
            id,
            metadata,
            temporary.resolve("selected.${format.extension}"),
            setOf("mods", "config", "saves", "resourcepacks", "shaderpacks"),
            format = format,
            options = PackExportOptions(remoteResources = remoteResources),
        ),
    )

    @Test
    fun `local selection previews never hash contents or query sources for any format`(): Unit = runBlocking {
        fixture { store, instance, root ->
            write(root, "mods/local.jar")
            write(root, "config/example.toml")
            write(root, "saves/world/region/r.0.0.mca")
            MockWebServer().use { server ->
                NetworkClient().use { network ->
                    val service = service(store, network, server)
                    for (format in PackExportFormat.offeredForExport) {
                        val remote = format != PackExportFormat.Modrinth
                        val plan = service.previewMrpackExport(instance.id, metadata, format, remote)
                        assertEquals(3, plan.files.size)
                        assertTrue(plan.files.all { it.sha1.isEmpty() && it.sha512.isEmpty() })
                        assertEquals(3, plan.fileStamps.size)
                    }
                    assertEquals(0, server.requestCount)
                }
            }
        }
    }

    @Test
    fun `remote selection hashes only mods resource packs and shaders`(): Unit = runBlocking {
        fixture { store, instance, root ->
            val resources = listOf("mods/a.jar", "resourcepacks/b.zip", "shaderpacks/c.zip")
            resources.forEach { write(root, it) }
            write(root, "config/embedded.jar")
            write(root, "saves/world/level.dat")
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("{}"))
                NetworkClient().use { network ->
                    val plan =
                        service(store, network, server).previewMrpackExport(
                            instance.id,
                            metadata,
                            PackExportFormat.Modrinth,
                            true,
                        )
                    assertEquals(
                        resources.toSet(),
                        plan.files
                            .filter { it.sha512.isNotEmpty() }
                            .map { it.path }
                            .toSet(),
                    )
                    val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                    val hashes = JsonParser.parseString(request.body.readUtf8()).asJsonObject.array("hashes")
                    assertEquals(
                        resources.map { digest(root.resolve(it), "SHA-512") }.toSet(),
                        hashes.map { it.asString }.toSet(),
                    )
                    assertEquals(1, server.requestCount)
                }
            }
        }
    }

    @Test
    fun `selected configuration exports offline without resource permission`(): Unit = runBlocking {
        fixture { store, instance, root ->
            write(root, "config/example.toml", "selected configuration")
            NetworkClient().use { network ->
                val service = service(store, network)
                // 原公开预览入口仍返回完整摘要，此用例也覆盖原权限误标问题。
                val plan = service.previewMrpackExport(instance.id, metadata, false)
                assertFalse(plan.files.single().requiresPermission)
                val target = temporary.resolve("configuration.mrpack")
                service.exportMrpack(
                    plan,
                    target,
                    JobContext(store, "export"),
                    setOf("config/example.toml"),
                    options = PackExportOptions(remoteResources = false),
                )
                ZipFile(target.toFile()).use { zip ->
                    val entry = assertNotNull(zip.getEntry("client-overrides/config/example.toml"))
                    assertEquals("selected configuration", zip.getInputStream(entry).reader().readText())
                }
            }
        }
    }

    @Test
    fun `lightweight previews write selected files and valid manifest hashes in every format`(): Unit = runBlocking {
        fixture { store, instance, root ->
            write(root, "config/example.toml", "selected configuration")
            write(root, "mods/local.jar", "local resource")
            write(root, "saves/unselected/level.dat", "unselected")
            NetworkClient().use { network ->
                val service = service(store, network)
                for (format in PackExportFormat.offeredForExport) {
                    val plan = service.previewMrpackExport(instance.id, metadata, format, false)
                    val target = temporary.resolve("selected-${format.name}.${format.extension}")
                    val selected = setOf("config/example.toml", "mods/local.jar")
                    service.exportMrpack(
                        plan,
                        target,
                        JobContext(store, "export"),
                        selected,
                        setOf("mods/local.jar"),
                        format,
                        PackExportOptions(remoteResources = false),
                    )
                    ZipFile(target.toFile()).use { zip ->
                        assertFalse(listing(zip).any { "unselected" in it })
                        for (path in selected) {
                            val entry = zip.entries().asSequence().single { it.name.endsWith("/$path") }
                            assertEquals(
                                root.resolve(path).readText(),
                                zip.getInputStream(entry).reader().readText(),
                            )
                        }
                        assertManifestHashes(zip, format, root)
                    }
                }
            }
        }
    }

    private fun assertManifestHashes(zip: ZipFile, format: PackExportFormat, root: java.nio.file.Path) {
        val manifest =
            when (format) {
                PackExportFormat.Mcbbs -> "mcbbs.packmeta"
                PackExportFormat.Backup -> "molifox-backup.json"
                else -> return
            }
        for (element in assertNotNull(packJson(zip, manifest)).array("files")) {
            val file = element.asJsonObject
            val key = if (format == PackExportFormat.Mcbbs) "hash" else "sha1"
            assertEquals(digest(root.resolve(file.string("path")), "SHA-1"), file.string(key))
        }
    }

    @Test
    fun `changed selection snapshot preserves existing output`(): Unit = runBlocking {
        fixture { store, instance, root ->
            val source = write(root, "config/example.toml", "before")
            NetworkClient().use { network ->
                val service = service(store, network)
                val plan = service.previewMrpackExport(instance.id, metadata, PackExportFormat.Modrinth, false)
                val stamp = Files.getLastModifiedTime(source)
                source.writeText("after!")
                Files.setLastModifiedTime(source, FileTime.fromMillis(stamp.toMillis() + 2000))
                val target = temporary.resolve("existing.mrpack").apply { writeText("old archive") }
                assertFailsWith<IllegalArgumentException> {
                    service.exportMrpack(plan, target, JobContext(store, "export"))
                }
                assertEquals("old archive", target.readText())
                assertTrue(temporary.listDirectoryEntries("*.part").isEmpty())
            }
        }
    }

    @Test
    fun `cancelling a stalled remote preview does not block local preview`(): Unit = runBlocking {
        fixture { store, instance, root ->
            write(root, "mods/a.jar")
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("{}").setBodyDelay(5, TimeUnit.SECONDS))
                NetworkClient().use { network ->
                    val service = service(store, network, server)
                    val remote =
                        async {
                            service.previewMrpackExport(instance.id, metadata, PackExportFormat.Modrinth, true)
                        }
                    assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
                    delay(200)
                    withTimeout(2000) { remote.cancelAndJoin() }
                    val local =
                        withTimeout(5000) {
                            service.previewMrpackExport(instance.id, metadata, PackExportFormat.Modrinth, false)
                        }
                    assertTrue(
                        local.files
                            .single()
                            .sha512
                            .isEmpty(),
                    )
                    assertEquals(1, server.requestCount)
                }
            }
        }
    }
}
