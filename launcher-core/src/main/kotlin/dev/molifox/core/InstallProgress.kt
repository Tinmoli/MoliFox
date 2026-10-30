// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责把校验与下载的工作量折算成单调递增的任务进度，并始终带上阶段标签。

package dev.molifox.core

import kotlin.io.path.fileSize

/** 安装与维护的阶段标签：单个阶段可能要处理数千个文件，必须让用户看出还在做什么。 */
internal object InstallPhase {
    const val VERIFY = "校验文件"
    const val LIBRARIES = "下载库"
    const val ASSETS = "下载资源"
    const val LOADER = "安装加载器"
}

/**
 * 进度单位：一个文件算 1 个单位，字节按 [PROGRESS_BYTES_PER_UNIT] 折算成额外单位。
 * 这样 5000 个几 KB 的资源仍然每个都能推动进度条，而几百 MB 的客户端 JAR 也不会
 * 只占一道刻度。
 */
internal const val PROGRESS_BYTES_PER_UNIT = 256L * 1024

/** 校验只读本地磁盘，同样的字节比网络下载快得多，按 4 MiB 折算一个单位。 */
internal const val PROGRESS_VERIFY_BYTES_PER_UNIT = 4L * 1024 * 1024

/** 进度刷新节流：约 10 次/秒；阶段切换和最终完成不节流。 */
private const val PROGRESS_EMIT_INTERVAL_MILLIS = 100L

/** 下载调度向进度模型汇报的最小接口。 */
internal interface DownloadProgressSink {
    /** 本批总工作量（文件数 + 字节数），在派发前一次性登记。 */
    fun planned(
        files: Int,
        bytes: Long,
    ) {}

    /** 开始处理某个文件（校验或下载）。 */
    fun started(
        key: String,
        artifact: DownloadArtifact,
    ) {}

    /** 文件已传输的字节数；分段下载会多次回调，进度按比例增长。 */
    fun bytes(
        key: String,
        done: Long,
        total: Long,
    ) {}

    /** 文件完成（下载并校验通过，或本地校验通过）。 */
    fun finished(
        key: String,
        artifact: DownloadArtifact,
    ) {}

    /** 文件失败；进度不回退，最终由调用方汇总报错。 */
    fun failed(
        key: String,
        artifact: DownloadArtifact,
    ) {}

    object None : DownloadProgressSink
}

/**
 * 一次安装（或一次启动前补全）的进度模型。
 *
 * - 总量在阶段开始前登记，[from]..[to] 是这段工作占总任务的比例；
 * - 新增工作量时重新锚定，已显示的百分比保持不动，因此进度**不会回退**；
 * - 单文件内部按字节推进，阶段切换立即上报，最终由 [finish] 精确到 [to]。
 */
internal class InstallProgress(
    private val context: JobContext,
    private val from: Float = 0f,
    private val to: Float = 1f,
    private val emitIntervalMillis: Long = PROGRESS_EMIT_INTERVAL_MILLIS,
) : DownloadProgressSink {
    private class PhaseWork(
        val label: String,
        val bytesPerUnit: Long,
    ) {
        var files = 0
        var finished = 0
        var bytes = 0L
    }

    private val lock = Any()
    private val phases = LinkedHashMap<String, PhaseWork>()
    private val credit = HashMap<String, Double>()
    private val completedFiles = HashSet<String>()
    private val sizeOf = HashMap<String, Long>()
    private val phaseOf = HashMap<String, String>()
    private var plannedUnits = 0.0
    private var doneUnits = 0.0
    private var anchorUnits = 0.0
    private var anchorFraction = from
    private var emitted = from
    private var lastEmitAt = 0L
    private var currentPhase = InstallPhase.VERIFY

    private fun composite(
        phase: String,
        key: String,
    ) = "$phase\u0000$key"

    /** 切换阶段：标签变化立即上报，哪怕还在节流窗口内。 */
    fun begin(label: String) {
        synchronized(lock) {
            if (currentPhase != label) {
                currentPhase = label
                emit(force = true)
            } else {
                emit(force = false)
            }
        }
    }

    /** 登记某个阶段已知的工作量；验证券与下载可以分两次登记同一批文件。 */
    fun plan(
        label: String,
        files: Int,
        bytes: Long,
        bytesPerUnit: Long = PROGRESS_BYTES_PER_UNIT,
    ) {
        if (files <= 0 && bytes <= 0) return
        synchronized(lock) {
            val phase = phases.getOrPut(label) { PhaseWork(label, bytesPerUnit) }
            phase.files += files.coerceAtLeast(0)
            phase.bytes += bytes.coerceAtLeast(0)
            // 新增工作量后重新锚定，已完成的部分保持当前百分比。
            anchorFraction = emitted
            anchorUnits = doneUnits
            plannedUnits +=
                files.coerceAtLeast(0) + bytes.coerceAtLeast(0).toDouble() / bytesPerUnit
            if (label != InstallPhase.VERIFY) context.expectFiles(files.coerceAtLeast(0))
            emit(force = false)
        }
    }

    /**
     * 释放预先登记、实际不会发生的工作量（校验后发现文件本来就好好的，不需要下载）。
     * 分母变小只会让百分比向上，不会回退。
     */
    fun release(
        label: String,
        files: Int,
        bytes: Long,
        bytesPerUnit: Long = PROGRESS_BYTES_PER_UNIT,
    ) {
        if (files <= 0 && bytes <= 0) return
        synchronized(lock) {
            val phase = phases[label] ?: return
            val units = files.coerceAtLeast(0) + bytes.coerceAtLeast(0).toDouble() / bytesPerUnit
            phase.files = (phase.files - files).coerceAtLeast(phase.finished)
            phase.bytes = (phase.bytes - bytes).coerceAtLeast(0)
            plannedUnits = (plannedUnits - units).coerceAtLeast(doneUnits)
            anchorUnits = minOf(anchorUnits, plannedUnits)
            emit(force = false)
        }
    }

    /** 本地校验完一个文件：直接记满它在「校验文件」阶段的份额。 */
    fun verified(
        label: String,
        artifact: DownloadArtifact,
    ) {
        synchronized(lock) {
            val key = composite(label, artifact.target.toString())
            phaseOf[key] = label
            sizeOf[key] = artifact.size
            markCompleted(label, key)
            setCredit(key, fullCredit(key))
            emit(force = false)
        }
    }

    override fun started(
        key: String,
        artifact: DownloadArtifact,
    ) {
        synchronized(lock) {
            val composite = composite(currentPhase, key)
            phaseOf[composite] = currentPhase
            sizeOf[composite] = artifact.size
            setCredit(composite, 0.0)
            // 任务中心的速度与「剩余文件」仍按真实传输统计，和进度条共用同一份数据。
            context.transfer(key, 0, artifact.size.coerceAtLeast(0))
        }
    }

    override fun bytes(
        key: String,
        done: Long,
        total: Long,
    ) {
        synchronized(lock) {
            val composite = composite(currentPhase, key)
            val size = sizeOf[composite] ?: 0L
            val measured =
                when {
                    total > 0 -> done.toDouble() / total
                    size > 0 -> done.toDouble() / size
                    else -> 0.0
                }
            setCredit(composite, fullCredit(composite) * measured.coerceIn(0.0, 1.0))
            context.transfer(key, done.coerceAtLeast(0), total.coerceAtLeast(0))
            emit(force = false)
        }
    }

    override fun finished(
        key: String,
        artifact: DownloadArtifact,
    ) {
        synchronized(lock) {
            val composite = composite(currentPhase, key)
            phaseOf[composite] = currentPhase
            if (sizeOf[composite] == null) sizeOf[composite] = artifact.size
            markCompleted(currentPhase, composite)
            setCredit(composite, fullCredit(composite))
            val size = transferredSize(artifact)
            context.transfer(key, size, size, completed = true)
            emit(force = false)
        }
    }

    override fun failed(
        key: String,
        artifact: DownloadArtifact,
    ) {
        synchronized(lock) {
            // 失败的文件也算「处理完」，否则任务中心会一直显示还有剩余文件。
            val size = transferredSize(artifact)
            context.transfer(key, size, size, completed = true)
            emit(force = false)
        }
    }

    private fun transferredSize(artifact: DownloadArtifact): Long =
        if (artifact.size > 0) {
            artifact.size
        } else {
            runCatching { artifact.target.fileSize() }.getOrDefault(0L)
        }

    /** 这段工作全部结束：精确落到 [to]，再由上层任务收尾。 */
    fun finish() {
        synchronized(lock) {
            emitted = maxOf(emitted, to)
            lastEmitAt = System.currentTimeMillis()
            context.progressImmediate(emitted, detail())
        }
    }

    private fun fullCredit(key: String): Double {
        val size = sizeOf[key] ?: 0L
        val perUnit = phases[phaseOf[key]]?.bytesPerUnit ?: PROGRESS_BYTES_PER_UNIT
        return 1.0 + if (size > 0) size.toDouble() / perUnit else 0.0
    }

    private fun setCredit(
        key: String,
        value: Double,
    ) {
        val previous = credit[key] ?: 0.0
        credit[key] = value
        doneUnits += value - previous
    }

    /** 每个文件在它所属阶段只计一次完成，用于「阶段 已完成/计划」的明细。 */
    private fun markCompleted(
        label: String,
        key: String,
    ) {
        if (completedFiles.add(key)) phases[label]?.let { it.finished += 1 }
    }

    private fun fraction(): Float {
        val remaining = plannedUnits - anchorUnits
        val raw =
            if (remaining <= 1e-6) {
                anchorFraction.toDouble()
            } else {
                anchorFraction.toDouble() +
                    (doneUnits - anchorUnits) / remaining *
                    (to.toDouble() - anchorFraction.toDouble())
            }
        return raw.coerceIn(from.toDouble(), to.toDouble()).toFloat()
    }

    private fun detail(): String {
        val phase = phases[currentPhase] ?: return currentPhase
        if (phase.files <= 0) return currentPhase
        return "$currentPhase ${minOf(phase.finished, phase.files)}/${phase.files}"
    }

    private fun emit(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastEmitAt < emitIntervalMillis) return
        lastEmitAt = now
        val value = fraction()
        if (value > emitted) emitted = value
        if (force) {
            context.progressImmediate(emitted, detail())
        } else {
            context.progress(emitted, detail())
        }
    }
}
