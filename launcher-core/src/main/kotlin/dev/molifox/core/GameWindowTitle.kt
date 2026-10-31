// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// Windows 游戏窗口标题与显卡偏好：属于平台适配，失败只返回提示，不阻断启动。

package dev.molifox.core

import com.sun.jna.NativeLibrary
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import java.util.concurrent.TimeUnit

private const val WINDOW_TITLE_TIMEOUT_MS = 15_000L
private const val WINDOW_TITLE_POLL_MS = 250L
private const val REGISTRY_GPU_KEY = "Software\\Microsoft\\DirectX\\UserGpuPreferences"
private const val REGISTRY_HIGH_PERFORMANCE = "GpuPreference=2;"
private const val REGISTRY_DEFAULT = "GpuPreference=0;"

/**
 * 等游戏窗口出现后写入自定义标题；返回是否成功。
 *
 * 只支持 Windows：其他平台没有稳定的、无需额外依赖的窗口标题接口，调用方应把 false 当作
 * “已忽略”而不是启动失败。
 */
internal fun applyWindowTitle(
    process: Process,
    title: String,
    osName: String,
): Boolean {
    var applied = false
    if (osName.startsWith("Windows", ignoreCase = true) && title.isNotBlank()) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WINDOW_TITLE_TIMEOUT_MS)
        val processId = process.pid()
        while (!applied && process.isAlive && System.nanoTime() < deadline) {
            val window = findVisibleWindow(processId)
            applied = window != null && setWindowText(window, title)
            if (!applied && !waitForNextPoll()) break
        }
    }
    return applied
}

/** 等待下一轮轮询；线程被中断时恢复中断标记并让调用方结束等待。 */
private fun waitForNextPoll(): Boolean =
    try {
        Thread.sleep(WINDOW_TITLE_POLL_MS)
        true
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

/** JNA 的 User32 封装没有暴露 SetWindowText，按原生符号名单独调用。 */
private fun setWindowText(
    window: WinDef.HWND,
    title: String,
): Boolean {
    val function = NativeLibrary.getInstance("user32").getFunction("SetWindowTextW")
    return function.invokeInt(arrayOf(window, WString(title))) != 0
}

/** 按进程号找第一个可见且带标题的顶层窗口。 */
private fun findVisibleWindow(processId: Long): WinDef.HWND? {
    var found: WinDef.HWND? = null
    User32.INSTANCE.EnumWindows(
        WinUser.WNDENUMPROC { window, _ ->
            if (found == null && isTargetWindow(window, processId)) found = window
            found == null
        },
        null,
    )
    return found
}

private fun isTargetWindow(
    window: WinDef.HWND,
    processId: Long,
): Boolean {
    val owner = IntByReference()
    User32.INSTANCE.GetWindowThreadProcessId(window, owner)
    return owner.value.toLong() == processId &&
        User32.INSTANCE.IsWindowVisible(window) &&
        User32.INSTANCE.GetWindowTextLength(window) > 0
}

/**
 * 把 Java 可执行文件加入 Windows「高性能」显卡偏好；[enabled] 为 false 时只在当前值是
 * 高性能时恢复默认，避免覆盖用户自己设置的其他偏好。
 *
 * 返回需要写入日志的提示；成功或无需操作返回 null。
 */
internal fun applyHighPerformanceGpu(
    osName: String,
    executable: String,
    enabled: Boolean,
): String? {
    if (!osName.startsWith("Windows", ignoreCase = true)) {
        return if (enabled) "高性能显卡设置仅支持 Windows，本次已忽略" else null
    }
    return runCatching {
        val current =
            runCatching {
                Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_CURRENT_USER,
                    REGISTRY_GPU_KEY,
                    executable,
                )
            }.getOrNull()
        when {
            enabled && current == REGISTRY_HIGH_PERFORMANCE -> null
            enabled -> {
                Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, REGISTRY_GPU_KEY)
                Advapi32Util.registrySetStringValue(
                    WinReg.HKEY_CURRENT_USER,
                    REGISTRY_GPU_KEY,
                    executable,
                    REGISTRY_HIGH_PERFORMANCE,
                )
                "已把 ${executable.substringAfterLast('\\')} 设为高性能显卡"
            }
            current == REGISTRY_HIGH_PERFORMANCE -> {
                Advapi32Util.registrySetStringValue(
                    WinReg.HKEY_CURRENT_USER,
                    REGISTRY_GPU_KEY,
                    executable,
                    REGISTRY_DEFAULT,
                )
                "已恢复 ${executable.substringAfterLast('\\')} 的默认显卡偏好"
            }
            else -> null
        }
    }.getOrElse { failure ->
        "设置高性能显卡失败：${failure.message ?: failure.javaClass.simpleName}"
    }
}
