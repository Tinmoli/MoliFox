// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import java.nio.file.Path

/** 下载完毕后留给校验与落盘，完成状态由任务队列发布。 */
private const val MAX_IN_PROGRESS_FRACTION = 0.999f

/** 安装任务的下载账目。由 JobContext 在同一锁内读写。 */
internal class InstallationTransfers {
    private class File(
        var total: Long,
    ) {
        var reused = 0L
        var received = 0L
        var started = false
        var complete = false
    }

    private val files = linkedMapOf<Path, File>()
    private val verifiedReusable = mutableSetOf<Path>()
    private var previousDownloads = 0
    private var previousBytes = 0L
    private var packSizes = emptyList<Long>()
    var planned = false
    var lastPublish = System.nanoTime()

    fun reserve(artifacts: List<DownloadArtifact>) {
        for (artifact in artifacts) {
            val path = key(artifact.target)
            if (path !in verifiedReusable) files.putIfAbsent(path, File(artifact.size.coerceAtLeast(0)))
        }
    }

    fun reservePack(sizes: List<Long>) {
        packSizes = sizes
    }

    fun beginPack(artifacts: List<DownloadArtifact>) {
        packSizes = emptyList()
        reserve(artifacts)
    }

    fun plan(
        target: Path,
        size: Long,
    ) {
        val path = key(target)
        verifiedReusable.remove(path)
        val previous = files[path]
        if (previous?.complete == true) {
            previousDownloads++
            previousBytes += previous.received
            files.remove(path)
        }
        val file = files.getOrPut(path) { File(size.coerceAtLeast(0)) }
        if (file.total == 0L && size > 0) file.total = size
    }

    fun reuse(target: Path) {
        val path = key(target)
        if (files[path]?.complete == true) return
        files.remove(path)
        verifiedReusable.add(path)
    }

    fun transfer(
        target: Path,
        done: Long,
        total: Long,
    ) {
        val file = files[key(target)] ?: return
        if (total > 0) file.total = total
        if (!file.started) {
            file.started = true
            file.reused = done
        } else if (done < file.reused) {
            file.reused = done
        }
        file.received = (done - file.reused).coerceAtLeast(0)
    }

    fun finish(
        target: Path,
        size: Long,
    ) {
        val file = files[key(target)] ?: return
        if (file.total == 0L) file.total = size
        file.received = (file.total - file.reused).coerceAtLeast(0)
        file.complete = true
    }

    fun apply(job: DownloadJob): DownloadJob {
        val total =
            previousBytes + packSizes.sum() + files.values.sumOf { (it.total - it.reused).coerceAtLeast(it.received) }
        val received = previousBytes + files.values.sumOf { it.received }
        val pending =
            !planned || total == 0L || packSizes.any { it == 0L } || files.values.any { it.total == 0L && !it.complete }
        val completed = previousDownloads + files.values.count { it.complete }
        return job.copy(
            progress =
                if (pending) {
                    0f
                } else {
                    (received.toDouble() / total).toFloat().coerceIn(
                        0f,
                        MAX_IN_PROGRESS_FRACTION,
                    )
                },
            downloadedBytes = received,
            totalBytes = total,
            totalFiles = previousDownloads + files.size + packSizes.size,
            completedFiles = completed,
            downloadTotalPending = pending,
        )
    }

    private fun key(target: Path): Path = target.toAbsolutePath().normalize()
}

internal fun JobContext.enableInstallationTransfers() {
    synchronized(this) {
        val transfers = installationTransfers ?: InstallationTransfers().also { installationTransfers = it }
        publishInstallationTransfers(transfers)
    }
}

internal fun JobContext.planInstallationTransfers(artifacts: List<DownloadArtifact>) {
    synchronized(this) {
        val transfers = installationTransfers ?: return
        transfers.reserve(artifacts)
        transfers.planned = true
        publishInstallationTransfers(transfers)
    }
}

internal fun JobContext.updateInstallationTransfer(update: (InstallationTransfers) -> Unit) {
    synchronized(this) {
        val transfers = installationTransfers ?: return
        update(transfers)
        val now = System.nanoTime()
        if (now - transfers.lastPublish >= TRANSFER_SAMPLE_INTERVAL_NANOS) {
            transfers.lastPublish = now
            publishInstallationTransfers(transfers)
        }
    }
}

private fun JobContext.publishInstallationTransfers(transfers: InstallationTransfers) {
    store.updateState { state ->
        state.copy(
            jobs =
                state.jobs.map { job ->
                    if (job.id == id) {
                        transfers.apply(job).copy(
                            bytesPerSecond = transferSpeed,
                            activeConnections = openConnections.get().coerceAtLeast(0),
                        )
                    } else {
                        job
                    }
                },
        )
    }
}
