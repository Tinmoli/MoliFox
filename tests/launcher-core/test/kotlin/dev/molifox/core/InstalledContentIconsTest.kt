// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证包内头像、登记来源与明确允许的摘要识别，全部使用假网络响应。

package dev.molifox.core

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstalledContentIconsTest {
    @TempDir lateinit var temporary: Path

    private inner class Fixture : AutoCloseable {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
        val instance =
            GameInstance(
                name = "Fixture",
                versionId = "fixture",
                installed = true,
                isolation = VersionIsolation.Enabled,
            )
        val root: Path
        val requests = CopyOnWriteArrayList<Request>()
        var projectReads = 0
        var correctHash = true
        lateinit var archive: Path
        val icon =
            ByteArrayOutputStream()
                .also {
                    ImageIO.write(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), "png", it)
                }.toByteArray()

        /** 模拟 CDN 返回的图标格式；Modrinth 实际常用 WebP。 */
        var remoteIcon: ByteArray? = null
        private val backend =
            object : ContentBackend {
                override suspend fun project(id: String): JsonObject {
                    assertEquals("fixture-project", id)
                    projectReads++
                    return gson
                        .toJsonTree(
                            mapOf("project_type" to "shader", "icon_url" to ICON_URL),
                        ).asJsonObject
                }

                override suspend fun version(id: String): JsonObject = error("Unexpected version")

                override suspend fun versions(
                    id: String,
                    instance: GameInstance,
                    kind: String,
                ): List<JsonObject> = error("Unexpected versions")

                override suspend fun browseVersions(id: String): List<JsonObject> = error("Unexpected browse")
            }
        val network =
            NetworkClient(
                interceptors =
                    listOf(
                        Interceptor { chain ->
                            val request = chain.request()
                            requests += request
                            val bytes =
                                if (request.url.host == "cdn.modrinth.com") {
                                    remoteIcon ?: icon
                                } else {
                                    check(request.url.encodedPath.startsWith("/v2/version_file/"))
                                    val hash =
                                        if (correctHash) {
                                            digest(
                                                archive,
                                                "SHA-512",
                                            )
                                        } else {
                                            "0".repeat(128)
                                        }
                                    gson
                                        .toJson(
                                            mapOf(
                                                "project_id" to "fixture-project",
                                                "files" to
                                                    listOf(
                                                        mapOf("hashes" to mapOf("sha512" to hash)),
                                                    ),
                                            ),
                                        ).toByteArray()
                                }
                            Response
                                .Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(java.net.HttpURLConnection.HTTP_OK)
                                .message("Fixture")
                                .body(
                                    bytes.toResponseBody("application/octet-stream".toMediaType()),
                                ).build()
                        },
                    ),
            )

        init {
            store.mutate { it.copy(instances = listOf(instance)) }
            root = Files.createDirectories(store.instancePath(instance.id))
        }

        fun zip(
            entries: Map<String, ByteArray> = mapOf("shaders/final.fsh" to byteArrayOf(1)),
            folder: PackFolder = PackFolder.ShaderPacks,
        ): Path {
            archive = root.resolve(folder.directory).resolve("private-user-shader.zip")
            Files.createDirectories(archive.parent)
            ZipOutputStream(Files.newOutputStream(archive)).use { output ->
                for ((name, bytes) in entries) {
                    output.putNextEntry(ZipEntry(name))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
            return archive
        }

        fun register(
            url: String,
            hash: String = digest(archive, "SHA-512"),
        ) {
            writeJson(
                root.resolve(".molifox-content.json"),
                mapOf(
                    "fixture-project" to
                        mapOf(
                            "file" to archive.fileName.toString(),
                            "folder" to "shaderpacks",
                            "hash" to hash,
                            "hashAlgorithm" to "SHA-512",
                            "iconUrl" to url,
                        ),
                ),
            )
        }

        fun reader(identify: Boolean = false) =
            InstalledContentIcons(store, network, backend, identifyUnregistered = identify)

        override fun close() = network.close()
    }

    @Test
    fun `embedded pack image works without any project or network lookup`(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                val file = fixture.zip(mapOf("pack.png" to fixture.icon))
                assertContentEquals(
                    fixture.icon,
                    fixture
                        .reader(
                            true,
                        ).read(fixture.instance.id, file, PackFolder.ShaderPacks)
                        .getOrThrow(),
                )
                assertTrue(fixture.requests.isEmpty())
                assertEquals(0, fixture.projectReads)
            }
        }

    @Test
    fun `ordinary shader textures are not used as an avatar`() {
        Fixture().use { fixture ->
            assertNull(shaderPackIcon(fixture.zip(mapOf("shaders/noise.png" to fixture.icon))))
        }
    }

    @Test
    fun `unregistered files do not send a fingerprint without explicit enablement`(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                val file = fixture.zip()
                assertNull(
                    fixture
                        .reader()
                        .read(
                            fixture.instance.id,
                            file,
                            PackFolder.ShaderPacks,
                        ).getOrThrow(),
                )
                assertTrue(fixture.requests.isEmpty())
            }
        }

    @Test
    fun `enabled identification sends only a hash and retrieves the exact project icon`(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                val file = fixture.zip()
                assertContentEquals(
                    fixture.icon,
                    fixture
                        .reader(
                            true,
                        ).read(fixture.instance.id, file, PackFolder.ShaderPacks)
                        .getOrThrow(),
                )
                assertEquals(2, fixture.requests.size)
                val identification = fixture.requests.first()
                assertEquals("GET", identification.method)
                assertNull(identification.body)
                assertEquals(digest(file, "SHA-512"), identification.url.pathSegments.last())
                assertEquals("sha512", identification.url.queryParameter("algorithm"))
                assertTrue(
                    fixture.requests.none { it.url.toString().contains(file.fileName.toString()) },
                )
            }
        }

    @Test
    fun `lossless webp project icons are accepted for shader avatars`(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                fixture.remoteIcon = WEBP_LOSSLESS_ICON
                val file = fixture.zip()
                assertContentEquals(
                    WEBP_LOSSLESS_ICON,
                    fixture
                        .reader(true)
                        .read(fixture.instance.id, file, PackFolder.ShaderPacks)
                        .getOrThrow(),
                )
                assertEquals(2, fixture.requests.size)
            }
        }

    @Test
    fun `lossy webp project icons are accepted for shader avatars`(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                fixture.remoteIcon = WEBP_LOSSY_ICON
                val file = fixture.zip()
                assertContentEquals(
                    WEBP_LOSSY_ICON,
                    fixture
                        .reader(true)
                        .read(fixture.instance.id, file, PackFolder.ShaderPacks)
                        .getOrThrow(),
                )
            }
        }

    @Test
    fun `unrelated fingerprint replies do not produce an avatar`(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                val file = fixture.zip()
                fixture.correctHash = false
                assertNull(
                    fixture
                        .reader(
                            true,
                        ).read(fixture.instance.id, file, PackFolder.ShaderPacks)
                        .getOrThrow(),
                )
                assertEquals(1, fixture.requests.size)
                assertEquals(0, fixture.projectReads)
            }
        }

    @Test
    fun `registered installs use verified origins without hash lookup`(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                val file = fixture.zip()
                fixture.register(ICON_URL)
                val reader = fixture.reader()
                assertContentEquals(
                    fixture.icon,
                    reader.read(fixture.instance.id, file, PackFolder.ShaderPacks).getOrThrow(),
                )
                assertEquals(0, fixture.projectReads)
                fixture.register("")
                assertContentEquals(
                    fixture.icon,
                    reader.read(fixture.instance.id, file, PackFolder.ShaderPacks).getOrThrow(),
                )
                assertEquals(1, fixture.projectReads)
                assertTrue(fixture.requests.all { it.url.host == "cdn.modrinth.com" })
            }
        }

    @Test
    fun `changed registered files cannot borrow an old project avatar`(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                val file = fixture.zip()
                fixture.register(ICON_URL, "0".repeat(128))
                assertNull(
                    fixture
                        .reader()
                        .read(
                            fixture.instance.id,
                            file,
                            PackFolder.ShaderPacks,
                        ).getOrThrow(),
                )
                assertTrue(fixture.requests.isEmpty())
            }
        }

    @Test
    fun `shader consent does not enable resource pack lookup`(): Unit =
        runBlocking {
            Fixture().use { fixture ->
                val file = fixture.zip(folder = PackFolder.ResourcePacks)
                assertNull(
                    fixture
                        .reader(
                            true,
                        ).read(fixture.instance.id, file, PackFolder.ResourcePacks)
                        .getOrThrow(),
                )
                assertTrue(fixture.requests.isEmpty())
            }
        }

    private companion object {
        const val ICON_URL = "https://cdn.modrinth.com/data/fixture-project/icon.png"
        val WEBP_LOSSY_ICON: ByteArray = Base64.getDecoder().decode(WEBP_LOSSY_BASE64)
        val WEBP_LOSSLESS_ICON: ByteArray = Base64.getDecoder().decode(WEBP_LOSSLESS_BASE64)
        const val WEBP_LOSSY_BASE64 =
            "UklGRlgAAABXRUJQVlA4IEwAAAAwBACdASpgADAAPm02mEkkIyKhIcgAgA2JaQB2APwAAB3gvxVY0Huy4AAA" +
                "/vCbQ//+QXLC65Gv//yA/5Af8gP/498W46FOdCAAAAAA"
        const val WEBP_LOSSLESS_BASE64 =
            "UklGRiIAAABXRUJQVlA4TBUAAAAvX8ALAAcQ/Y/+B4CE8H++FNH/1AcA"
    }
}
