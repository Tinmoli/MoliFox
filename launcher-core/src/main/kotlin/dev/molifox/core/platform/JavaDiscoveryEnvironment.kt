// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core.platform

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.CancellationException
import java.util.TreeMap

/** 当前进程优先；Windows 持久环境另作候选，刷新时可发现启动后新增的 Java。 */
internal fun javaDiscoveryEnvironments(
    windows: Boolean,
    onFailure: (String, Throwable) -> Unit,
): List<Map<String, String>> {
    val current = System.getenv().toMap()
    if (!windows) return listOf(current)
    val machine =
        readEnvironmentRegistry(
            WinReg.HKEY_LOCAL_MACHINE,
            "SYSTEM\\CurrentControlSet\\Control\\Session Manager\\Environment",
            onFailure,
        )
    val user = readEnvironmentRegistry(WinReg.HKEY_CURRENT_USER, "Environment", onFailure)
    return buildList {
        add(TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply { putAll(current) })
        if (machine.isNotEmpty()) add(mergedEnvironment(current, machine))
        if (user.isNotEmpty()) add(mergedEnvironment(mergedEnvironment(current, machine), user))
    }
}

private fun mergedEnvironment(
    base: Map<String, String>,
    overlay: Map<String, String>,
): Map<String, String> =
    TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply {
        putAll(base)
        putAll(overlay)
    }

private fun readEnvironmentRegistry(
    root: WinReg.HKEY,
    key: String,
    onFailure: (String, Throwable) -> Unit,
): Map<String, String> =
    runCatching {
        if (!Advapi32Util.registryKeyExists(root, key)) {
            emptyMap()
        } else {
            Advapi32Util
                .registryGetValues(root, key)
                .mapNotNull { (name, value) ->
                    (value as? String)?.let { name to it }
                }.toMap()
        }
    }.onFailure { failure ->
        if (failure !is Exception || failure is CancellationException) throw failure
        onFailure(key, failure)
    }.getOrDefault(emptyMap())
