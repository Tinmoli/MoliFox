// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例的安装、启动与设置。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Path

/** 实例的安装、启动、重命名与设置。 */
internal interface LauncherInstanceApi {
    fun repair(id: String): String

    fun launch(
        id: String,
        target: GameLaunchTarget? = null,
    ): String

    fun selectInstance(id: String)

    /**
     * 重命名实例：移动真实版本目录并同步同名 JSON/JAR、配置 `id` 与版本引用。
     * 校验在动磁盘前完成（非法字符、目录或记录重名、其他实例的基础版本、运行/任务占用），失败回滚并抛中文错误。
     */
    fun renameInstance(
        instanceId: String,
        newName: String,
    ): GameInstance

    /**
     * 写入实例设置（内存、窗口、参数、备注等）。[notify] 为 false 时不弹「版本设置已保存」提示，
     * 供设置页的自动保存使用，避免每次改动都刷一条提示。
     */
    fun saveInstanceConfiguration(
        configuration: GameInstance,
        notify: Boolean = true,
    )

    /** 升级既有实例的加载器小版本（同加载器、同游戏版本）；模组、存档与配置不动。 */
    fun upgradeInstanceLoader(
        instanceId: String,
        loaderVersion: String,
    ): String

    suspend fun previewLaunch(instanceId: String): LaunchPlan
}

private const val MAX_INSTANCE_NAME_LENGTH = 80
private const val MIN_INITIAL_MEMORY_MB = 128
private const val MEMORY_HEADROOM_MB = 512
private const val MAX_NOTES_LENGTH = 2000

/** 实现由 [LauncherController] 委托调用；方法体与原实现一致。 */
internal abstract class LauncherInstanceActions(
    core: LauncherControllerCore,
) : LauncherJavaActions(core),
    LauncherInstanceApi {
    internal fun install(
        request: InstallRequest,
        gameDirectoryId: String?,
    ): String {
        val instance = installer.createInstance(request, gameDirectoryId)
        return jobs.enqueue(
            I18n.t("instA2.tasks.install", instance.name),
            taskResourcesForInstances(store, instance.id),
            kind = LauncherTaskKind.InstallInstance,
            prepare = { NewInstallationCleanup(store).register(instance, it) },
        ) {
            network.resetSourceHealth()
            // 新安装：目录名就是实例名，开工前再确认它没有被别的目录或实例占用。
            installer.install(instance.id, this, freshInstall = true)
            // 安装完成后版本 JSON 已落盘，让列表从扫描结果而不是内存记录更新。
            store.refreshInstances()
        }
    }

    override fun repair(id: String): String {
        requireIdle(id)
        val instance = store.instance(id)
        return jobs.enqueue(
            I18n.t("instA2.tasks.repair", instance.name),
            taskResourcesForInstances(store, id),
            kind = LauncherTaskKind.RepairInstance,
        ) {
            network.resetSourceHealth()
            if (instance.packFile.isNotBlank()) {
                content.importPack(Path.of(instance.packFile), this, instance.id)
            } else {
                installer.install(id, this)
            }
            store.refreshInstances()
        }
    }

    override fun launch(
        id: String,
        target: GameLaunchTarget?,
    ): String {
        val instance = store.instance(id)
        // 损坏/缺失 JSON 的目录仍显示在列表里，但绝不尝试启动或隐式改写它。
        require(!instance.broken) {
            I18n.t("instA2.instance.unrecognized", instance.brokenReason)
        }
        require(instance.installed) { I18n.t("instA2.instance.notInstalled") }
        val resources = taskResourcesForInstances(store, id)
        check(
            state.value.jobs.none {
                it.kind == LauncherTaskKind.ExportContent &&
                    it.status in setOf(JobStatus.Queued, JobStatus.Running) &&
                    taskResourcesConflict(resources, it.resources)
            },
        ) { I18n.t("miscA1.runtimeActions.instanceInUse") }
        require(store.data.activeAccountId != null) { I18n.t("core.accountService.1") }
        return jobs.enqueue(
            I18n.t("instA2.tasks.launch", store.instance(id).name),
            resources,
            kind = LauncherTaskKind.LaunchGame,
        ) {
            game.launch(id, this, target)
        }
    }

    override fun selectInstance(id: String) {
        val instance = store.instance(id)
        store.mutate {
            it.copy(selectedInstanceId = id, activeGameDirectoryId = instance.gameDirectoryId)
        }
        refreshJavaPrompt()
    }

    /**
     * 启动检查：当前选中实例缺少兼容 Java 时，给出「下载并安装 / 选择已有 / 使用系统 Java / 取消」入口。
     * 已显式指定 Java（实例或全局）、用户点过不再提示、或已有满足要求的候选时都不弹窗。
     */

    override fun renameInstance(
        instanceId: String,
        newName: String,
    ): GameInstance {
        requireIdle(instanceId)
        val oldFolder = store.instance(instanceId).instanceFolderName()
        val renamed = renamer.rename(instanceId, newName)
        // 设置表按目录名索引：目录改名后把隔离、Java、图标、收藏和最近游玩搬到新键。
        store.renameInstanceSettings(oldFolder, newName)
        store.refreshInstances()
        return store.instance(newName)
    }

    /**
     * 写入实例设置（内存、窗口、参数、备注等）。[notify] 为 false 时不弹「版本设置已保存」提示，
     * 供设置页的自动保存使用，避免每次改动都刷一条提示。
     */

    override fun saveInstanceConfiguration(
        configuration: GameInstance,
        notify: Boolean,
    ) {
        requireIdle(configuration.id)
        validateInstanceConfiguration(configuration)
        val previousJavaPath = store.instance(configuration.id).javaPath
        store.updateInstance(configuration.id) {
            it.copy(
                name = configuration.name.trim(),
                memoryMb = configuration.memoryMb,
                autoMemory = configuration.autoMemory,
                javaPath = configuration.javaPath,
                isolation = configuration.isolation,
                initialMemoryMb = configuration.initialMemoryMb,
                width = configuration.width,
                height = configuration.height,
                windowMode = configuration.windowMode,
                extraJvmArgs = configuration.extraJvmArgs,
                extraGameArgs = configuration.extraGameArgs,
                advancedLaunch = configuration.advancedLaunch,
                notes = configuration.notes,
                replaceSaveOnRestore = configuration.replaceSaveOnRestore,
            )
        }
        if (previousJavaPath != configuration.javaPath) {
            java.invalidate()
            refreshJavaPrompt()
        }
        if (notify) store.notify(I18n.t("instA2.instance.settingsSaved"))
    }

    /** 校验实例设置；失败时抛出的文案直接展示在设置页。 */
    private fun validateInstanceConfiguration(configuration: GameInstance) {
        require(
            configuration.name.isNotBlank() &&
                configuration.name.length <= MAX_INSTANCE_NAME_LENGTH,
        ) {
            I18n.t("instA2.instance.nameLength")
        }
        validateInstanceLaunchOptions(
            memoryMb = configuration.memoryMb,
            width = configuration.width,
            height = configuration.height,
            windowMode = configuration.windowMode,
            extraJvmArgs = configuration.extraJvmArgs,
            extraGameArgs = configuration.extraGameArgs,
            totalMemoryMb = platform.totalMemoryMb,
        )
        val maximumMemory = maximumMemoryMb(configuration)
        require(
            configuration.initialMemoryMb == 0 ||
                configuration.initialMemoryMb in MIN_INITIAL_MEMORY_MB..maximumMemory,
        ) {
            I18n.t("instA2.instance.initialMemoryInvalid")
        }
        require(configuration.notes.length <= MAX_NOTES_LENGTH) {
            I18n.t("instA2.instance.notesTooLong")
        }
        configuration.advancedLaunch?.let {
            validateAdvancedLaunchSettings(
                it,
                platform.osName,
            )
        }
    }

    /** 允许的最大内存：自动分配时给系统留出同等余量，否则使用实例或全局设置。 */
    private fun maximumMemoryMb(configuration: GameInstance): Int {
        val inheritedAutoMemory =
            configuration.autoMemory == null &&
                configuration.memoryMb == 0 &&
                store.data.settings.autoMemory
        val autoMemoryRequested = configuration.autoMemory == true || inheritedAutoMemory
        return if (autoMemoryRequested) {
            maxOf(MEMORY_HEADROOM_MB, platform.totalMemoryMb - MEMORY_HEADROOM_MB)
        } else {
            configuration.memoryMb.takeIf { it > 0 } ?: store.data.settings.memoryMb
        }
    }

    /** 升级既有实例的加载器小版本（同加载器、同游戏版本）；模组、存档与配置不动。 */
    override fun upgradeInstanceLoader(
        instanceId: String,
        loaderVersion: String,
    ): String {
        requireIdle(instanceId)
        val instance = store.instance(instanceId)
        return jobs.enqueue(
            I18n.t("instA2.tasks.upgradeLoader", instance.name),
            taskResourcesForInstances(store, instanceId),
            kind = LauncherTaskKind.UpgradeLoader,
        ) {
            installer.upgradeLoader(instanceId, loaderVersion, this)
        }
    }

    override suspend fun previewLaunch(instanceId: String): LaunchPlan {
        val instance = store.instance(instanceId)
        val profile =
            VersionResolver(store.minecraftRoot(instance), platform).resolve(instance.versionId)
        val javaPath =
            instance.javaPath
                .ifBlank { store.data.settings.javaPath }
                .ifBlank { I18n.t("instA2.preview.autoJava", profile.requiredJava) }
        val account = store.data.accounts.find { it.id == store.data.activeAccountId }
        val plan =
            LaunchPlanner(store)
                .plan(
                    instance,
                    profile,
                    JavaRuntime(
                        javaPath,
                        profile.requiredJava,
                        I18n.t("instA2.preview.vendor"),
                        platform.architecture,
                    ),
                    GameCredentials(
                        account?.name ?: "Player",
                        account?.uuid ?: I18n.t("instA2.preview.playerUuid"),
                        I18n.t("instA2.preview.accessToken"),
                    ),
                    prepareFiles = false,
                )
        return plan.copy(environment = environmentForDiagnostics(plan.environment))
    }
}
