// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责按任务使用的资源排队，让不冲突的实例操作并行执行。

package dev.molifox.core

import java.nio.file.Path
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

internal const val GLOBAL_TASK_RESOURCE = "*"

internal fun taskPathKey(path: Path): String =
    (runCatching { path.toRealPath() }.getOrNull() ?: path.toAbsolutePath().normalize())
        .toString()
        .let {
            if (System.getProperty("os.name").startsWith("Windows", true)) it.lowercase(Locale.ROOT)
            else it
        }

internal fun taskResourcesForInstances(store: LauncherStore, vararg ids: String): Set<String> =
    buildSet {
        ids.forEach { id ->
            val instance = store.instance(id)
            add("instance:$id")
            add("files:${taskPathKey(store.gamePath(instance))}")
            for (version in setOf(instance.versionId, instance.gameVersion)) {
                add(
                    "version:${taskPathKey(store.minecraftRoot(instance).resolve("versions").resolve(version))}"
                )
            }
        }
    }

internal fun taskResourcesConflict(left: Set<String>, right: Set<String>): Boolean =
    GLOBAL_TASK_RESOURCE in left || GLOBAL_TASK_RESOURCE in right || left.any { it in right }

internal class TaskResourceGate {
    private val waiting = linkedMapOf<String, Set<String>>()
    private val active = mutableSetOf<String>()
    private val changes = MutableStateFlow(0L)

    @Synchronized
    fun register(id: String, resources: Set<String>) {
        waiting[id] = resources
        changes.value++
    }

    suspend fun acquire(id: String) {
        while (true) {
            val revision = changes.value
            if (tryStart(id)) return
            changes.first { it != revision }
        }
    }

    @Synchronized
    private fun tryStart(id: String): Boolean {
        val resources = waiting[id] ?: error("任务已不在队列中")
        for ((otherId, otherResources) in waiting) {
            if (otherId == id) break
            if (taskResourcesConflict(resources, otherResources)) return false
        }
        if (active.any { taskResourcesConflict(resources, waiting.getValue(it)) }) return false
        active.add(id)
        return true
    }

    @Synchronized
    fun release(id: String) {
        waiting.remove(id)
        active.remove(id)
        changes.value++
    }

    @Synchronized
    fun rebind(id: String, resources: Set<String>) {
        check(id in active)
        check(active.none { it != id && taskResourcesConflict(resources, waiting.getValue(it)) }) {
            "整合包目标正在被其他任务使用"
        }
        waiting[id] = resources.toSet()
        changes.value++
    }
}
