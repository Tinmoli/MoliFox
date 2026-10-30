// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证已识别资源的事务化导入与索引重建。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MrpackExportImportTest : MrpackExportTestBase() {
    @Test
    fun `recognized resources are verified indexed and imported transactionally`() =
        runBlocking {
            fixture { store, _, instance, root ->
                val payloads =
                    (0..100).associate { i ->
                        "mods/mod-$i.jar${if (i == 0) ".disabled" else ""}" to "payload $i"
                    }
                payloads.forEach { (path, value) -> write(root, path, value) }
                write(root, "config/中文.toml", "配置")
                MockWebServer().use { server ->
                    NetworkClient().use { network ->
                        val service = service(store, network, server)
                        val offline = service.previewMrpackExport(instance.id, metadata, false)
                        val resources = offline.files.filter { it.requiresPermission }
                        server.dispatcher = fingerprintDispatcher(resources)
                        val plan = service.previewMrpackExport(instance.id, metadata)
                        assertFingerprintLookups(server)
                        assertTrue(plan.warnings.isEmpty())
                        assertEquals(102, plan.defaultSelection.size)
                        assertTrue(plan.files.all { !it.requiresPermission })
                        val target = temporary.resolve("indexed.mrpack")
                        service.exportMrpack(plan, target, JobContext(store, "test"))
                        ZipFile(target.toFile()).use { zip ->
                            assertIndexedArchive(zip)
                            val manifest =
                                readPackManifest(zip, temporary.resolve("imported"), "windows")
                            assertIndexedManifest(manifest)
                            importAndVerify(store, zip, manifest, payloads)
                        }
                    }
                }
            }
        }

    /** 按请求中的指纹返回对应的远程版本条目。 */
    private fun fingerprintDispatcher(resources: List<MrpackExportFile>) =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body =
                    JsonParser
                        .parseString(
                            request.body.clone().readUtf8(),
                        ).asJsonObject
                val hashes = body.array("hashes").map { it.asString }.toSet()
                return MockResponse()
                    .setBody(
                        gson.toJson(
                            JsonObject().apply {
                                resources
                                    .filter { it.sha512 in hashes }
                                    .forEach { add(it.sha512, remote(it)) }
                            },
                        ),
                    )
            }
        }

    private fun assertFingerprintLookups(server: MockWebServer) {
        assertEquals(2, server.requestCount)
        repeat(2) {
            val request = server.takeRequest()
            assertEquals("/v2/version_files", request.path)
            assertEquals("POST", request.method)
            assertNull(request.getHeader("Authorization"))
        }
    }

    private fun assertIndexedArchive(zip: ZipFile) {
        assertEquals(
            setOf("modrinth.index.json", "client-overrides/config/中文.toml"),
            zip
                .entries()
                .asSequence()
                .map { it.name }
                .toSet(),
        )
    }

    private fun assertIndexedManifest(manifest: PackManifest) {
        assertEquals(101, manifest.requiredFiles)
        assertTrue(
            checkNotNull(manifest.index).array("files").any {
                it.asJsonObject.string("path").endsWith(".disabled")
            },
        )
        assertTrue(
            manifest.index.array("files").all {
                it.asJsonObject.obj("env").string("server") == "unsupported"
            },
        )
    }

    private suspend fun importAndVerify(
        store: LauncherStore,
        zip: ZipFile,
        manifest: PackManifest,
        payloads: Map<String, String>,
    ) {
        val destination = temporary.resolve("imported").apply { createDirectories() }
        val transaction = PackFileTransaction(store, destination)
        try {
            stagePackFiles(
                manifest,
                zip,
                transaction,
                destination,
                null,
                false,
                JobContext(store, "test"),
            ) { artifact ->
                val relative =
                    transaction.content
                        .relativize(artifact.target)
                        .joinToString("/")
                write(
                    transaction.content,
                    relative,
                    payloads.getValue(relative),
                )
            }
            transaction.commit(JobContext(store, "test"))
            payloads.forEach { (path, value) ->
                assertEquals(value, destination.resolve(path).readText())
            }
            assertEquals("配置", destination.resolve("config/中文.toml").readText())
        } finally {
            transaction.cleanup()
        }
    }
}
