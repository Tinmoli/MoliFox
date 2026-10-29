// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证离线皮肤落盘、模组目录边界与校验规则。

package dev.molifox.core

import java.awt.image.BufferedImage
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.imageio.ImageIO
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class OfflineSkinTest {
    @TempDir lateinit var temporary: Path

    private fun png(name: String = "skin.png", width: Int = 64, height: Int = 64): Path =
        temporary.resolve(name).apply {
            ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", toFile())
        }

    private fun store(): LauncherStore =
        LauncherStore(DesktopPlatform(), temporary.resolve("launcher"))

    private fun offlineAccount(name: String = "OfflineUser") =
        Account(name = name, uuid = offlineUuid(name), type = AccountType.Offline)

    private fun service(store: LauncherStore, network: NetworkClient) =
        SkinService(store, network, { error("离线皮肤不得触发账户认证") })

    private fun selectSharedInstance(store: LauncherStore, game: Path) {
        store.mutate {
            it.copy(settings = it.settings.copy(gameRoot = game.toString(), isolateVersions = false))
        }
        val instance =
            GameInstance(
                id = "offline-instance",
                name = "Offline",
                gameVersion = "1.20.1",
                versionId = "offline",
                gameDirectoryName = "offline",
                installed = true,
                isolation = VersionIsolation.Disabled,
            )
        store.updateState {
            it.copy(
                data =
                    it.data.copy(
                        instances = listOf(instance),
                        selectedInstanceId = instance.id,
                    )
            )
        }
    }

    @Test
    fun `offline skin persists variant and fills an existing custom skin loader folder`() =
        runBlocking {
            val store = store()
            val game = temporary.resolve("game").createDirectories()
            val modDirectory =
                game.resolve("CustomSkinLoader/LocalSkin/skins").createDirectories()
            selectSharedInstance(store, game)
            val account = offlineAccount()
            store.mutate { it.copy(accounts = listOf(account), activeAccountId = account.id) }
            val source = png(height = 64)
            NetworkClient().use { network ->
                val service = service(store, network)
                val entry = service.library.importSkin(source, "slim", SkinVariant.Slim)
                val placement = service.useOfflinePreview(account.id, entry.id)
                assertNotNull(placement)
                assertEquals(service.library.directory.resolve("${entry.id}.png"), placement.library)
                assertEquals(modDirectory.resolve("OfflineUser.png"), placement.customSkinLoader)
                assertContentEquals(source.readBytes(), placement.customSkinLoader!!.readBytes())
                val updated = store.data.accounts.single()
                assertEquals(entry.id, updated.localSkinId)
                assertEquals(SkinVariant.Slim, updated.skinVariant)
                // 变体随 launcher.json 持久化，重启后仍一致。
                val reloaded =
                    LauncherStore(DesktopPlatform(), store.dataDirectory).data.accounts.single()
                assertEquals(entry.id, reloaded.localSkinId)
                assertEquals(SkinVariant.Slim, reloaded.skinVariant)
            }
        }

    @Test
    fun `mod folders are never created when custom skin loader is absent`() = runBlocking {
        val store = store()
        val game = temporary.resolve("game").createDirectories()
        // 只有外层 CustomSkinLoader、没有 LocalSkin/skins 时也不能替用户补目录。
        game.resolve("CustomSkinLoader/LocalSkin").createDirectories()
        selectSharedInstance(store, game)
        val account = offlineAccount()
        store.mutate { it.copy(accounts = listOf(account), activeAccountId = account.id) }
        NetworkClient().use { network ->
            val service = service(store, network)
            val entry = service.library.importSkin(png(), "classic", SkinVariant.Classic)
            val placement = service.useOfflinePreview(account.id, entry.id)
            assertNotNull(placement)
            assertNull(placement.customSkinLoader)
            assertTrue(game.resolve("CustomSkinLoader/LocalSkin/skins").notExists())
            assertTrue(service.library.directory.resolve("${entry.id}.png").exists())
        }
    }

    @Test
    fun `no selected instance means no mod directory lookup`() = runBlocking {
        val store = store()
        val account = offlineAccount()
        store.mutate { it.copy(accounts = listOf(account), activeAccountId = account.id) }
        NetworkClient().use { network ->
            val service = service(store, network)
            assertNull(service.customSkinLoaderDirectory())
            val entry = service.library.importSkin(png(), "classic", SkinVariant.Classic)
            assertNull(service.useOfflinePreview(account.id, entry.id)?.customSkinLoader)
        }
    }

    @Test
    fun `local mod skin accepts 64x64 and 64x32 but rejects other sizes and oversized files`() {
        val standard = png("standard.png", 64, 64)
        val legacy = png("legacy.png", 64, 32)
        validateLocalSkinFile(standard, SkinVariant.Slim)
        validateLocalSkinFile(legacy, SkinVariant.Classic)
        assertFailsWith<IllegalArgumentException> {
            validateLocalSkinFile(legacy, SkinVariant.Slim)
        }
        assertFailsWith<IllegalArgumentException> {
            validateLocalSkinFile(png("hd.png", 128, 128), SkinVariant.Classic)
        }
        assertFailsWith<IllegalArgumentException> {
            validateLocalSkinFile(png("wrong.png", 63, 64), SkinVariant.Classic)
        }
        assertFailsWith<IllegalArgumentException> {
            validateLocalSkinFile(png("ratio.png", 64, 48), SkinVariant.Classic)
        }
        val oversized =
            png("oversized.png", 64, 64).apply {
                java.nio.file.Files.write(this, ByteArray(1024 * 1024), StandardOpenOption.APPEND)
            }
        assertFailsWith<IllegalArgumentException> {
            validateLocalSkinFile(oversized, SkinVariant.Classic)
        }
    }

    @Test
    fun `offline account never reports an uploaded skin`() = runBlocking {
        val store = store()
        val account = offlineAccount()
        store.mutate { it.copy(accounts = listOf(account), activeAccountId = account.id) }
        NetworkClient().use { network ->
            val service = service(store, network)
            val entry = service.library.importSkin(png(), "local", SkinVariant.Classic)
            service.useOfflinePreview(account.id, entry.id)
            val saved = store.data.accounts.single()
            assertEquals("", saved.skinUrl)
            assertEquals("", saved.authlibServer)
            assertNotNull(service.texture(saved))
            val failure = assertFailsWith<IllegalArgumentException> { service.upload(saved.id, entry.id) }
            assertTrue(failure.message.orEmpty().contains("微软账户"))
        }
    }
}