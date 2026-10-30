// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 进度模型的可变状态与折算 / 上报辅助函数；由 InstallProgress 独占访问。

package dev.molifox.core

import kotlin.io.path.fileSize

/** 单个阶段已登记与已完成的工作量。 */
internal class InstallProgressPhase(
    val label: String,
    val bytesPerUnit: Long,
) {
    var files = 0
    var finished = 0
    var bytes = 0L
}

/** 一次任务的全部进度状态；锁与之前的单锁一致，保证折算与上报原子。 */
internal class InstallProgressState(
    val context: JobContext,
    val from: Float,
    val to: Float,
    val emitIntervalMillis: Long,
) {
    val lock = Any()
    val phases = LinkedHashMap<String, InstallProgressPhase>()
    val credit = HashMap<String, Double>()
    val completedFiles = HashSet<String>()
    val sizeOf = HashMap<String, Long>()
    val phaseOf = HashMap<String, String>()
    var plannedUnits = 0.0
    var doneUnits = 0.0
    var anchorUnits = 0.0
    var anchorFraction = from
    var emitted = from
    var lastEmitAt = 0L
    var currentPhase = InstallPhase.VERIFY
}

internal fun InstallProgressState.composite(
    phase: String,
    key: String,
): String = "$phase\u0000$key"

internal fun InstallProgressState.fullCredit(key: String): Double {
    val size = sizeOf[key] ?: 0L
    val perUnit = phases[phaseOf[key]]?.bytesPerUnit ?: PROGRESS_BYTES_PER_UNIT
    return 1.0 + if (size > 0) size.toDouble() / perUnit else 0.0
}

internal fun InstallProgressState.setCredit(
    key: String,
    value: Double,
) {
    val previous = credit[key] ?: 0.0
    credit[key] = value
    doneUnits += value - previous
}

/** 每个文件在它所属阶段只计一次完成，用于「阶段 已完成/计划」的明细。 */
internal fun InstallProgressState.markCompleted(
    label: String,
    key: String,
) {
    if (completedFiles.add(key)) phases[label]?.let { it.finished += 1 }
}

internal fun InstallProgressState.fraction(): Float {
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

internal fun InstallProgressState.detail(): String {
    val phase = phases[currentPhase] ?: return currentPhase
    if (phase.files <= 0) return currentPhase
    return "$currentPhase ${minOf(phase.finished, phase.files)}/${phase.files}"
}

internal fun InstallProgressState.emit(force: Boolean) {
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

internal fun InstallProgressState.transferredSize(artifact: DownloadArtifact): Long =
    if (artifact.size > 0) {
        artifact.size
    } else {
        runCatching { artifact.target.fileSize() }.getOrDefault(0L)
    }
