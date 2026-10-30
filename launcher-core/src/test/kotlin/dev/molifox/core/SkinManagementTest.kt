// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证皮肤库与正版皮肤管理的行为与边界。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.notExists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkinManagementTest {
    @TempDir lateinit var temporary: Path

    private fun png(
        name: String = "skin.png",
        width: Int = 64,
        height: Int = 64,
    ): Path =
        temporary.resolve(name).apply {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            for (x in 8 until minOf(16, width)) {
                for (y in 8 until minOf(16, height)) {
                    image.setRGB(
                        x,
                        y,
                        0xFF36B5A5.toInt(),
                    )
                }
            }
            ImageIO.write(image, "png", toFile())
        }

    private fun store() = LauncherStore(DesktopPlatform(), temporary.resolve("launcher"))

    private fun account() =
        Account(name = "SkinUser", uuid = "a".repeat(32), type = AccountType.Microsoft)

    private fun response(
        account: Account,
        variant: String = "SLIM",
    ) = gson.toJson(
        mapOf(
            "id" to account.uuid,
            "name" to "UpdatedName",
            "skins" to
                listOf(
                    mapOf(
                        "state" to "ACTIVE",
                        "variant" to variant,
                        "url" to "http://textures.minecraft.net/texture/${"b".repeat(64)}",
                    ),
                ),
        ),
    )

    @Test
    fun `local library snapshots original persists and deduplicates by bytes and model`() =
        runBlocking {
            val store = store()
            val library = SkinLibrary(store)
            val original = png()
            val bytes = original.readBytes()
            val first = library.importSkin(original, "中文皮肤", SkinVariant.Classic)
            val duplicate = library.importSkin(original, "Other name", SkinVariant.Classic)
            assertEquals(first, duplicate)
            val slim = library.importSkin(original, "Slim", SkinVariant.Slim)
            assertNotEquals(first.id, slim.id)
            assertEquals(2, SkinLibrary(store).list().size)
            original.deleteExisting()
            assertContentEquals(bytes, library.texture(first.id).readBytes())
            assertTrue(store.minecraftRoot.notExists())
            assertTrue(
                store.dataDirectory
                    .resolve("skins")
                    .listDirectoryEntries("*.tmp")
                    .isEmpty(),
            )
        }

    @Test
    fun `previews accept legacy and HD skins while upload validates standard skins`(): Unit =
        runBlocking {
            val library = SkinLibrary(store())
            val legacy = png("legacy.png", height = 32)
            val hd = png("hd.png", 128, 128)
            assertNotNull(library.importSkin(legacy, "legacy", SkinVariant.Classic))
            assertNotNull(library.importSkin(hd, "hd", SkinVariant.Slim))
            validateUploadSkin(legacy, SkinVariant.Classic)
            assertFailsWith<IllegalArgumentException> {
                validateUploadSkin(legacy, SkinVariant.Slim)
            }
            assertFailsWith<IllegalArgumentException> {
                validateUploadSkin(hd, SkinVariant.Classic)
            }
        }

    @Test
    fun `invalid names and damaged textures leave original output unchanged`() =
        runBlocking {
            val store = store()
            val library = SkinLibrary(store)
            val invalid = temporary.resolve("fake.png").apply { writeText("not an image") }
            assertFailsWith<IllegalArgumentException> {
                library.importSkin(invalid, "invalid", SkinVariant.Classic)
            }
            assertFailsWith<IllegalArgumentException> {
                library.importSkin(png(), " ", SkinVariant.Classic)
            }
            assertTrue(library.list().isEmpty())
            val entry = library.importSkin(png(), "skin", SkinVariant.Classic)
            val managed = library.texture(entry.id)
            managed.writeText("corrupt")
            val destination = temporary.resolve("export.png").apply { writeText("keep") }
            assertFailsWith<IllegalArgumentException> { library.exportSkin(entry.id, destination) }
            assertEquals("keep", destination.readText())
        }

    @Test
    fun `export and removal use owned files and preserve imported source`() =
        runBlocking {
            val store = store()
            val library = SkinLibrary(store)
            val source = png()
            val entry = library.importSkin(source, "skin", SkinVariant.Classic)
            val managed = library.texture(entry.id)
            val target = temporary.resolve("output/skin.png")
            library.exportSkin(entry.id, target)
            assertContentEquals(source.readBytes(), target.readBytes())
            assertFailsWith<IllegalArgumentException> { library.exportSkin(entry.id, managed) }
            library.removeSkin(entry.id)
            assertTrue(library.list().isEmpty())
            assertFalse(managed.exists())
            assertTrue(source.exists())
            assertTrue(target.exists())
        }

    @Test
    fun `offline preview changes metadata only and cannot be deleted while referenced`() =
        runBlocking {
            val store = store()
            val offline = Account(name = "Offline", uuid = offlineUuid("Offline"))
            store.mutate { it.copy(accounts = listOf(offline), activeAccountId = offline.id) }
            NetworkClient().use { network ->
                val service =
                    SkinService(store, network, { error("Offline preview must not authenticate") })
                val entry = service.library.importSkin(png(), "local", SkinVariant.Slim)
                service.useOfflinePreview(offline.id, entry.id)
                val updated = store.data.accounts.single()
                assertEquals(entry.id, updated.localSkinId)
                assertEquals(SkinVariant.Slim, updated.skinVariant)
                assertNotNull(service.texture(updated))
                assertFailsWith<IllegalArgumentException> { service.library.removeSkin(entry.id) }
                val exported = temporary.resolve("offline-export.png")
                service.exportCurrent(offline.id, exported)
                assertTrue(validSkinTexture(exported))
                service.useOfflinePreview(offline.id, null)
                assertNull(service.texture(store.data.accounts.single()))
                service.library.removeSkin(entry.id)
                assertTrue(store.minecraftRoot.notExists())
            }
        }

    @Test
    fun `corrupt library identifiers cannot resolve or remove files outside managed storage`() =
        runBlocking {
            val store = store()
            val sentinel = temporary.resolve("sentinel.png").apply { writeText("keep") }
            writeJson(
                store.dataDirectory.resolve("skins/library.json"),
                mapOf(
                    "formatVersion" to 1,
                    "skins" to
                        listOf(
                            mapOf(
                                "id" to "../../../sentinel",
                                "name" to "bad",
                                "variant" to "Classic",
                                "sha256" to "a".repeat(64),
                            ),
                        ),
                ),
            )
            val library = SkinLibrary(store)
            assertFailsWith<IllegalArgumentException> { library.list() }
            assertFailsWith<IllegalArgumentException> { library.removeSkin("../../../sentinel") }
            assertEquals("keep", sentinel.readText())
        }

    @Test
    fun `upload targets selected account uses multipart and updates normalized profile`() =
        runBlocking {
            val store = store()
            val target = account()
            val other = Account(name = "Other", type = AccountType.Microsoft)
            store.mutate { it.copy(accounts = listOf(target, other), activeAccountId = other.id) }
            MockWebServer().use { server ->
                NetworkClient().use { network ->
                    var credentialId = ""
                    val service =
                        SkinService(
                            store,
                            network,
                            { id ->
                                credentialId = id
                                GameCredentials(target.name, target.uuid, "fixture-token", "msa")
                            },
                            server.loopbackUrl("").toString().trimEnd('/'),
                        )
                    val entry = service.library.importSkin(png(), "upload", SkinVariant.Slim)
                    server.enqueue(MockResponse().setBody("{}"))
                    server.enqueue(MockResponse().setBody(response(target)))
                    val profile = service.upload(target.id, entry.id)
                    assertUploadedProfile(store, target, other, profile, credentialId)
                    assertMultipartUpload(server)
                }
            }
        }

    /** 上传后本地档案只更新目标账户：其它账户与当前选中账户保持不变。 */
    private fun assertUploadedProfile(
        store: LauncherStore,
        target: Account,
        other: Account,
        profile: MinecraftSkinProfile,
        credentialId: String,
    ) {
        assertEquals(target.id, credentialId)
        assertEquals(SkinVariant.Slim, profile.variant)
        assertTrue(profile.textureUrl.startsWith("https://textures.minecraft.net/"))
        assertEquals(other.id, store.data.activeAccountId)
        assertEquals(
            "UpdatedName",
            store.data.accounts
                .first { it.id == target.id }
                .name,
        )
        assertEquals(other, store.data.accounts.first { it.id == other.id })
    }

    /** 请求体是 multipart：先上传皮肤，再读取一次档案。 */
    private fun assertMultipartUpload(server: MockWebServer) {
        val upload = server.takeRequest()
        assertEquals("POST", upload.method)
        assertEquals("/minecraft/profile/skins", upload.path)
        assertEquals("Bearer fixture-token", upload.getHeader("Authorization"))
        val body = upload.body.readByteArray()
        val text = body.toString(Charsets.ISO_8859_1)
        assertContains(text, "name=\"variant\"")
        assertContains(text, "slim")
        assertContains(text, "filename=\"skin.png\"")
        assertContains(text, "Content-Type: image/png")
        assertTrue(body.size > png().fileSize())
        assertEquals("GET", server.takeRequest().method)
    }

    @Test
    fun `unsupported upload input and mismatched credentials fail before any remote write`() =
        runBlocking {
            val store = store()
            val account = account()
            store.mutate { it.copy(accounts = listOf(account)) }
            MockWebServer().use { server ->
                NetworkClient().use { network ->
                    var credentialsRead = 0
                    val service =
                        SkinService(
                            store,
                            network,
                            {
                                credentialsRead++
                                GameCredentials("wrong", "c".repeat(32), "token")
                            },
                            server.loopbackUrl("").toString().trimEnd('/'),
                        )
                    val hd =
                        service.library.importSkin(
                            png("hd.png", 128, 128),
                            "hd",
                            SkinVariant.Classic,
                        )
                    assertFailsWith<IllegalArgumentException> { service.upload(account.id, hd.id) }
                    assertEquals(0, credentialsRead)
                    val standard =
                        service.library.importSkin(png(), "standard", SkinVariant.Classic)
                    assertFailsWith<IllegalArgumentException> {
                        service.upload(account.id, standard.id)
                    }
                    assertEquals(0, server.requestCount)
                }
            }
        }

    @Test
    fun `upload denial does not expose server body or mutate local profile`() =
        runBlocking {
            val store = store()
            val account = account()
            store.mutate { it.copy(accounts = listOf(account)) }
            MockWebServer().use { server ->
                NetworkClient().use { network ->
                    val service =
                        SkinService(
                            store,
                            network,
                            { GameCredentials(account.name, account.uuid, "secret-token") },
                            server.loopbackUrl("").toString().trimEnd('/'),
                        )
                    val entry = service.library.importSkin(png(), "skin", SkinVariant.Classic)
                    server.enqueue(MockResponse().setResponseCode(403).setBody("secret-response"))
                    val error =
                        assertFailsWith<ServiceHttpException> {
                            service.upload(
                                account.id,
                                entry.id,
                            )
                        }
                    assertEquals(403, error.statusCode)
                    assertFalse(error.message.orEmpty().contains("secret-response"))
                    assertFalse(error.message.orEmpty().contains("secret-token"))
                    assertEquals(account, store.data.accounts.single())
                }
            }
        }

    @Test
    fun `upload redirects are refused and successful writes report failed refresh separately`() =
        runBlocking {
            val store = store()
            val account = account()
            store.mutate { it.copy(accounts = listOf(account)) }
            MockWebServer().use { server ->
                MockWebServer().use { other ->
                    NetworkClient().use { network ->
                        val service =
                            SkinService(
                                store,
                                network,
                                { GameCredentials(account.name, account.uuid, "token") },
                                server.loopbackUrl("").toString().trimEnd('/'),
                            )
                        val entry = service.library.importSkin(png(), "skin", SkinVariant.Classic)
                        server.enqueue(
                            MockResponse()
                                .setResponseCode(307)
                                .setHeader(
                                    "Location",
                                    other.loopbackUrl("/minecraft/profile/skins"),
                                ),
                        )
                        assertFailsWith<ServiceHttpException> {
                            service.upload(account.id, entry.id)
                        }
                        assertEquals(0, other.requestCount)
                        server.enqueue(MockResponse().setBody("{}"))
                        server.enqueue(MockResponse().setResponseCode(500))
                        val result = service.upload(account.id, entry.id)
                        assertEquals("", result.textureUrl)
                        assertTrue(
                            store.state.value.logs
                                .any { it.message.contains("皮肤已上传，但档案刷新失败") },
                        )
                    }
                }
            }
        }

    @Test
    fun `profile refresh cannot overwrite another role and remote upload origin is constrained`() =
        runBlocking {
            val store = store()
            val account = account()
            store.mutate { it.copy(accounts = listOf(account)) }
            MockWebServer().use { server ->
                NetworkClient().use { network ->
                    val service =
                        SkinService(
                            store,
                            network,
                            { GameCredentials(account.name, account.uuid, "token") },
                            server.loopbackUrl("").toString().trimEnd('/'),
                        )
                    server.enqueue(
                        MockResponse().setBody(response(account.copy(uuid = "c".repeat(32)))),
                    )
                    assertFailsWith<IllegalArgumentException> { service.refreshProfile(account.id) }
                    assertEquals(account, store.data.accounts.single())
                    for (
                    url in
                    listOf(
                        "https://example.com/minecraft/profile/skins",
                        "https://api.minecraftservices.com.evil/minecraft/profile/skins",
                        "https://api.minecraftservices.com/minecraft/profile/skins?x=1",
                    )
                    ) {
                        assertFailsWith<IllegalArgumentException> {
                            network.uploadMinecraftSkin(url, "token", "classic", byteArrayOf(1))
                        }
                    }
                }
            }
        }
}
