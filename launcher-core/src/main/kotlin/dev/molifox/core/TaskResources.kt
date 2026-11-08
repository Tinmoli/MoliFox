// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责按任务使用的资源排队，让不冲突的实例操作并行执行。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.nio.file.Path
import java.util.Locale

internal const val GLOBAL_TASK_RESOURCE = "*"

internal fun taskPathKey(path: Path): String =
    (runCatching { path.toRealPath() }.getOrNull() ?: path.toAbsolutePath().normalize())
        .toString()
        .let {
            if (System.getProperty("os.name").startsWith("Windows", true)) {
                it.lowercase(Locale.ROOT)
            } else {
                it
            }
        }

internal fun taskResourcesForInstances(
    store: LauncherStore,
    vararg ids: String,
): Set<String> =
    buildSet {
        ids.forEach { id ->
            addAll(taskResourcesForInstance(store, id, store.instance(id)))
        }
    }

/** 保留调用方的原 ID 键；只读查询可以传入已解析快照，不改变任务隔离语义。 */
internal fun taskResourcesForInstance(
    store: LauncherStore,
    id: String,
    instance: GameInstance,
): Set<String> =
    buildSet {
        add("instance:$id")
        add("files:${taskPathKey(store.gamePath(instance))}")
        for (version in setOf(instance.versionId, instance.gameVersion)) {
            add(
                "version:${taskPathKey(
                    store.minecraftRoot(instance).resolve("versions").resolve(version),
                )}",
            )
        }
    }

internal fun taskResourcesConflict(
    left: Set<String>,
    right: Set<String>,
): Boolean = GLOBAL_TASK_RESOURCE in left || GLOBAL_TASK_RESOURCE in right || left.any { it in right }

internal class TaskResourceGate {
    private val waiting = linkedMapOf<String, Set<String>>()
    private val active = mutableSetOf<String>()
    private val changes = MutableStateFlow(0L)

    @Synchronized
    fun register(
        id: String,
        resources: Set<String>,
    ) {
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
        val resources = waiting[id] ?: error(I18n.t("diagA2.taskResources.taskNotQueued"))
        // 只检查排在当前任务之前登记的条目，再检查正在运行的任务；任一处冲突即视为不可启动。
        val blocked =
            waiting.entries
                .asSequence()
                .takeWhile { it.key != id }
                .any { taskResourcesConflict(resources, it.value) } ||
                active.any { taskResourcesConflict(resources, waiting.getValue(it)) }
        return if (blocked) {
            false
        } else {
            active.add(id)
            true
        }
    }

    @Synchronized
    fun release(id: String) {
        waiting.remove(id)
        active.remove(id)
        changes.value++
    }

    @Synchronized
    fun rebind(
        id: String,
        resources: Set<String>,
    ) {
        check(id in active)
        check(active.none { it != id && taskResourcesConflict(resources, waiting.getValue(it)) }) {
            "diagA2.taskResources.packTargetBusy"
        }
        waiting[id] = resources.toSet()
        changes.value++
    }
}
