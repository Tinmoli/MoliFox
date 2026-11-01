// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 Java 探测、安装与提示。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.file.Path
import kotlin.io.path.name

/** Java 提示、安装、选择与移除。 */
internal interface LauncherJavaApi {
    fun refreshJavaPrompt()

    /** [remember] 为 true 时把「不再提示」写进实例；只影响提示，不影响手动选择的 Java。 */
    fun dismissJavaPrompt(remember: Boolean)

    /** 从提示弹窗直接安装缺少的主版本；进度进入任务队列，成功后清除提示。 */
    fun installJavaForPrompt(): String

    /** 用户从提示弹窗选择已有 Java：校验兼容后写进实例设置（显式路径优先于自动选择）。 */
    fun chooseJavaForPrompt(path: String): Job

    /**
     * 移除 Java 条目：启动器安装的删除目录与清单；用户手动添加的只删记录、不动文件；
     * 系统 Java 只读。被移除路径若仍是全局/实例选择，一并清回「自动」，避免留下失效选择。
     */
    fun removeJavaRuntime(runtime: JavaRuntime): Job

    fun installJava(
        major: Int,
        imageType: String? = null,
    ): String

    /** 「自动」安装：按当前实例的游戏版本换算主版本（26.x→25、1.20.5+→21、1.17–1.20.4→17、更老→8）。 */
    fun installAutoJava(imageType: String? = null): String
}

private const val BYTES_PER_MEGABYTE = 1048576f

/** 实现由 [LauncherController] 委托调用；方法体与原实现一致。 */
internal abstract class LauncherJavaActions(
    core: LauncherControllerCore,
) : LauncherRuntimeActions(core),
    LauncherJavaApi {
    override fun refreshJavaPrompt() {
        scope.launch { evaluateJavaPrompt() }
    }

    private suspend fun evaluateJavaPrompt() {
        val selectedId = store.data.selectedInstanceId
        val existing = state.value.javaPrompt
        if (existing != null && existing.instanceId != selectedId) {
            store.updateState { it.copy(javaPrompt = null) }
        }
        val instance =
            selectedId?.let { id -> store.data.instances.find { it.id == id } }
        if (instance == null) return
        val needsPrompt =
            instance.installed &&
                !instance.suppressJavaPrompt &&
                instance.javaPath.ifBlank { store.data.settings.javaPath }.isBlank()
        if (!needsPrompt) {
            clearJavaPrompt()
        } else {
            resolveJavaPrompt(instance)
        }
    }

    /** 清除当前提示；本来没有提示时不写状态。 */
    private fun clearJavaPrompt() {
        if (state.value.javaPrompt != null) store.updateState { it.copy(javaPrompt = null) }
    }

    /** 解析实例版本配置并检测本机 Java；没有满足要求的候选时登记提示。 */
    private suspend fun resolveJavaPrompt(instance: GameInstance) {
        val profile =
            runCatching {
                VersionResolver(store.minecraftRoot(instance), platform)
                    .resolve(instance.versionId)
            }.getOrNull()
        if (profile != null) {
            val requirement = JavaRequirement.from(profile)
            val candidates = java.detect().filter { it.usable }
            if (candidates.any { requirement.problem(it, platform.architecture) == null }) {
                clearJavaPrompt()
            } else {
                val gameVersion = instance.gameVersion.ifBlank { profile.id }
                store.updateState {
                    it.copy(
                        javaPrompt =
                            JavaPrompt(
                                instanceId = instance.id,
                                instanceName = instance.name,
                                gameVersion = gameVersion,
                                requiredMajor =
                                    requiredJavaMajor(gameVersion, profile.requiredJava),
                                candidates = candidates,
                            ),
                    )
                }
            }
        }
    }

    /** [remember] 为 true 时把「不再提示」写进实例；只影响提示，不影响手动选择的 Java。 */
    override fun dismissJavaPrompt(remember: Boolean) {
        val prompt = state.value.javaPrompt ?: return
        if (remember) {
            store.updateInstance(prompt.instanceId) { it.copy(suppressJavaPrompt = true) }
        }
        store.updateState { it.copy(javaPrompt = null) }
    }

    /** 从提示弹窗直接安装缺少的主版本；进度进入任务队列，成功后清除提示。 */
    override fun installJavaForPrompt(): String {
        val prompt = state.value.javaPrompt ?: error(I18n.t("coreC.java.noneToInstall"))
        return jobs.enqueue(
            I18n.t("coreC.tasks.installJava", prompt.requiredMajor),
            setOf("java:${prompt.requiredMajor}"),
            kind = LauncherTaskKind.InstallJava,
        ) {
            val runtime = java.install(prompt.requiredMajor, this)
            store.updateState { it.copy(javaPrompt = null) }
            store.notify(I18n.t("coreC.java.installed", runtime.displayName))
        }
    }

    /** 用户从提示弹窗选择已有 Java：校验兼容后写进实例设置（显式路径优先于自动选择）。 */
    override fun chooseJavaForPrompt(path: String) =
        perform {
            val prompt = state.value.javaPrompt ?: error(I18n.t("coreC.java.noneToHandle"))
            val runtime =
                java.inspect(path, verifyRunnable = true)
                    ?: error(I18n.t("coreC.java.notRunnable", path))
            val instance = store.instance(prompt.instanceId)
            runCatching {
                VersionResolver(
                    store.minecraftRoot(instance),
                    platform,
                ).resolve(instance.versionId)
            }.getOrNull()
                ?.let { profile ->
                    val requirement = JavaRequirement.from(profile)
                    val problem = requirement.problem(runtime, platform.architecture)
                    require(problem == null) { I18n.t("coreC.java.incompatible", problem, instance.name) }
                }
            store.updateInstance(instance.id) {
                it.copy(javaPath = path, suppressJavaPrompt = false)
            }
            java.invalidate()
            store.updateState { it.copy(javaPrompt = null) }
            store.notify(I18n.t("coreC.java.selected", runtime.displayName, runtime.path))
        }

    /**
     * 移除 Java 条目：启动器安装的删除目录与清单；用户手动添加的只删记录、不动文件；
     * 系统 Java 只读。被移除路径若仍是全局/实例选择，一并清回「自动」，避免留下失效选择。
     */

    override fun removeJavaRuntime(runtime: JavaRuntime) =
        perform {
            requireIdle()

            fun samePath(
                left: String,
                right: String,
            ): Boolean {
                if (left.isBlank() || right.isBlank()) return false
                return runCatching {
                    val a = Path.of(left).toAbsolutePath().normalize()
                    val b = Path.of(right).toAbsolutePath().normalize()
                    a.toString().equals(b.toString(), ignoreCase = platform.osName == "windows")
                }.getOrDefault(false)
            }

            fun forgetPath() {
                store.mutate { data ->
                    data.copy(
                        settings =
                            data.settings.copy(
                                javaPath =
                                    if (samePath(data.settings.javaPath, runtime.path)) {
                                        ""
                                    } else {
                                        data.settings.javaPath
                                    },
                                customJavaPaths =
                                    data.settings.customJavaPaths.filterNot {
                                        samePath(it, runtime.path)
                                    },
                            ),
                        instances =
                            data.instances.map {
                                if (samePath(it.javaPath, runtime.path)) {
                                    it.copy(javaPath = "")
                                } else {
                                    it
                                }
                            },
                    )
                }
                java.invalidate()
            }
            when {
                runtime.managed -> {
                    val removed = java.remove(runtime)
                    forgetPath()
                    store.notify(
                        I18n.t(
                            "coreC.java.removed",
                            removed.displayName,
                            "%.0f MB".format(runtime.sizeBytes / BYTES_PER_MEGABYTE),
                        ),
                    )
                }
                runtime.source == JavaRuntimeSource.Custom -> {
                    forgetPath()
                    store.notify(I18n.t("coreC.java.removedFromList", runtime.path))
                }
                else -> error(I18n.t("coreC.java.systemReadOnly"))
            }
            refreshJavaPrompt()
        }

    override fun installJava(
        major: Int,
        imageType: String?,
    ): String {
        require(imageType == null || imageType in listOf("jre", "jdk")) { I18n.t("coreC.java.invalidType") }
        val typeLabel = imageType?.uppercase().orEmpty()
        return jobs.enqueue(
            if (typeLabel.isBlank()) {
                I18n.t("coreC.tasks.installJava", major)
            } else {
                I18n.t("coreC.tasks.installJavaWithType", major, typeLabel)
            },
            setOf("java:$major"),
            kind = LauncherTaskKind.InstallJava,
        ) {
            java.install(major, this, imageType)
        }
    }

    /** 「自动」安装：按当前实例的游戏版本换算主版本（26.x→25、1.20.5+→21、1.17–1.20.4→17、更老→8）。 */
    override fun installAutoJava(imageType: String?): String {
        val instance =
            store.data.selectedInstanceId?.let { id ->
                store.data.instances.find { it.id == id }
            }
        val profile =
            instance?.let {
                runCatching {
                    VersionResolver(store.minecraftRoot(it), platform).resolve(it.versionId)
                }.getOrNull()
            }
        val gameVersion = instance?.gameVersion?.ifBlank { profile?.id.orEmpty() }.orEmpty()
        val major =
            when {
                gameJavaMajor(gameVersion) != null ->
                    requiredJavaMajor(gameVersion, profile?.requiredJava ?: 8)
                profile != null -> profile.requiredJava
                else -> 21
            }
        return installJava(major, imageType)
    }
}
