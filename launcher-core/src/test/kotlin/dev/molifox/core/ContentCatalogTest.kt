// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证内容文件索引、更新检查和版本锁定的行为与边界。

package dev.molifox.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentCatalogTest {
    @TempDir lateinit var temporary: Path

    private fun fixture(
        mode: String = "normal",
        action: suspend (ContentService, LauncherStore, GameInstance, Path) -> Unit,
    ) = runBlocking {
        MockWebServer().use { server ->
            val (store, instance, root) = prepareCatalogFixture()
            server.dispatcher = CatalogPayloads(mode, server).dispatcher()
            NetworkClient().use { network ->
                action(
                    ContentService(
                        store,
                        network,
                        MinecraftInstaller(store, network, JavaService(store, network)),
                        server.loopbackUrl("/v2").toString(),
                    ),
                    store,
                    instance,
                    root,
                )
            }
        }
    }

    private fun prepareCatalogFixture(): Triple<LauncherStore, GameInstance, Path> {
        val platform =
            object : PlatformServices by DesktopPlatform() {
                override val defaultDataDirectory = temporary.resolve("profile")

                override fun moveToTrash(file: Path) {
                    val target = temporary.resolve("trash/${UUID.randomUUID()}")
                    target.parent.createDirectories()
                    Files.move(file, target)
                }
            }
        val store = LauncherStore(platform)
        val instance =
            GameInstance(
                name = "Catalog",
                gameVersion = "1.20.1",
                versionId = "fixture",
                loader = LoaderType.Fabric,
                installed = true,
            )
        store.mutate { it.copy(instances = listOf(instance)) }
        val root =
            store.instancePath(instance.id).apply {
                resolve("mods").createDirectories()
                resolve("resourcepacks").createDirectories()
            }
        root.resolve("mods/a-old.jar.disabled").writeText("a1")
        root.resolve("mods/b-old.jar").writeText("b1")
        root.resolve("mods/unknown.jar").writeText("unknown")
        root.resolve("resourcepacks/r-old.zip").writeText("r1")

        return Triple(store, instance, root)
    }

    @Test
    fun `hash recognition is read only and batch update preserves disabled local mods`() =
        fixture { service, store, instance, root ->
            val report = service.catalog.checkUpdates(instance.id)
            assertEquals(4, report.items.size)
            assertNull(checkNotNull(report.items.find { it.filename == "unknown.jar" }).source)
            assertFalse(root.resolve(".molifox-content.json").exists())
            val plan =
                service.planUpdates(
                    report,
                    report.items
                        .filter { it.update != null }
                        .map { it.key }
                        .toSet(),
                )
            assertEquals(3, plan.files.size)
            service.installPlan(plan, JobContext(store, "test"))
            assertEquals("a2", root.resolve("mods/a2.jar.disabled").readText())
            assertFalse(root.resolve("mods/a-old.jar.disabled").exists())
            assertEquals("b2", root.resolve("mods/b2.jar").readText())
            assertEquals("r2", root.resolve("resourcepacks/r2.zip").readText())
            assertEquals("unknown", root.resolve("mods/unknown.jar").readText())
        }

    @Test
    fun `version lock excludes updates and rejects dependency replacement`() =
        fixture { service, _, instance, root ->
            val source =
                checkNotNull(
                    service.catalog
                        .checkUpdates(instance.id)
                        .items
                        .first { it.filename == "b-old.jar" }
                        .source,
                )
            service.catalog.setLocked(instance.id, source, true)
            val report = service.catalog.checkUpdates(instance.id)
            assertTrue(
                checkNotNull(
                    report.items
                        .first { it.filename == "b-old.jar" }
                        .source,
                ).locked,
            )
            assertNull(report.items.first { it.filename == "b-old.jar" }.update)
            assertFailsWith<IllegalArgumentException> {
                service.planUpdates(report, setOf("mods/a-old.jar.disabled"))
            }
            service.catalog.setLocked(instance.id, source, false)
            assertFalse(
                readObject(root.resolve(".molifox-content.json")).obj("b").boolean("locked"),
            )
        }

    @Test
    fun `details filter incompatible versions and explicit version reaches installation plan`() =
        fixture { service, _, instance, _ ->
            val details = service.catalog.details("a", instance.id)
            assertEquals("MIT", details.license)
            assertEquals(listOf("a2", "a1"), details.versions.map { it.id })
            assertEquals(
                "a1",
                service
                    .planContent(details.hit, instance.id, versionId = "a1")
                    .files
                    .single()
                    .versionId,
            )
        }

    @Test
    fun `incompatible update is not offered`() =
        fixture("incompatible") { service, _, instance, _ ->
            assertTrue(
                service.catalog
                    .checkUpdates(instance.id)
                    .items
                    .all { it.update == null },
            )
            assertEquals(
                listOf("a1"),
                service.catalog
                    .details("a", instance.id)
                    .versions
                    .map { it.id },
            )
        }

    @Test
    fun `prerelease requires explicit opt in`() =
        fixture("beta") { service, _, instance, _ ->
            assertTrue(
                service.catalog
                    .checkUpdates(instance.id)
                    .items
                    .all { it.update == null },
            )
            assertEquals(
                3,
                service.catalog
                    .checkUpdates(instance.id, true)
                    .items
                    .count { it.update != null },
            )
        }

    @Test
    fun `failed update restores unmanaged originals without creating registry`() =
        fixture("download-fail") { service, store, instance, root ->
            val report = service.catalog.checkUpdates(instance.id)
            val plan =
                service.planUpdates(report, setOf("mods/a-old.jar.disabled", "mods/b-old.jar"))
            assertFails { service.installPlan(plan, JobContext(store, "test")) }
            assertEquals("a1", root.resolve("mods/a-old.jar.disabled").readText())
            assertEquals("b1", root.resolve("mods/b-old.jar").readText())
            assertFalse(root.resolve(".molifox-content.json").exists())
            assertTrue(root.listDirectoryEntries().none { it.name.startsWith(".molifox-content-") })
        }

    @Test
    fun `changed files and registry invalidate prepared updates`() =
        fixture { service, store, instance, root ->
            val report = service.catalog.checkUpdates(instance.id)
            val plan = service.planUpdates(report, setOf("mods/a-old.jar.disabled"))
            root.resolve("mods/a-old.jar.disabled").writeText("edited")
            assertFails { service.installPlan(plan, JobContext(store, "test")) }
            assertEquals("edited", root.resolve("mods/a-old.jar.disabled").readText())
            assertFails {
                service.catalog.setLocked(
                    instance.id,
                    checkNotNull(report.items.first { it.filename == "a-old.jar.disabled" }.source),
                    true,
                )
            }
            writeJson(root.resolve(".molifox-content.json"), mapOf("changed" to true))
            assertFails { service.planUpdates(report, setOf("mods/b-old.jar")) }
        }

    @Test
    fun `conflicting selected versions fail as one dependency graph`() =
        fixture("conflict") { service, _, instance, _ ->
            val report = service.catalog.checkUpdates(instance.id)
            assertFailsWith<IllegalArgumentException> {
                service.planUpdates(report, setOf("mods/a-old.jar.disabled", "mods/b-old.jar"))
            }
        }

    @Test
    fun `network failure is not reported as no updates`() =
        fixture("network-fail") { service, _, instance, _ ->
            assertFails { service.catalog.checkUpdates(instance.id) }
        }

    @Test
    fun `updating one root also replaces recognized unmanaged dependencies`() =
        fixture { service, store, instance, root ->
            val report = service.catalog.checkUpdates(instance.id)
            service.installPlan(
                service.planUpdates(report, setOf("mods/a-old.jar.disabled")),
                JobContext(store, "test"),
            )
            assertEquals("b2", root.resolve("mods/b2.jar").readText())
            assertFalse(root.resolve("mods/b-old.jar").exists())
        }

    @Test
    fun `collision after first replacement restores adopted dependency`() =
        fixture { service, store, instance, root ->
            root.resolve("mods/a2.jar.disabled").writeText("keep-conflict")
            val report = service.catalog.checkUpdates(instance.id)
            val plan = service.planUpdates(report, setOf("mods/a-old.jar.disabled"))
            assertFails { service.installPlan(plan, JobContext(store, "test")) }
            assertEquals("b1", root.resolve("mods/b-old.jar").readText())
            assertEquals("keep-conflict", root.resolve("mods/a2.jar.disabled").readText())
            assertFalse(root.resolve("mods/b2.jar").exists())
            assertFalse(root.resolve(".molifox-content.json").exists())
        }

    @Test
    fun `case insensitive disabled suffix is preserved during update`() =
        fixture { service, store, instance, root ->
            val original = root.resolve("mods/a-old.jar.disabled")
            val bytes = original.readBytes()
            original.deleteExisting()
            root.resolve("mods/a-old.jar.DISABLED").writeBytes(bytes)
            val report = service.catalog.checkUpdates(instance.id)
            service.installPlan(
                service.planUpdates(report, setOf("mods/a-old.jar.DISABLED")),
                JobContext(store, "test"),
            )
            assertEquals("a2", root.resolve("mods/a2.jar.disabled").readText())
            assertFalse(root.resolve("mods/a-old.jar.DISABLED").exists())
        }
}

/** 按 mode 生成 Modrinth 录制样本：正常、beta、不兼容、依赖冲突、下载失败与网络失败。 */
private class CatalogPayloads(
    private val mode: String,
    private val server: MockWebServer,
) {
    fun dispatcher(): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = responseFor(request)
        }

    private fun responseFor(request: RecordedRequest): MockResponse {
        val path = checkNotNull(request.requestUrl).encodedPath
        if (mode == "network-fail" && path == "/v2/version_files/update") {
            return MockResponse().setResponseCode(503)
        }
        return when {
            path == "/v2/version_files" || path == "/v2/version_files/update" ->
                MockResponse().setBody(gson.toJson(versionFileBody(request, path)))
            path.startsWith("/v2/version/") ->
                MockResponse().setBody(gson.toJson(version(path.substringAfterLast('/'))))
            path.endsWith("/version") ->
                MockResponse().setBody(gson.toJson(projectVersions(path)))
            path.startsWith("/v2/project/") ->
                MockResponse().setBody(gson.toJson(projectBody(path)))
            path.startsWith("/files/") -> fileResponse(path)
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun projectVersions(path: String): List<Map<String, Any>> {
        val project = path.removePrefix("/v2/project/").substringBefore('/')
        return listOf(version("${project}2"), version("${project}1"))
    }

    private fun projectBody(path: String): Map<String, Any> {
        val project = path.substringAfterLast('/')
        return mapOf(
            "project_type" to if (project == "r") "resourcepack" else "mod",
            "title" to "Project $project",
            "slug" to project,
            "body" to "Project body",
            "license" to mapOf("id" to "MIT"),
        )
    }

    private fun fileResponse(path: String): MockResponse =
        if (mode == "download-fail" && path.endsWith("b2")) {
            MockResponse().setResponseCode(404)
        } else {
            MockResponse().setBody(path.substringAfterLast('/'))
        }

    private fun versionFileBody(
        request: RecordedRequest,
        path: String,
    ): Map<String, Map<String, Any>> {
        val data = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        val requested = data.array("hashes").map { it.asString }.toSet()
        return listOf("a", "b", "r")
            .filter { hash("${it}1") in requested }
            .associate { project ->
                val suffix = if (path.endsWith("update")) "2" else "1"
                hash("${project}1") to version("$project$suffix")
            }
    }

    private fun version(id: String): Map<String, Any> {
        val project = id.take(1)
        return mapOf(
            "id" to id,
            "project_id" to project,
            "name" to "$project $id",
            "version_number" to id,
            "version_type" to versionType(mode, id),
            "date_published" to publishedAt(id),
            "changelog" to "Changes $id",
            "game_versions" to gameVersions(mode, id),
            "loaders" to listOf(if (project == "r") "minecraft" else "fabric"),
            "dependencies" to dependencies(mode, project, id),
            "files" to
                listOf(
                    mapOf(
                        "filename" to "$id.${if (project == "r") "zip" else "jar"}",
                        "primary" to true,
                        "url" to server.loopbackUrl("/files/$id").toString(),
                        "size" to id.length,
                        "hashes" to mapOf("sha512" to hash(id)),
                    ),
                ),
        )
    }
}

private fun hash(value: String): String =
    java.security.MessageDigest
        .getInstance("SHA-512")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

private fun versionType(
    mode: String,
    id: String,
): String = if (mode == "beta" && id.endsWith("2")) "beta" else "release"

private fun publishedAt(id: String): String =
    if (id.endsWith("1")) "2025-01-01T00:00:00Z" else "2025-02-01T00:00:00Z"

private fun gameVersions(
    mode: String,
    id: String,
): List<String> = listOf(if (mode == "incompatible" && id.endsWith("2")) "1.21" else "1.20.1")

private fun dependencies(
    mode: String,
    project: String,
    id: String,
): List<Map<String, String>> =
    if (project == "a" && id == "a2") {
        listOf(
            mapOf(
                "project_id" to "b",
                "version_id" to if (mode == "conflict") "b1" else "b2",
                "dependency_type" to "required",
            ),
        )
    } else {
        emptyList()
    }
