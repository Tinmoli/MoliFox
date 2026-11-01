// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证安装进度单调、覆盖每个阶段，并精确到达 100%。

package dev.molifox.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstallProgressTest {
    @TempDir lateinit var directory: Path

    private class Recorder {
        val values = mutableListOf<Float>()
        val details = mutableListOf<String>()

        fun record(
            value: Float,
            detail: String,
        ) {
            values += value
            details += detail
        }

        fun keepMonotone() {
            var previous = -1f
            values.forEach { value ->
                assertTrue(value >= previous, "进度回退：$value 出现在 $previous 之后；全部=$values")
                assertTrue(value in 0f..1f, "进度越界：$value")
                previous = value
            }
        }

        fun valueDuring(phase: String) = values.zip(details).filter { it.second.startsWith(phase) }.map { it.first }
    }

    private fun context(recorder: Recorder): JobContext {
        val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
        val context = JobContext(store, "test")
        context.progressObserver = recorder::record
        return context
    }

    private fun artifact(
        name: String,
        size: Long = 0,
    ) = DownloadArtifact(
        "https://example.invalid/$name",
        directory.resolve("files/$name"),
        size = size,
    )

    @Test
    fun `progress never regresses covers each phase and ends exactly at one`() {
        val recorder = Recorder()
        val progress = InstallProgress(context(recorder), 0f, 1f, emitIntervalMillis = 0)
        val clientJar = artifact("client.jar", 8L * 1024 * 1024)
        val libraryJar = artifact("library.jar", 4096)
        val assets = (1..5000).map { artifact("asset-$it") }

        planAllPhases(progress, clientJar, libraryJar, assets)
        verifyAllFiles(progress, clientJar, libraryJar, assets)
        downloadLibrary(progress, clientJar)

        // 还有整批资源没下：此时绝不能已经显示 100%。
        val beforeAssets = recorder.values.last()
        assertTrue(beforeAssets < .99f, "库下完、资源还没开始时不能到 100%，实际 $beforeAssets")

        downloadAllAssets(progress, assets)
        progress.finish()
        assertProgressSummary(recorder)
    }

    /** 和真实安装一致：所有阶段的工作量在开始前一次登记，分母固定。 */
    private fun planAllPhases(
        progress: InstallProgress,
        clientJar: DownloadArtifact,
        libraryJar: DownloadArtifact,
        assets: List<DownloadArtifact>,
    ) {
        progress.plan(
            InstallPhase.VERIFY,
            2 + assets.size,
            clientJar.size + libraryJar.size,
            PROGRESS_VERIFY_BYTES_PER_UNIT,
        )
        progress.plan(InstallPhase.LIBRARIES, 2, clientJar.size + libraryJar.size)
        progress.plan(InstallPhase.ASSETS, assets.size, 0)
    }

    /** 校验阶段：每个文件都记满「校验文件」的份额；已存在的库释放下载份额（客户端 JAR 缺失）。 */
    private fun verifyAllFiles(
        progress: InstallProgress,
        clientJar: DownloadArtifact,
        libraryJar: DownloadArtifact,
        assets: List<DownloadArtifact>,
    ) {
        progress.begin(InstallPhase.VERIFY)
        progress.verified(InstallPhase.VERIFY, clientJar)
        progress.verified(InstallPhase.VERIFY, libraryJar)
        assets.forEach { progress.verified(InstallPhase.VERIFY, it) }
        progress.release(InstallPhase.LIBRARIES, 1, libraryJar.size)
        progress.release(InstallPhase.ASSETS, 0, 0)
    }

    /** 下载库：大文件按字节推进。 */
    private fun downloadLibrary(
        progress: InstallProgress,
        clientJar: DownloadArtifact,
    ) {
        progress.begin(InstallPhase.LIBRARIES)
        progress.started("client.jar", clientJar)
        var done = 0L
        while (done <= clientJar.size) {
            progress.bytes("client.jar", done, clientJar.size)
            done += 256 * 1024
        }
        progress.finished("client.jar", clientJar)
    }

    /** 下载资源：5000 个文件，每个完成都应推进。 */
    private fun downloadAllAssets(
        progress: InstallProgress,
        assets: List<DownloadArtifact>,
    ) {
        progress.begin(InstallPhase.ASSETS)
        assets.forEach { asset ->
            progress.started(asset.target.toString(), asset)
            progress.finished(asset.target.toString(), asset)
        }
    }

    /** 单调性、终值与三种阶段标签都应满足。 */
    private fun assertProgressSummary(recorder: Recorder) {
        recorder.keepMonotone()
        assertEquals(1f, recorder.values.last(), "最终进度必须精确等于 1.0")
        assertTrue(recorder.details.any { it.startsWith(InstallPhase.VERIFY) }, "缺少「校验文件」阶段标签")
        assertTrue(
            recorder.details.any { it.startsWith(InstallPhase.LIBRARIES) },
            "缺少「下载库」阶段标签",
        )
        assertTrue(
            recorder.details.any { it.startsWith(InstallPhase.ASSETS) },
            "缺少「下载资源」阶段标签",
        )
        assertTrue(recorder.values.distinct().size > 20, "阶段内应持续推进，而不是只跳几次")
        // 下载阶段的大文件必须按字节推进出多个中间值，而不是只在完成时跳一格。
        assertTrue(
            recorder.valueDuring(InstallPhase.LIBRARIES).distinct().size >= 8,
            "大文件下载应按字节推进：${recorder.valueDuring(InstallPhase.LIBRARIES)}",
        )
        // 5000 个资源文件里，前 100 个就应产生多次推进。
        assertTrue(
            recorder.valueDuring(InstallPhase.ASSETS).distinct().size >= 100,
            "每个完成的资源文件都应推动进度",
        )
    }

    @Test
    fun `releasing files that were already intact never rewinds the bar`() {
        val recorder = Recorder()
        val progress = InstallProgress(context(recorder), 0f, 1f, emitIntervalMillis = 0)
        val library = artifact("library.jar", 0)
        val asset = artifact("asset.bin", 0)
        progress.plan(InstallPhase.VERIFY, 2, 0, PROGRESS_VERIFY_BYTES_PER_UNIT)
        progress.plan(InstallPhase.LIBRARIES, 1, 0)
        progress.plan(InstallPhase.ASSETS, 1, 0)
        progress.begin(InstallPhase.VERIFY)
        progress.verified(InstallPhase.VERIFY, library)
        progress.verified(InstallPhase.VERIFY, asset)
        val beforeRelease = recorder.values.last()
        progress.release(InstallPhase.LIBRARIES, 1, 0)
        progress.release(InstallPhase.ASSETS, 1, 0)
        assertTrue(
            recorder.values.last() >= beforeRelease,
            "释放已完成的工作量后进度回退：${recorder.values.last()} < $beforeRelease",
        )
        progress.finish()
        recorder.keepMonotone()
        assertEquals(1f, recorder.values.last())
    }

    @Test
    fun `failed optional files never push progress above the window`() {
        val recorder = Recorder()
        val progress = InstallProgress(context(recorder), 0f, .5f, emitIntervalMillis = 0)
        progress.begin(InstallPhase.LIBRARIES)
        progress.plan(InstallPhase.LIBRARIES, 2, 0)
        val failed = artifact("optional-log-config.xml")
        progress.started("optional-log-config.xml", failed)
        progress.failed("optional-log-config.xml", failed)
        progress.finish()
        recorder.keepMonotone()
        assertEquals(.5f, recorder.values.last())
    }
}
