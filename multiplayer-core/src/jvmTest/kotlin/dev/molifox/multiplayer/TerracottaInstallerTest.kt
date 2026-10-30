// 校验联机核心安装器：SHA-512 校验、断点重试与不完整安装的清理。
package dev.molifox.multiplayer

import com.sun.net.httpserver.HttpServer
import dev.molifox.multiplayer.data.TerracottaInstaller
import dev.molifox.multiplayer.data.TerracottaPackage
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerracottaInstallerTest {
    @Test
    fun `installs verified package and skips second download`() =
        runBlocking {
            val work = Files.createTempDirectory("terra-install-")
            val content = "core-binary".toByteArray()
            val archive = TarTestSupport.archive(listOf(TarEntry("core.bin", content)))
            FakePackageServer(archive).use { server ->
                val installer = installer(work, server.url, content, archive)
                val status = installer.install { }.getOrThrow()
                assertTrue(status.installed)
                val installed = work.resolve("terracotta/0.0.test/test-x86_64/core.bin")
                assertContentEquals(content, Files.readAllBytes(installed))
                installer.install { }.getOrThrow()
                assertEquals(1, server.requests)
            }
            work.toFile().deleteRecursively()
        }

    @Test
    fun `rejects package with wrong checksum and leaves no install`() =
        runBlocking {
            val work = Files.createTempDirectory("terra-install-")
            val content = "core-binary".toByteArray()
            val archive = TarTestSupport.archive(listOf(TarEntry("core.bin", content)))
            FakePackageServer(archive).use { server ->
                val installer =
                    TerracottaInstaller(
                        runtimesRoot = work,
                        packageProvider = {
                            TerracottaPackage(
                                version = "0.0.test",
                                classifier = "test-x86_64",
                                archiveSha512 = "0".repeat(128),
                                downloadBytes = archive.size.toLong(),
                                files = mapOf("core.bin" to sha512(content)),
                            )
                        },
                        urlProvider = { listOf(server.url) },
                    )
                val failure = installer.install { }.exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains("校验失败"))
                val target = work.resolve("terracotta")
                assertFalse(Files.exists(target))
            }
            work.toFile().deleteRecursively()
        }

    private fun installer(
        work: java.nio.file.Path,
        url: String,
        content: ByteArray,
        archive: ByteArray,
    ): TerracottaInstaller =
        TerracottaInstaller(
            runtimesRoot = work,
            packageProvider = {
                TerracottaPackage(
                    version = "0.0.test",
                    classifier = "test-x86_64",
                    archiveSha512 = sha512(archive),
                    downloadBytes = archive.size.toLong(),
                    files = mapOf("core.bin" to sha512(content)),
                )
            },
            urlProvider = { listOf(url) },
        )

    private fun sha512(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-512").digest(bytes).joinToString("") { "%02x".format(it) }
}

private class FakePackageServer(
    private val body: ByteArray,
) : AutoCloseable {
    @Volatile
    var requests: Int = 0

    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/pkg.tar.gz") { exchange ->
                requests++
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

    val url: String get() = "http://127.0.0.1:${server.address.port}/pkg.tar.gz"

    override fun close() = server.stop(0)
}
