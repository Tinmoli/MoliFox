// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 让 JVM 跟随原生外壳退出，避免外壳被强杀后留下 Java 与联机核心进程。

package dev.molifox.core.platform

/**
 * 原生外壳被任务管理器强杀时，JVM 仍可能是存活进程；联机核心又绑定在 JVM 上，
 * 因此必须让 JVM 跟随外壳退出，不能只依赖正常关闭窗口的清理逻辑。
 *
 * 引导模块在显示准备窗口前就会调用一次，桌面模块启动时再调用也安全；重复调用不会创建多个线程。
 */
fun watchNativeLauncher() {
    val launcherPid =
        System.getenv("MOLIFOX_WRAPPER_PID")?.toLongOrNull()?.takeIf { it > 0 } ?: return
    if (launcherPid == ProcessHandle.current().pid()) return
    val launcher = ProcessHandle.of(launcherPid).orElse(null)
    if (launcher == null) {
        // 已提供外壳 PID 却找不到进程，说明它在监视初始化前已退出，不能按开发环境忽略。
        Runtime.getRuntime().halt(1)
        return
    }
    // 引导和桌面使用不同类加载器；协调标记和锁必须使用 JVM 共享对象。
    val properties = System.getProperties()
    synchronized(properties) {
        if (properties.getProperty(WATCHDOG_PID_PROPERTY) == launcherPid.toString()) return
        startNativeLauncherWatchdog(launcher)
        properties.setProperty(WATCHDOG_PID_PROPERTY, launcherPid.toString())
    }
}

private fun startNativeLauncherWatchdog(launcher: ProcessHandle) {
    Thread(
        {
            while (launcher.isAlive) {
                try {
                    Thread.sleep(NATIVE_LAUNCHER_WATCH_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
            // halt 不执行关闭钩子；JVM 退出后系统会关闭 job 句柄，联机核心随之被终止。
            Runtime.getRuntime().halt(1)
        },
        "native-launcher-watchdog",
    ).apply {
        isDaemon = true
        start()
    }
}

private const val NATIVE_LAUNCHER_WATCH_INTERVAL_MILLIS = 1_000L
private const val WATCHDOG_PID_PROPERTY = "molifox.nativeLauncher.watchdogPid"
