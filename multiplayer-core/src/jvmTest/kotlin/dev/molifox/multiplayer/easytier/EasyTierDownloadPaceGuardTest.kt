// 下载速度守卫：只有窗口内明显偏慢才建议换源，避免误伤慢但稳定的下载。
package dev.molifox.multiplayer.easytier

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EasyTierDownloadPaceGuardTest {
    private var now = 0L
    private val guard =
        DownloadPaceGuard(
            windowMillis = WINDOW_MILLIS,
            minRatio = MIN_RATIO,
            clock = { now },
        )

    @Test
    fun `does not abort before the observation window`() {
        guard.tooSlow(received = 0, expectedBytes = EXPECTED_BYTES)
        now += WINDOW_MILLIS - 1
        assertFalse(guard.tooSlow(received = 1, expectedBytes = EXPECTED_BYTES))
    }

    @Test
    fun `aborts when far below the minimum progress ratio`() {
        guard.tooSlow(received = 0, expectedBytes = EXPECTED_BYTES)
        now += WINDOW_MILLIS
        assertTrue(guard.tooSlow(received = MIN_BYTES - 1, expectedBytes = EXPECTED_BYTES))
    }

    @Test
    fun `keeps downloading when progress is acceptable`() {
        guard.tooSlow(received = 0, expectedBytes = EXPECTED_BYTES)
        now += WINDOW_MILLIS
        assertFalse(guard.tooSlow(received = MIN_BYTES, expectedBytes = EXPECTED_BYTES))
    }

    @Test
    fun `ignores unknown expected size`() {
        guard.tooSlow(received = 0, expectedBytes = 0)
        now += WINDOW_MILLIS * 2
        assertFalse(guard.tooSlow(received = 0, expectedBytes = 0))
    }

    private companion object {
        const val WINDOW_MILLIS = 1_000L
        const val MIN_RATIO = 0.05
        const val EXPECTED_BYTES = 1_000L
        const val MIN_BYTES = 50L
    }
}
