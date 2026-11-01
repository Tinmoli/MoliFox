// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val CLEANUP_SKIN_SIZE = 64

class AccountSkinCleanupTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `skin cleanup failures preserve success fallback and cancellation`(): Unit = runBlocking {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("launcher"))
        val account =
            Account(
                type = AccountType.Microsoft,
                skinUrl = "https://textures.minecraft.net/texture/${"a".repeat(64)}",
            )
        NetworkClient().use { network ->
            var bytes = ByteArrayOutputStream().use { output ->
                val image = BufferedImage(CLEANUP_SKIN_SIZE, CLEANUP_SKIN_SIZE, BufferedImage.TYPE_INT_ARGB)
                ImageIO.write(image, "png", output)
                output.toByteArray()
            }
            var cancel = false
            val cache =
                AccountSkinCache(store, network, removeTemporary = {
                    if (cancel) throw CancellationException("cancel during cleanup")
                    throw IOException("fixture locked file")
                }) { bytes }
            val original = requireNotNull(cache.load(account, false))
            assertTrue(validSkinTexture(original))
            bytes = byteArrayOf(1, 2, 3)
            assertEquals(original, cache.load(account, true))
            assertTrue(validSkinTexture(original))
            cancel = true
            assertFailsWith<CancellationException> { cache.load(account, true) }
        }
    }
}
