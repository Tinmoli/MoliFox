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
    fun `installs from a local zip after verification`() =
        runBlocking {
            withWork { work ->
                val archive = archive(includeCli = true)
                val zip = work.resolve("local-easytier.zip")
                Files.write(zip, archive)
                val installer = installer(work, FakeDownloader(ByteArray(0)), packageFor(archive))
                val status = installer.installFromArchive(zip).getOrThrow()
                assertTrue(status.installed)
                assertEquals("core", Files.readString(installer.executable()))
            }
        }

    @Test
    fun `rejects local zip with wrong checksum`() =
        runBlocking {
            withWork { work ->
                val archive = archive(includeCli = true)
                val zip = work.resolve("broken-easytier.zip")
                Files.write(zip, archive)
                val installer =
                    installer(
                        work,
                        FakeDownloader(ByteArray(0)),
                        packageFor(archive, digest = "0".repeat(64)),
                    )
                val failure = installer.installFromArchive(zip).exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains("校验失败"), "实际：${failure?.message}")
                assertFalse(Files.exists(work.resolve("easytier")))
            }
        }

    @Test
    fun `reports missing local zip`() =
        runBlocking {
            withWork { work ->
                val archive = archive(includeCli = true)
                val installer = installer(work, FakeDownloader(ByteArray(0)), packageFor(archive))
                val failure =
                    installer
                        .installFromArchive(
                            work.resolve("absent.zip"),
                        ).exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains("找不到安装包"), "实际：${failure?.message}")
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
    fun `downloads from the fastest measured source`() =
        runBlocking {
            withWork { work ->
                val archive = archive(includeCli = true)
                val pkg =
                    packageFor(
                        archive,
                        mirrors =
                            listOf(
                                "https://fast.invalid/easytier.zip",
                                "https://slow.invalid/easytier.zip",
                            ),
                    )
                val downloader = RoutingDownloader(archive)
                val probe =
                    FakeProbe(
                        mapOf(
                            pkg.url to 10L,
                            "https://fast.invalid/easytier.zip" to 99L,
                            "https://slow.invalid/easytier.zip" to 1L,
                        ),
                    )
                installer(work, downloader, pkg, probe).install { }.getOrThrow()
                assertEquals("https://fast.invalid/easytier.zip", downloader.urls.first())
                assertEquals(1, downloader.urls.size)
            }
        }

    @Test
    fun `falls back to next source when the fastest fails`() =
        runBlocking {
            withWork { work ->
                val archive = archive(includeCli = true)
                val pkg =
                    packageFor(
                        archive,
                        mirrors =
                            listOf(
                                "https://fast.invalid/easytier.zip",
                                "https://slow.invalid/easytier.zip",
                            ),
                    )
                val downloader =
                    RoutingDownloader(
                        archive,
                        failureUrls = setOf("https://fast.invalid/easytier.zip"),
                    )
                val probe =
                    FakeProbe(
                        mapOf(
                            pkg.url to 10L,
                            "https://fast.invalid/easytier.zip" to 99L,
                            "https://slow.invalid/easytier.zip" to 1L,
                        ),
                    )
                installer(work, downloader, pkg, probe).install { }.getOrThrow()
                assertEquals(
                    listOf("https://fast.invalid/easytier.zip", pkg.url),
                    downloader.urls,
                )
            }
        }

    @Test
    fun `falls back to next source when the first archive fails checksum`() =
        runBlocking {
            withWork { work ->
                val archive = archive(includeCli = true)
                val pkg =
                    packageFor(
                        archive,
                        mirrors = listOf("https://fast.invalid/easytier.zip"),
                    )
                val downloader =
                    RoutingDownloader(
                        archive,
                        badBodyUrls = setOf("https://fast.invalid/easytier.zip"),
                    )
                val probe =
                    FakeProbe(
                        mapOf(
                            pkg.url to 1L,
                            "https://fast.invalid/easytier.zip" to 99L,
                        ),
                    )
                installer(work, downloader, pkg, probe).install { }.getOrThrow()
                assertEquals(
                    listOf("https://fast.invalid/easytier.zip", pkg.url),
                    downloader.urls,
                )
            }
        }

    @Test
    fun `speed test runs once and ranking is reused`() =
        runBlocking {
            withWork { work ->
                val archive = archive(includeCli = true)
                val fastSource = "https://fast.invalid/easytier.zip"
                val pkg = packageFor(archive, mirrors = listOf(fastSource))
                val probe = CountingProbe(mapOf(fastSource to 99L, pkg.url to 1L))
                val downloader = FlakyDownloader(archive, failuresBeforeSuccess = 3)
                val installer = installer(work, downloader, pkg, probe)

                assertTrue(installer.install { }.isFailure)
                assertEquals(2, probe.calls)
                installer.install { }.getOrThrow()
                assertEquals(2, probe.calls)
            }
        }

    @Test
    fun `keeps official source first when probing finds nothing`() =
        runBlocking {
            withWork { work ->
                val archive = archive(includeCli = true)
                val pkg =
                    packageFor(archive, mirrors = listOf("https://mirror.invalid/easytier.zip"))
                val downloader = RoutingDownloader(archive)
                installer(work, downloader, pkg, FakeProbe(emptyMap())).install { }.getOrThrow()
                assertEquals(pkg.url, downloader.urls.first())
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
        probe: EasyTierSourceProbe = FakeProbe(emptyMap()),
    ): EasyTierInstaller =
        EasyTierInstaller(
            runtimesRoot = work,
            packageProvider = { pkg },
            downloader = downloader,
            probe = probe,
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
        mirrors: List<String> = emptyList(),
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
            mirrors = mirrors,
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

/** 按 URL 返回固定测速结果；未列出的 URL 视为不可用。 */
private class FakeProbe(
    private val speeds: Map<String, Long?>,
) : EasyTierSourceProbe {
    override suspend fun measureSpeed(url: String): Long? = speeds[url]
}

/** 记录下载顺序，并可按 URL 模拟网络失败或错误内容。 */
private class RoutingDownloader(
    private val defaultBody: ByteArray,
    private val failureUrls: Set<String> = emptySet(),
    private val badBodyUrls: Set<String> = emptySet(),
) : EasyTierArchiveDownloader {
    val urls = mutableListOf<String>()

    override suspend fun download(
        url: String,
        target: Path,
        expectedBytes: Long,
        onProgress: (Float?) -> Unit,
    ) {
        urls += url
        if (url in failureUrls) throw IOException("mirror down")
        val body = if (url in badBodyUrls) ByteArray(4) { 1 } else defaultBody
        onProgress(0.5f)
        Files.write(target, body)
    }
}

/** 统计测速次数，用于验证排序只测一次。 */
private class CountingProbe(
    private val speeds: Map<String, Long?>,
) : EasyTierSourceProbe {
    var calls = 0
        private set

    override suspend fun measureSpeed(url: String): Long? {
        calls++
        return speeds[url]
    }
}

/** 前 N 次下载失败，之后成功，用于验证失败换源与排序缓存。 */
private class FlakyDownloader(
    private val body: ByteArray,
    private var failuresBeforeSuccess: Int,
) : EasyTierArchiveDownloader {
    override suspend fun download(
        url: String,
        target: Path,
        expectedBytes: Long,
        onProgress: (Float?) -> Unit,
    ) {
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess--
            throw IOException("simulated failure")
        }
        onProgress(0.5f)
        Files.write(target, body)
    }
}
