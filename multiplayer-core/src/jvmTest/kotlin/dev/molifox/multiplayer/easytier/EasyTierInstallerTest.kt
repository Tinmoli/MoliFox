// 校验 EasyTier 安装器：SHA-256 校验、失败清理、已有安装复用与重试。
package dev.molifox.multiplayer.easytier

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EasyTierInstallerTest {
    @Test
    fun `installs verified archive and reuses existing install`() =
        runBlocking {
            withWork { work ->
                val archive = archive(includeCli = true)
                val downloader = FakeDownloader(archive)
                val installer = installer(work, downloader, packageFor(archive))
                assertFalse(installer.inspect().installed)
                val progress = mutableListOf<Float?>()
                val status = installer.install { progress += it }.getOrThrow()
                assertTrue(status.installed)
                assertEquals("0.0.test", status.version)
                assertEquals("test-x86_64", status.platform)
                assertEquals(
                    work.resolve("easytier/0.0.test/test-x86_64").toString(),
                    status.directory,
                )
                assertEquals("core", Files.readString(installer.executable()))
                assertEquals("cli", Files.readString(installer.cliExecutable()))
                assertEquals(1f, progress.last())
                assertEquals(setOf("easytier"), children(work))
                installer.install { }.getOrThrow()
                assertEquals(1, downloader.calls)
            }
        }

    @Test
    fun `rejects wrong checksum and leaves no install`() =
        runBlocking {
            withWork { work ->
                val archive = archive(includeCli = true)
                val downloader = FakeDownloader(archive)
                val broken = packageFor(archive, digest = "0".repeat(64))
                val failure = installer(work, downloader, broken).install { }.exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains("校验失败"), "实际：${failure?.message}")
                assertEquals(3, downloader.calls)
                assertFalse(Files.exists(work.resolve("easytier")))
                assertEquals(emptySet(), children(work))
            }
        }

    @Test
    fun `rejects archive missing required files`() =
        runBlocking {
            withWork { work ->
                val archive = archive(includeCli = false)
                val downloader = FakeDownloader(archive)
                val failure =
                    installer(
                        work,
                        downloader,
                        packageFor(archive),
                    ).install { }.exceptionOrNull()
                assertTrue(
                    failure?.message.orEmpty().contains("缺少必需文件"),
                    "实际：${failure?.message}",
                )
                assertEquals(1, downloader.calls)
                assertFalse(Files.exists(work.resolve("easytier")))
                assertEquals(emptySet(), children(work))
            }
        }

    @Test
    fun `retries download and reports network failure`() =
        runBlocking {
            withWork { work ->
                val downloader = FakeDownloader(ByteArray(0), IOException("boom"))
                val failure =
                    installer(
                        work,
                        downloader,
                        packageFor(ByteArray(0)),
                    ).install { }.exceptionOrNull()
                assertTrue(
                    failure?.message.orEmpty().contains("下载 EasyTier 失败"),
                    "实际：${failure?.message}",
                )
                assertEquals(3, downloader.calls)
                assertEquals(emptySet(), children(work))
            }
        }

    @Test
    fun `reports unsupported platform`() =
        runBlocking {
            withWork { work ->
                val installer =
                    EasyTierInstaller(
                        runtimesRoot = work,
                        packageProvider = { null },
                        downloader = FakeDownloader(ByteArray(0)),
                    )
                val failure = installer.install { }.exceptionOrNull()
                assertTrue(
                    failure?.message.orEmpty().contains("暂不支持"),
                    "实际：${failure?.message}",
                )
                val thrown = assertFailsWith<IllegalStateException> { installer.packageInfo() }
                assertTrue(thrown.message.orEmpty().contains("暂不支持"))
            }
        }

    private fun installer(
        work: Path,
        downloader: EasyTierArchiveDownloader,
        pkg: EasyTierPackage,
    ): EasyTierInstaller =
        EasyTierInstaller(
            runtimesRoot = work,
            packageProvider = { pkg },
            downloader = downloader,
        )

    private fun archive(includeCli: Boolean): ByteArray =
        ZipTestSupport.zip(
            buildList {
                add(ZipTestEntry("easytier-test/core.bin", "core".toByteArray()))
                if (includeCli) add(ZipTestEntry("easytier-test/cli.bin", "cli".toByteArray()))
            },
        )

    private fun packageFor(
        archive: ByteArray,
        digest: String = sha256(archive),
    ): EasyTierPackage =
        EasyTierPackage(
            version = "0.0.test",
            classifier = "test-x86_64",
            url = "https://example.invalid/easytier.zip",
            sha256 = digest,
            downloadBytes = archive.size.toLong(),
            rootPrefix = "easytier-test",
            coreFile = "core.bin",
            cliFile = "cli.bin",
        )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun children(directory: Path): Set<String> =
        Files
            .list(directory)
            .use { stream ->
                stream.map { it.fileName.toString() }.toList()
            }.toSet()

    private suspend fun withWork(block: suspend (Path) -> Unit) {
        val work = Files.createTempDirectory("easytier-install-")
        try {
            block(work)
        } finally {
            work.toFile().deleteRecursively()
        }
    }
}

private class FakeDownloader(
    private val body: ByteArray = ByteArray(0),
    private val failure: IOException? = null,
) : EasyTierArchiveDownloader {
    var calls: Int = 0
        private set

    override suspend fun download(
        url: String,
        target: Path,
        expectedBytes: Long,
        onProgress: (Float?) -> Unit,
    ) {
        calls++
        onProgress(PROGRESS_MIDDLE)
        failure?.let { throw it }
        Files.write(target, body)
    }

    private companion object {
        const val PROGRESS_MIDDLE = 0.25f
    }
}
