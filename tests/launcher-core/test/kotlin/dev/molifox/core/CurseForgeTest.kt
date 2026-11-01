// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证CurseForge API 适配和授权下载规则的行为与边界。

package dev.molifox.core

import org.junit.jupiter.api.Test
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 检索、详情、依赖规划与指纹更新的行为与边界。 */
class CurseForgeTest : CurseForgeTestBase() {
    @Test
    fun `search filters version loader category and bounded pagination`() =
        fixture {
            assertEquals(
                "curseforge:1",
                service
                    .search("hello world", "mod", instance.id)
                    .hits
                    .single()
                    .id,
            )
            val request =
                checkNotNull(
                    checkNotNull(
                        requests.find {
                            checkNotNull(it.requestUrl).encodedPath.endsWith("/search")
                        },
                    ).requestUrl,
                )
            assertEquals("hello world", request.queryParameter("searchFilter"))
            assertEquals("1.20.1", request.queryParameter("gameVersion"))
            assertEquals("4", request.queryParameter("modLoaderType"))
            assertEquals("6", request.queryParameter("classId"))
            service.search("", "mod", offset = 9996)
            assertEquals("4", checkNotNull(requests.last().requestUrl).queryParameter("pageSize"))
            val count = requests.size
            assertTrue(service.search("", "mod", offset = 10000).hits.isEmpty())
            // 此时只查询分类，不能发出超出范围的搜索请求。
            assertEquals(count + 1, requests.size)
        }

    @Test
    fun `details and exact versions keep provider identity when source changes`() =
        fixture {
            store.mutate {
                it.copy(
                    settings = it.settings.copy(contentDownloadSource = "modrinth"),
                )
            }
            val details = service.catalog.details(hit().id, instance.id)
            assertEquals(
                listOf("curseforge:1:12", "curseforge:1:11"),
                details.versions.map { it.id },
            )
            assertEquals("Project description", details.body)
            assertEquals("Author", details.hit.author)
            assertEquals("CurseForge", details.providerName)
            assertEquals(
                "Changes",
                service.catalog.versionDetails(hit().id, "curseforge:1:11", instance.id).changelog,
            )
            assertEquals(
                "https://www.curseforge.com/minecraft/mc-mods/project-1",
                contentWebsite(details.hit),
            )
            val plan = service.planContent(hit(), instance.id, versionId = "curseforge:1:11")
            assertEquals(listOf("curseforge:1:11"), plan.files.map { it.versionId })
        }

    @Test
    fun `provider versions exclude loader tags without requiring an instance`() =
        fixture {
            store.mutate {
                it.copy(settings = it.settings.copy(contentDownloadSource = "modrinth"))
            }
            val details = service.catalog.browse(hit().id)
            assertEquals("CurseForge", details.providerName)
            assertEquals(
                listOf("curseforge:1:12", "curseforge:1:11"),
                details.versions.map { it.id },
            )
            assertEquals(listOf("1.20.1"), details.versions.first().gameVersions)
            assertEquals(listOf("fabric"), details.versions.first().loaders)
            val request =
                checkNotNull(
                    requests
                        .last {
                            checkNotNull(it.requestUrl).encodedPath.endsWith("/files")
                        }.requestUrl,
                )
            assertNull(request.queryParameter("gameVersion"))
            assertNull(request.queryParameter("modLoaderType"))
            assertEquals("curseforge:1:12", recommendedContentVersion(details.versions)?.id)
            assertFalse(details.links.any { it.url.contains("search.mcmod.cn") })
        }

    @Test
    fun `custom search filters override instance for CurseForge and can show all versions`() =
        fixture {
            service.search(
                "",
                "mod",
                instance.id,
                filter = ContentFilter("26.3", LoaderType.NeoForge),
            )
            val request =
                checkNotNull(
                    requests
                        .last {
                            checkNotNull(it.requestUrl).encodedPath.endsWith("/search")
                        }.requestUrl,
                )
            assertEquals("26.3", request.queryParameter("gameVersion"))
            assertEquals("6", request.queryParameter("modLoaderType"))
            service.search("", "mod", instance.id, filter = ContentFilter())
            val unfiltered =
                checkNotNull(
                    requests
                        .last {
                            checkNotNull(it.requestUrl).encodedPath.endsWith("/search")
                        }.requestUrl,
                )
            assertNull(unfiltered.queryParameter("gameVersion"))
            assertNull(unfiltered.queryParameter("modLoaderType"))
        }

    @Test
    fun `required optional and embedded dependencies use one transactional install`() =
        fixture {
            val first = service.planContent(hit(), instance.id)
            assertEquals(
                setOf("curseforge:1", "curseforge:2"),
                first.files.map { it.projectId }.toSet(),
            )
            assertEquals(listOf("curseforge:3"), first.optional.map { it.id })
            val plan = service.planContent(hit(), instance.id, setOf("curseforge:3"))
            assertEquals(3, plan.files.size)
            service.installPlan(plan, JobContext(store, "test"))
            val root = store.instancePath(instance.id)
            assertEquals("file12", root.resolve("mods/12.jar").readText())
            assertEquals("file21", root.resolve("mods/21.jar").readText())
            assertEquals("file31", root.resolve("mods/31.jar").readText())
            assertTrue(
                requests
                    .filter { checkNotNull(it.path).startsWith("/files/") }
                    .all { it.getHeader("Authorization") == null },
            )
            assertTrue(readObject(root.resolve(".molifox-content.json")).has("curseforge:1"))
        }

    @Test
    fun `blocked dependencies are all reported without guessing downloads`() =
        fixture {
            mode = "blocked"
            val error =
                assertFailsWith<ManualContentRequired> {
                    service.planContent(hit(), instance.id, setOf("curseforge:3"))
                }
            assertEquals(setOf("21.jar", "31.jar"), error.files.map { it.filename }.toSet())
            assertTrue(
                error.files.all {
                    it.website.startsWith("https://www.curseforge.com/minecraft/mc-mods/")
                },
            )
            assertFalse(store.instancePath(instance.id).resolve("mods").exists())
            assertTrue(requests.none { checkNotNull(it.path).startsWith("/files/") })
        }

    @Test
    fun `missing download URLs also require manual files`() =
        fixture {
            mode = "missing-url"
            assertEquals(
                "21.jar",
                assertFailsWith<ManualContentRequired> { service.planContent(hit(), instance.id) }
                    .files
                    .single()
                    .filename,
            )
        }

    @Test
    fun `incompatible metadata and malformed hashes are rejected`() =
        fixture {
            for (
            value in
            listOf("wrong-version", "wrong-loader", "wrong-game", "server-pack", "bad-hash")
            ) {
                mode = value
                assertFails(value) { service.planContent(hit(), instance.id) }
            }
            assertFalse(store.instancePath(instance.id).resolve("mods").exists())
        }

    @Test
    fun `failed dependency download leaves files and registry untouched`() =
        fixture {
            val root = store.instancePath(instance.id)
            root.resolve("mods").createDirectories()
            root.resolve("mods/user.jar").writeText("user content")
            mode = "download-fail"
            assertFails {
                service.installPlan(
                    service.planContent(hit(), instance.id),
                    JobContext(store, "test"),
                )
            }
            assertEquals("user content", root.resolve("mods/user.jar").readText())
            assertFalse(root.resolve("mods/12.jar").exists())
            assertFalse(root.resolve(".molifox-content.json").exists())
            assertTrue(root.listDirectoryEntries(".molifox-content-*").isEmpty())
        }

    @Test
    fun `fingerprint match needs sha1 before adoption and update preserves disabled state`() =
        fixture {
            val root = store.instancePath(instance.id)
            val old = root.resolve("mods/old.jar.disabled")
            old.parent.createDirectories()
            old.writeText("file11")
            mode = "false-fingerprint"
            assertNull(
                service.catalog
                    .checkUpdates(instance.id)
                    .items
                    .single()
                    .source,
            )
            mode = "normal"
            val report = service.catalog.checkUpdates(instance.id)
            val item = report.items.single()
            assertEquals("curseforge:1:12", checkNotNull(item.update).id)
            val plan = service.planUpdates(report, setOf(item.key))
            service.installPlan(plan, JobContext(store, "test"))
            assertFalse(old.exists())
            assertEquals("file12", root.resolve("mods/12.jar.disabled").readText())
            assertFalse(root.resolve("mods/12.jar").exists())
        }
}
