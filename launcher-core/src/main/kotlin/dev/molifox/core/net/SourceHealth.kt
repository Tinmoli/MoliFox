// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责单次任务内的源健康度：连续失败禁用、403/429 退避重试同一源。

package dev.molifox.core.net

/**
 * 每个源在当前任务里的状态：成功/失败次数、连续失败、禁用与限流到期时间。
 * 403/429 只记限流，不进 [failures]，也不影响 [consecutiveFailures]。
 */
class SourceHealth(
    private val clock: () -> Long = System::currentTimeMillis,
    /** 连续多少次真实失败后禁用该源。 */
    private val disableAfter: Int = 3,
    /** 禁用冷却：到期后重新参加；一次安装任务内通常不会到期。 */
    private val disableMillis: Long = 5 * 60_000L,
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
        val disabledUntil: Long = 0L,
        val rateLimitedUntil: Long = 0L,
    ) {
        fun disabled(now: Long): Boolean = disabledUntil > now

        fun rateLimited(now: Long): Boolean = rateLimitedUntil > now
    }

    private val states = HashMap<String, State>()

    @Synchronized
    fun state(key: String): State = states[key] ?: State()

    @Synchronized
    fun recordSuccess(key: String) {
        val current = state(key)
        states[key] = current.copy(successes = current.successes + 1, consecutiveFailures = 0, rateLimitStep = 0, rateLimitedUntil = 0L)
    }

    /** 记录一次真实失败；返回是否刚好在这次触发禁用（调用方只记一次日志）。 */
    @Synchronized
    fun recordFailure(key: String): Boolean {
        val current = state(key)
        val now = clock()
        val consecutive = current.consecutiveFailures + 1
        val disable = consecutive >= disableAfter
        states[key] =
            current.copy(
                failures = current.failures + 1,
                consecutiveFailures = consecutive,
                disabledUntil = if (disable) now + disableMillis else current.disabledUntil,
            )
        return disable && !current.disabled(now)
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
                rateLimitStep = (step + 1).coerceAtMost(5),
                rateLimitedUntil = clock() + wait,
            )
        return wait
    }

    /**
     * 按健康度重排候选：可用源保持原顺序，限流的排到末尾，禁用的剔除。
     * 全部被禁用时退回原列表，好让失败信息仍能列出每个源的地址。
     */
    @Synchronized
    fun arrange(candidates: List<CandidateSource>): List<CandidateSource> {
        if (candidates.size < 2) return candidates
        val now = clock()
        val usable = mutableListOf<CandidateSource>()
        val limited = mutableListOf<CandidateSource>()
        for (candidate in candidates) {
            val state = states[candidate.source.key] ?: State()
            when {
                state.disabled(now) -> Unit
                state.rateLimited(now) -> limited += candidate
                else -> usable += candidate
            }
        }
        val ordered = usable + limited
        return if (ordered.isEmpty()) candidates else ordered
    }

    @Synchronized
    fun reset() = states.clear()
}