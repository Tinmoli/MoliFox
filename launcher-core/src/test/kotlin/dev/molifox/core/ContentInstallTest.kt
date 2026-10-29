// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证资源依赖规划和事务安装的行为与边界。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ContentInstallTest {
    @TempDir lateinit var temporary: Path

    private fun fixture(
        mode: String,
        action: suspend (ContentService, LauncherStore, GameInstance, Path) -> Unit,
    ) = runBlocking {
        MockWebServer().use { server ->
            val platform =
                object : PlatformServices by DesktopPlatform() {
                    override val defaultDataDirectory = temporary.resolve("profile")

                    override fun moveToTrash(file: Path) {
                        val target =
                            temporary.resolve("trash/${UUID.randomUUID()}-${file.fileName}")
                        target.parent.createDirectories()
                        Files.move(file, target)
                    }
                }
            val store = LauncherStore(platform)
            val instance =
                GameInstance(
                    name = "Mods",
                    gameVersion = "1.20.1",
                    versionId = "fixture",
                    loader = LoaderType.Fabric,
                    installed = true,
                )
            store.mutate { it.copy(instances = listOf(instance)) }
            val root = store.instancePath(instance.id).apply { createDirectories() }
            fun dependency(project: String, version: String, relation: String = "required") =
                mapOf(
                    "project_id" to project,
                    "version_id" to version,
                    "dependency_type" to relation,
                )
            fun version(project: String, versionId: String): String {
                val dependencies =
                    when (project) {
                        "a" ->
                            listOf(dependency("b", "b1")) +
                                when (mode) {
                                    "optional" -> listOf(dependency("c", "c1", "optional"))
                                    "conflict" -> listOf(dependency("c", "c1"))
                                    "incompatible" -> listOf(dependency("b", "b1", "incompatible"))
                                    else -> emptyList()
                                }
                        "c" ->
                            if (mode == "conflict") listOf(dependency("b", "b2")) else emptyList()
                        else -> emptyList()
                    }
                val body = "$project-new".toByteArray()
                return gson.toJson(
                    mapOf(
                        "id" to versionId,
                        "project_id" to project,
                        "name" to versionId,
                        "game_versions" to listOf("1.20.1"),
                        "loaders" to listOf("fabric"),
                        "version_type" to "release",
                        "dependencies" to dependencies,
                        "files" to
                            listOf(
                                mapOf(
                                    "filename" to "$project.jar",
                                    "primary" to true,
                                    "url" to server.loopbackUrl("/files/$project.jar").toString(),
                                    "size" to body.size,
                                    "hashes" to mapOf("sha1" to sha1(body)),
                                )
                            ),
                    )
                )
            }
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.path.orEmpty().substringBefore('?')
                        return when {
                            path.startsWith("/v2/project/") && path.endsWith("/version") -> {
                                val id = path.removePrefix("/v2/project/").substringBefore('/')
                                MockResponse().setBody("[${version(id, "${id}1")}]")
                            }
                            path.startsWith("/v2/project/") ->
                                MockResponse()
                                    .setBody(
                                        gson.toJson(
                                            mapOf(
                                                "project_type" to "mod",
                                                "title" to path.substringAfterLast('/'),
                                            )
                                        )
                                    )
                            path.startsWith("/v2/version/") -> {
                                val id = path.substringAfterLast('/')
                                MockResponse().setBody(version(id.take(1), id))
                            }
                            path.startsWith("/files/") ->
                                if (mode == "download-failure" && path.endsWith("a.jar"))
                                    MockResponse().setResponseCode(404)
                                else
                                    MockResponse()
                                        .setBody(
                                            "${path.substringAfterLast('/').substringBefore('.')}-new"
                                        )
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
            val network = NetworkClient()
            try {
                val service =
                    ContentService(
                        store,
                        network,
                        MinecraftInstaller(store, network, JavaService(store, network)),
                        server.loopbackUrl("/v2").toString(),
                    )
                action(service, store, instance, root)
            } finally {
                network.close()
            }
        }
    }

    private fun hit() = ContentHit("a", "a", "Test project", "", "", 0, "mod", null)

    private fun oldMod(root: Path) {
        root.resolve("mods").createDirectories()
        root.resolve("mods/b-old.jar.disabled").writeText("keep-old")
        writeJson(
            root.resolve(".molifox-content.json"),
            mapOf("b" to mapOf("file" to "b-old.jar", "folder" to "mods", "versionId" to "old")),
        )
    }

    @Test
    fun `required dependencies install together and updates preserve disabled mods`() =
        fixture("normal") { service, store, instance, root ->
            oldMod(root)
            val plan = service.planContent(hit(), instance.id)
            assertEquals(setOf("a", "b"), plan.files.map { it.projectId }.toSet())
            service.installPlan(plan, JobContext(store, "test"))
            assertEquals("b-new", root.resolve("mods/b.jar.disabled").readText())
            assertFalse(root.resolve("mods/b.jar").exists())
            assertEquals("a-new", root.resolve("mods/a.jar").readText())
            assertEquals(
                "b1",
                readObject(root.resolve(".molifox-content.json")).obj("b").string("versionId"),
            )
        }

    @Test
    fun `download failure never replaces an installed mod`() =
        fixture("download-failure") { service, store, instance, root ->
            oldMod(root)
            val registry = root.resolve(".molifox-content.json").readText()
            val plan = service.planContent(hit(), instance.id)
            assertFails { service.installPlan(plan, JobContext(store, "test")) }
            assertEquals("keep-old", root.resolve("mods/b-old.jar.disabled").readText())
            assertEquals(registry, root.resolve(".molifox-content.json").readText())
            assertFalse(root.resolve("mods/b.jar.disabled").exists())
            assertTrue(root.listDirectoryEntries().none { it.name.startsWith(".molifox-content-") })
        }

    @Test
    fun `later file collision rolls back earlier file replacements`() =
        fixture("normal") { service, store, instance, root ->
            oldMod(root)
            root.resolve("mods/a.jar").writeText("my-local-file")
            val registry = root.resolve(".molifox-content.json").readText()
            assertFails {
                service.installPlan(
                    service.planContent(hit(), instance.id),
                    JobContext(store, "test"),
                )
            }
            assertEquals("my-local-file", root.resolve("mods/a.jar").readText())
            assertEquals("keep-old", root.resolve("mods/b-old.jar.disabled").readText())
            assertFalse(root.resolve("mods/b.jar.disabled").exists())
            assertEquals(registry, root.resolve(".molifox-content.json").readText())
        }

    @Test
    fun `optional dependencies are selected explicitly`() =
        fixture("optional") { service, _, instance, _ ->
            val required = service.planContent(hit(), instance.id)
            assertFalse(required.files.any { it.projectId == "c" })
            assertEquals("c", required.optional.single().id)
            assertTrue(
                service.planContent(hit(), instance.id, setOf("c")).files.any {
                    it.projectId == "c"
                }
            )
        }

    @Test
    fun `conflicting exact dependency versions fail during planning`() =
        fixture("conflict") { service, _, instance, _ ->
            val error =
                assertFailsWith<IllegalArgumentException> {
                    service.planContent(hit(), instance.id)
                }
            assertTrue(error.message.orEmpty().contains("不同版本"))
        }

    @Test
    fun `incompatible installed dependency is reported before installing`() =
        fixture("incompatible") { service, _, instance, root ->
            writeJson(
                root.resolve(".molifox-content.json"),
                mapOf("b" to mapOf("file" to "b.jar", "folder" to "mods", "versionId" to "b1")),
            )
            assertFailsWith<IllegalArgumentException> { service.planContent(hit(), instance.id) }
            assertFalse(root.resolve("mods/a.jar").exists())
        }
}
