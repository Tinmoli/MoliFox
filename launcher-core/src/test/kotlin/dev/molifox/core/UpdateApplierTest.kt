// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 便携版路径识别：只有真正的 exe/.run 才允许自动替换，开发环境的 java 进程必须拒绝。

package dev.molifox.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateApplierTest {
    @Test
    fun `windows portable exe is detected`() {
        assertEquals(
            Path.of("C:/Games/MoliFox.exe"),
            LauncherBundlePath.current("windows", command = "C:/Games/MoliFox.exe"),
        )
    }

    @Test
    fun `dev java process is refused`() {
        assertNull(LauncherBundlePath.current("windows", command = "C:/Java/bin/java.exe"))
        assertNull(LauncherBundlePath.current("linux", command = "/usr/bin/java", env = { null }))
    }

    @Test
    fun `linux uses wrapper path from environment`() {
        assertEquals(
            Path.of("/opt/MoliFox-linux-x64.run"),
            LauncherBundlePath.current(
                "linux",
                command = "/usr/bin/java",
                env = { key ->
                    if (key ==
                        "MOLIFOX_BUNDLE_PATH"
                    ) {
                        "/opt/MoliFox-linux-x64.run"
                    } else {
                        null
                    }
                },
            ),
        )
    }
}
