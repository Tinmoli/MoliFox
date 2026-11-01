// 联机子进程的系统归属保护；不应用于游戏、更新助手或用户启动的其它程序。
package dev.molifox.core.platform

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Platform
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT.HANDLE
import java.io.IOException

/** Linux 通过专属 stdin 管道感知 JVM 消失；核心不继承该管道，避免 EOF 被其它子进程延迟。 */
fun ownedChildCommand(command: List<String>): List<String> =
    if (Platform.isLinux()) {
        listOf("/bin/sh", "-c", LINUX_GUARD, "molifox-core-guard") + command
    } else {
        command
    }

/** Windows job 的唯一句柄留在 JVM；JVM 即使被强杀，系统关闭句柄也会结束已绑定的核心。 */
fun bindOwnedChild(process: Process): AutoCloseable {
    if (!Platform.isWindows()) return AutoCloseable { }
    val kernel = NativeLibrary.getInstance("kernel32")
    val job = HANDLE(kernel.getFunction("CreateJobObjectW").invokePointer(arrayOf(null, null)))
    if (job.pointer == null) throw IOException("CreateJobObjectW failed: ${Native.getLastError()}")
    try {
        // 本项目仅交付 x86_64；EXTENDED_LIMIT_INFORMATION 为 144 字节，LimitFlags 位于 16。
        Memory(144).use { limits ->
            limits.clear()
            limits.setInt(16, 0x2000)
            val configured = kernel.getFunction("SetInformationJobObject")
                .invokeInt(arrayOf(job, 9, limits, limits.size().toInt()))
            if (configured == 0) throw IOException("SetInformationJobObject failed: ${Native.getLastError()}")
        }
        val child = Kernel32.INSTANCE.OpenProcess(0x0101, false, process.pid().toInt())
            ?: throw IOException("OpenProcess failed: ${Native.getLastError()}")
        try {
            val assigned = kernel.getFunction("AssignProcessToJobObject").invokeInt(arrayOf(job, child))
            if (assigned == 0) throw IOException("AssignProcessToJobObject failed: ${Native.getLastError()}")
        } finally {
            Kernel32.INSTANCE.CloseHandle(child)
        }
    } catch (failure: Exception) {
        Kernel32.INSTANCE.CloseHandle(job)
        throw failure
    }
    return AutoCloseable { Kernel32.INSTANCE.CloseHandle(job) }
}

private val LINUX_GUARD = """
    child=
    watcher=
    cleanup() {
        trap - HUP INT TERM
        [ -z "${'$'}watcher" ] || kill "${'$'}watcher" 2>/dev/null || :
        if [ -n "${'$'}child" ]; then
            kill -KILL "${'$'}child" 2>/dev/null || :
            wait "${'$'}child" 2>/dev/null || :
        fi
    }
    trap 'cleanup; exit' HUP INT TERM
    "${'$'}@" </dev/null &
    child=${'$'}!
    # JVM 不向这条管道写数据；正常关闭或被强杀都会产生 EOF，无需轮询父 PID。
    (read -r ignored || :; kill -KILL "${'$'}child" 2>/dev/null || :) <&0 &
    watcher=${'$'}!
    wait "${'$'}child"
    result=${'$'}?
    kill "${'$'}watcher" 2>/dev/null || :
    wait "${'$'}watcher" 2>/dev/null || :
    exit "${'$'}result"
""".trimIndent()
