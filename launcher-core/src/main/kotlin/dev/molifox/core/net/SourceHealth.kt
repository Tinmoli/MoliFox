// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责单次任务内的源健康度：失败计数、403/429 退避重试同一源；失败后由候选序列切换下一个源。

package dev.molifox.core.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val MAX_RATE_LIMIT_STEP = 5
private const val MIN_SPEED_SAMPLE_MILLIS = 5_000L
private const val SLOW_BYTES_PER_SECOND = 64 * 1024L
private const val SLOW_SOURCE_RECHECK_MILLIS = 60_000L

/**
 * 每个源在当前任务里的状态：成功/失败次数与限流到期时间。
 * 403/429 只记限流，不进 [failures]，也不影响 [consecutiveFailures]。
 * 源不会被禁用：单个文件失败后立即切换下一个候选源，全部失败才算文件失败。
 */
class SourceHealth(
    private val clock: () -> Long = System::currentTimeMillis,
    /** 403/429 的首次退避与上限：1s → 2s → 4s …（上限 30s）。 */
    private val rateLimitBaseMillis: Long = 1_000L,
    private val rateLimitCapMillis: Long = 30_000L,
) {
    data class State(
        val successes: Int = 0,
        val failures: Int = 0,
        val consecutiveFailures: Int = 0,
        val rateLimitHits: Int = 0,
        val rateLimitStep: Int = 0,
        val rateLimitedUntil: Long = 0L,
    ) {
        fun rateLimited(now: Long): Boolean = rateLimitedUntil > now
    }

    private val states = HashMap<String, State>()
    private val slowHosts = HashMap<String, Long>()

    @Synchronized
    fun state(key: String): State = states[key] ?: State()

    @Synchronized
    fun recordSuccess(key: String) {
        val current = state(key)
        states[key] =
            current.copy(
                successes = current.successes + 1,
                consecutiveFailures = 0,
                rateLimitStep = 0,
                rateLimitedUntil = 0L,
            )
    }

    /** 记录一次真实失败；只累计计数供排障与进度展示，不影响源是否继续参加。 */
    @Synchronized
    fun recordFailure(key: String) {
        val current = state(key)
        states[key] =
            current.copy(
                failures = current.failures + 1,
                consecutiveFailures = current.consecutiveFailures + 1,
            )
    }

    /** 403/429：不算失败，返回按指数退避应等待的毫秒数，并推迟同一源的重试时间。 */
    @Synchronized
    fun recordRateLimited(key: String): Long {
        val current = state(key)
        val step = current.rateLimitStep.coerceIn(0, 5)
        val wait = minOf(rateLimitBaseMillis shl step, rateLimitCapMillis)
        states[key] =
            current.copy(
                rateLimitHits = current.rateLimitHits + 1,
                rateLimitStep = (step + 1).coerceAtMost(MAX_RATE_LIMIT_STEP),
                rateLimitedUntil = clock() + wait,
            )
        return wait
    }

    /**
     * 按健康度重排候选：可用源保持原顺序，限流的排到末尾。
     * 全部限流时退回原列表，好让失败信息仍能列出每个源的地址。
     */
    @Synchronized
    fun arrange(candidates: List<CandidateSource>): List<CandidateSource> {
        if (candidates.size < 2) return candidates
        val now = clock()
        val usable = mutableListOf<CandidateSource>()
        val limited = mutableListOf<CandidateSource>()
        for (candidate in candidates) {
            val state = states[candidate.source.key] ?: State()
            if (state.rateLimited(now)) limited += candidate else usable += candidate
        }
        val ordered = usable + limited
        return if (ordered.isEmpty()) candidates else ordered
    }

    /** 只根据实际传输观察降低同一主机的优先级，短请求不参与。 */
    @Synchronized
    internal fun recordTransfer(
        candidate: CandidateSource,
        bytes: Long,
        elapsedMillis: Long,
    ) {
        if (elapsedMillis < MIN_SPEED_SAMPLE_MILLIS) return
        val now = clock()
        slowHosts.values.removeIf { it <= now }
        val key = speedKey(candidate)
        val bytesPerSecond = bytes * 1000.0 / elapsedMillis
        if (bytesPerSecond < SLOW_BYTES_PER_SECOND) {
            slowHosts[key] = now + SLOW_SOURCE_RECHECK_MILLIS
        } else {
            slowHosts.remove(key)
        }
    }

    @Synchronized
    internal fun arrangeTransfers(candidates: List<CandidateSource>): List<CandidateSource> {
        val now = clock()
        return candidates.sortedBy { candidate ->
            when {
                state(candidate.source.key).rateLimited(now) -> 2
                (slowHosts[speedKey(candidate)] ?: 0) > now -> 1
                else -> 0
            }
        }
    }

    private fun speedKey(candidate: CandidateSource): String =
        "${candidate.source.key}@${candidate.url.toHttpUrlOrNull()?.host}"

    @Synchronized
    fun reset() {
        states.clear()
        slowHosts.clear()
    }
}
